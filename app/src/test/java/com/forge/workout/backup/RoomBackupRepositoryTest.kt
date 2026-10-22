package com.forge.workout.backup

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomBackupRepositoryTest {
    private lateinit var database: ForgeDatabase
    private lateinit var backupRepository: RoomBackupRepository
    private var preferences = UserPreferences()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        backupRepository = RoomBackupRepository(database) { preferences }
    }

    @After fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase("backup_restore.db")
    }

    @Test fun `snapshot contains built in archived active full relationships metadata and preferences`() = runBlocking {
        val imported = completeBackup()
        backupRepository.restore(imported) { preferences = imported.preferences }
        assertEquals(imported, backupRepository.snapshot().copy(createdAt = imported.createdAt))
        assertEquals(imported, BackupCodec.decode(BackupCodec.encode(backupRepository.snapshot().copy(createdAt = imported.createdAt))))
        val dao = database.workoutDao()
        assertEquals(SetType.entries.toSet(), dao.backupSets().map { it.setType }.toSet())
        assertEquals(imported.exercises.size - 1, dao.observeExercises().first().size)
        assertTrue(backupRepository.snapshot().exercises.any { it.archived })
        assertTrue(backupRepository.snapshot().exercises.any { !it.isCustom })
        assertNotNull(dao.activeWorkout())
    }

    @Test fun `restore replaces existing rows preserves IDs and relationships and refreshes flows`() = runBlocking {
        val workouts = RoomWorkoutRepository(database)
        workouts.ensureSeeded()
        val oldExercise = workouts.saveExercise(Exercise(name = "Old custom", primaryMuscleGroup = MuscleGroup.BACK))
        val oldWorkout = workouts.startWorkout("Old active")
        workouts.addExercise(oldWorkout, oldExercise)
        val routineId = workouts.saveRoutine(null, "Old routine")
        workouts.addRoutineExercise(routineId, oldExercise)
        val imported = completeBackup()
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) { workouts.observeWorkouts().first { rows -> rows.map { it.workout.id }.toSet() == setOf(201L, 202L) } }
        }
        backupRepository.restore(imported) { preferences = imported.preferences }
        assertEquals(2, observed.await().size)
        assertEquals(imported, backupRepository.snapshot().copy(createdAt = imported.createdAt))
        assertNull(database.workoutDao().exercise(oldExercise))
        assertNull(database.workoutDao().workout(oldWorkout))
        assertNull(database.workoutDao().routine(routineId))
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        backupRepository.restore(imported)
        assertEquals(imported, backupRepository.snapshot().copy(createdAt = imported.createdAt))
        val newId = workouts.saveExercise(Exercise(name = "After import", primaryMuscleGroup = MuscleGroup.CORE))
        assertTrue(newId > imported.exercises.maxOf { it.id })
    }

    @Test fun `restored backup persists across actual database close and reopen`() = runBlocking {
        database.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("backup_restore.db")
        database = Room.databaseBuilder(context, ForgeDatabase::class.java, "backup_restore.db").build()
        backupRepository = RoomBackupRepository(database) { preferences }
        val imported = completeBackup()
        backupRepository.restore(imported) { preferences = imported.preferences }
        database.close()
        database = Room.databaseBuilder(context, ForgeDatabase::class.java, "backup_restore.db").build()
        backupRepository = RoomBackupRepository(database) { preferences }
        assertEquals(imported, backupRepository.snapshot().copy(createdAt = imported.createdAt))
        assertEquals(imported.sets, database.workoutDao().backupSets())
        assertEquals(SetType.entries.toSet(), database.workoutDao().backupSets().map { it.setType }.toSet())
    }

    @Test fun `callback failure after imported inserts rolls every table back to prior contents`() = runBlocking {
        val original = completeBackup()
        backupRepository.restore(original) { preferences = original.preferences }
        val before = backupRepository.snapshot().copy(createdAt = 123)
        val replacement = original.copy(
            exercises = original.exercises.map { it.copy(notes = "Imported") },
            workouts = original.workouts.map { it.copy(name = "Replacement ${it.id}") },
            sets = original.sets.map { it.copy(completed = false, setType = SetType.WORKING) },
            routines = original.routines.map { it.copy(name = "Replacement routine") },
            metadata = listOf(AppMetadata("default_exercises_v1", "seeded"), AppMetadata("replacement", "yes")),
        )
        var callbackRan = false
        try {
            backupRepository.restore(replacement) {
                callbackRan = true
                assertEquals(replacement.workouts, database.workoutDao().backupWorkouts())
                assertEquals(replacement.sets, database.workoutDao().backupSets())
                assertEquals(replacement.metadata.sortedBy { it.key }, database.workoutDao().backupMetadata())
                throw IOException("Preference write failed")
            }
            fail("Expected callback failure")
        } catch (error: IOException) { assertEquals("Preference write failed", error.message) }
        assertTrue(callbackRan)
        assertEquals(before, backupRepository.snapshot().copy(createdAt = 123))
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun `SQL insertion failure restores every old row after child first deletion`() = runBlocking {
        val original = completeBackup()
        backupRepository.restore(original) { preferences = original.preferences }
        val before = backupRepository.snapshot().copy(createdAt = 123)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_import BEFORE INSERT ON workout_sets WHEN NEW.id = 404 BEGIN SELECT RAISE(ABORT, 'Rejected test import'); END",
        )
        var callbackRan = false
        try {
            backupRepository.restore(original.copy(
                exercises = original.exercises.map { it.copy(notes = "New") },
                sets = original.sets.map { it.copy(setType = SetType.entries[(it.setType.ordinal + 1) % SetType.entries.size]) },
            )) { callbackRan = true }
            fail("Expected SQL failure")
        } catch (_: SQLiteConstraintException) { /* SQLite abort must roll back the entire transaction. */ }
        assertFalse(callbackRan)
        assertEquals(before, backupRepository.snapshot().copy(createdAt = 123))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_import")
    }

    @Test fun `invalid backup is rejected before deletion or callback`() = runBlocking {
        val original = completeBackup()
        backupRepository.restore(original) { preferences = original.preferences }
        val before = backupRepository.snapshot().copy(createdAt = 123)
        val invalid = listOf(
            original.copy(sets = original.sets + original.sets.first()),
            original.copy(workoutExercises = original.workoutExercises.drop(1)),
            original.copy(preferences = original.preferences.copy(restSeconds = 45)),
        )
        var callbackRan = false
        for (backup in invalid) {
            try {
                backupRepository.restore(backup) { callbackRan = true }
                fail("Expected validation failure")
            } catch (error: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, error.issue) }
            assertFalse(callbackRan)
            assertEquals(before, backupRepository.snapshot().copy(createdAt = 123))
        }
    }

    @Test fun `missing seed metadata is normalized and does not recreate exercises after import`() = runBlocking {
        val imported = completeBackup().copy(metadata = emptyList())
        backupRepository.restore(imported)
        assertEquals("seeded", database.workoutDao().metadata("default_exercises_v1"))
        RoomWorkoutRepository(database).ensureSeeded()
        assertEquals(imported.exercises, database.workoutDao().backupExercises())
        backupRepository.restore(ForgeBackup())
        RoomWorkoutRepository(database).ensureSeeded()
        assertTrue(database.workoutDao().backupExercises().isEmpty())
        assertTrue(database.workoutDao().backupWorkouts().isEmpty())
        assertTrue(database.workoutDao().backupSets().isEmpty())
    }
}

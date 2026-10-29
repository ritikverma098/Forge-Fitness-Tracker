package com.forge.workout.backup

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExerciseNameBackupHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var backups: RoomBackupRepository
    private var preferences = UserPreferences()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        backups = RoomBackupRepository(database) { preferences }
    }

    @After fun tearDown() { database.close() }

    @Test fun `repository saved dotted I and combining dot names preserve their workout and routine graph through backup`() = runBlocking {
        val workouts = RoomWorkoutRepository(database)
        workouts.ensureSeeded()
        val names = listOf("\u0130 Press", "i\u0307 Press")
        val ids = names.map { workouts.saveExercise(exercise(name = it)) }
        val workoutId = workouts.startWorkout("Unicode workout")
        val routineId = workouts.saveRoutine(name = "Unicode routine")
        ids.forEach {
            workouts.addExercise(workoutId, it)
            workouts.addRoutineExercise(routineId, it)
        }
        val original = snapshot()
        assertEquals(names, original.exercises.filter { it.id in ids }.map { it.name })
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, decoded)

        workouts.saveExercise(exercise(name = "Temporary exercise"))
        backups.restore(decoded) { preferences = decoded.preferences }
        assertEquals(original, snapshot())
        assertEquals(ids, database.workoutDao().workoutExercises(workoutId).map { it.exerciseId })
        assertEquals(ids, database.workoutDao().routineExercises(routineId).map { it.exerciseId })
        assertForeignKeys()
    }

    @Test fun `legacy backups retain dotted dotless and sigma names that the UI creation rule treats as equal`() = runBlocking {
        val names = listOf("I Row", "\u0131 Row", "\u0130 Press", "i Press", "\u03a3 Squat", "\u03c2 Squat", "\u03c3 Raise", "\u03c2 Raise")
        names.chunked(2).forEach { assertTrue(it[0].equals(it[1], ignoreCase = true)) }
        val source = completeBackup()
        val original = source.copy(exercises = names.mapIndexed { index, name ->
            source.exercises.getOrNull(index)?.copy(name = name) ?: exercise(101L + index, name)
        })
        val legacyJson = JSONObject(BackupCodec.encode(original)).put("formatVersion", 2).put("databaseVersion", 3)
        legacyJson.remove("nutrition")
        val legacy = BackupCodec.decode(legacyJson.toString())
        assertEquals(original.copy(nutrition = null), legacy)
        backups.restore(legacy) { preferences = legacy.preferences }
        assertEquals(legacy, snapshot().copy(nutrition = null))

        val exported = snapshot()
        val decoded = BackupCodec.decode(BackupCodec.encode(exported))
        assertEquals(exported, decoded)
        backups.restore(decoded) { preferences = decoded.preferences }
        assertEquals(exported, snapshot())
        assertEquals(names, snapshot().exercises.map { it.name })
        assertForeignKeys()
    }

    @Test fun `backup name validity matches the actual Room unique index for Unicode whitespace and NUL byte lengths`() = runBlocking {
        val pairs = listOf(
            "Press" to "PRESS",
            "Mixed ASCII Row" to "mIXED ascii rOW",
            "\u0130 Press" to "i\u0307 Press",
            "\u0130 Press" to "i Press",
            "I Row" to "\u0131 Row",
            "\u03a3 Squat" to "\u03c2 Squat",
            "\u03c3 Squat" to "\u03c2 Squat",
            "\u00c9 Press" to "\u00e9 Press",
            " Press" to "Press",
            "Press " to "Press",
            "\ud801\udc00 Row" to "\ud801\udc28 Row",
            "\ud83d\udcaa Press" to "\ud83d\udcaa PRESS",
            "Press\u0000x" to "PRESS\u0000y",
            "Press\u0000x" to "PRESS\u0000yz",
            "Press\u0000\u00e9" to "PRESS\u0000ab",
            "Press\u0000\ud83d\udcaa" to "PRESS\u0000abcd",
            "Press\u0000\ud83d\udcaa" to "PRESS\u0000\u00e9",
            "Press\u0000" to "PRESS",
            "Press\u0000\u0000" to "PRESS\u0000x",
            "Press\u0000x" to "Press\u0001x",
        )
        val dao = database.workoutDao()
        for ((first, second) in pairs) {
            dao.deleteBackupExercises()
            dao.insertExercise(exercise(1, first))
            val roomAccepts = try {
                dao.insertExercise(exercise(2, second))
                true
            } catch (_: SQLiteConstraintException) { false }
            val candidate = ForgeBackup(createdAt = 10000, exercises = listOf(exercise(1, first), exercise(2, second)))
            val backupAccepts = try {
                BackupValidation.validate(candidate)
                true
            } catch (error: BackupValidationException) {
                assertEquals(BackupIssue.INVALID_DATA, error.issue)
                false
            }
            assertEquals("Room index and backup differ for ${listOf(first, second)}", roomAccepts, backupAccepts)
            if (roomAccepts) {
                assertEquals(candidate, BackupCodec.decode(BackupCodec.encode(candidate)))
                assertEquals(candidate.exercises, dao.backupExercises())
            } else {
                expectInvalid { BackupCodec.encode(candidate) }
                val json = JSONObject(BackupCodec.encode(candidate.copy(exercises = listOf(exercise(1, "First"), exercise(2, "Second")))))
                json.getJSONArray("exercises").getJSONObject(0).put("name", first)
                json.getJSONArray("exercises").getJSONObject(1).put("name", second)
                expectInvalid { BackupCodec.decode(json.toString()) }
            }
        }
    }

    @Test fun `storage duplicate names reject direct restore before deleting the existing graph or calling back`() = runBlocking {
        val original = completeBackup()
        backups.restore(original) { preferences = original.preferences }
        val before = snapshot()
        for ((first, second) in listOf("Press" to "PRESS", "Press\u0000\u00e9" to "PRESS\u0000ab")) {
            val candidate = original.copy(exercises = original.exercises + listOf(exercise(998, first), exercise(999, second)))
            var callbackRan = false
            try {
                backups.restore(candidate) { callbackRan = true }
                fail("Expected duplicate names to be rejected")
            } catch (error: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, error.issue) }
            assertFalse(callbackRan)
            assertEquals(before, snapshot())
        }
        assertForeignKeys()
    }

    @Test fun `UI exercise saves keep their existing Unicode case insensitive duplicate policy`() = runBlocking {
        val workouts = RoomWorkoutRepository(database)
        workouts.saveExercise(exercise(name = "\u0130 Press"))
        try {
            workouts.saveExercise(exercise(name = "i Press"))
            fail("Expected the existing UI duplicate rule to reject this name")
        } catch (error: IllegalArgumentException) {
            assertEquals("An exercise with this name already exists.", error.message)
        }
        assertEquals(listOf("\u0130 Press"), database.workoutDao().backupExercises().map { it.name })
    }

    private fun exercise(id: Long = 0, name: String) = Exercise(id = id, name = name, primaryMuscleGroup = MuscleGroup.CHEST)
    private suspend fun snapshot() = backups.snapshot().copy(createdAt = 10000)
    private fun assertForeignKeys() = database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("Expected duplicate names to be rejected") }
        catch (error: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, error.issue) }
    }
}

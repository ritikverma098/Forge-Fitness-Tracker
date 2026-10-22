package com.forge.workout.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import com.forge.workout.repository.RoomWorkoutRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Builds actual legacy SQLite files from checked-in Room schemas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationHostTest {
    private var database: ForgeDatabase? = null

    @Before fun removePreviousFixture() { RuntimeEnvironment.getApplication().deleteDatabase("forge.db") }
    @After fun closeAndRemoveFixture() {
        database?.close()
        RuntimeEnvironment.getApplication().deleteDatabase("forge.db")
    }

    @Test fun `version1 migration chain validates schema4 and preserves workouts exercises sets routines and seed metadata`() = runBlocking {
        createLegacyFixture(1)
        val context = RuntimeEnvironment.getApplication()
        // Use the production factory: opening exercises the registered migration and
        // Room's generated schema validation, rather than executing ALTER TABLE alone.
        val migrated = ForgeDatabase.create(context)
        database = migrated
        val dao = migrated.workoutDao()
        val repository = RoomWorkoutRepository(migrated)
        val completed = dao.workoutDetail(21)!!
        val active = dao.workoutDetail(22)!!

        assertEquals("Upper strength", completed.workout.name)
        assertEquals(1000L, completed.workout.startTime)
        assertEquals(9000L, completed.workout.endTime)
        assertEquals("Keep all history", completed.workout.notes)
        assertNull(completed.workout.restEndTime)
        assertTrue(completed.workout.muscleGroups.isEmpty())
        assertNull(active.workout.endTime)
        assertEquals(123456789L, active.workout.restEndTime)
        assertTrue(active.workout.muscleGroups.isEmpty())

        val custom = completed.exercises.single()
        assertEquals(31L, custom.entry.id)
        assertEquals(0, custom.entry.orderIndex)
        assertEquals("Paused reps", custom.entry.notes)
        assertEquals(11L, custom.exercise.id)
        assertEquals("Paused Press", custom.exercise.name)
        assertEquals(MuscleGroup.CHEST, custom.exercise.primaryMuscleGroup)
        assertEquals(MuscleGroup.TRICEPS, custom.exercise.secondaryMuscleGroup)
        assertEquals(ExerciseType.WEIGHT_REPS, custom.exercise.exerciseType)
        assertEquals("Two second pause", custom.exercise.notes)
        assertTrue(custom.exercise.isCustom)
        val completedSet = custom.sets.single()
        assertEquals(41L, completedSet.id)
        assertEquals(1, completedSet.setNumber)
        assertEquals(61.5, completedSet.weight, 0.000001)
        assertEquals(12, completedSet.reps)
        assertEquals(0, completedSet.duration)
        assertEquals(0.0, completedSet.distance, 0.000001)
        assertTrue(completedSet.completed)
        assertEquals(SetType.WORKING, completedSet.setType)
        val archived = active.exercises.single()
        assertTrue(archived.exercise.archived)
        assertEquals("Retired Plank", archived.exercise.name)
        assertEquals(45, archived.sets.single().duration)
        assertEquals(3.5, archived.sets.single().distance, 0.000001)
        assertFalse(archived.sets.single().completed)
        assertEquals(SetType.WORKING, archived.sets.single().setType)

        val routine = dao.routineDetail(51)!!
        assertEquals("Upper template", routine.routine.name)
        assertEquals(4567L, routine.routine.createdAt)
        assertEquals(listOf(61L, 62L), routine.sortedExercises.map { it.entry.id })
        assertEquals(listOf(11L, 12L), routine.sortedExercises.map { it.exercise.id })
        assertEquals("seeded", dao.metadata("default_exercises_v1"))
        assertEquals("do not reset", dao.metadata("user_fixture"))
        repository.ensureSeeded()
        assertEquals(3, dao.exercises().size)
        assertEquals(2, repository.observeWorkouts().first().size)
        assertEquals(1, repository.observeRoutines().first().size)
        assertFalse(dao.exercise(13)!!.isCustom)

        val sqlite = migrated.openHelper.writableDatabase
        assertEquals(4, sqlite.version)
        sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        sqlite.query("PRAGMA table_info(workouts)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "muscleGroups") {
                    found = true
                    assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("''", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue(found)
        }
        assertSetTypeColumn(migrated)

        repository.updateMuscleGroups(22, setOf(MuscleGroup.TRICEPS, MuscleGroup.BICEPS))
        migrated.close()
        val reopened = ForgeDatabase.create(context)
        database = reopened
        assertEquals(listOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS), reopened.workoutDao().workout(22)!!.muscleGroups)
        assertEquals(completed, reopened.workoutDao().workoutDetail(21))
        assertEquals(routine, reopened.workoutDao().routineDetail(51))
        assertNotNull(reopened.workoutDao().workoutSet(42))
    }

    @Test fun `version2 migration chain validates schema4 preserves groups and every row then persists new set types`() = runBlocking {
        createLegacyFixture(2)
        val context = RuntimeEnvironment.getApplication()
        val migrated = ForgeDatabase.create(context)
        database = migrated
        val dao = migrated.workoutDao()
        val repository = RoomWorkoutRepository(migrated)
        val completed = dao.workoutDetail(21)!!
        val active = dao.workoutDetail(22)!!
        val routine = dao.routineDetail(51)!!
        assertEquals(listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS), completed.workout.muscleGroups)
        assertEquals(listOf(MuscleGroup.CORE), active.workout.muscleGroups)
        assertEquals("Upper strength", completed.workout.name)
        assertEquals("Keep all history", completed.workout.notes)
        assertEquals(1000L, completed.workout.startTime)
        assertEquals(9000L, completed.workout.endTime)
        assertNull(active.workout.endTime)
        assertEquals(123456789L, active.workout.restEndTime)
        assertEquals("Keep draft", active.workout.notes)
        assertEquals("Paused Press", completed.exercises.single().exercise.name)
        assertEquals("Two second pause", completed.exercises.single().exercise.notes)
        assertEquals(MuscleGroup.TRICEPS, completed.exercises.single().exercise.secondaryMuscleGroup)
        assertEquals("Paused reps", completed.exercises.single().entry.notes)
        assertEquals(61.5, completed.exercises.single().sets.single().weight, 0.000001)
        assertEquals(12, completed.exercises.single().sets.single().reps)
        assertTrue(completed.exercises.single().sets.single().completed)
        assertTrue(active.exercises.single().exercise.archived)
        assertEquals(45, active.exercises.single().sets.single().duration)
        assertEquals(3.5, active.exercises.single().sets.single().distance, 0.000001)
        assertEquals(listOf(SetType.WORKING, SetType.WORKING), dao.backupSets().map { it.setType })
        assertEquals(listOf(11L, 12L, 13L), dao.backupExercises().map { it.id })
        assertEquals(listOf(21L, 22L), dao.backupWorkouts().map { it.id })
        assertEquals(listOf(31L, 32L), dao.backupWorkoutExercises().map { it.id })
        assertEquals(listOf(41L, 42L), dao.backupSets().map { it.id })
        assertEquals(listOf(51L), dao.backupRoutines().map { it.id })
        assertEquals(listOf(61L, 62L), dao.backupRoutineExercises().map { it.id })
        assertEquals("Upper template", routine.routine.name)
        assertEquals(4567L, routine.routine.createdAt)
        assertEquals(listOf(11L, 12L), routine.sortedExercises.map { it.exercise.id })
        assertEquals("seeded", dao.metadata("default_exercises_v1"))
        assertEquals("do not reset", dao.metadata("user_fixture"))
        assertEquals(2, dao.backupMetadata().size)
        assertFalse(dao.exercise(13)!!.isCustom)
        repository.ensureSeeded()
        assertEquals(3, dao.exercises().size)
        assertEquals(4, migrated.openHelper.writableDatabase.version)
        assertSetTypeColumn(migrated)
        migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

        val originalSet = active.exercises.single().sets.single()
        for (type in SetType.entries) {
            repository.updateSet(originalSet.copy(setType = type))
            assertEquals(type, dao.workoutSet(originalSet.id)!!.setType)
        }
        val changed = dao.workoutSet(originalSet.id)!!
        migrated.close()
        val reopened = ForgeDatabase.create(context)
        database = reopened
        assertEquals(changed, reopened.workoutDao().workoutSet(originalSet.id))
        assertEquals(SetType.FAILURE, reopened.workoutDao().workoutSet(originalSet.id)!!.setType)
        assertEquals(completed, reopened.workoutDao().workoutDetail(21))
        assertEquals(active.workout, reopened.workoutDao().workout(22))
        assertEquals(routine, reopened.workoutDao().routineDetail(51))
        assertEquals("do not reset", reopened.workoutDao().metadata("user_fixture"))
    }

    private fun assertSetTypeColumn(database: ForgeDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA table_info(workout_sets)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "setType") {
                    found = true
                    assertEquals("TEXT", cursor.getString(cursor.getColumnIndexOrThrow("type")))
                    assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("notnull")))
                    assertEquals("'WORKING'", cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")))
                }
            }
            assertTrue(found)
        }
    }

    private fun createLegacyFixture(version: Int) {
        val schemaRelativePath = "schemas/com.forge.workout.data.database.ForgeDatabase/$version.json"
        val snapshot = listOf(File(schemaRelativePath), File("app", schemaRelativePath)).firstOrNull { it.isFile }
        requireNotNull(snapshot) { "The checked-in Room version$version schema was not found." }
        val schema = JSONObject(snapshot.readText()).getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        val context = RuntimeEnvironment.getApplication()
        val path = context.getDatabasePath("forge.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            sqlite.execSQL("PRAGMA foreign_keys = ON")
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (indexNumber in 0 until (indices?.length() ?: 0)) {
                    sqlite.execSQL(indices!!.getJSONObject(indexNumber).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
            sqlite.execSQL("INSERT INTO exercises VALUES (11, 'Paused Press', 'CHEST', 'TRICEPS', 'WEIGHT_REPS', 'Two second pause', 1, 0)")
            sqlite.execSQL("INSERT INTO exercises VALUES (12, 'Retired Plank', 'CORE', NULL, 'DURATION', 'Archived exercise', 1, 1)")
            sqlite.execSQL("INSERT INTO exercises VALUES (13, 'Default press', 'CHEST', NULL, 'WEIGHT_REPS', '', 0, 0)")
            sqlite.execSQL("INSERT INTO workouts (id, name, startTime, endTime, notes, restEndTime) VALUES (21, 'Upper strength', 1000, 9000, 'Keep all history', NULL)")
            sqlite.execSQL("INSERT INTO workouts (id, name, startTime, endTime, notes, restEndTime) VALUES (22, 'Active core', 10000, NULL, 'Keep draft', 123456789)")
            if (version == 2) {
                sqlite.execSQL("UPDATE workouts SET muscleGroups = 'CHEST,TRICEPS' WHERE id = 21")
                sqlite.execSQL("UPDATE workouts SET muscleGroups = 'CORE' WHERE id = 22")
            }
            sqlite.execSQL("INSERT INTO workout_exercises VALUES (31, 21, 11, 0, 'Paused reps')")
            sqlite.execSQL("INSERT INTO workout_exercises VALUES (32, 22, 12, 0, 'Keep notes')")
            sqlite.execSQL("INSERT INTO workout_sets VALUES (41, 31, 1, 61.5, 12, 0, 0.0, 1)")
            sqlite.execSQL("INSERT INTO workout_sets VALUES (42, 32, 1, 0.0, 0, 45, 3.5, 0)")
            sqlite.execSQL("INSERT INTO routines VALUES (51, 'Upper template', 4567)")
            sqlite.execSQL("INSERT INTO routine_exercises VALUES (61, 51, 11, 0)")
            sqlite.execSQL("INSERT INTO routine_exercises VALUES (62, 51, 12, 1)")
            sqlite.execSQL("INSERT INTO app_metadata VALUES ('default_exercises_v1', 'seeded')")
            sqlite.execSQL("INSERT INTO app_metadata VALUES ('user_fixture', 'do not reset')")
            sqlite.version = version
        }
    }
}

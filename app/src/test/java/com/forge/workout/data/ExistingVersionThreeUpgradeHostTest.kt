package com.forge.workout.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.backup.NutritionBackup
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RestTimer
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExistingVersionThreeUpgradeHostTest {
    private var database: ForgeDatabase? = null

    @Before fun clearFixture() { RuntimeEnvironment.getApplication().deleteDatabase("forge.db") }
    @After fun closeFixture() {
        database?.close()
        RuntimeEnvironment.getApplication().deleteDatabase("forge.db")
    }

    @Test fun `existing shipped version3 database migrates to4 retaining all rows settings and auto IDs`() = runBlocking {
        assertShippedVersionThreeSchemaUnchanged()
        val expected = fixture()
        createExistingDatabase(expected)
        val context = RuntimeEnvironment.getApplication()
        val preferences = PreferencesRepository(context, "existing-v3-${UUID.randomUUID()}")
        preferences.replace(expected.preferences)
        val opened = ForgeDatabase.create(context)
        database = opened
        val repository = RoomWorkoutRepository(opened)
        val backup = RoomBackupRepository(opened) { preferences.preferences.first() }
        repository.ensureSeeded()
        assertEquals(expected.copy(nutrition = NutritionBackup()), backup.snapshot().copy(createdAt = expected.createdAt))
        assertEquals(4, opened.openHelper.writableDatabase.version)
        assertEquals(RestTimer(22, DEADLINE, "Existing active"), repository.restTimer())
        assertEquals(5, repository.observeExercises().first().size)
        assertTrue(opened.workoutDao().exercise(14)!!.archived)
        assertFalse(opened.workoutDao().exercise(16)!!.isCustom)
        assertEquals(ExerciseType.entries.toSet(), opened.workoutDao().workoutDetail(21)!!.exercises.map { it.exercise.exerciseType }.toSet())
        assertEquals(SetType.entries.toSet(), opened.workoutDao().backupSets().map { it.setType }.toSet())
        assertForeignKeys(opened)

        val addedExercise = repository.saveExercise(Exercise(name = "Added after upgrade", primaryMuscleGroup = MuscleGroup.BACK))
        val addedRoutine = repository.saveRoutine(name = "Added template")
        val addedWorkout = opened.workoutDao().insertWorkout(Workout(name = "Added history", startTime = 20_000, endTime = 30_000))
        assertTrue(addedExercise > 16)
        assertTrue(addedRoutine > 51)
        assertTrue(addedWorkout > 22)
        repository.addRoutineExercise(addedRoutine, addedExercise)
        repository.addExercise(addedWorkout, addedExercise)
        val newEntry = opened.workoutDao().workoutDetail(addedWorkout)!!.exercises.single()
        assertTrue(newEntry.entry.id > 36)
        assertTrue(newEntry.sets.single().id > 46)
        assertTrue(opened.workoutDao().routineExercises(addedRoutine).single().id > 65)
        assertEquals(SetType.WORKING, newEntry.sets.single().setType)
        repository.updateSet(newEntry.sets.single().copy(weight = 80.0, reps = 5, completed = true))
        val afterWrites = backup.snapshot().copy(createdAt = expected.createdAt)
        assertEquals(expected.exercises, afterWrites.exercises.filter { it.id <= 16 })
        assertEquals(expected.workouts, afterWrites.workouts.filter { it.id <= 22 })
        assertEquals(expected.sets, afterWrites.sets.filter { it.id <= 46 })
        assertForeignKeys(opened)
        opened.close()

        val reopened = ForgeDatabase.create(context)
        database = reopened
        val reopenedBackup = RoomBackupRepository(reopened) { preferences.preferences.first() }
        assertEquals(afterWrites, reopenedBackup.snapshot().copy(createdAt = expected.createdAt))
        assertEquals(expected.preferences, preferences.preferences.first())
        assertEquals(RestTimer(22, DEADLINE, "Existing active"), RoomWorkoutRepository(reopened).restTimer())
        reopened.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            assertTrue(it.moveToFirst())
            assertEquals(currentSchemaIdentity(), it.getString(0))
        }
        assertForeignKeys(reopened)
    }

    private fun currentSchemaIdentity(): String {
        val relative = "schemas/com.forge.workout.data.database.ForgeDatabase/4.json"
        val file = listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
        val schema = JSONObject(requireNotNull(file).readText()).getJSONObject("database")
        assertEquals(4, schema.getInt("version"))
        return schema.getString("identityHash")
    }

    private fun assertShippedVersionThreeSchemaUnchanged() {
        val relative = "schemas/com.forge.workout.data.database.ForgeDatabase/3.json"
        val file = listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
        val schema = JSONObject(requireNotNull(file).readText()).getJSONObject("database")
        assertEquals(3, schema.getInt("version"))
        assertEquals(PRIOR_IDENTITY, schema.getString("identityHash"))
        val statements = mutableListOf<String>()
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val table = entity.getString("tableName")
            statements += entity.getString("createSql").replace("\${TABLE_NAME}", table)
            val indices = entity.optJSONArray("indices") ?: continue
            for (indexNumber in 0 until indices.length()) {
                statements += indices.getJSONObject(indexNumber).getString("createSql").replace("\${TABLE_NAME}", table)
            }
        }
        assertEquals(PRIOR_SCHEMA_SQL, statements)
    }

    private fun createExistingDatabase(backup: ForgeBackup) {
        val path = RuntimeEnvironment.getApplication().getDatabasePath("forge.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            sqlite.execSQL("PRAGMA foreign_keys = ON")
            PRIOR_SCHEMA_SQL.forEach(sqlite::execSQL)
            sqlite.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            sqlite.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf<Any?>(PRIOR_IDENTITY))
            backup.exercises.forEach { row -> sqlite.execSQL("INSERT INTO exercises VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(row.id, row.name, row.primaryMuscleGroup.name, row.secondaryMuscleGroup?.name, row.exerciseType.name,
                    row.notes, if (row.isCustom) 1 else 0, if (row.archived) 1 else 0)) }
            backup.workouts.forEach { row -> sqlite.execSQL("INSERT INTO workouts VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(row.id, row.name, row.startTime, row.endTime, row.notes, row.restEndTime, row.muscleGroups.joinToString(",") { it.name })) }
            backup.routines.forEach { row -> sqlite.execSQL("INSERT INTO routines VALUES (?, ?, ?)", arrayOf<Any?>(row.id, row.name, row.createdAt)) }
            backup.workoutExercises.forEach { row -> sqlite.execSQL("INSERT INTO workout_exercises VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(row.id, row.workoutId, row.exerciseId, row.orderIndex, row.notes)) }
            backup.sets.forEach { row -> sqlite.execSQL("INSERT INTO workout_sets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(row.id, row.workoutExerciseId, row.setNumber, row.weight, row.reps, row.duration, row.distance,
                    if (row.completed) 1 else 0, row.setType.name)) }
            backup.routineExercises.forEach { row -> sqlite.execSQL("INSERT INTO routine_exercises VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(row.id, row.routineId, row.exerciseId, row.orderIndex)) }
            backup.metadata.forEach { row -> sqlite.execSQL("INSERT INTO app_metadata VALUES (?, ?)", arrayOf<Any?>(row.key, row.value)) }
            sqlite.version = 3
        }
    }

    private fun assertForeignKeys(database: ForgeDatabase) {
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun fixture() = ForgeBackup(
        createdAt = 1_000,
        exercises = ExerciseType.entries.mapIndexed { index, type -> Exercise(id = 11L + index, name = "Existing ${type.name}",
            primaryMuscleGroup = MuscleGroup.CORE, secondaryMuscleGroup = MuscleGroup.SHOULDERS, exerciseType = type,
            notes = "Original exercise notes $index", archived = type == ExerciseType.DURATION) } +
            Exercise(id = 16, name = "Existing default", primaryMuscleGroup = MuscleGroup.CHEST, isCustom = false),
        workouts = listOf(
            Workout(id = 21, name = "Existing history", startTime = 1_000, endTime = 9_000, notes = "Keep history notes",
                muscleGroups = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS)),
            Workout(id = 22, name = "Existing active", startTime = 10_000, notes = "Keep active draft", restEndTime = DEADLINE,
                muscleGroups = listOf(MuscleGroup.CORE, MuscleGroup.CARDIO)),
        ),
        workoutExercises = ExerciseType.entries.mapIndexed { index, _ -> WorkoutExercise(id = 31L + index, workoutId = 21,
            exerciseId = 11L + index, orderIndex = index, notes = "Original entry notes $index") } +
            WorkoutExercise(id = 36, workoutId = 22, exerciseId = 14, orderIndex = 0, notes = "Active exercise notes"),
        sets = ExerciseType.entries.mapIndexed { index, type -> WorkoutSet(id = 41L + index, workoutExerciseId = 31L + index,
            setNumber = 1, weight = if (type == ExerciseType.WEIGHT_REPS) 72.5 else 0.0,
            reps = when (type) { ExerciseType.WEIGHT_REPS -> 8; ExerciseType.BODYWEIGHT -> 15; ExerciseType.REPS_ONLY -> 30; else -> 0 },
            duration = when (type) { ExerciseType.DURATION -> 120; ExerciseType.DISTANCE -> 3_600; else -> 0 },
            distance = if (type == ExerciseType.DISTANCE) 5.75 else 0.0, completed = true, setType = SetType.entries[index % 4]) } +
            WorkoutSet(id = 46, workoutExerciseId = 36, setNumber = 1, duration = 90, setType = SetType.FAILURE),
        routines = listOf(Routine(id = 51, name = "Existing template", createdAt = 500)),
        routineExercises = ExerciseType.entries.mapIndexed { index, _ -> RoutineExercise(id = 61L + index, routineId = 51,
            exerciseId = 11L + index, orderIndex = index) },
        metadata = listOf(AppMetadata("default_exercises_v1", "seeded"), AppMetadata("user_fixture", "Keep metadata")),
        preferences = UserPreferences(ThemeMode.DARK, WeightUnit.LB, 180, false),
    )

    private companion object {
        const val PRIOR_IDENTITY = "c2af463c10a84d7fa83e8bcac1dc9298"
        const val DEADLINE = 4_102_444_800_000L
        // Exact resolved DDL from Forge-1.2.0-AndroidStudio.zip's exported schema 3.
        val PRIOR_SCHEMA_SQL = listOf(
            "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `primaryMuscleGroup` TEXT NOT NULL, `secondaryMuscleGroup` TEXT, `exerciseType` TEXT NOT NULL, `notes` TEXT NOT NULL, `isCustom` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_name` ON `exercises` (`name`)",
            "CREATE TABLE IF NOT EXISTS `workouts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `notes` TEXT NOT NULL, `restEndTime` INTEGER, `muscleGroups` TEXT NOT NULL DEFAULT '')",
            "CREATE INDEX IF NOT EXISTS `index_workouts_startTime` ON `workouts` (`startTime`)",
            "CREATE INDEX IF NOT EXISTS `index_workouts_endTime` ON `workouts` (`endTime`)",
            "CREATE TABLE IF NOT EXISTS `workout_exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, `notes` TEXT NOT NULL, FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            "CREATE INDEX IF NOT EXISTS `index_workout_exercises_workoutId` ON `workout_exercises` (`workoutId`)",
            "CREATE INDEX IF NOT EXISTS `index_workout_exercises_exerciseId` ON `workout_exercises` (`exerciseId`)",
            "CREATE TABLE IF NOT EXISTS `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutExerciseId` INTEGER NOT NULL, `setNumber` INTEGER NOT NULL, `weight` REAL NOT NULL, `reps` INTEGER NOT NULL, `duration` INTEGER NOT NULL, `distance` REAL NOT NULL, `completed` INTEGER NOT NULL, `setType` TEXT NOT NULL DEFAULT 'WORKING', FOREIGN KEY(`workoutExerciseId`) REFERENCES `workout_exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_workout_sets_workoutExerciseId` ON `workout_sets` (`workoutExerciseId`)",
            "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `routine_exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routineId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `orderIndex` INTEGER NOT NULL, FOREIGN KEY(`routineId`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
            "CREATE INDEX IF NOT EXISTS `index_routine_exercises_routineId` ON `routine_exercises` (`routineId`)",
            "CREATE INDEX IF NOT EXISTS `index_routine_exercises_exerciseId` ON `routine_exercises` (`exerciseId`)",
            "CREATE TABLE IF NOT EXISTS `app_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}

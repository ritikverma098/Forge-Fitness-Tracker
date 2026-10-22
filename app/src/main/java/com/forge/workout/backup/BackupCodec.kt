package com.forge.workout.backup

import com.forge.workout.data.database.AppMetadata
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.model.SetType
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object BackupCodec {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_DEPTH = 16
    const val FORMAT = "forge-workout-backup"
    const val FORMAT_VERSION = 3
    const val DATABASE_VERSION = 4

    fun encode(source: ForgeBackup): String = parseChecked {
        val backup = source.copy(exercises = source.exercises.toList(), workouts = source.workouts.map { it.copy(muscleGroups = it.muscleGroups.toList()) },
            workoutExercises = source.workoutExercises.toList(), sets = source.sets.toList(), routines = source.routines.toList(),
            routineExercises = source.routineExercises.toList(), metadata = source.metadata.toList(), nutrition = source.nutrition?.freeze())
        BackupValidation.validate(backup)
        val budget = BackupJsonBudget()
        fun json(vararg values: Pair<String, Any?>) = budget.objectValue(*values)
        fun array(values: List<Any>) = budget.arrayValue(values)
        val json = json(
            "format" to FORMAT, "formatVersion" to FORMAT_VERSION, "databaseVersion" to DATABASE_VERSION,
            "createdAt" to backup.createdAt,
            "exercises" to array(backup.exercises.map { exercise -> json(
                "id" to exercise.id, "name" to exercise.name, "primaryMuscleGroup" to exercise.primaryMuscleGroup.name,
                "secondaryMuscleGroup" to exercise.secondaryMuscleGroup?.name, "exerciseType" to exercise.exerciseType.name,
                "notes" to exercise.notes, "isCustom" to exercise.isCustom, "archived" to exercise.archived,
            ) }),
            "workouts" to array(backup.workouts.map { workout -> json(
                "id" to workout.id, "name" to workout.name, "startTime" to workout.startTime,
                "endTime" to workout.endTime, "notes" to workout.notes, "restEndTime" to workout.restEndTime,
                "muscleGroups" to array(workout.muscleGroups.map { it.name }),
            ) }),
            "workoutExercises" to array(backup.workoutExercises.map { entry -> json(
                "id" to entry.id, "workoutId" to entry.workoutId, "exerciseId" to entry.exerciseId,
                "orderIndex" to entry.orderIndex, "notes" to entry.notes,
            ) }),
            "sets" to array(backup.sets.map { set -> json(
                "id" to set.id, "workoutExerciseId" to set.workoutExerciseId, "setNumber" to set.setNumber,
                "weight" to set.weight, "reps" to set.reps, "duration" to set.duration,
                "distance" to set.distance, "completed" to set.completed, "setType" to set.setType.name,
            ) }),
            "routines" to array(backup.routines.map { routine -> json(
                "id" to routine.id, "name" to routine.name, "createdAt" to routine.createdAt,
            ) }),
            "routineExercises" to array(backup.routineExercises.map { entry -> json(
                "id" to entry.id, "routineId" to entry.routineId, "exerciseId" to entry.exerciseId,
                "orderIndex" to entry.orderIndex,
            ) }),
            "metadata" to array(backup.metadata.map { json("key" to it.key, "value" to it.value) }),
            "preferences" to json(
                "themeMode" to backup.preferences.themeMode.name, "weightUnit" to backup.preferences.weightUnit.name,
                "restSeconds" to backup.preferences.restSeconds, "confirmDeletion" to backup.preferences.confirmDeletion,
            ),
            "nutrition" to backup.nutrition?.let { NutritionBackupCodec.encode(it, budget) },
        )
        json.toString().also { checkSize(it) }
    }

    fun decode(source: String): ForgeBackup = parseChecked {
        checkSize(source)
        BackupJsonSyntax(source).validate()
        val root = JSONObject(source)
        require(root.string("format") == FORMAT) { "This file is not a Forge backup." }
        val version = root.integer("formatVersion")
        val database = root.integer("databaseVersion")
        val legacy = version == 1 && database == 2
        val versionTwo = version == 2 && database == 3
        val current = version == FORMAT_VERSION && database == DATABASE_VERSION
        if (!legacy && !versionTwo && !current) {
            throw BackupValidationException(BackupIssue.UNSUPPORTED_VERSION)
        }
        val fields = arrayOf("format", "formatVersion", "databaseVersion", "createdAt", "exercises", "workouts",
            "workoutExercises", "sets", "routines", "routineExercises", "metadata", "preferences")
        root.shape(*(if (current) fields + "nutrition" else fields))
        val preferences = root.objectValue("preferences").also {
            it.shape("themeMode", "weightUnit", "restSeconds", "confirmDeletion")
        }
        ForgeBackup(
            createdAt = root.long("createdAt"),
            exercises = root.objects("exercises") { value ->
                value.shape("id", "name", "primaryMuscleGroup", "secondaryMuscleGroup", "exerciseType", "notes", "isCustom", "archived")
                Exercise(value.long("id"), value.string("name"), enumValueOf<MuscleGroup>(value.string("primaryMuscleGroup")),
                    value.nullableString("secondaryMuscleGroup")?.let { enumValueOf<MuscleGroup>(it) },
                    enumValueOf<ExerciseType>(value.string("exerciseType")), value.string("notes"),
                    value.boolean("isCustom"), value.boolean("archived"))
            },
            workouts = root.objects("workouts") { value ->
                value.shape("id", "name", "startTime", "endTime", "notes", "restEndTime", "muscleGroups")
                Workout(value.long("id"), value.string("name"), value.long("startTime"), value.nullableLong("endTime"),
                    value.string("notes"), value.nullableLong("restEndTime"), value.arrayValue("muscleGroups").let { groups ->
                        List(groups.length()) { index -> enumValueOf<MuscleGroup>(groups.get(index).strictString()) }
                    })
            },
            workoutExercises = root.objects("workoutExercises") { value ->
                value.shape("id", "workoutId", "exerciseId", "orderIndex", "notes")
                WorkoutExercise(value.long("id"), value.long("workoutId"), value.long("exerciseId"),
                    value.integer("orderIndex"), value.string("notes"))
            },
            sets = root.objects("sets") { value ->
                val fields = arrayOf("id", "workoutExerciseId", "setNumber", "weight", "reps", "duration", "distance", "completed")
                value.shape(*(if(legacy) fields else fields + "setType"))
                WorkoutSet(value.long("id"), value.long("workoutExerciseId"), value.integer("setNumber"),
                    value.decimal("weight"), value.integer("reps"), value.integer("duration"),
                    value.decimal("distance"), value.boolean("completed"),
                    if(legacy) SetType.WORKING else enumValueOf<SetType>(value.string("setType")))
            },
            routines = root.objects("routines") { value ->
                value.shape("id", "name", "createdAt")
                Routine(value.long("id"), value.string("name"), value.long("createdAt"))
            },
            routineExercises = root.objects("routineExercises") { value ->
                value.shape("id", "routineId", "exerciseId", "orderIndex")
                RoutineExercise(value.long("id"), value.long("routineId"), value.long("exerciseId"), value.integer("orderIndex"))
            },
            metadata = root.objects("metadata") { value ->
                value.shape("key", "value")
                AppMetadata(value.string("key"), value.string("value"))
            },
            preferences = UserPreferences(enumValueOf<ThemeMode>(preferences.string("themeMode")),
                enumValueOf<WeightUnit>(preferences.string("weightUnit")), preferences.integer("restSeconds"),
                preferences.boolean("confirmDeletion")),
            nutrition = if (!current || root.get("nutrition") === JSONObject.NULL) null
                else NutritionBackupCodec.decode(root.objectValue("nutrition")),
        ).also { BackupValidation.validate(it) }
    }

    private fun checkSize(source: String) {
        require(source.length <= MAX_BYTES && source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the size limit." }
    }

    private fun JSONObject.shape(vararg expectedKeys: String) {
        require(keys().asSequence().toSet() == expectedKeys.toSet()) { "Invalid backup structure." }
    }
    private fun Any.strictString(): String { require(this is String) { "Expected text." }; return this }
    private fun JSONObject.string(key: String): String = get(key).strictString()
    private fun JSONObject.nullableString(key: String): String? = if (get(key) === JSONObject.NULL) null else string(key)
    private fun JSONObject.long(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "Expected an integer." }
        return (value as Number).toLong()
    }
    private fun JSONObject.nullableLong(key: String): Long? = if (get(key) === JSONObject.NULL) null else long(key)
    private fun JSONObject.integer(key: String): Int {
        val value = long(key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer is out of range." }
        return value.toInt()
    }
    private fun JSONObject.decimal(key: String): Double {
        val value = get(key)
        require(value is Number && value.toDouble().isFinite()) { "Expected a finite number." }
        return value.toDouble()
    }
    private fun JSONObject.boolean(key: String): Boolean {
        val value = get(key)
        require(value is Boolean) { "Expected a boolean." }
        return value
    }
    private fun JSONObject.arrayValue(key: String): JSONArray = get(key).let {
        require(it is JSONArray) { "Expected a list." }; it
    }
    private fun JSONObject.objectValue(key: String): JSONObject = get(key).let {
        require(it is JSONObject) { "Expected an object." }; it
    }
    private fun <T> JSONObject.objects(key: String, read: (JSONObject) -> T): List<T> {
        val values = arrayValue(key)
        return List(values.length()) { index ->
            val value = values.get(index)
            require(value is JSONObject) { "Expected a row object." }
            read(value)
        }
    }
    private fun <T> parseChecked(action: () -> T): T = try {
        BackupValidation.checked(action)
    } catch (error: JSONException) {
        throw BackupValidationException(BackupIssue.INVALID_DATA, "Invalid backup JSON.", error)
    }
}

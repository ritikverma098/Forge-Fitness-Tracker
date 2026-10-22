package com.forge.workout.backup

import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.utils.ExerciseValidation
import com.forge.workout.utils.WorkoutValidation
import java.util.Locale

internal object BackupValidation {
    const val SEED_KEY = "default_exercises_v1"

    fun validate(backup: ForgeBackup) = checked {
        require(backup.createdAt >= 0) { "Invalid backup date." }
        uniqueIds(backup.exercises.map { it.id })
        uniqueIds(backup.workouts.map { it.id })
        uniqueIds(backup.workoutExercises.map { it.id })
        uniqueIds(backup.sets.map { it.id })
        uniqueIds(backup.routines.map { it.id })
        uniqueIds(backup.routineExercises.map { it.id })

        val names = HashSet<String>()
        backup.exercises.forEach { exercise ->
            ExerciseValidation.validate(exercise)
            require(names.add(exercise.name.trim().lowercase(Locale.ROOT))) { "Duplicate exercise name." }
        }
        backup.workouts.forEach { workout ->
            WorkoutValidation.validateName(workout.name)
            validNotes(workout.notes)
            require(workout.startTime >= 0 && (workout.endTime == null || workout.endTime >= workout.startTime)) { "Invalid workout date." }
            require(workout.restEndTime == null || workout.restEndTime >= 0) { "Invalid rest timer." }
            require(workout.muscleGroups.distinct().size == workout.muscleGroups.size) { "Duplicate muscle group." }
        }
        require(backup.workouts.count { it.endTime == null } <= 1) { "Multiple active workouts." }
        backup.routines.forEach { routine ->
            WorkoutValidation.validateName(routine.name)
            require(routine.createdAt >= 0) { "Invalid routine date." }
        }
        val exercises = backup.exercises.associateBy { it.id }
        val workouts = backup.workouts.map { it.id }.toSet()
        val routines = backup.routines.map { it.id }.toSet()
        val workoutExercises = backup.workoutExercises.associateBy { it.id }
        backup.workoutExercises.forEach { entry ->
            require(entry.workoutId in workouts && entry.exerciseId in exercises) { "Missing workout or exercise." }
            validNotes(entry.notes)
        }
        backup.workoutExercises.groupBy { it.workoutId }.values.forEach { entries ->
            consecutive(entries.map { it.orderIndex }, 0)
            require(entries.map { it.exerciseId }.distinct().size == entries.size) { "Duplicate workout exercise." }
        }
        backup.routineExercises.forEach { entry ->
            require(entry.routineId in routines && entry.exerciseId in exercises) { "Missing routine or exercise." }
        }
        backup.routineExercises.groupBy { it.routineId }.values.forEach { entries ->
            consecutive(entries.map { it.orderIndex }, 0)
            require(entries.map { it.exerciseId }.distinct().size == entries.size) { "Duplicate routine exercise." }
        }
        backup.sets.forEach { set ->
            val entry = requireNotNull(workoutExercises[set.workoutExerciseId]) { "Missing workout exercise." }
            WorkoutValidation.validateSet(set, exercises.getValue(entry.exerciseId).exerciseType)
        }
        backup.sets.groupBy { it.workoutExerciseId }.values.forEach { sets -> consecutive(sets.map { it.setNumber }, 1) }
        require(backup.metadata.map { it.key }.distinct().size == backup.metadata.size) { "Duplicate metadata key." }
        backup.metadata.forEach {
            require(it.key.isNotBlank() && it.key.length <= 100 && it.value.length <= 5000) { "Invalid metadata." }
            require(it.key != SEED_KEY || it.value == "seeded") { "Invalid seed metadata." }
        }
        require(backup.preferences.restSeconds in PreferencesRepository.REST_PRESETS) { "Invalid default rest timer." }
        backup.nutrition?.let { NutritionBackupValidation.validate(it, backup.workouts) }
    }

    private fun uniqueIds(ids: List<Long>) {
        require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Invalid or duplicate row ID." }
    }

    private fun consecutive(values: List<Int>, start: Int) {
        require(values.sorted() == List(values.size) { it + start }) { "Invalid order or set number." }
    }

    private fun validNotes(notes: String) { require(notes.length <= 5000) { "Notes are too long." } }

    fun <T> checked(action: () -> T): T = try {
        action()
    } catch (error: BackupValidationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw BackupValidationException(BackupIssue.INVALID_DATA, error.message ?: "Invalid backup data.", error)
    }
}

package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutSet

object ExerciseValidation {
    fun validate(exercise: Exercise, existing: List<Exercise> = emptyList()) {
        require(exercise.name.trim().isNotEmpty()) { "Exercise name cannot be empty." }
        require(exercise.name.trim().length <= 100) { "Exercise name must be 100 characters or fewer." }
        require(existing.none { it.id != exercise.id && it.name.equals(exercise.name.trim(), ignoreCase = true) }) {
            "An exercise with this name already exists."
        }
        require(exercise.secondaryMuscleGroup != exercise.primaryMuscleGroup) { "Choose a different secondary muscle group." }
        require(exercise.notes.length <= 5000) { "Notes must be 5,000 characters or fewer." }
    }
}

object WorkoutValidation {
    fun validateName(name: String) {
        require(name.trim().isNotEmpty()) { "Name cannot be empty." }
        require(name.trim().length <= 100) { "Name must be 100 characters or fewer." }
    }

    fun validateSet(set: WorkoutSet, type: ExerciseType) {
        require(set.weight.isFinite() && set.weight >= 0 && set.weight <= 10000) { "Enter a weight from 0 to 10,000 kg." }
        require(set.reps in 0..10000) { "Enter repetitions from 0 to 10,000." }
        require(set.duration in 0..604800) { "Enter a duration from 0 to 604,800 seconds." }
        require(set.distance.isFinite() && set.distance >= 0 && set.distance <= 100000) { "Enter a distance from 0 to 100,000 km." }
        if (set.completed) {
            when (type) {
                ExerciseType.WEIGHT_REPS, ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY ->
                    require(set.reps > 0) { "Enter repetitions before completing this set." }
                ExerciseType.DURATION -> require(set.duration > 0) { "Enter a duration before completing this set." }
                ExerciseType.DISTANCE -> require(set.distance > 0) { "Enter a distance before completing this set." }
            }
        }
    }
}

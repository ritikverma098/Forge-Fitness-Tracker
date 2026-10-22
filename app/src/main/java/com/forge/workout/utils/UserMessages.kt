package com.forge.workout.utils

import android.content.Context
import com.forge.workout.R

/** Keeps domain validation Android-free while presenting localized messages in the UI. */
fun userMessage(context: Context, error: Throwable): String = context.getString(
    when (error.message) {
        "Exercise name cannot be empty." -> R.string.validation_exercise_name_empty
        "Exercise name must be 100 characters or fewer." -> R.string.validation_exercise_name_length
        "An exercise with this name already exists." -> R.string.validation_exercise_duplicate
        "Choose a different secondary muscle group." -> R.string.validation_secondary_muscle
        "Notes must be 5,000 characters or fewer." -> R.string.validation_notes_length
        "Name cannot be empty." -> R.string.validation_name_empty
        "Name must be 100 characters or fewer." -> R.string.validation_name_length
        "Enter a weight from 0 to 10,000 kg." -> R.string.validation_weight
        "Enter repetitions from 0 to 10,000." -> R.string.validation_reps
        "Enter a duration from 0 to 604,800 seconds." -> R.string.validation_duration
        "Enter a distance from 0 to 100,000 km." -> R.string.validation_distance
        "Enter repetitions before completing this set." -> R.string.validation_complete_reps
        "Enter a duration before completing this set." -> R.string.validation_complete_duration
        "Enter a distance before completing this set." -> R.string.validation_complete_distance
        "Exercise was not found." -> R.string.validation_exercise_missing
        "Only custom exercises can be edited." -> R.string.validation_edit_default
        "This exercise has workout history. Create a new exercise to use a different tracking type." -> R.string.validation_type_history
        "Only custom exercises can be deleted." -> R.string.validation_delete_default
        "Select one workout source." -> R.string.validation_workout_source
        "Routine was not found." -> R.string.validation_routine_missing
        "Workout was not found." -> R.string.validation_workout_missing
        "This workout is already complete." -> R.string.validation_already_complete
        "Complete at least one set before finishing this workout." -> R.string.validation_empty_workout
        "This exercise has been deleted." -> R.string.validation_exercise_deleted
        "This exercise is already in your workout." -> R.string.validation_duplicate_workout_exercise
        "Workout exercise was not found." -> R.string.validation_workout_exercise_missing
        "Set was not found." -> R.string.validation_set_missing
        "A set cannot be moved to another exercise." -> R.string.validation_move_set
        "A finished workout cannot start a rest timer." -> R.string.validation_rest_finished
        "This exercise is already in this routine." -> R.string.validation_duplicate_routine_exercise
        "Routine exercise was not found." -> R.string.validation_routine_exercise_missing
        else -> R.string.error_generic
    },
)

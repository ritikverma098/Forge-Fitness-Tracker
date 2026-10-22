package com.forge.workout.utils

import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
import kotlin.math.roundToLong

enum class RecordType { HIGHEST_WEIGHT, REPS_AT_WEIGHT, WORKOUT_VOLUME }

data class PersonalRecord(
    val exerciseId: Long?,
    val exerciseName: String,
    val type: RecordType,
    val value: Double,
    val weightKg: Double? = null,
)

object PersonalRecords {
    fun detect(workout: WorkoutDetail, history: List<WorkoutDetail>): List<PersonalRecord> {
        // Compare against history that existed when this workout began. Reopening an old
        // summary should not make its records disappear because of a later workout.
        val previous = history.filter {
            it.workout.endTime != null && it.workout.id != workout.workout.id &&
                (it.workout.startTime < workout.workout.startTime ||
                    (it.workout.startTime == workout.workout.startTime && it.workout.id < workout.workout.id))
        }
        return buildList {
            workout.sortedExercises.forEach { entry ->
                val completed = entry.sortedSets.filter { it.completed }
                val oldSets = previous.flatMap { it.exercises }.filter { it.exercise.id == entry.exercise.id }
                    .flatMap { it.sets }.filter { it.completed }
                if (entry.exercise.exerciseType == ExerciseType.WEIGHT_REPS) {
                    val maximum = completed.maxOfOrNull { it.weight } ?: 0.0
                    if (maximum > (oldSets.maxOfOrNull { it.weight } ?: 0.0) + 0.000001) {
                        add(PersonalRecord(entry.exercise.id, entry.exercise.name, RecordType.HIGHEST_WEIGHT, maximum))
                    }
                    val oldReps = oldSets.groupBy { weightKey(it.weight) }.mapValues { (_, sets) -> sets.maxOf { it.reps } }
                    val improved = completed.groupBy { weightKey(it.weight) }.values.mapNotNull { sets ->
                        val best = sets.maxBy { it.reps }
                        if (best.reps > (oldReps[weightKey(best.weight)] ?: 0)) best else null
                    }.maxWithOrNull(compareBy<com.forge.workout.model.WorkoutSet> { it.weight }.thenBy { it.reps })
                    if (improved != null) add(PersonalRecord(entry.exercise.id, entry.exercise.name,
                        RecordType.REPS_AT_WEIGHT, improved.reps.toDouble(), improved.weight))
                } else if (entry.exercise.exerciseType == ExerciseType.BODYWEIGHT || entry.exercise.exerciseType == ExerciseType.REPS_ONLY) {
                    val maximum = completed.maxOfOrNull { it.reps } ?: 0
                    if (maximum > (oldSets.maxOfOrNull { it.reps } ?: 0)) {
                        add(PersonalRecord(entry.exercise.id, entry.exercise.name, RecordType.REPS_AT_WEIGHT, maximum.toDouble(), 0.0))
                    }
                }
            }
            val volume = WorkoutCalculations.volume(workout)
            if (volume > (previous.maxOfOrNull { WorkoutCalculations.volume(it) } ?: 0.0) + 0.000001) {
                add(PersonalRecord(null, workout.workout.name, RecordType.WORKOUT_VOLUME, volume))
            }
        }
    }

    // Canonical weight is stored in kilograms; rounding to a gram avoids false
    // distinct weights after conversion from pounds and floating-point edits.
    private fun weightKey(weight: Double): Long = (weight * 1000).roundToLong()
}

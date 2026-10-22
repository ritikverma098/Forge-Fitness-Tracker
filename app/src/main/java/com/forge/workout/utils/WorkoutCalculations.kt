package com.forge.workout.utils

import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutSet

data class WorkoutSummary(
    val durationSeconds: Long,
    val exerciseCount: Int,
    val setCount: Int,
    val totalReps: Int,
    val totalVolumeKg: Double,
)

data class ProgressPoint(val date: Long, val weightKg: Double, val volumeKg: Double)

data class ExerciseProgress(
    val bestWeightKg: Double,
    val bestSet: WorkoutSet?,
    val maxReps: Int,
    val workoutCount: Int,
    val points: List<ProgressPoint>,
    val bestDurationSeconds: Int = 0,
    val bestDistanceKm: Double = 0.0,
)

object WorkoutCalculations {
    fun volume(detail: WorkoutDetail): Double = detail.exercises.sumOf { exercise ->
        if (exercise.exercise.exerciseType == ExerciseType.WEIGHT_REPS) {
            exercise.sets.filter { it.completed }.sumOf { it.weight * it.reps }
        } else 0.0
    }

    fun summary(detail: WorkoutDetail, now: Long = System.currentTimeMillis()): WorkoutSummary {
        val completed = detail.exercises.flatMap { it.sets }.filter { it.completed }
        return WorkoutSummary(
            durationSeconds = ((detail.workout.endTime ?: now) - detail.workout.startTime).coerceAtLeast(0) / 1000,
            exerciseCount = detail.exercises.count { exercise -> exercise.sets.any { it.completed } },
            setCount = completed.size,
            totalReps = detail.exercises.filter { it.exercise.exerciseType in repetitionTypes }
                .sumOf { exercise -> exercise.sets.filter { it.completed }.sumOf { it.reps } },
            totalVolumeKg = volume(detail),
        )
    }

    fun progress(exerciseId: Long, completedHistory: List<WorkoutDetail>): ExerciseProgress {
        val history = completedHistory.filter { it.workout.endTime != null }.sortedBy { it.workout.startTime }
        val entries = history.flatMap { workout -> workout.exercises.filter { it.exercise.id == exerciseId } }
        val sets = entries.flatMap { it.sets }.filter { it.completed }
        val type = entries.firstOrNull()?.exercise?.exerciseType ?: ExerciseType.WEIGHT_REPS
        val bestSet = when (type) {
            ExerciseType.WEIGHT_REPS -> sets.maxWithOrNull(compareBy<WorkoutSet> { it.weight }.thenBy { it.reps })
            ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY -> sets.maxByOrNull { it.reps }
            ExerciseType.DURATION -> sets.maxByOrNull { it.duration }
            ExerciseType.DISTANCE -> sets.maxByOrNull { it.distance }
        }
        val points = history.mapNotNull { workout ->
            val matching = workout.exercises.filter { it.exercise.id == exerciseId }
            val completed = matching.flatMap { it.sets }.filter { it.completed }
            if (completed.isEmpty()) null
            else ProgressPoint(workout.workout.startTime, completed.maxOf { it.weight },
                matching.filter { it.exercise.exerciseType == ExerciseType.WEIGHT_REPS }
                    .sumOf { item -> item.sets.filter { it.completed }.sumOf { it.weight * it.reps } })
        }
        return ExerciseProgress(
            bestWeightKg = sets.maxOfOrNull { it.weight } ?: 0.0,
            bestSet = bestSet,
            maxReps = sets.maxOfOrNull { it.reps } ?: 0,
            workoutCount = points.size,
            points = points,
            bestDurationSeconds = sets.maxOfOrNull { it.duration } ?: 0,
            bestDistanceKm = sets.maxOfOrNull { it.distance } ?: 0.0,
        )
    }

    private val repetitionTypes = setOf(ExerciseType.WEIGHT_REPS, ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY)
}

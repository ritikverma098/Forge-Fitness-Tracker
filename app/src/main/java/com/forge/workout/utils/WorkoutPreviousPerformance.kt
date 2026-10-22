package com.forge.workout.utils

import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExerciseDetail

object WorkoutPreviousPerformance {
    fun build(workout: Workout, history: List<WorkoutDetail>): Map<Long, WorkoutExerciseDetail> {
        val previous = linkedMapOf<Long, WorkoutExerciseDetail>()
        history.asSequence().filter { detail ->
            val old = detail.workout
            old.endTime != null && old.id != workout.id &&
                (old.startTime < workout.startTime || old.startTime == workout.startTime && old.id < workout.id)
        }.sortedByDescending { it.workout.startTime }.forEach { detail ->
            detail.exercises.forEach { entry ->
                if (entry.exercise.id !in previous && entry.sets.any { it.completed }) {
                    previous[entry.exercise.id] = entry
                }
            }
        }
        return previous
    }
}

package com.forge.workout.utils

import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.WorkoutDetail
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HistoryFilters(
    val name: String = "",
    val exerciseId: Long? = null,
    val muscleGroup: MuscleGroup? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
) {
    val hasFilters: Boolean get() = name.isNotBlank() || exerciseId != null || muscleGroup != null || fromDate != null || toDate != null
}

object HistoryFiltering {
    fun filter(workouts: List<WorkoutDetail>, filters: HistoryFilters, zone: ZoneId = ZoneId.systemDefault()): List<WorkoutDetail> {
        require(filters.fromDate == null || filters.toDate == null || filters.fromDate <= filters.toDate)
        val query = filters.name.trim()
        return workouts.asSequence().filter { detail ->
            val workout = detail.workout
            val date = Instant.ofEpochMilli(workout.startTime).atZone(zone).toLocalDate()
            workout.endTime != null && workout.name.contains(query, ignoreCase = true) &&
                (filters.exerciseId == null || detail.exercises.any { it.exercise.id == filters.exerciseId }) &&
                (filters.muscleGroup == null || filters.muscleGroup in workout.muscleGroups || detail.exercises.any {
                    it.exercise.primaryMuscleGroup == filters.muscleGroup || it.exercise.secondaryMuscleGroup == filters.muscleGroup
                }) && (filters.fromDate == null || date >= filters.fromDate) && (filters.toDate == null || date <= filters.toDate)
        }.sortedWith(compareByDescending<WorkoutDetail> { it.workout.startTime }.thenByDescending { it.workout.id }).toList()
    }
}

package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import java.util.Locale

object ExerciseFiltering {
    fun filter(
        exercises: List<Exercise>,
        query: String = "",
        workoutMuscleGroups: Set<MuscleGroup> = emptySet(),
        selectedMuscleGroup: MuscleGroup? = null,
        showAll: Boolean = false,
        sortAlphabetically: Boolean = true,
    ): List<Exercise> {
        val groups = selectedMuscleGroup?.let { setOf(it) }
            ?: if (showAll) emptySet() else workoutMuscleGroups
        val search = query.trim()
        val filtered = exercises.filter { exercise ->
            !exercise.archived && exercise.name.contains(search, ignoreCase = true) &&
                (groups.isEmpty() || exercise.primaryMuscleGroup in groups ||
                    exercise.secondaryMuscleGroup?.let { it in groups } == true)
        }
        return if(sortAlphabetically) filtered.sortedBy { it.name.lowercase(Locale.ROOT) } else filtered
    }
}

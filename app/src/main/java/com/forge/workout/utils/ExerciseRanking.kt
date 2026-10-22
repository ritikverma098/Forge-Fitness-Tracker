package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import java.util.Locale

object ExerciseRanking {
    const val RECENT_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun rank(exercises: List<Exercise>, usage: List<ExerciseUsage>, now: Long): List<Exercise> {
        val byId = usage.associateBy { it.exerciseId }
        val recentSince = now - RECENT_WINDOW_MILLIS
        fun category(exercise: Exercise): Int {
            val stats = byId[exercise.id] ?: return 2
            return if(stats.lastUsedAt >= recentSince) 0 else 1
        }
        return exercises.sortedWith(compareBy<Exercise> { category(it) }
            .thenByDescending { if(category(it) == 0) byId[it.id]?.lastUsedAt ?: 0L else 0L }
            .thenByDescending { byId[it.id]?.workoutCount ?: 0 }
            .thenByDescending { byId[it.id]?.lastUsedAt ?: 0L }
            .thenBy { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }
}

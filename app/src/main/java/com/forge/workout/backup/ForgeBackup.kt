package com.forge.workout.backup

import com.forge.workout.data.database.AppMetadata
import com.forge.workout.model.Exercise
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.UserPreferences

data class ForgeBackup(
    val createdAt: Long = System.currentTimeMillis(),
    val exercises: List<Exercise> = emptyList(),
    val workouts: List<Workout> = emptyList(),
    val workoutExercises: List<WorkoutExercise> = emptyList(),
    val sets: List<WorkoutSet> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val routineExercises: List<RoutineExercise> = emptyList(),
    val metadata: List<AppMetadata> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val nutrition: NutritionBackup? = null,
)

enum class BackupIssue { UNSUPPORTED_VERSION, INVALID_DATA }

class BackupValidationException(
    val issue: BackupIssue,
    message: String = issue.name,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

interface BackupRepository {
    suspend fun snapshot(): ForgeBackup
    suspend fun restore(backup: ForgeBackup, beforeCommit: suspend () -> Unit = {})
}

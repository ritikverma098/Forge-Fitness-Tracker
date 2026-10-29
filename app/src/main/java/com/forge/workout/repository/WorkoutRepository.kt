package com.forge.workout.repository

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RestTimer
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface WorkoutRepository {
    fun observeExerciseUsage(): Flow<List<ExerciseUsage>>
    fun observeExercises(): Flow<List<Exercise>>
    fun observeWorkouts(): Flow<List<WorkoutDetail>>
    fun observeRoutines(): Flow<List<RoutineDetail>>
    suspend fun workout(id: Long): Workout?
    suspend fun workoutSet(id: Long): WorkoutSet?
    suspend fun workoutExerciseSets(workoutExerciseId: Long): List<WorkoutSet>
    suspend fun hasWorkoutExercise(workoutId: Long, exerciseId: Long): Boolean
    suspend fun ensureSeeded()
    suspend fun saveExercise(exercise: Exercise): Long
    suspend fun deleteExercise(id: Long)
    suspend fun startWorkout(name: String, routineId: Long? = null, repeatWorkoutId: Long? = null): Long
    suspend fun updateWorkout(id: Long, name: String, notes: String)
    suspend fun updateMuscleGroups(id: Long, groups: Set<MuscleGroup>)
    suspend fun finishWorkout(id: Long)
    suspend fun deleteWorkout(id: Long)
    suspend fun addExercise(workoutId: Long, exerciseId: Long)
    suspend fun removeWorkoutExercise(id: Long)
    suspend fun moveWorkoutExercise(id: Long, delta: Int)
    suspend fun addSet(workoutExerciseId: Long)
    suspend fun updateSet(set: WorkoutSet)
    suspend fun deleteSet(id: Long)
    suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String)
    suspend fun setRestEndTime(workoutId: Long, endTime: Long?)
    fun observeRestTimer(): Flow<RestTimer?> = observeWorkouts().map { workouts ->
        workouts.firstOrNull { it.workout.endTime == null && it.workout.restEndTime != null }?.workout?.let {
            RestTimer(it.id, requireNotNull(it.restEndTime), it.name)
        }
    }
    suspend fun restTimer(): RestTimer? = observeRestTimer().first()
    suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean
    suspend fun saveRoutine(id: Long? = null, name: String): Long
    suspend fun addRoutineExercise(routineId: Long, exerciseId: Long)
    suspend fun removeRoutineExercise(id: Long)
    suspend fun moveRoutineExercise(id: Long, delta: Int)
    suspend fun deleteRoutine(id: Long)
}

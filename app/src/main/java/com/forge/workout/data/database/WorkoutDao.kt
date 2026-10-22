package com.forge.workout.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.forge.workout.model.Exercise
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.RestTimer
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Query("SELECT id AS workoutId, restEndTime AS deadline, name AS workoutName FROM workouts WHERE endTime IS NULL AND restEndTime IS NOT NULL ORDER BY startTime DESC, id DESC LIMIT 1")
    fun observeRestTimer(): Flow<RestTimer?>

    @Query("SELECT id AS workoutId, restEndTime AS deadline, name AS workoutName FROM workouts WHERE endTime IS NULL AND restEndTime IS NOT NULL ORDER BY startTime DESC, id DESC LIMIT 1")
    suspend fun restTimer(): RestTimer?

    @Query("UPDATE workouts SET restEndTime = NULL WHERE id = :workoutId AND endTime IS NULL AND restEndTime = :expectedDeadline AND restEndTime <= :now")
    suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Int

    @Query("""
        SELECT we.exerciseId, MAX(w.startTime) AS lastUsedAt, COUNT(DISTINCT w.id) AS workoutCount
        FROM workout_exercises we INNER JOIN workouts w ON w.id = we.workoutId
        WHERE w.endTime IS NOT NULL AND EXISTS (
            SELECT 1 FROM workout_sets s WHERE s.workoutExerciseId = we.id AND s.completed = 1
        ) GROUP BY we.exerciseId
    """)
    fun observeExerciseUsage(): Flow<List<ExerciseUsage>>

    @Query("SELECT * FROM exercises WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeExercises(): Flow<List<Exercise>>

    @Transaction @Query("SELECT * FROM workouts ORDER BY startTime DESC, id DESC")
    fun observeWorkouts(): Flow<List<WorkoutDetail>>

    @Transaction @Query("SELECT * FROM routines ORDER BY createdAt DESC, id DESC")
    fun observeRoutines(): Flow<List<RoutineDetail>>

    @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE") suspend fun exercises(): List<Exercise>
    @Query("SELECT * FROM exercises WHERE id = :id") suspend fun exercise(id: Long): Exercise?
    @Insert suspend fun insertExercise(exercise: Exercise): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeedExercises(exercises: List<Exercise>)
    @Update suspend fun updateExercise(exercise: Exercise)
    @Query("DELETE FROM exercises WHERE id = :id") suspend fun deleteExercise(id: Long)
    @Query("SELECT (SELECT COUNT(*) FROM workout_exercises WHERE exerciseId = :id) + (SELECT COUNT(*) FROM routine_exercises WHERE exerciseId = :id)")
    suspend fun exerciseReferenceCount(id: Long): Int
    @Query("SELECT COUNT(*) FROM workout_exercises WHERE exerciseId = :id")
    suspend fun workoutExerciseReferenceCount(id: Long): Int

    @Query("SELECT value FROM app_metadata WHERE `key` = :key") suspend fun metadata(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(value: AppMetadata)

    @Query("SELECT * FROM workouts WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1") suspend fun activeWorkout(): Workout?
    @Query("SELECT * FROM workouts WHERE id = :id") suspend fun workout(id: Long): Workout?
    @Transaction @Query("SELECT * FROM workouts WHERE id = :id") suspend fun workoutDetail(id: Long): WorkoutDetail?
    @Insert suspend fun insertWorkout(workout: Workout): Long
    @Update suspend fun updateWorkout(workout: Workout)
    @Query("DELETE FROM workouts WHERE id = :id") suspend fun deleteWorkout(id: Long)

    @Query("SELECT * FROM workout_exercises WHERE workoutId = :workoutId ORDER BY orderIndex, id") suspend fun workoutExercises(workoutId: Long): List<WorkoutExercise>
    @Query("SELECT * FROM workout_exercises WHERE id = :id") suspend fun workoutExercise(id: Long): WorkoutExercise?
    @Insert suspend fun insertWorkoutExercise(exercise: WorkoutExercise): Long
    @Update suspend fun updateWorkoutExercise(exercise: WorkoutExercise)
    @Query("DELETE FROM workout_exercises WHERE id = :id") suspend fun deleteWorkoutExercise(id: Long)

    @Query("SELECT * FROM workout_sets WHERE workoutExerciseId = :id ORDER BY setNumber, id") suspend fun sets(id: Long): List<WorkoutSet>
    @Query("SELECT * FROM workout_sets WHERE id = :id") suspend fun workoutSet(id: Long): WorkoutSet?
    @Insert suspend fun insertSet(set: WorkoutSet): Long
    @Update suspend fun updateSet(set: WorkoutSet)
    @Query("DELETE FROM workout_sets WHERE id = :id") suspend fun deleteSet(id: Long)
    @Query("SELECT workout_sets.* FROM workout_sets INNER JOIN workout_exercises ON workout_sets.workoutExerciseId = workout_exercises.id INNER JOIN workouts ON workout_exercises.workoutId = workouts.id WHERE workout_exercises.exerciseId = :exerciseId AND workouts.id != :workoutId AND (workouts.startTime < :beforeTime OR (workouts.startTime = :beforeTime AND workouts.id < :workoutId)) AND workouts.endTime IS NOT NULL AND workout_sets.completed = 1 ORDER BY workouts.startTime DESC, workouts.id DESC, workout_sets.setNumber DESC, workout_sets.id DESC LIMIT 1")
    suspend fun previousSet(exerciseId: Long, workoutId: Long, beforeTime: Long): WorkoutSet?

    @Query("SELECT * FROM routines WHERE id = :id") suspend fun routine(id: Long): Routine?
    @Transaction @Query("SELECT * FROM routines WHERE id = :id") suspend fun routineDetail(id: Long): RoutineDetail?
    @Insert suspend fun insertRoutine(routine: Routine): Long
    @Update suspend fun updateRoutine(routine: Routine)
    @Query("DELETE FROM routines WHERE id = :id") suspend fun deleteRoutine(id: Long)
    @Query("SELECT * FROM routine_exercises WHERE routineId = :id ORDER BY orderIndex, id") suspend fun routineExercises(id: Long): List<RoutineExercise>
    @Query("SELECT * FROM routine_exercises WHERE id = :id") suspend fun routineExercise(id: Long): RoutineExercise?
    @Insert suspend fun insertRoutineExercise(exercise: RoutineExercise): Long
    @Update suspend fun updateRoutineExercise(exercise: RoutineExercise)
    @Query("DELETE FROM routine_exercises WHERE id = :id") suspend fun deleteRoutineExercise(id: Long)

    @Query("SELECT * FROM exercises ORDER BY id") suspend fun backupExercises(): List<Exercise>
    @Query("SELECT * FROM workouts ORDER BY id") suspend fun backupWorkouts(): List<Workout>
    @Query("SELECT * FROM workout_exercises ORDER BY id") suspend fun backupWorkoutExercises(): List<WorkoutExercise>
    @Query("SELECT * FROM workout_sets ORDER BY id") suspend fun backupSets(): List<WorkoutSet>
    @Query("SELECT * FROM routines ORDER BY id") suspend fun backupRoutines(): List<Routine>
    @Query("SELECT * FROM routine_exercises ORDER BY id") suspend fun backupRoutineExercises(): List<RoutineExercise>
    @Query("SELECT * FROM app_metadata ORDER BY `key`") suspend fun backupMetadata(): List<AppMetadata>
    @Query("DELETE FROM workout_sets") suspend fun deleteBackupSets()
    @Query("DELETE FROM workout_exercises") suspend fun deleteBackupWorkoutExercises()
    @Query("DELETE FROM routine_exercises") suspend fun deleteBackupRoutineExercises()
    @Query("DELETE FROM workouts") suspend fun deleteBackupWorkouts()
    @Query("DELETE FROM routines") suspend fun deleteBackupRoutines()
    @Query("DELETE FROM exercises") suspend fun deleteBackupExercises()
    @Query("DELETE FROM app_metadata") suspend fun deleteBackupMetadata()
}

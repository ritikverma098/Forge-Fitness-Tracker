package com.forge.workout.repository

import androidx.room.withTransaction
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.data.seed.DefaultExercises
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RestTimer
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.model.SetType
import com.forge.workout.utils.ExerciseValidation
import com.forge.workout.utils.WorkoutValidation
import kotlinx.coroutines.flow.Flow

class RoomWorkoutRepository(private val database: ForgeDatabase) : WorkoutRepository {
    private val dao = database.workoutDao()
    override fun observeRestTimer(): Flow<RestTimer?> = dao.observeRestTimer()
    override suspend fun restTimer(): RestTimer? = dao.restTimer()
    override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean =
        dao.consumeRestTimer(workoutId, expectedDeadline, now) == 1
    override fun observeExerciseUsage(): Flow<List<ExerciseUsage>> = dao.observeExerciseUsage()

    override fun observeExercises(): Flow<List<Exercise>> = dao.observeExercises()
    override fun observeWorkouts(): Flow<List<WorkoutDetail>> = dao.observeWorkouts()
    override fun observeRoutines(): Flow<List<RoutineDetail>> = dao.observeRoutines()
    override suspend fun workout(id: Long): Workout? = dao.workout(id)
    override suspend fun workoutSet(id: Long): WorkoutSet? = dao.workoutSet(id)
    override suspend fun workoutExerciseSets(workoutExerciseId: Long): List<WorkoutSet> = dao.sets(workoutExerciseId)
    override suspend fun hasWorkoutExercise(workoutId: Long, exerciseId: Long): Boolean =
        dao.workoutExercises(workoutId).any { it.exerciseId == exerciseId }

    override suspend fun ensureSeeded() = database.withTransaction {
        if (dao.metadata("default_exercises_v1") == null) {
            dao.insertSeedExercises(DefaultExercises.all)
            dao.putMetadata(AppMetadata("default_exercises_v1", "seeded"))
        }
    }

    override suspend fun saveExercise(exercise: Exercise): Long = database.withTransaction {
        ExerciseValidation.validate(exercise, dao.exercises())
        val cleaned = exercise.copy(name = exercise.name.trim(), notes = exercise.notes.trim())
        if (exercise.id == 0L) {
            dao.insertExercise(cleaned.copy(isCustom = true, archived = false))
        } else {
            val stored = requireNotNull(dao.exercise(exercise.id)) { "Exercise was not found." }
            require(stored.isCustom) { "Only custom exercises can be edited." }
            require(stored.exerciseType == exercise.exerciseType || dao.workoutExerciseReferenceCount(exercise.id) == 0) {
                "This exercise has workout history. Create a new exercise to use a different tracking type."
            }
            dao.updateExercise(cleaned.copy(isCustom = stored.isCustom, archived = stored.archived))
            exercise.id
        }
    }

    override suspend fun deleteExercise(id: Long) = database.withTransaction {
        val exercise = requireNotNull(dao.exercise(id)) { "Exercise was not found." }
        require(exercise.isCustom) { "Only custom exercises can be deleted." }
        if (dao.exerciseReferenceCount(id) > 0) dao.updateExercise(exercise.copy(archived = true))
        else dao.deleteExercise(id)
    }

    override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long = database.withTransaction {
        WorkoutValidation.validateName(name)
        require(routineId == null || repeatWorkoutId == null) { "Select one workout source." }
        dao.activeWorkout()?.let { return@withTransaction it.id }
        val routine = routineId?.let { requireNotNull(dao.routineDetail(it)) { "Routine was not found." } }
        val previous = repeatWorkoutId?.let { requireNotNull(dao.workoutDetail(it)) { "Workout was not found." } }
        val workoutName = routine?.routine?.name ?: previous?.workout?.name ?: name.trim()
        val id = dao.insertWorkout(Workout(
            name = workoutName,
            notes = previous?.workout?.notes.orEmpty(),
            muscleGroups = previous?.workout?.muscleGroups.orEmpty(),
        ))
        if (routine != null) {
            routine.sortedExercises.forEach { item ->
                if (!item.exercise.archived) addExerciseInternal(id, item.exercise.id)
            }
        } else if (previous != null) {
            previous.sortedExercises.forEachIndexed { index, item ->
                val entryId = dao.insertWorkoutExercise(WorkoutExercise(workoutId = id, exerciseId = item.exercise.id,
                    orderIndex = index, notes = item.entry.notes))
                val firstSet = item.sets.minWithOrNull(compareBy<WorkoutSet> { it.setNumber }.thenBy { it.id })
                val set = firstSet?.copy(id = 0, workoutExerciseId = entryId, setNumber = 1, completed = false, setType = SetType.WORKING)
                    ?: WorkoutSet(workoutExerciseId = entryId, setNumber = 1)
                dao.insertSet(set)
            }
        }
        id
    }

    override suspend fun updateWorkout(id: Long, name: String, notes: String) = database.withTransaction {
        WorkoutValidation.validateName(name)
        require(notes.length <= 5000) { "Notes must be 5,000 characters or fewer." }
        val workout = requireNotNull(dao.workout(id)) { "Workout was not found." }
        dao.updateWorkout(workout.copy(name = name.trim(), notes = notes))
    }

    override suspend fun updateMuscleGroups(id: Long, groups: Set<MuscleGroup>) = database.withTransaction {
        val workout = requireNotNull(dao.workout(id)) { "Workout was not found." }
        dao.updateWorkout(workout.copy(muscleGroups = groups.sortedBy { it.ordinal }))
    }

    override suspend fun finishWorkout(id: Long) = database.withTransaction {
        val detail = requireNotNull(dao.workoutDetail(id)) { "Workout was not found." }
        require(detail.workout.endTime == null) { "This workout is already complete." }
        require(detail.exercises.any { exercise -> exercise.sets.any { it.completed } }) {
            "Complete at least one set before finishing this workout."
        }
        detail.exercises.forEach { exercise -> exercise.sets.filter { it.completed }.forEach {
            WorkoutValidation.validateSet(it, exercise.exercise.exerciseType)
        } }
        dao.updateWorkout(detail.workout.copy(endTime = maxOf(System.currentTimeMillis(), detail.workout.startTime), restEndTime = null))
    }

    override suspend fun deleteWorkout(id: Long) = database.withTransaction { dao.deleteWorkout(id) }

    override suspend fun addExercise(workoutId: Long, exerciseId: Long) = database.withTransaction {
        addExerciseInternal(workoutId, exerciseId)
        Unit
    }

    private suspend fun addExerciseInternal(workoutId: Long, exerciseId: Long): Long {
        requireNotNull(dao.workout(workoutId)) { "Workout was not found." }
        val exercise = requireNotNull(dao.exercise(exerciseId)) { "Exercise was not found." }
        require(!exercise.archived) { "This exercise has been deleted." }
        val entries = dao.workoutExercises(workoutId)
        require(entries.none { it.exerciseId == exerciseId }) { "This exercise is already in your workout." }
        val entryId = dao.insertWorkoutExercise(WorkoutExercise(workoutId = workoutId, exerciseId = exerciseId, orderIndex = entries.size))
        addSetInternal(entryId)
        return entryId
    }

    override suspend fun removeWorkoutExercise(id: Long) = database.withTransaction {
        val entry = requireNotNull(dao.workoutExercise(id)) { "Workout exercise was not found." }
        dao.deleteWorkoutExercise(id)
        dao.workoutExercises(entry.workoutId).forEachIndexed { index, item -> dao.updateWorkoutExercise(item.copy(orderIndex = index)) }
    }

    override suspend fun moveWorkoutExercise(id: Long, delta: Int) = database.withTransaction {
        val item = requireNotNull(dao.workoutExercise(id)) { "Workout exercise was not found." }
        val entries = dao.workoutExercises(item.workoutId)
        val index = entries.indexOfFirst { it.id == id }
        val next = (index + delta).coerceIn(0, entries.lastIndex)
        if (next != index) {
            val reordered = entries.toMutableList().apply { add(next, removeAt(index)) }
            reordered.forEachIndexed { order, entry -> dao.updateWorkoutExercise(entry.copy(orderIndex = order)) }
        }
    }

    override suspend fun addSet(workoutExerciseId: Long) = database.withTransaction {
        addSetInternal(workoutExerciseId)
        Unit
    }

    private suspend fun addSetInternal(workoutExerciseId: Long): Long {
        val entry = requireNotNull(dao.workoutExercise(workoutExerciseId)) { "Workout exercise was not found." }
        val workout = requireNotNull(dao.workout(entry.workoutId)) { "Workout was not found." }
        val sets = dao.sets(workoutExerciseId)
        val previous = sets.lastOrNull() ?: dao.previousSet(entry.exerciseId, entry.workoutId, workout.startTime)
        val set = previous?.copy(id = 0, workoutExerciseId = workoutExerciseId, setNumber = sets.size + 1, completed = false, setType = SetType.WORKING)
            ?: WorkoutSet(workoutExerciseId = workoutExerciseId, setNumber = sets.size + 1)
        return dao.insertSet(set)
    }

    override suspend fun updateSet(set: WorkoutSet) = database.withTransaction {
        val stored = requireNotNull(dao.workoutSet(set.id)) { "Set was not found." }
        require(set.workoutExerciseId == stored.workoutExerciseId) { "A set cannot be moved to another exercise." }
        val entry = requireNotNull(dao.workoutExercise(stored.workoutExerciseId))
        val exercise = requireNotNull(dao.exercise(entry.exerciseId))
        WorkoutValidation.validateSet(set, exercise.exerciseType)
        dao.updateSet(set.copy(setNumber = stored.setNumber))
    }

    override suspend fun deleteSet(id: Long) = database.withTransaction {
        val set = requireNotNull(dao.workoutSet(id)) { "Set was not found." }
        dao.deleteSet(id)
        dao.sets(set.workoutExerciseId).forEachIndexed { index, item -> dao.updateSet(item.copy(setNumber = index + 1)) }
    }

    override suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String) = database.withTransaction {
        require(notes.length <= 5000) { "Notes must be 5,000 characters or fewer." }
        val entry = requireNotNull(dao.workoutExercise(workoutExerciseId)) { "Workout exercise was not found." }
        dao.updateWorkoutExercise(entry.copy(notes = notes))
    }

    override suspend fun setRestEndTime(workoutId: Long, endTime: Long?) = database.withTransaction {
        val workout = requireNotNull(dao.workout(workoutId)) { "Workout was not found." }
        require(workout.endTime == null || endTime == null) { "A finished workout cannot start a rest timer." }
        dao.updateWorkout(workout.copy(restEndTime = endTime))
    }

    override suspend fun saveRoutine(id: Long?, name: String): Long = database.withTransaction {
        WorkoutValidation.validateName(name)
        if (id == null || id == 0L) dao.insertRoutine(Routine(name = name.trim()))
        else {
            val routine = requireNotNull(dao.routine(id)) { "Routine was not found." }
            dao.updateRoutine(routine.copy(name = name.trim()))
            id
        }
    }

    override suspend fun addRoutineExercise(routineId: Long, exerciseId: Long) = database.withTransaction {
        requireNotNull(dao.routine(routineId)) { "Routine was not found." }
        val exercise = requireNotNull(dao.exercise(exerciseId)) { "Exercise was not found." }
        require(!exercise.archived) { "This exercise has been deleted." }
        val entries = dao.routineExercises(routineId)
        require(entries.none { it.exerciseId == exerciseId }) { "This exercise is already in this routine." }
        dao.insertRoutineExercise(RoutineExercise(routineId = routineId, exerciseId = exerciseId, orderIndex = entries.size))
        Unit
    }

    override suspend fun removeRoutineExercise(id: Long) = database.withTransaction {
        val entry = requireNotNull(dao.routineExercise(id)) { "Routine exercise was not found." }
        dao.deleteRoutineExercise(id)
        dao.routineExercises(entry.routineId).forEachIndexed { index, item -> dao.updateRoutineExercise(item.copy(orderIndex = index)) }
    }

    override suspend fun moveRoutineExercise(id: Long, delta: Int) = database.withTransaction {
        val item = requireNotNull(dao.routineExercise(id)) { "Routine exercise was not found." }
        val entries = dao.routineExercises(item.routineId)
        val index = entries.indexOfFirst { it.id == id }
        val next = (index + delta).coerceIn(0, entries.lastIndex)
        if (next != index) {
            val reordered = entries.toMutableList().apply { add(next, removeAt(index)) }
            reordered.forEachIndexed { order, entry -> dao.updateRoutineExercise(entry.copy(orderIndex = order)) }
        }
    }

    override suspend fun deleteRoutine(id: Long) = database.withTransaction { dao.deleteRoutine(id) }
}

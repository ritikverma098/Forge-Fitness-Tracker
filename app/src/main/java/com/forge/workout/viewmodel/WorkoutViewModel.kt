package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.model.WorkoutSet
import com.forge.workout.model.SetType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkoutViewModel(
    private val repository: WorkoutRepository,
    private val errorFormatter: (Throwable) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutex = Mutex()
    private val pendingLock = Any()
    private val pendingJobs = mutableSetOf<Job>()
    private var mutationFailed = false
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _invalidInputs = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    val invalidInputs = _invalidInputs.asStateFlow()

    fun clearError() { _error.value = null }

    fun inputValidity(workoutId: Long, key: String, valid: Boolean) {
        _invalidInputs.update { current ->
            val keys = current[workoutId].orEmpty().let { if (valid) it - key else it + key }
            if (keys.isEmpty()) current - workoutId else current + (workoutId to keys)
        }
    }

    fun clearWorkoutValidity(workoutId: Long) {
        _invalidInputs.update { it - workoutId }
    }
    fun clearAllValidity() { _invalidInputs.value = emptyMap() }

    private fun clearInputsMatching(predicate: (String) -> Boolean) {
        _invalidInputs.update { current ->
            current.mapValues { (_, keys) -> keys.filterNot(predicate).toSet() }.filterValues { it.isNotEmpty() }
        }
    }

    private fun perform(action: suspend () -> Unit) {
        // Register before dispatching so flush sees jobs that have not entered the mutex yet.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            mutex.withLock {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutationFailed = true
                    _error.value = errorFormatter(failure)
                }
            }
        }
        synchronized(pendingLock) { pendingJobs += job }
        job.invokeOnCompletion { synchronized(pendingLock) { pendingJobs -= job } }
        job.start()
    }

    fun rename(id: Long, name: String, notes: String) = perform { repository.updateWorkout(id, name, notes) }
    fun toggleMuscleGroup(id: Long, group: MuscleGroup) = perform {
        val selected = repository.workout(id)?.muscleGroups?.toSet() ?: return@perform
        repository.updateMuscleGroups(id, if(group in selected) selected - group else selected + group)
    }
    fun addExercise(id: Long, exerciseId: Long) = perform { repository.addExercise(id, exerciseId) }
    fun moveExercise(id: Long, delta: Int) = perform { repository.moveWorkoutExercise(id, delta) }
    fun exerciseNotes(id: Long, notes: String) = perform { repository.updateExerciseNotes(id, notes) }
    fun addSet(id: Long) = perform { repository.addSet(id) }
    fun rest(id: Long, endTime: Long?) = perform { repository.setRestEndTime(id, endTime) }
    fun adjustRest(id: Long, seconds: Int) = perform {
        require(seconds == -15 || seconds == 15)
        val workout = repository.workout(id)
            ?: return@perform
        val deadline = workout.restEndTime ?: return@perform
        val now = clock()
        if(workout.endTime != null || deadline <= now) return@perform
        val delta = seconds * 1_000L
        val adjusted = if(delta > 0) deadline.coerceAtMost(Long.MAX_VALUE - delta) + delta else deadline + delta
        repository.setRestEndTime(id, adjusted.coerceAtLeast(now))
    }

    fun expireRest(id: Long, expectedDeadline: Long, onExpired: () -> Unit = {}) = perform {
        val workout = repository.workout(id)
            ?: return@perform
        // A stale screen effect must not clear a timer that was extended or restarted.
        if(workout.endTime != null || workout.restEndTime != expectedDeadline || expectedDeadline > clock()) return@perform
        if(repository.consumeRestTimer(id, expectedDeadline, clock())) onExpired()
    }

    fun removeExercise(id: Long) = perform {
        val sets = repository.workoutExerciseSets(id)
        repository.removeWorkoutExercise(id)
        val setPrefixes = sets.map { "set_${it.id}_" }
        clearInputsMatching { key ->
            key == "notes_$id" || key.startsWith("exercise_${id}_") || setPrefixes.any { prefix -> key.startsWith(prefix) }
        }
    }

    fun deleteSet(id: Long) = perform {
        repository.deleteSet(id)
        clearInputsMatching { it.startsWith("set_${id}_") }
    }

    fun setValue(id: Long, field: SetField, value: Double) = perform {
        validateValue(field, value)
        val set = currentSet(id) ?: return@perform
        repository.updateSet(when (field) {
            SetField.WEIGHT -> set.copy(weight = value)
            SetField.REPS -> set.copy(reps = value.toInt())
            SetField.DURATION -> set.copy(duration = value.toInt())
            SetField.DISTANCE -> set.copy(distance = value)
        })
    }
    fun setType(id: Long, type: SetType) = perform {
        val set = currentSet(id) ?: return@perform
        repository.updateSet(set.copy(setType = type))
    }
    fun completeSet(id: Long, completed: Boolean, workoutId: Long, restSeconds: Int, editing: Boolean) = perform {
        val set = currentSet(id) ?: return@perform
        repository.updateSet(set.copy(completed = completed))
        if (completed && restSeconds > 0 && !editing) {
            repository.setRestEndTime(workoutId, clock() + restSeconds * 1_000L)
        }
    }

    private suspend fun currentSet(id: Long): WorkoutSet? = repository.workoutSet(id)

    private fun validateValue(field: SetField, value: Double) {
        when (field) {
            SetField.WEIGHT -> require(value.isFinite() && value in 0.0..10000.0) { "Enter a weight from 0 to 10,000 kg." }
            SetField.REPS -> require(value.isFinite() && value in 0.0..10000.0 && value % 1.0 == 0.0) { "Enter repetitions from 0 to 10,000." }
            SetField.DURATION -> require(value.isFinite() && value in 0.0..604800.0 && value % 1.0 == 0.0) { "Enter a duration from 0 to 604,800 seconds." }
            SetField.DISTANCE -> require(value.isFinite() && value in 0.0..100000.0) { "Enter a distance from 0 to 100,000 km." }
        }
    }

    /** Waits for queued mutations and reports failures before a finish, discard, or return. */
    suspend fun flush(): Boolean {
        while (true) {
            val waiting = synchronized(pendingLock) { pendingJobs.toList() }
            if (waiting.isEmpty()) break
            waiting.joinAll()
        }
        return mutex.withLock {
            val succeeded = !mutationFailed
            mutationFailed = false
            succeeded
        }
    }
}
enum class SetField { WEIGHT, REPS, DURATION, DISTANCE }

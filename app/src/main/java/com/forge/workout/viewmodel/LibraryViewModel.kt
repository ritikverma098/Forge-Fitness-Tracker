package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.model.Exercise
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.utils.ExerciseRanking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

data class ExerciseSaveResult(val requestId: String, val savedExercise: Exercise? = null, val error: String? = null)

class LibraryViewModel(private val repository: WorkoutRepository, private val errorFormatter: (Throwable) -> String) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _exerciseSaveResult = MutableStateFlow<ExerciseSaveResult?>(null)
    val exerciseSaveResult: StateFlow<ExerciseSaveResult?> = _exerciseSaveResult.asStateFlow()
    val rankedExercises = combine(repository.observeExercises().distinctUntilChanged(), repository.observeExerciseUsage().distinctUntilChanged()) { exercises, usage ->
        ExerciseRanking.rank(exercises, usage, System.currentTimeMillis())
    }.flowOn(Dispatchers.Default).catch { failure ->
        if(failure is CancellationException) throw failure
        _error.value = errorFormatter(failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearError() { _error.value = null }

    fun acknowledgeExerciseSave(requestId: String) {
        val result = _exerciseSaveResult.value
        if (result?.requestId == requestId) _exerciseSaveResult.compareAndSet(result, null)
    }

    fun saveExercise(exercise: Exercise, requestId: String? = null, onSaved: (Long) -> Unit = {}) = perform {
        try {
            val id = repository.saveExercise(exercise)
            if (requestId != null) _exerciseSaveResult.value = ExerciseSaveResult(requestId,
                exercise.copy(id = id, name = exercise.name.trim(), notes = exercise.notes.trim(),
                    isCustom = if (exercise.id == 0L) true else exercise.isCustom,
                    archived = if (exercise.id == 0L) false else exercise.archived))
            onSaved(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (requestId != null) _exerciseSaveResult.value = ExerciseSaveResult(requestId,
                error = errorFormatter(failure))
            throw failure
        }
    }

    fun deleteExercise(id: Long, onDeleted: () -> Unit = {}) = perform {
        repository.deleteExercise(id)
        onDeleted()
    }

    private fun perform(operation: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = errorFormatter(failure)
            } finally {
                _busy.value = false
            }
        }
    }
}

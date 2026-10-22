package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoutineViewModel(private val repository: WorkoutRepository, private val errorFormatter: (Throwable) -> String) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun clearError() { _error.value = null }

    fun saveRoutine(id: Long?, name: String, onSaved: (Long) -> Unit = {}) = perform {
        onSaved(repository.saveRoutine(id, name.trim()))
    }

    fun addExercise(routineId: Long, exerciseId: Long, onAdded: () -> Unit = {}) = perform {
        repository.addRoutineExercise(routineId, exerciseId)
        onAdded()
    }

    fun removeExercise(id: Long) = perform { repository.removeRoutineExercise(id) }

    fun moveExercise(id: Long, delta: Int) = perform { repository.moveRoutineExercise(id, delta) }

    fun deleteRoutine(id: Long, onDeleted: () -> Unit = {}) = perform {
        repository.deleteRoutine(id)
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

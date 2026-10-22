package com.forge.workout.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.R
import com.forge.workout.backup.*
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.preferences.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupPreview(
    val createdAt: Long,
    val workoutCount: Int,
    val routineCount: Int,
    val customExerciseCount: Int,
    val hasNutrition: Boolean = false,
    val customFoodCount: Int = 0,
    val recipeCount: Int = 0,
    val diaryCount: Int = 0,
    val bodyWeightCount: Int = 0,
)
data class BackupUiState(
    val busy: Boolean = false,
    val pending: BackupPreview? = null,
    val restoreGeneration: Int = 0,
    val errorMessage: Int? = null,
)

class BackupViewModel(
    private val backupRepository: BackupRepository,
    private val preferences: PreferencesRepository,
    private val documents: BackupDocuments,
    private val flushBeforeOperation: suspend () -> Boolean = { true },
    private val onRestored: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val beforeRestore: suspend () -> Unit = {},
    private val afterRestoreAttempt: suspend () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(BackupUiState())
    val state = _state.asStateFlow()
    private val messages = Channel<Int>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private var prepared: ForgeBackup? = null

    fun writeBackup(uri: Uri) = perform(R.string.backup_write_failed) {
        requireSavedChanges()
        withContext(ioDispatcher) {
            documents.write(uri, BackupCodec.encode(backupRepository.snapshot()))
        }
        messages.send(R.string.backup_created)
    }

    fun readBackup(uri: Uri) {
        if(_state.value.busy) return
        prepared = null
        _state.update { it.copy(pending = null) }
        perform(R.string.backup_read_failed) {
            val backup = withContext(ioDispatcher) { BackupCodec.decode(documents.read(uri)) }
            val nutrition = backup.nutrition
            prepared = backup
            _state.update { it.copy(pending = BackupPreview(backup.createdAt, backup.workouts.size,
                backup.routines.size, backup.exercises.count { exercise -> exercise.isCustom },
                hasNutrition = nutrition != null,
                customFoodCount = nutrition?.foods?.count { it.food.sourceType == FoodSourceType.CUSTOM } ?: 0,
                recipeCount = nutrition?.recipes?.size ?: 0,
                diaryCount = nutrition?.diary?.size ?: 0,
                bodyWeightCount = nutrition?.bodyWeights?.size ?: 0)) }
        }
    }

    fun cancelRestore() {
        if(_state.value.busy) return
        prepared = null
        _state.update { it.copy(pending = null, errorMessage = null) }
    }

    fun confirmRestore() {
        val backup = prepared ?: return
        perform(R.string.backup_restore_failed) {
            requireSavedChanges()
            try {
                beforeRestore()
                withContext(ioDispatcher) {
                    val previous = preferences.preferences.first()
                    var preferencesWritten = false
                    try {
                        backupRepository.restore(backup) {
                            // Record a completed DataStore write even if the Room transaction is cancelled.
                            withContext(NonCancellable) {
                                preferences.replace(backup.preferences)
                                preferencesWritten = true
                            }
                        }
                    } catch(failure: Exception) {
                        if(preferencesWritten) withContext(NonCancellable) { preferences.replace(previous) }
                        throw failure
                    }
                }
            } finally {
                withContext(NonCancellable) { afterRestoreAttempt() }
            }
            prepared = null
            onRestored()
            _state.update { it.copy(pending = null, restoreGeneration = it.restoreGeneration + 1) }
            messages.send(R.string.backup_restored)
        }
    }

    private suspend fun requireSavedChanges() {
        if(!flushBeforeOperation()) throw UnsavedChangesException()
    }

    private fun perform(failureMessage: Int, action: suspend () -> Unit) {
        if(_state.value.busy) return
        _state.update { it.copy(busy = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                action()
            } catch(cancelled: CancellationException) {
                throw cancelled
            } catch(failure: Exception) {
                val message = when(failure) {
                    is BackupValidationException -> if(failure.issue == BackupIssue.UNSUPPORTED_VERSION)
                        R.string.backup_version_unsupported else R.string.backup_invalid
                    is UnsavedChangesException -> R.string.failed_save_retry
                    else -> failureMessage
                }
                _state.update { it.copy(errorMessage = message) }
                messages.send(message)
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private class UnsavedChangesException : Exception()
}

package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.model.*
import com.forge.workout.preferences.*
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AppUiState(
    val exercises: List<Exercise> = emptyList(),
    val workouts: List<WorkoutDetail> = emptyList(),
    val routines: List<RoutineDetail> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val ready: Boolean = false
) { val activeWorkout: WorkoutDetail? get() = workouts.firstOrNull { it.workout.endTime == null } }
sealed interface AppEvent {
    data class Message(val value: String): AppEvent
    data class OpenWorkout(val id: Long): AppEvent
    data class OpenSummary(val id: Long): AppEvent
    data object GoHome: AppEvent
}
class AppViewModel(private val repository: WorkoutRepository, private val preferencesRepository: PreferencesRepository,
    private val errorFormatter: (Throwable) -> String, private val newWorkoutName: () -> String): ViewModel() {
    private val ready = MutableStateFlow(false)
    private val eventChannel = Channel<AppEvent>(Channel.BUFFERED)
    private val actions = Mutex()
    val events = eventChannel.receiveAsFlow()
    val state = combine(repository.observeExercises(), repository.observeWorkouts(), repository.observeRoutines(), preferencesRepository.preferences, ready) { exercises,workouts,routines,preferences,loaded ->
        AppUiState(exercises,workouts,routines,preferences,loaded)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())
    init { perform { repository.ensureSeeded(); ready.value = true } }
    private fun perform(action: suspend () -> Unit) { viewModelScope.launch {
        actions.withLock {
            try { action() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { eventChannel.send(AppEvent.Message(errorFormatter(e))) }
        }
    } }
    fun start(routineId: Long? = null, repeatId: Long? = null) = perform {
        val id = repository.startWorkout(newWorkoutName(), routineId, repeatId)
        eventChannel.send(AppEvent.OpenWorkout(id))
    }
    fun finish(id: Long) = perform { repository.finishWorkout(id); eventChannel.send(AppEvent.OpenSummary(id)) }
    fun deleteWorkout(id: Long) = perform { repository.deleteWorkout(id); eventChannel.send(AppEvent.GoHome) }
    fun setTheme(mode: ThemeMode) = perform { preferencesRepository.setTheme(mode) }
    fun setUnit(unit: WeightUnit) = perform { preferencesRepository.setUnit(unit) }
    fun setRest(seconds: Int) = perform { preferencesRepository.setRest(seconds) }
    fun setConfirmation(value: Boolean) = perform { preferencesRepository.setConfirmation(value) }

    /** Waits for preceding actions, including preference writes, before a backup operation. */
    suspend fun flush() { actions.withLock { } }
}

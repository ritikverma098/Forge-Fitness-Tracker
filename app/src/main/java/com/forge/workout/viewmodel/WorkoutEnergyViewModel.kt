package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class WorkoutEnergyState(val workout: EnergyWorkout? = null, val energy: WorkoutEnergyEntity? = null,
    val loading: Boolean = true, val error: Boolean = false, val workoutMissing: Boolean = false, val missingWeight: Boolean = false)
internal data class EnergySource(val workout: EnergyWorkout? = null, val energy: WorkoutEnergyEntity? = null,
    val weight: BodyWeightEntity? = null, val settings: NutritionSettingsEntity = NutritionSettingsEntity(),
    val loading: Boolean = true, val error: Boolean = false) {
    val workoutMissing get() = !loading && (workout == null || (workout.endTime - workout.startTime) / 1000 <= 0)
    val missingWeight get() = !loading && !workoutMissing && weight == null && energy == null
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeEnergySource(repository: NutritionRepository, id: Long, zone: ZoneId): Flow<EnergySource> = flow {
    emit(EnergySource())
    emitAll(combine(repository.observeEnergyWorkout(id), repository.observeWorkoutEnergyById(id), repository.observeSettings()) { workout, energy, settings ->
        EnergySource(workout, energy, settings = settings, loading = false)
    }.flatMapLatest { source ->
        val workout = source.workout
        if (workout == null) flowOf(source) else repository.observeLatestBodyWeight(WorkoutEnergyMath.day(workout, source.energy, zone))
            .map { source.copy(weight = it) }
    })
}.catch { failure ->
    if (failure is CancellationException) throw failure
    emit(EnergySource(loading = false, error = true))
}.flowOn(Dispatchers.Default)

/** Reads targeted persisted inputs; actual estimation belongs to the transactional activity writer. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEnergyViewModel(repository: NutritionRepository, id: Long, zone: ZoneId = ZoneId.systemDefault()) : ViewModel() {
    private val generation = MutableStateFlow(0)
    val state: StateFlow<WorkoutEnergyState> = generation.flatMapLatest { observeEnergySource(repository, id, zone) }
        .map { WorkoutEnergyState(it.workout, it.energy, it.loading, it.error, it.workoutMissing, it.missingWeight) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutEnergyState())
    fun reload() { generation.value++ }
}

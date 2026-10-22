package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.DailyNutritionTotals
import com.forge.workout.nutrition.model.DiaryEntity
import com.forge.workout.nutrition.model.DiaryMath
import com.forge.workout.nutrition.model.NutritionGoalEntity
import com.forge.workout.nutrition.model.WorkoutEnergyEntity
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class DailyNutritionState(
    val day: String,
    val loading: Boolean = true,
    val entries: List<DiaryEntity> = emptyList(),
    val goal: NutritionGoalEntity? = null,
    val totals: DailyNutritionTotals = DailyNutritionTotals(),
    val error: Boolean = false,
    val workoutEnergy: List<WorkoutEnergyEntity> = emptyList(),
    val includeWorkoutCalories: Boolean = false,
)

/** Watches only the selected diary day and its effective goal; summation happens off the UI thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionDashboardViewModel(repository: NutritionRepository, day: StateFlow<String>) : ViewModel() {
    private val generation = MutableStateFlow(0)

    val state: StateFlow<DailyNutritionState> = combine(day, generation) { date, _ -> date }.flatMapLatest { date ->
        flow {
            emit(DailyNutritionState(date))
            emitAll(combine(repository.observeDiary(date), repository.observeGoal(date),
                repository.observeWorkoutEnergy(date), repository.observeSettings()) { entries, goal, energy, settings ->
                val frozen = entries.map { it.copy(snapshot = it.snapshot.copy(nutrients = it.snapshot.nutrients.toList())) }
                DailyNutritionState(date, loading = false, entries = frozen, goal = goal, totals = DiaryMath.totals(frozen),
                    workoutEnergy = energy.toList(), includeWorkoutCalories = settings.includeWorkoutCalories)
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(DailyNutritionState(date, loading = false, error = true))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyNutritionState(day.value))

    fun reload() { generation.value++ }
}

package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FoodLogResult(val requestId: String, val entryId: Long? = null, val error: String? = null, val operationId: Long = 0)

/** Owns the selected logging context and writer across picker/detail navigation and rotation. */
class FoodLogViewModel(
    private val repository: NutritionRepository,
    private val savedState: SavedStateHandle,
    private val formatError: (Throwable) -> String,
) : ViewModel() {
    val day: StateFlow<String> = savedState.getStateFlow("food_log_day", LocalDate.now().toString())
    val meal: StateFlow<String> = savedState.getStateFlow("food_log_meal", MealType.BREAKFAST.name)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _result = MutableStateFlow<FoodLogResult?>(null)
    val result = _result.asStateFlow()
    private var sequence = 0L
    private var generation = 0

    fun selectDay(value: String) { NutritionValidation.day(value); savedState["food_log_day"] = value }
    fun selectMeal(value: MealType) { savedState["food_log_meal"] = value.name }

    fun log(requestId: String, snapshot: FoodSnapshot, choice: FoodServingChoice, quantity: Double) {
        if (!_busy.compareAndSet(false, true)) return
        val date = day.value
        val category = MealType.valueOf(meal.value)
        val stable = snapshot.copy(nutrients = snapshot.nutrients.toList())
        _result.value = null
        val operationId = ++sequence
        val expectedGeneration = generation
        viewModelScope.launch {
            try {
                val factor = NutritionMath.factor(stable, choice, quantity)
                val id = repository.saveDiary(DiaryEntity(day = date, meal = category,
                    foodId = stable.foodId, snapshot = stable, quantity = quantity, unit = choice.unit,
                    portionId = choice.portionId, portionLabel = choice.label.takeIf { choice.portionId != null }, factor = factor))
                if (expectedGeneration == generation) _result.value = FoodLogResult(requestId, entryId = id, operationId = operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (expectedGeneration == generation) _result.value = FoodLogResult(requestId, error = formatError(failure), operationId = operationId)
            } finally { _busy.value = false }
        }
    }

    fun acknowledge(requestId: String) {
        val current = _result.value
        if (current?.requestId == requestId) _result.compareAndSet(current, null)
    }

    fun acknowledge(expected: FoodLogResult) { if (_result.value === expected) _result.compareAndSet(expected, null) }

    suspend fun flush() { busy.first { !it } }
    fun resetAfterRestore() {
        generation++
        _result.value = null
        selectDay(LocalDate.now().toString())
        selectMeal(MealType.BREAKFAST)
    }
}

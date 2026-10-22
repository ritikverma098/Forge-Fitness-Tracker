package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiaryMutationResult(val requestId: String, val entryId: Long? = null, val error: String? = null, val operationId: Long = 0)

/** Activity-owned writes retain outcomes across destination pauses; one pending operation rejects double taps. */
class DiaryMutationViewModel(private val repository: NutritionRepository, private val formatError: (Throwable) -> String) : ViewModel() {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableResult = MutableStateFlow<DiaryMutationResult?>(null)
    val result = mutableResult.asStateFlow()
    private var operation: Job? = null
    private var generation = 0
    private var sequence = 0L

    fun saveEntry(requestId: String, original: DiaryEntity, choice: FoodServingChoice, quantity: Double, day: String, meal: MealType) {
        val stable = original.copy(snapshot = original.snapshot.copy(nutrients = original.snapshot.nutrients.toList()))
        perform(requestId) {
            require(stable.id > 0) { "Diary entry was not found." }
            validateChoice(stable.snapshot, choice)
            val factor = NutritionMath.factor(stable.snapshot, choice, quantity)
            val id = repository.saveDiary(stable.copy(day = day, meal = meal, quantity = quantity, unit = choice.unit,
                portionId = choice.portionId, portionLabel = choice.label.takeIf { choice.portionId != null }, factor = factor))
            DiaryMutationResult(requestId, id)
        }
    }

    fun delete(requestId: String, id: Long) = perform(requestId) {
        repository.deleteDiary(id)
        DiaryMutationResult(requestId, id)
    }

    fun move(requestId: String, id: Long, day: String, meal: MealType) = perform(requestId) {
        repository.moveDiary(id, day, meal)
        DiaryMutationResult(requestId, id)
    }

    fun repeat(requestId: String, ids: List<Long>, day: String, meal: MealType) {
        val stableIds = ids.toList()
        perform(requestId) { DiaryMutationResult(requestId, repository.repeatDiary(stableIds, day, meal).firstOrNull()) }
    }

    fun saveGoal(requestId: String, goal: NutritionGoalEntity) = perform(requestId) {
        repository.saveGoal(goal)
        DiaryMutationResult(requestId)
    }

    fun acknowledge(expected: DiaryMutationResult) {
        if (mutableResult.value === expected) mutableResult.compareAndSet(expected, null)
    }

    suspend fun flush() { operation?.join() }

    fun resetAfterRestore() { generation++; mutableResult.value = null }

    private fun perform(requestId: String, action: suspend () -> DiaryMutationResult) {
        if (!mutableBusy.compareAndSet(false, true)) return
        mutableResult.value = null
        val expectedGeneration = generation
        val operationId = ++sequence
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val value = action()
                if (generation == expectedGeneration) mutableResult.value = value.copy(operationId = operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == expectedGeneration) mutableResult.value = DiaryMutationResult(requestId, error = formatError(failure), operationId = operationId)
            }
        }
        operation = job
        job.invokeOnCompletion {
            if (operation === job) { operation = null; mutableBusy.value = false }
        }
        job.start()
    }

    private fun validateChoice(snapshot: FoodSnapshot, choice: FoodServingChoice) {
        NutritionValidation.text(choice.id, 300, "Serving choice ID")
        require((choice.portionId == null) == (choice.label == null)) { "Selected portion needs both ID and label." }
        if (choice.portionId == null) {
            val native = NutritionMath.choices(snapshot).firstOrNull { it.unit == choice.unit }
            require(native != null && kotlin.math.abs(native.baseQuantity - choice.baseQuantity) <= maxOf(1.0, native.baseQuantity) * 1e-9) {
                "Food conversion must match its recorded snapshot."
            }
        }
    }
}

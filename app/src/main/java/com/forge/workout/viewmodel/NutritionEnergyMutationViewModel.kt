package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NutritionEnergyMutationResult(val requestId: String, val error: String? = null, val operationId: Long = 0)

/** Each command resolves current database inputs in the repository's transaction. */
class NutritionEnergyMutationViewModel(private val repository: NutritionRepository, private val formatError: (Throwable) -> String) : ViewModel() {
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableResult = MutableStateFlow<NutritionEnergyMutationResult?>(null)
    val result = mutableResult.asStateFlow()
    private var operation: Job? = null
    private var generation = 0
    private var sequence = 0L
    fun saveWeight(requestId: String, day: String, kg: Double) = perform(requestId) { repository.saveBodyWeight(BodyWeightEntity(day = day, kg = kg)) }
    fun deleteWeight(requestId: String, id: Long) = perform(requestId) { repository.deleteBodyWeight(id) }
    fun saveSettings(requestId: String, settings: NutritionSettingsEntity) = perform(requestId) { repository.saveSettings(settings) }
    fun saveEstimate(requestId: String, workoutId: Long, preset: WorkoutActivityPreset, activeFraction: Double, manualKcal: Double?) = perform(requestId) {
        requireNotNull(repository.updateWorkoutEstimate(workoutId, preset, activeFraction, manualKcal)) { "Record an eligible body weight before estimating this workout." }
    }
    fun acknowledge(expected: NutritionEnergyMutationResult) { if (mutableResult.value === expected) mutableResult.compareAndSet(expected, null) }
    suspend fun flush() { operation?.join() }
    fun resetAfterRestore() { generation++; mutableResult.value = null }
    private fun perform(requestId: String, action: suspend () -> Unit) {
        if (!mutableBusy.compareAndSet(false, true)) return
        mutableResult.value = null
        val expectedGeneration = generation
        val operationId = ++sequence
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
                if (generation == expectedGeneration) mutableResult.value = NutritionEnergyMutationResult(requestId, operationId = operationId)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == expectedGeneration) mutableResult.value = NutritionEnergyMutationResult(requestId, formatError(failure), operationId)
            }
        }
        operation = job
        job.invokeOnCompletion { if (operation === job) { operation = null; mutableBusy.value = false } }
        job.start()
    }
}

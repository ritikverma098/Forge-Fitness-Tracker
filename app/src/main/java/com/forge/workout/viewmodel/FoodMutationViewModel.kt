package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.FavoriteEntity
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.RecipeEntity
import com.forge.workout.nutrition.model.RecipeIngredientEntity
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FoodMutationResult(
    val requestId: String,
    val foodId: String? = null,
    val deleted: Boolean = false,
    val error: String? = null,
    val operationId: Long = 0,
)

/** Retained request results keep a successful save deliverable after the editor rotates or pauses. */
class FoodMutationViewModel(
    private val repository: NutritionRepository,
    private val formatError: (Throwable) -> String,
) : ViewModel() {
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableResult = MutableStateFlow<FoodMutationResult?>(null)
    val saveResult: StateFlow<FoodMutationResult?> = mutableResult.asStateFlow()
    private var operation: Job? = null
    private var resetGeneration = 0
    private var sequence = 0L

    fun saveFood(requestId: String, detail: FoodDetail) {
        val stable = detail.copy(nutrients = detail.nutrients.toList(), portions = detail.portions.toList())
        perform(requestId, stable.food.id) {
            val id = repository.saveCustomFood(stable)
            FoodMutationResult(requestId, id)
        }
    }

    fun deleteFood(requestId: String, id: String) = perform(requestId, id) {
        repository.deleteCustomFood(id)
        FoodMutationResult(requestId, id, deleted = true)
    }

    fun saveRecipe(requestId: String, recipe: RecipeEntity, food: FoodDetail, ingredients: List<RecipeIngredientEntity>) {
        val stableFood = food.copy(nutrients = food.nutrients.toList(), portions = food.portions.toList())
        val stableIngredients = ingredients.map { it.copy(snapshot = it.snapshot.copy(nutrients = it.snapshot.nutrients.toList())) }
        perform(requestId, recipe.id) {
            require(recipe.id.startsWith("recipe:") || repository.recipe(recipe.id) != null) { "New recipes need a recipe ID." }
            val id = repository.saveRecipe(recipe, stableFood, stableIngredients)
            FoodMutationResult(requestId, id)
        }
    }

    fun deleteRecipe(requestId: String, id: String) = perform(requestId, id) {
        repository.deleteRecipe(id)
        FoodMutationResult(requestId, id, deleted = true)
    }

    fun favorite(id: String, name: String?, enabled: Boolean) = perform("favorite:$id", id) {
        if (enabled) repository.saveFavorite(FavoriteEntity(id, name?.trim()?.take(250)))
        else repository.deleteFavorite(id)
        null
    }

    fun acknowledge(requestId: String) {
        val result = mutableResult.value
        if (result?.requestId == requestId) acknowledge(result)
    }

    fun acknowledge(expectedResult: FoodMutationResult) {
        if (mutableResult.value === expectedResult && mutableResult.compareAndSet(expectedResult, null)) mutableError.value = null
    }

    fun clearError() { mutableError.value = null }
    suspend fun flush() { operation?.join() }

    fun resetAfterRestore() {
        resetGeneration++
        mutableResult.value = null
        mutableError.value = null
    }

    private fun perform(requestId: String, id: String, action: suspend () -> FoodMutationResult?) {
        if (!mutableBusy.compareAndSet(false, true)) return
        mutableError.value = null
        mutableResult.value = null
        val generation = resetGeneration
        val operationId = ++sequence
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = action()
                if (generation == resetGeneration && result != null) mutableResult.value = result.copy(operationId = operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == resetGeneration) {
                    val message = formatError(failure)
                    mutableError.value = message
                    mutableResult.value = FoodMutationResult(requestId, id, error = message, operationId = operationId)
                }
            }
        }
        operation = job
        job.invokeOnCompletion {
            if (operation === job) {
                operation = null
                mutableBusy.value = false
            }
        }
        job.start()
    }
}

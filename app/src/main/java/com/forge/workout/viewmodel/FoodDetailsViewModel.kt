package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class FoodDetailsUiState(
    val loading: Boolean = true,
    val detail: FoodDetail? = null,
    val error: Boolean = false,
    val favorite: Boolean = false,
)

/** Observes one stable food ID; a catalog update refreshes this screen without loading the library. */
@OptIn(ExperimentalCoroutinesApi::class)
class FoodDetailsViewModel(repository: NutritionRepository, id: String) : ViewModel() {
    private val generation = MutableStateFlow(0)

    val state: StateFlow<FoodDetailsUiState> = generation.flatMapLatest {
        flow {
            emit(FoodDetailsUiState())
            val detail = repository.observeFood(id).map { value -> value?.copy(
                nutrients = value.nutrients.toList(), portions = value.portions.toList()) }
            emitAll(combine(detail, repository.observeFavorite(id)) { food, favorite ->
                FoodDetailsUiState(loading = false, detail = food, favorite = favorite != null)
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(FoodDetailsUiState(loading = false, error = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodDetailsUiState())

    fun reload() { generation.value++ }
}

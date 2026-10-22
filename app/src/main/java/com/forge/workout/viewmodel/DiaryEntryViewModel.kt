package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.DiaryEntity
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class DiaryEntryState(val entry: DiaryEntity? = null, val loading: Boolean = true, val error: Boolean = false)

/** A recorded food is self-contained, even after its catalog or recipe is edited or deleted. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryEntryViewModel(repository: NutritionRepository, id: Long) : ViewModel() {
    private val generation = MutableStateFlow(0)

    val state: StateFlow<DiaryEntryState> = generation.flatMapLatest {
        flow {
            emit(DiaryEntryState())
            emitAll(repository.observeDiaryEntry(id).map { entry ->
                DiaryEntryState(entry?.copy(snapshot = entry.snapshot.copy(nutrients = entry.snapshot.nutrients.toList())), loading = false)
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(DiaryEntryState(loading = false, error = true))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryEntryState())

    fun reload() { generation.value++ }
}

package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.FoodBrowseFilter
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

data class FoodPickerUiState(
    val query: String = "",
    val filter: FoodBrowseFilter = FoodBrowseFilter.ALL,
    val foods: List<FoodEntity> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val hasMore: Boolean = false,
    val limitReached: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FoodPickerViewModel(
    private val repository: NutritionRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private data class Request(val query: String, val filter: FoodBrowseFilter, val rows: Int, val revision: Long = 0)
    private data class Slice(val offset: Int, val limit: Int, val visibleRows: Int)
    private data class Result(val request: Request, val ui: FoodPickerUiState)

    private val requests = MutableStateFlow(Request(
        savedStateHandle[QUERY] ?: "",
        savedStateHandle.get<String>(FILTER)?.let { name -> FoodBrowseFilter.entries.find { it.name == name } }
            ?: FoodBrowseFilter.ALL,
        ((savedStateHandle.get<Int>(ROWS) ?: PAGE_SIZE).coerceIn(PAGE_SIZE, MAX_ROWS) / PAGE_SIZE) * PAGE_SIZE,
    ))
    private var completedRequest: Request? = null
    val state: StateFlow<FoodPickerUiState> = requests.flatMapLatest(::observe)
        .filter { it.request == requests.value }
        .onEach { if (!it.ui.loading) completedRequest = it.request }
        .map { it.ui }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            FoodPickerUiState(query = requests.value.query, filter = requests.value.filter))

    fun setQuery(query: String) {
        val current = requests.value
        if (query == current.query) return
        update(current.copy(query = query, rows = PAGE_SIZE, revision = current.revision + 1))
    }

    fun setFilter(filter: FoodBrowseFilter) {
        val current = requests.value
        if (filter == current.filter) return
        update(current.copy(filter = filter, rows = PAGE_SIZE, revision = current.revision + 1))
    }

    fun loadMore() {
        val current = requests.value
        if (completedRequest != current || state.value.loading || state.value.error || !state.value.hasMore || current.rows >= MAX_ROWS) return
        update(current.copy(rows = (current.rows + PAGE_SIZE).coerceAtMost(MAX_ROWS), revision = current.revision + 1))
    }

    fun reload() {
        val current = requests.value
        update(current.copy(revision = current.revision + 1))
    }

    private fun update(request: Request) {
        savedStateHandle[QUERY] = request.query
        savedStateHandle[FILTER] = request.filter.name
        savedStateHandle[ROWS] = request.rows
        requests.value = request
    }

    private fun observe(request: Request): Flow<Result> = flow {
        val old = state.value
        val retained = if (old.query == request.query && old.filter == request.filter) old.foods else emptyList()
        emit(Result(request, FoodPickerUiState(query = request.query, filter = request.filter, foods = retained)))
        val slices = slices(request.rows)
        val pages = slices.map { slice ->
            repository.observeFoodPage(request.query, request.filter, slice.limit, slice.offset)
        }
        emitAll(combine(pages) { results ->
            val visible = results.indices.flatMap { index -> results[index].take(slices[index].visibleRows) }
                .distinctBy { it.id }.take(request.rows)
            val lookahead = results.last().size > slices.last().visibleRows
            Result(request, FoodPickerUiState(request.query, request.filter, visible, loading = false,
                hasMore = lookahead && request.rows < MAX_ROWS, limitReached = lookahead && request.rows == MAX_ROWS))
        })
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(Result(request, FoodPickerUiState(query = request.query, filter = request.filter, loading = false, error = true)))
    }.flowOn(Dispatchers.Default)

    private fun slices(rows: Int): List<Slice> = buildList {
        var offset = 0
        while (offset < rows) {
            val visible = minOf(QUERY_LIMIT, rows - offset)
            val last = offset + visible == rows
            add(Slice(offset, visible + if (last && visible < QUERY_LIMIT) 1 else 0, visible))
            offset += visible
        }
        // The DAO's bound is 200, so a full final chunk uses its own one-row lookahead.
        if (last().visibleRows == QUERY_LIMIT) add(Slice(rows, 1, 0))
    }

    private companion object {
        const val QUERY = "food_picker_query"
        const val FILTER = "food_picker_filter"
        const val ROWS = "food_picker_rows"
        const val PAGE_SIZE = 50
        const val QUERY_LIMIT = 200
        const val MAX_ROWS = 5_000
    }
}

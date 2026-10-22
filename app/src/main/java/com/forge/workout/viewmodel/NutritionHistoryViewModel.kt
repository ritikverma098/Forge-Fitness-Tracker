package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.NutritionHistoryDay
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class NutritionHistoryUiState(val days: List<NutritionHistoryDay> = emptyList(), val fromDay: String = "",
    val toDay: String = "", val loading: Boolean = true, val error: Boolean = false,
    val hasMore: Boolean = false, val limitReached: Boolean = false)

/** Explicit paging watches cached daily aggregates; no history food snapshots are materialized. */
@OptIn(ExperimentalCoroutinesApi::class)
class NutritionHistoryViewModel(private val repository: NutritionRepository, private val savedState: SavedStateHandle) : ViewModel() {
    private data class Request(val from: String, val to: String, val rows: Int, val revision: Long = 0)
    private data class Slice(val offset: Int, val limit: Int, val visible: Int)
    private data class Result(val request: Request, val state: NutritionHistoryUiState)
    private val restoredFrom = savedState.get<String>(FROM).orEmpty()
    private val restoredTo = savedState.get<String>(TO).orEmpty()
    private val restoredRange = validRange(restoredFrom, restoredTo)
    private val requests = MutableStateFlow(Request(if (restoredRange) restoredFrom else "", if (restoredRange) restoredTo else "",
        if (restoredRange) ((savedState.get<Int>(ROWS) ?: PAGE).coerceIn(PAGE, MAX_ROWS) / PAGE) * PAGE else PAGE))
    private var completed: Request? = null
    val state: StateFlow<NutritionHistoryUiState> = requests.flatMapLatest(::observe)
        .filter { it.request == requests.value }
        .onEach { if (!it.state.loading) completed = it.request }
        .map { it.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NutritionHistoryUiState(fromDay = requests.value.from, toDay = requests.value.to))

    init { persist(requests.value) }

    fun setRange(fromDay: String, toDay: String): Boolean {
        val from = fromDay.trim()
        val to = toDay.trim()
        if (!validRange(from, to)) return false
        val current = requests.value
        if (current.from != from || current.to != to) update(current.copy(from = from, to = to, rows = PAGE, revision = current.revision + 1))
        return true
    }
    fun loadMore() {
        val current = requests.value
        if (completed != current || state.value.loading || state.value.error || !state.value.hasMore || current.rows >= MAX_ROWS) return
        update(current.copy(rows = (current.rows + PAGE).coerceAtMost(MAX_ROWS), revision = current.revision + 1))
    }
    fun reload() { requests.value.let { update(it.copy(revision = it.revision + 1)) } }
    private fun update(request: Request) { persist(request); requests.value = request }
    private fun persist(request: Request) { savedState[FROM] = request.from; savedState[TO] = request.to; savedState[ROWS] = request.rows }
    private fun observe(request: Request): Flow<Result> = flow {
        val old = state.value
        val retained = if (old.fromDay == request.from && old.toDay == request.to) old.days else emptyList()
        emit(Result(request, NutritionHistoryUiState(retained, request.from, request.to)))
        val slices = slices(request.rows)
        val pages = slices.map { repository.observeNutritionHistory(request.from.ifBlank { EARLIEST }, request.to.ifBlank { LATEST }, it.limit, it.offset) }
        emitAll(combine(pages) { results ->
            val visible = results.indices.flatMap { index -> results[index].take(slices[index].visible) }
                .distinctBy { it.day }.sortedByDescending { it.day }.take(request.rows)
            val more = results.last().size > slices.last().visible
            Result(request, NutritionHistoryUiState(visible, request.from, request.to, loading = false,
                hasMore = more && request.rows < MAX_ROWS, limitReached = more && request.rows == MAX_ROWS))
        })
    }.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(Result(request, NutritionHistoryUiState(fromDay = request.from, toDay = request.to, loading = false, error = true)))
    }.flowOn(Dispatchers.Default)

    private fun slices(rows: Int): List<Slice> = buildList {
        var offset = 0
        while (offset < rows) {
            val visible = minOf(QUERY_LIMIT, rows - offset)
            add(Slice(offset, visible + if (offset + visible == rows && visible < QUERY_LIMIT) 1 else 0, visible))
            offset += visible
        }
        if (last().visible == QUERY_LIMIT) add(Slice(rows, 1, 0))
    }
    private companion object {
        const val FROM = "nutrition_history_from"; const val TO = "nutrition_history_to"; const val ROWS = "nutrition_history_rows"
        const val EARLIEST = "0001-01-01"; const val LATEST = "9999-12-31"
        const val PAGE = 50; const val QUERY_LIMIT = 200; const val MAX_ROWS = 5000
        fun validRange(from: String, to: String): Boolean = runCatching {
            val start = from.ifBlank { EARLIEST }; val end = to.ifBlank { LATEST }
            NutritionValidation.day(start); NutritionValidation.day(end)
            require(start <= end)
        }.isSuccess
    }
}

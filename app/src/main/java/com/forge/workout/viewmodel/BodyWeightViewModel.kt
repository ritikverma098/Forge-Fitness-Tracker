package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.BodyWeightEntity
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.preferences.WeightUnit
import java.math.RoundingMode
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class BodyWeightDraft(val day: String, val weight: String = "")
data class BodyWeightUiState(val draft: BodyWeightDraft, val unit: WeightUnit = WeightUnit.KG,
    val history: List<BodyWeightEntity> = emptyList(), val selected: BodyWeightEntity? = null,
    val loading: Boolean = true, val error: Boolean = false, val invalidFields: Set<String> = emptySet(),
    val hasMore: Boolean = false, val limitReached: Boolean = false)
private data class WeightForm(val draft: BodyWeightDraft, val unit: WeightUnit, val dirty: Boolean,
    val canonicalKg: Double? = null, val invalidFields: Set<String> = emptySet())
private data class WeightSource(val day: String, val entry: BodyWeightEntity? = null, val loading: Boolean = true, val error: Boolean = false)
private data class WeightHistory(val rows: List<BodyWeightEntity> = emptyList(), val loading: Boolean = true,
    val error: Boolean = false, val hasMore: Boolean = false, val limitReached: Boolean = false, val requestLimit: Int = 0)

/** Numeric drafts remember their input unit; database values and write commands always use kilograms. */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyWeightViewModel(repository: NutritionRepository, private val savedState: SavedStateHandle) : ViewModel() {
    private val initialDay = savedState.get<String>(DAY)?.takeIf { runCatching { NutritionValidation.day(it) }.isSuccess }
        ?: LocalDate.now().toString()
    private val initialUnit = runCatching { WeightUnit.valueOf(savedState.get<String>(UNIT) ?: WeightUnit.KG.name) }.getOrDefault(WeightUnit.KG)
    private val initial = WeightForm(BodyWeightDraft(initialDay, savedState[VALUE] ?: ""), initialUnit,
        savedState.get<Boolean>(DIRTY) == true, savedState.get<Double>(KG)?.takeIf { it.isFinite() })
    private val form = MutableStateFlow(initial)
    private val generation = MutableStateFlow(0)
    private val requested = MutableStateFlow((savedState.get<Int>(LIMIT) ?: 100).coerceIn(100, MAX_ROWS))
    private val selected = combine(form.map { it.draft.day }.distinctUntilChanged(), generation) { day, _ -> day }.flatMapLatest { day ->
        flow {
            emit(WeightSource(day))
            emitAll(repository.observeBodyWeights(day, day, 1).map { WeightSource(day, it.firstOrNull(), loading = false) })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(WeightSource(day, loading = false, error = true))
        }
    }.flowOn(Dispatchers.Default).onEach { loaded ->
        val current = form.value
        if (loaded.day == current.draft.day && !loaded.loading && !loaded.error && !current.dirty) {
            val updated = current.copy(draft = current.draft.copy(weight = text(loaded.entry?.kg?.let(current.unit::fromKg))), canonicalKg = loaded.entry?.kg)
            persist(updated); form.value = updated
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightSource(initialDay))
    private val history = combine(requested, generation) { count, _ -> count }.flatMapLatest { count ->
        flow {
            emit(WeightHistory(rows = state.value.history))
            emitAll(repository.observeBodyWeights("0001-01-01", "9999-12-31", count + 1).map { rows ->
                val more = rows.size > count
                WeightHistory(rows.take(count).toList(), loading = false, hasMore = more && count < MAX_ROWS,
                    limitReached = more && count == MAX_ROWS, requestLimit = count)
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(WeightHistory(loading = false, error = true))
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightHistory())

    val state: StateFlow<BodyWeightUiState> = combine(form, selected, history) { fields, loaded, rows ->
        val sameDay = fields.draft.day == loaded.day
        val draft = if (!fields.dirty && sameDay && !loaded.loading && !loaded.error)
            fields.draft.copy(weight = text(loaded.entry?.kg?.let(fields.unit::fromKg))) else fields.draft
        BodyWeightUiState(draft, fields.unit, rows.rows, loaded.entry.takeIf { sameDay },
            loading = rows.loading || loaded.loading || !sameDay, error = rows.error || loaded.error,
            invalidFields = fields.invalidFields, hasMore = rows.hasMore, limitReached = rows.limitReached)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyWeightUiState(initial.draft, initial.unit))

    init { persist(initial) }

    fun setDay(day: String): Boolean {
        if (runCatching { NutritionValidation.day(day) }.isFailure) { form.value = form.value.copy(invalidFields = setOf("day")); return false }
        val current = form.value
        if (day != current.draft.day) {
            val updated = WeightForm(BodyWeightDraft(day), current.unit, dirty = false)
            persist(updated); form.value = updated
        }
        return true
    }

    fun setUnit(unit: WeightUnit) {
        val current = form.value
        if (current.unit == unit) return
        val kg = current.canonicalKg ?: parse(current.draft.weight)?.let(current.unit::toKg)
        val converted = kg?.let(unit::fromKg)?.takeIf { it.isFinite() }
        val updated = current.copy(unit = unit, draft = current.draft.copy(weight = converted?.let(::text) ?: current.draft.weight), canonicalKg = kg)
        persist(updated); form.value = updated
    }

    fun updateDraft(draft: BodyWeightDraft, unit: WeightUnit): Boolean {
        val invalid = linkedSetOf<String>()
        if (draft.weight.length > 250) invalid += "weight"
        if (runCatching { NutritionValidation.day(draft.day) }.isFailure) invalid += "day"
        if (invalid.isNotEmpty()) { form.value = form.value.copy(invalidFields = invalid); return false }
        val updated = WeightForm(draft.copy(), unit, dirty = true, canonicalKg = parse(draft.weight)?.let(unit::toKg))
        persist(updated); form.value = updated
        return true
    }

    fun validatedWeight(): BodyWeightEntity? {
        val current = form.value
        val kg = current.canonicalKg ?: parse(current.draft.weight)?.let(current.unit::toKg)
        val valid = kg != null && runCatching { NutritionValidation.number(kg, "body weight", positive = true, max = 1000.0) }.isSuccess
        form.value = current.copy(invalidFields = if (valid) emptySet() else setOf("weight"))
        return kg?.takeIf { valid }?.let { BodyWeightEntity(day = current.draft.day, kg = it) }
    }

    fun loadMore() {
        if (history.value.loading || !history.value.hasMore || history.value.requestLimit != requested.value) return
        requested.value = (requested.value + 50).coerceAtMost(MAX_ROWS)
        savedState[LIMIT] = requested.value
    }
    fun reload() { generation.value++ }
    private fun persist(value: WeightForm) {
        savedState[DAY] = value.draft.day; savedState[VALUE] = value.draft.weight; savedState[UNIT] = value.unit.name
        savedState[DIRTY] = value.dirty; savedState[KG] = value.canonicalKg
    }
    private companion object {
        const val DAY = "body_weight_day"; const val VALUE = "body_weight_value"; const val UNIT = "body_weight_unit"
        const val DIRTY = "body_weight_dirty"; const val KG = "body_weight_kg"; const val LIMIT = "body_weight_limit"
        const val MAX_ROWS = 9999
        fun parse(value: String) = value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        fun text(value: Double?): String = value?.toBigDecimal()?.setScale(2, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString().orEmpty()
    }
}

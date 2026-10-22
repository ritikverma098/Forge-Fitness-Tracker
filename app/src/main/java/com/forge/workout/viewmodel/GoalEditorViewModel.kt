package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.NutritionGoalEntity
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

data class GoalDraft(val day: String, val kcal: String = "", val protein: String = "", val carbs: String = "", val fat: String = "")
data class GoalEditorState(val draft: GoalDraft, val loading: Boolean = true, val error: Boolean = false,
    val invalidFields: Set<String> = emptySet())
private data class GoalForm(val draft: GoalDraft, val dirty: Boolean, val invalidFields: Set<String> = emptySet())
private data class GoalSource(val loading: Boolean = true, val goal: NutritionGoalEntity? = null, val error: Boolean = false)

/** An effective earlier goal supplies defaults; saving creates a version for this editor's selected day. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalEditorViewModel(repository: NutritionRepository, private val day: String, private val savedState: SavedStateHandle) : ViewModel() {
    private val restored = savedState.get<String>(DAY_KEY) == day
    private val initial = if (restored) GoalDraft(day, savedState["goal_kcal"] ?: "", savedState["goal_protein"] ?: "",
        savedState["goal_carbs"] ?: "", savedState["goal_fat"] ?: "") else GoalDraft(day)
    private val form = MutableStateFlow(GoalForm(initial, restored && savedState.get<Boolean>(DIRTY_KEY) == true))
    private val generation = MutableStateFlow(0)
    private val source = generation.flatMapLatest {
        flow {
            emit(GoalSource())
            emitAll(repository.observeGoal(day).map { GoalSource(loading = false, goal = it) })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(GoalSource(loading = false, error = true))
        }
    }.flowOn(Dispatchers.Default).onEach { loaded ->
        if (!loaded.loading && !loaded.error && !form.value.dirty) {
            val draft = draftFrom(loaded.goal)
            persist(draft, dirty = false)
            form.value = GoalForm(draft, dirty = false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalSource())

    val state: StateFlow<GoalEditorState> = combine(form, source) { fields, loaded ->
        val draft = if (!fields.dirty && !loaded.loading && !loaded.error) draftFrom(loaded.goal) else fields.draft
        GoalEditorState(draft, loaded.loading, loaded.error, fields.invalidFields)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalEditorState(initial))

    init { persist(initial, form.value.dirty) }

    fun updateDraft(draft: GoalDraft): Boolean {
        val stable = draft.copy(day = day)
        val tooLong = values(stable).filterValues { it.length > 250 }.keys
        if (tooLong.isNotEmpty()) { form.value = form.value.copy(invalidFields = tooLong); return false }
        persist(stable, dirty = true)
        form.value = GoalForm(stable, dirty = true)
        return true
    }

    fun reload() { generation.value++ }

    fun validatedGoal(): NutritionGoalEntity? {
        if (source.value.loading || source.value.error) return null
        val invalid = linkedSetOf<String>()
        val draft = form.value.draft
        fun number(field: String, raw: String): Double? {
            if (raw.isBlank()) return null
            val value = raw.trim().replace(',', '.').toDoubleOrNull()
            if (value == null || runCatching { NutritionValidation.number(value, field) }.isFailure) { invalid += field; return null }
            return value
        }
        val result = NutritionGoalEntity(day, number("kcal", draft.kcal), number("protein", draft.protein),
            number("carbs", draft.carbs), number("fat", draft.fat))
        if (runCatching { NutritionValidation.day(day) }.isFailure) invalid += "day"
        form.value = form.value.copy(invalidFields = invalid.toSet())
        return result.takeIf { invalid.isEmpty() }
    }

    private fun persist(draft: GoalDraft, dirty: Boolean) {
        savedState[DAY_KEY] = day
        savedState[DIRTY_KEY] = dirty
        values(draft).forEach { (field, value) -> savedState["goal_$field"] = value }
    }
    private fun draftFrom(goal: NutritionGoalEntity?) = GoalDraft(day, text(goal?.kcal), text(goal?.protein), text(goal?.carbs), text(goal?.fat))
    private companion object {
        const val DAY_KEY = "goal_day"
        const val DIRTY_KEY = "goal_dirty"
        fun values(draft: GoalDraft) = mapOf("kcal" to draft.kcal, "protein" to draft.protein, "carbs" to draft.carbs, "fat" to draft.fat)
        fun text(value: Double?): String = value?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty()
    }
}

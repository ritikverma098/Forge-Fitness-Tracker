package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class CustomFoodEditorState(
    val draft: CustomFoodDraft,
    val loading: Boolean = false,
    val error: Boolean = false,
    val invalidFields: Set<String> = emptySet(),
)

private data class CustomFoodForm(val draft: CustomFoodDraft, val dirty: Boolean, val invalidFields: Set<String> = emptySet())
private data class CustomFoodSource(val loading: Boolean = false, val detail: FoodDetail? = null, val error: Boolean = false)

/** Destination-owned drafts survive recreation; writes belong to the activity-owned mutation model. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomFoodEditorViewModel(
    repository: NutritionRepository,
    private val savedState: SavedStateHandle,
    private val id: String? = null,
) : ViewModel() {
    private val restored = savedState.get<String>(DRAFT_KEY)?.let(::decodeDraft)?.takeIf {
        if (id == null) it.id.startsWith("custom:") else it.id == id
    }
    private val initial = restored ?: if (id == null) CustomFoodDraft.new() else CustomFoodDraft(id)
    private val form = MutableStateFlow(CustomFoodForm(initial, restored != null && savedState.get<Boolean>(DIRTY_KEY) == true))
    private val generation = MutableStateFlow(0)
    private val source = generation.flatMapLatest {
        if (id == null) flowOf(CustomFoodSource()) else flow {
            emit(CustomFoodSource(loading = true))
            emitAll(repository.observeFood(id).map { detail ->
                if (detail == null || detail.food.sourceType != FoodSourceType.CUSTOM) CustomFoodSource(error = true)
                else CustomFoodSource(detail = detail.copy(nutrients = detail.nutrients.toList(), portions = detail.portions.toList()))
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(CustomFoodSource(error = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomFoodSource(loading = id != null))

    val state: StateFlow<CustomFoodEditorState> = combine(form, source) { fields, loaded ->
        CustomFoodEditorState(currentDraft(fields, loaded), loaded.loading, loaded.error, fields.invalidFields)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CustomFoodEditorState(initial, loading = id != null))

    init { persist(form.value) }

    fun updateDraft(draft: CustomFoodDraft) {
        val stable = CustomFoodFactory.normalizeUpdate(currentDraft(form.value, source.value), draft.copy(id = initial.id))
        form.value = CustomFoodForm(stable, dirty = true)
        persist(form.value)
    }

    fun reload() { generation.value++ }

    fun validatedFood(): FoodDetail? {
        val loaded = source.value
        if (id != null && (loaded.loading || loaded.error || loaded.detail == null)) {
            form.value = form.value.copy(invalidFields = setOf("food"))
            return null
        }
        val result = CustomFoodFactory.validate(currentDraft(form.value, loaded), loaded.detail)
        form.value = form.value.copy(invalidFields = result.invalidFields)
        return result.food
    }

    private fun currentDraft(fields: CustomFoodForm, loaded: CustomFoodSource): CustomFoodDraft =
        if (!fields.dirty && loaded.detail != null) CustomFoodFactory.fromFood(loaded.detail) else fields.draft

    private fun persist(fields: CustomFoodForm) {
        val encoded = encodeDraft(fields.draft)
        if (encoded.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES) {
            savedState[DRAFT_KEY] = encoded
            savedState[DIRTY_KEY] = fields.dirty
        } else form.value = fields.copy(invalidFields = fields.invalidFields + "food")
    }

    private companion object {
        const val DRAFT_KEY = "custom_food_draft"
        const val DIRTY_KEY = "custom_food_dirty"
        const val MAX_DRAFT_BYTES = 512 * 1024

        fun encodeDraft(draft: CustomFoodDraft): String = JSONObject()
            .put("id", draft.id).put("name", draft.name).put("brand", draft.brand).put("baseAmount", draft.baseAmount)
            .put("baseUnit", draft.baseUnit.name).put("notes", draft.notes).put("gramsPerBase", draft.gramsPerBase)
            .put("millilitersPerBase", draft.millilitersPerBase)
            .put("nutrients", JSONArray().also { array -> draft.nutrients.forEach { row ->
                array.put(JSONObject().put("code", row.code).put("name", row.name).put("unit", row.unit).put("value", row.value))
            } }).put("removed", JSONArray(draft.removedNutrientCodes)).toString()

        fun decodeDraft(value: String): CustomFoodDraft? = runCatching {
            require(value.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES)
            val json = JSONObject(value)
            val nutrients = json.getJSONArray("nutrients")
            require(nutrients.length() <= 300)
            val removed = json.optJSONArray("removed") ?: JSONArray()
            require(removed.length() <= 300)
            CustomFoodDraft(json.getString("id"), json.getString("name"), json.getString("brand"),
                json.getString("baseAmount"), FoodUnit.valueOf(json.getString("baseUnit")), json.getString("notes"),
                (0 until nutrients.length()).map { index -> nutrients.getJSONObject(index).let { row ->
                    CustomNutrientDraft(row.getString("code"), row.getString("name"), row.getString("unit"), row.getString("value"))
                } }, json.getString("gramsPerBase"), json.getString("millilitersPerBase"),
                (0 until removed.length()).map(removed::getString))
        }.getOrNull()
    }
}

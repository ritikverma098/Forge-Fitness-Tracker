package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.data.NutritionConverters
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

data class RecipeEditorState(
    val draft: RecipeDraft,
    val loading: Boolean = false,
    val error: Boolean = false,
    val invalidFields: Set<String> = emptySet(),
    val perServing: List<NutrientValue>? = null,
)

private data class RecipeForm(val draft: RecipeDraft, val dirty: Boolean, val invalidFields: Set<String> = emptySet())
private data class RecipeSource(val loading: Boolean = false, val detail: RecipeDetail? = null, val food: FoodDetail? = null,
    val error: Boolean = false)

/** This destination owns a bounded, snapshot-based draft; the activity owns the eventual database write. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeEditorViewModel(
    repository: NutritionRepository,
    private val savedState: SavedStateHandle,
    private val id: String? = null,
) : ViewModel() {
    private val restoredText = savedState.get<String>(DRAFT_KEY)
    private val restored = restoredText?.let { runCatching { RecipeDraftCodec.decode(it) }.getOrNull() }?.takeIf {
        if (id == null) it.id.startsWith("recipe:") else it.id == id
    }
    private val initial = restored ?: if (id == null) RecipeDraft() else RecipeDraft(id = id)
    private val form = MutableStateFlow(RecipeForm(initial, restored != null && savedState.get<Boolean>(DIRTY_KEY) == true,
        if ((restoredText != null && restored == null) || savedState.get<Boolean>(SIZE_ERROR_KEY) == true) setOf("draft") else emptySet()))
    private val generation = MutableStateFlow(0)
    private val source = generation.flatMapLatest {
        if (id == null) flowOf(RecipeSource()) else flow {
            emit(RecipeSource(loading = true))
            emitAll(combine(repository.observeRecipe(id), repository.observeFood(id)) { recipe, food ->
                if (recipe == null || food == null || recipe.recipe.id != id || food.food.id != id ||
                    food.food.sourceType != FoodSourceType.RECIPE) RecipeSource(error = true)
                else RecipeSource(detail = recipe.copy(ingredients = recipe.ingredients.map { entry ->
                    entry.copy(snapshot = freeze(entry.snapshot))
                }), food = food.copy(nutrients = food.nutrients.toList(), portions = food.portions.toList()))
            })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(RecipeSource(error = true))
        }
    }.flowOn(Dispatchers.Default).onEach { loaded ->
        // Persist a loaded draft before leaving for the picker, even if no field has been edited yet.
        if (!form.value.dirty && "draft" !in form.value.invalidFields && loaded.detail != null && loaded.food != null) {
            val draft = runCatching { RecipeFactory.fromRecipe(loaded.detail, loaded.food) }.getOrNull()
            if (draft != null) accept(draft, dirty = false) else form.value = form.value.copy(invalidFields = setOf("food"))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipeSource(loading = id != null))

    val state: StateFlow<RecipeEditorState> = combine(form, source) { fields, loaded ->
        val validation = if (loaded.loading || loaded.error || "draft" in fields.invalidFields) null else validate(fields.draft, loaded.food)
        RecipeEditorState(fields.draft, loaded.loading, loaded.error, fields.invalidFields,
            validation?.perServing?.takeIf { it.isNotEmpty() }?.toList())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        RecipeEditorState(initial, loading = id != null, invalidFields = form.value.invalidFields))

    init {
        if (restored == null) persistInitial()
    }

    fun updateDraft(draft: RecipeDraft): Boolean = accept(draft.copy(id = initial.id), dirty = true)

    fun addIngredient(snapshot: FoodSnapshot, choice: FoodServingChoice, quantity: Double): Boolean {
        val current = form.value.draft
        if (current.ingredients.size >= 500 || snapshot.foodId == current.id || runCatching {
                NutritionValidation.snapshot(snapshot)
                NutritionMath.factor(snapshot, choice, quantity)
                NutritionValidation.text(choice.id, 300, "Serving choice ID")
                require((choice.portionId == null) == (choice.label == null))
                choice.portionId?.let(NutritionValidation::id)
                choice.label?.let { NutritionValidation.text(it, 250, "Selected portion") }
                if (choice.portionId == null) {
                    val native = NutritionMath.choices(snapshot).firstOrNull { it.unit == choice.unit }
                    require(native != null && kotlin.math.abs(native.baseQuantity - choice.baseQuantity) <= maxOf(1.0, native.baseQuantity) * 1e-9)
                }
            }.isFailure) {
            form.value = form.value.copy(invalidFields = setOf("ingredients"))
            return false
        }
        return accept(current.copy(ingredients = current.ingredients + RecipeIngredientDraft(snapshot = freeze(snapshot),
            choice = choice.copy(), quantity = quantity.toString())), dirty = true)
    }

    fun removeIngredient(key: String) {
        val current = form.value.draft
        if (current.ingredients.any { it.key == key }) accept(current.copy(ingredients = current.ingredients.filterNot { it.key == key }), true)
    }

    fun changeIngredientQuantity(key: String, quantity: String) {
        val current = form.value.draft
        if (current.ingredients.any { it.key == key }) accept(current.copy(ingredients = current.ingredients.map {
            if (it.key == key) it.copy(quantity = quantity) else it
        }), true)
    }

    fun reload() { generation.value++ }

    fun validatedRecipe(): RecipeValidation? {
        val fields = form.value
        if ("draft" in fields.invalidFields) return null
        val loaded = source.value
        if (id != null && (loaded.loading || loaded.error || loaded.detail == null || loaded.food == null)) {
            form.value = fields.copy(invalidFields = setOf("food"))
            return null
        }
        val result = validate(fields.draft, loaded.food)
        form.value = fields.copy(invalidFields = result.invalidFields)
        return result.takeIf { it.invalidFields.isEmpty() && it.recipe != null && it.food != null }
    }

    private fun accept(draft: RecipeDraft, dirty: Boolean): Boolean {
        val frozen = draft.copy(id = initial.id, ingredients = draft.ingredients.map { it.copy(snapshot = freeze(it.snapshot), choice = it.choice.copy()) })
        val encoded = runCatching { RecipeDraftCodec.encode(frozen) }.getOrNull()
        if (encoded == null) {
            savedState[SIZE_ERROR_KEY] = true
            form.value = form.value.copy(invalidFields = form.value.invalidFields + "draft")
            return false
        }
        savedState[DRAFT_KEY] = encoded
        savedState[DIRTY_KEY] = dirty
        savedState[SIZE_ERROR_KEY] = false
        form.value = RecipeForm(frozen, dirty)
        return true
    }

    private fun persistInitial() {
        savedState[DRAFT_KEY] = RecipeDraftCodec.encode(initial)
        savedState[DIRTY_KEY] = false
        savedState[SIZE_ERROR_KEY] = "draft" in form.value.invalidFields
    }

    private companion object {
        const val DRAFT_KEY = "recipe_editor_draft"
        const val DIRTY_KEY = "recipe_editor_dirty"
        const val SIZE_ERROR_KEY = "recipe_editor_size_error"
        fun freeze(value: FoodSnapshot) = value.copy(nutrients = value.nutrients.toList())
        fun validate(draft: RecipeDraft, existingFood: FoodDetail? = null): RecipeValidation = try {
            RecipeFactory.validate(draft, existingFood?.takeIf { it.food.id == draft.id && it.food.sourceType == FoodSourceType.RECIPE })
        }
            catch (_: IllegalArgumentException) { RecipeValidation(invalidFields = setOf("ingredients")) }
    }
}

/** Shares identical snapshots and bounds both the saved Bundle value and decompression allocation. */
private object RecipeDraftCodec {
    private const val MAX_ENCODED_BYTES = 256 * 1024
    private const val MAX_DECODED_BYTES = 32 * 1024 * 1024
    private val converters = NutritionConverters()

    fun encode(draft: RecipeDraft): String {
        require(draft.ingredients.size <= 500)
        require(listOf(draft.id, draft.name, draft.servings, draft.cookedWeightGrams).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_DECODED_BYTES)
        val snapshots = linkedMapOf<FoodSnapshot, Int>()
        val snapshotJson = JSONArray()
        var decodedSize = 0L
        val rows = JSONArray()
        draft.ingredients.forEach { ingredient ->
            val index = snapshots[ingredient.snapshot] ?: snapshots.size.also { next ->
                val text = converters.snapshotToString(ingredient.snapshot)
                decodedSize += text.toByteArray(Charsets.UTF_8).size * 2L
                require(decodedSize <= MAX_DECODED_BYTES)
                snapshots[ingredient.snapshot] = next
                snapshotJson.put(text)
            }
            rows.put(JSONObject().put("key", ingredient.key).put("rowId", ingredient.rowId).put("snapshot", index)
                .put("quantity", ingredient.quantity).put("choice", JSONObject().put("id", ingredient.choice.id)
                    .put("label", ingredient.choice.label ?: JSONObject.NULL).put("unit", ingredient.choice.unit.name)
                    .put("baseQuantity", ingredient.choice.baseQuantity).put("suggestedQuantity", ingredient.choice.suggestedQuantity)
                    .put("portionId", ingredient.choice.portionId ?: JSONObject.NULL)))
        }
        val json = JSONObject().put("version", 1).put("id", draft.id).put("name", draft.name).put("servings", draft.servings)
            .put("cookedWeightGrams", draft.cookedWeightGrams).put("snapshots", snapshotJson).put("ingredients", rows)
            .toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_DECODED_BYTES)
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json) }
        return Base64.getEncoder().encodeToString(output.toByteArray()).also { require(it.length <= MAX_ENCODED_BYTES) }
    }

    fun decode(value: String): RecipeDraft {
        require(value.length <= MAX_ENCODED_BYTES)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(output.size().toLong() + size <= MAX_DECODED_BYTES)
                output.write(buffer, 0, size)
            }
        }
        val root = JSONObject(output.toString(Charsets.UTF_8.name()))
        require(root.getInt("version") == 1)
        val snapshotArray = root.getJSONArray("snapshots")
        val rows = root.getJSONArray("ingredients")
        require(snapshotArray.length() <= 500 && rows.length() <= 500)
        val snapshots = List(snapshotArray.length()) { converters.stringToSnapshot(snapshotArray.getString(it)) }
        val ingredients = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val choice = row.getJSONObject("choice")
            RecipeIngredientDraft(key = row.getString("key"), rowId = row.getLong("rowId"),
                snapshot = snapshots[row.getInt("snapshot")], quantity = row.getString("quantity"),
                choice = FoodServingChoice(choice.getString("id"), choice.nullableString("label"), FoodUnit.valueOf(choice.getString("unit")),
                    choice.getDouble("baseQuantity"), choice.getDouble("suggestedQuantity"), choice.nullableString("portionId"))).also {
                require(it.rowId >= 0 && it.key.isNotBlank() && it.choice.baseQuantity.isFinite() && it.choice.baseQuantity > 0 &&
                    it.choice.suggestedQuantity.isFinite() && it.choice.suggestedQuantity > 0)
            }
        }
        require(ingredients.map { it.key }.distinct().size == ingredients.size)
        return RecipeDraft(root.getString("id"), root.getString("name"), root.getString("servings"),
            root.getString("cookedWeightGrams"), ingredients)
    }

    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
}

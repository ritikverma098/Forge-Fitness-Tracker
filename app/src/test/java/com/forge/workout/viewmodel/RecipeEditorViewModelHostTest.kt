package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecipeEditorViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
    }

    @After fun tearDown() { store.clear(); database.close(); Dispatchers.resetMain() }

    @Test fun `new draft and ingredient choices freeze caller lists and restore before state is observed`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val editor = keep(handle)
        val initial = editor.state.value.draft
        val nutrients = snapshot("ingredient").nutrients.toMutableList()
        val captured = snapshot("ingredient").copy(nutrients = nutrients)
        val choice = FoodServingChoice("portion:cup", "1 cup (150g)", FoodUnit.SERVING, 150.0, 1.0, "cup")
        assertTrue(editor.addIngredient(captured, choice, 2.0))
        nutrients.clear()
        val copied = keep(copyHandle(handle), key = "restored")
        observe(copied)
        val restored = copied.state.first { it.draft.ingredients.size == 1 }.draft
        assertEquals(initial.id, restored.id)
        assertTrue(restored.id.startsWith("recipe:"))
        assertEquals(snapshot("ingredient").nutrients, restored.ingredients.single().snapshot.nutrients)
        assertEquals(choice, restored.ingredients.single().choice)
        val draft = restored.copy(name = "Fresh bowl", servings = "2", cookedWeightGrams = "300")
        copied.updateDraft(draft)
        copied.changeIngredientQuantity(restored.ingredients.single().key, "1,5")
        val valid = copied.validatedRecipe()!!
        assertEquals(initial.id, valid.recipe!!.id)
        assertEquals(2.0, valid.recipe!!.servings, 0.0)
        assertEquals(300.0, valid.recipe!!.cookedWeightGrams!!, 0.0)
        assertEquals(2.25, valid.ingredients.single().factor, 0.0)
        assertEquals(225.0, valid.perServing.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertNull(valid.perServing.single { it.code == "fiber" }.value)
        assertTrue(repository.searchFoods().isEmpty())
    }

    @Test fun `edit observes only its parent identities and keeps stored portions after catalog edit and deletion`() = runTest(dispatcher) {
        val id = "recipe:bowl"
        repository.upsertFood(food("ingredient", "Original ingredient"))
        saveRecipe(id)
        val originalFood = repository.food(id)!!
        repository.upsertFood(originalFood.copy(food = originalFood.food.copy(brand = "Home", aliases = "lunch bowl", source = "Kitchen",
            sourceId = "batch-42", sourceUrl = "https://example.com/recipe", region = "IN", tags = "homemade", notes = "Keep chilled", archived = true)))
        val metadata = repository.food(id)!!.food
        val before = repository.recipe(id)!!
        val reads = mutableListOf<String>()
        val recipeReads = mutableListOf<String>()
        val targeted = object : NutritionRepository by repository {
            override fun observeFood(id: String): Flow<FoodDetail?> { reads += id; return repository.observeFood(id) }
            override fun observeRecipe(id: String): Flow<RecipeDetail?> { recipeReads += id; return repository.observeRecipe(id) }
            override fun observeFoodPage(query: String, filter: FoodBrowseFilter, limit: Int, offset: Int): Flow<List<FoodEntity>> =
                error("Recipe editing must not observe the catalog")
        }
        val handle = SavedStateHandle()
        val editor = keep(handle, id, targeted)
        observe(editor)
        val loaded = editor.state.first { !it.loading && !it.error && it.draft.ingredients.isNotEmpty() }.draft
        assertEquals(before.ingredients.single().id, loaded.ingredients.single().rowId)
        assertEquals(before.ingredients.single().snapshot, loaded.ingredients.single().snapshot)
        assertEquals(150.0, loaded.ingredients.single().choice.baseQuantity, 0.0)
        val key = loaded.ingredients.single().key
        repository.upsertFood(food("ingredient", "Changed ingredient", 900.0))
        repository.deleteFood("ingredient")
        val restored = keep(copyHandle(handle), id, key = "recreated-edit")
        observe(restored)
        val restoredDraft = restored.state.first { !it.loading && it.draft.ingredients.isNotEmpty() }.draft
        assertEquals(key, restoredDraft.ingredients.single().key)
        assertEquals(loaded.ingredients.single().snapshot, restoredDraft.ingredients.single().snapshot)
        restored.changeIngredientQuantity(key, "3")
        val validation = restored.validatedRecipe()!!
        assertEquals(before.ingredients.single().id, validation.ingredients.single().id)
        assertEquals(4.5, validation.ingredients.single().factor, 0.0)
        assertEquals(900.0, validation.perServing.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        val editedFood = validation.food!!.food
        assertEquals(metadata.rowId, editedFood.rowId)
        assertEquals(metadata.brand, editedFood.brand)
        assertEquals(metadata.aliases, editedFood.aliases)
        assertEquals(metadata.source, editedFood.source)
        assertEquals(metadata.sourceId, editedFood.sourceId)
        assertEquals(metadata.sourceUrl, editedFood.sourceUrl)
        assertEquals(metadata.region, editedFood.region)
        assertEquals(metadata.tags, editedFood.tags)
        assertEquals(metadata.notes, editedFood.notes)
        assertEquals(metadata.archived, editedFood.archived)
        assertEquals(before, repository.recipe(id))
        assertEquals(listOf(id), reads)
        assertEquals(listOf(id), recipeReads)
    }

    @Test fun `picker additions and rapid edits while unsubscribed use the accepted form instead of stale published state`() = runTest(dispatcher) {
        val id = "recipe:offscreen"
        saveRecipe(id)
        val handle = SavedStateHandle()
        val editor = keep(handle, id)
        val collector = observe(editor)
        val initial = editor.state.first { !it.loading && it.draft.ingredients.isNotEmpty() }.draft
        collector.cancelAndJoin()
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(editor.addIngredient(snapshot("second"), gramChoice(), 50.0))
        assertTrue(editor.addIngredient(snapshot("third"), gramChoice(), 75.0))
        editor.changeIngredientQuantity(initial.ingredients.single().key, "250")
        val recreated = keep(copyHandle(handle), id, key = "offscreen-recreated")
        observe(recreated)
        val accepted = recreated.state.first { !it.loading && it.draft.ingredients.size == 3 }.draft
        assertEquals(listOf("ingredient", "second", "third"), accepted.ingredients.map { it.snapshot.foodId })
        assertEquals("250", accepted.ingredients.first().quantity)
        assertEquals(3, accepted.ingredients.map { it.key }.distinct().size)
        recreated.updateDraft(accepted.copy(ingredients = accepted.ingredients.reversed()))
        val validation = recreated.validatedRecipe()!!
        assertEquals(listOf("third", "second", "ingredient"), validation.ingredients.map { it.foodId })
        assertEquals(listOf(0, 1, 2), validation.ingredients.map { it.orderIndex })
        assertEquals(1, repository.recipe(id)!!.ingredients.size)
    }

    @Test fun `quantity and mass validation block saving and removal or a valid edit immediately corrects errors`() = runTest(dispatcher) {
        val editor = keep()
        observe(editor)
        assertTrue(editor.addIngredient(snapshot("ingredient"), gramChoice(), 100.0))
        val loaded = editor.state.first { it.draft.ingredients.size == 1 }.draft
        editor.updateDraft(loaded.copy(name = "Bowl", servings = "0", cookedWeightGrams = "-1"))
        editor.changeIngredientQuantity(loaded.ingredients.single().key, "NaN")
        assertNull(editor.validatedRecipe())
        val invalid = editor.state.first { it.invalidFields.isNotEmpty() }
        assertTrue(invalid.invalidFields.containsAll(setOf("servings", "cookedWeightGrams", "ingredient:${loaded.ingredients.single().key}")))
        assertNull(invalid.perServing)
        editor.updateDraft(loaded.copy(name = "Bowl", servings = "1", cookedWeightGrams = ""))
        assertNotNull(editor.validatedRecipe())
        assertFalse(editor.addIngredient(snapshot("invalid"), gramChoice(), -1.0))
        assertFalse(editor.addIngredient(snapshot(loaded.id), gramChoice(), 1.0))
        editor.removeIngredient(loaded.ingredients.single().key)
        assertNull(editor.validatedRecipe())
        assertTrue(editor.state.first { "ingredients" in it.invalidFields }.draft.ingredients.isEmpty())
        assertTrue(repository.searchFoods().isEmpty())
    }

    @Test fun `dirty restored draft keeps frozen nutrition through parent refresh and source deletion blocks save`() = runTest(dispatcher) {
        val id = "recipe:dirty"
        saveRecipe(id)
        val handle = SavedStateHandle()
        val editor = keep(handle, id)
        observe(editor)
        val initial = editor.state.first { !it.loading && it.draft.ingredients.isNotEmpty() }.draft
        editor.updateDraft(initial.copy(name = "Unsaved name", servings = "3"))
        saveRecipe(id, name = "Other saved change", snapshot = snapshot("replacement", 800.0))
        val recreated = keep(copyHandle(handle), id, key = "dirty-restored")
        observe(recreated)
        val restored = recreated.state.first { !it.loading }.draft
        assertEquals("Unsaved name", restored.name)
        assertEquals("3", restored.servings)
        assertEquals(initial.ingredients.single().snapshot, restored.ingredients.single().snapshot)
        assertEquals(initial.ingredients.single().key, restored.ingredients.single().key)
        assertNotNull(recreated.validatedRecipe())
        repository.deleteRecipe(id)
        assertTrue(recreated.state.first { it.error }.error)
        assertNull(recreated.validatedRecipe())
        assertEquals("Unsaved name", recreated.state.value.draft.name)
    }

    @Test fun `target guard read retry and cancellation never convert a catalog item into an editable recipe`() = runTest(dispatcher) {
        repository.upsertFood(food("not-recipe", "Catalog food"))
        val guarded = keep(id = "not-recipe", key = "guarded")
        observe(guarded)
        assertTrue(guarded.state.first { it.error }.error)
        assertNull(guarded.validatedRecipe())
        saveRecipe("recipe:retry")
        var requests = 0
        val unreliable = object : NutritionRepository by repository {
            override fun observeRecipe(id: String): Flow<RecipeDetail?> = if (++requests == 1)
                flow { throw IOException("Injected recipe read failure") } else repository.observeRecipe(id)
        }
        val retry = keep(id = "recipe:retry", source = unreliable, key = "retry")
        observe(retry)
        assertFalse(retry.state.first { it.error }.loading)
        retry.reload()
        assertFalse(retry.state.first { !it.loading && it.draft.ingredients.isNotEmpty() }.error)
        assertNotNull(retry.validatedRecipe())
        val cancelled = object : NutritionRepository by repository {
            override fun observeRecipe(id: String): Flow<RecipeDetail?> = flow { throw CancellationException("Cancelled observation") }
        }
        val cancellation = keep(id = "recipe:retry", source = cancelled, key = "cancelled")
        observe(cancellation)
        runCurrent()
        assertFalse(cancellation.state.value.error)
    }

    @Test fun `oversize compressed draft rejects update retains accepted form and persists explicit error until corrected`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val editor = keep(handle)
        observe(editor)
        assertTrue(editor.addIngredient(snapshot("ingredient"), gramChoice(), 100.0))
        val accepted = editor.state.first { it.draft.ingredients.isNotEmpty() }.draft.copy(name = "Accepted bowl")
        assertTrue(editor.updateDraft(accepted))
        val random = Random(41)
        val incompressible = CharArray(600_000) { ('!'.code + random.nextInt(90)).toChar() }.concatToString()
        assertFalse(editor.updateDraft(accepted.copy(name = incompressible)))
        val rejected = editor.state.first { "draft" in it.invalidFields }
        assertEquals(accepted, rejected.draft)
        assertNull(editor.validatedRecipe())
        assertTrue(handle.get<String>("recipe_editor_draft")!!.length <= 256 * 1024)
        val recreatedHandle = copyHandle(handle)
        val recreated = keep(recreatedHandle, key = "oversize-restored")
        observe(recreated)
        assertEquals(accepted, recreated.state.first { "draft" in it.invalidFields }.draft)
        assertNull(recreated.validatedRecipe())
        assertTrue(recreated.updateDraft(accepted.copy(name = "Correction")))
        assertTrue(recreated.updateDraft(accepted.copy(name = "Corrected bowl")))
        assertEquals("Corrected bowl", recreated.validatedRecipe()!!.recipe!!.name)
        assertFalse(recreatedHandle.get<Boolean>("recipe_editor_size_error")!!)
        val corrected = keep(copyHandle(recreatedHandle), key = "corrected-restored")
        observe(corrected)
        assertTrue(corrected.state.first { it.draft.name == "Corrected bowl" }.invalidFields.isEmpty())
    }

    @Test fun `valid maximum length portion ID can be picked restored validated and persisted`() = runTest(dispatcher) {
        val portionId = "p".repeat(200)
        val detail = food("long-portion", "Long portion food").copy(portions = listOf(
            FoodPortionEntity("long-portion", portionId, "Measured cup", 150.0, grams = 150.0)))
        repository.upsertFood(detail)
        val stored = repository.food(detail.food.id)!!
        val choice = NutritionMath.choices(stored).single { it.portionId == portionId }
        assertTrue(choice.id.length > 200)
        val handle = SavedStateHandle()
        val editor = keep(handle)
        assertTrue(editor.addIngredient(stored.snapshot(), choice, 2.0))
        val recreated = keep(copyHandle(handle), key = "long-portion-restored")
        observe(recreated)
        val draft = recreated.state.first { it.draft.ingredients.isNotEmpty() }.draft
        assertEquals(choice, draft.ingredients.single().choice)
        assertTrue(recreated.updateDraft(draft.copy(name = "Long portion bowl")))
        val validated = recreated.validatedRecipe()!!
        assertEquals(portionId, validated.ingredients.single().portionId)
        assertEquals(3.0, validated.ingredients.single().factor, 0.0)
        repository.saveRecipe(validated.recipe!!, validated.food!!, validated.ingredients)
        assertEquals(portionId, repository.recipe(draft.id)!!.ingredients.single().portionId)
        assertFalse(recreated.addIngredient(stored.snapshot(), choice.copy(id = "x".repeat(301)), 1.0))
        assertEquals(1, recreated.validatedRecipe()!!.ingredients.size)
    }

    @Test fun `malformed and decompression bomb saved values recover explicitly without loading unbounded drafts`() = runTest(dispatcher) {
        val malformed = keep(SavedStateHandle(mapOf("recipe_editor_draft" to "not-base64")), key = "malformed")
        observe(malformed)
        assertTrue("draft" in malformed.state.first { !it.loading }.invalidFields)
        assertNull(malformed.validatedRecipe())
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            val chunk = ByteArray(8192) { 'a'.code.toByte() }
            repeat(32 * 1024 * 1024 / chunk.size) { gzip.write(chunk) }
            gzip.write('a'.code)
        }
        val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
        assertTrue(encoded.length < 256 * 1024)
        val bounded = keep(SavedStateHandle(mapOf("recipe_editor_draft" to encoded)), key = "bounded")
        observe(bounded)
        assertTrue("draft" in bounded.state.first { !it.loading }.invalidFields)
        assertNull(bounded.validatedRecipe())
        bounded.updateDraft(bounded.state.value.draft.copy(name = "Recoverable draft"))
        assertTrue(bounded.state.first { it.draft.name == "Recoverable draft" }.invalidFields.isEmpty())
    }

    private fun keep(handle: SavedStateHandle = SavedStateHandle(), id: String? = null,
        source: NutritionRepository = repository, key: String = "editor") =
        RecipeEditorViewModel(source, handle, id).also { store.put(key, it) }
    private fun TestScope.observe(model: RecipeEditorViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    private fun gramChoice() = FoodServingChoice("GRAM", unit = FoodUnit.GRAM, baseQuantity = 1.0, suggestedQuantity = 100.0)
    private fun snapshot(id: String, kcal: Double = 200.0) = FoodSnapshot(foodId = id, name = "Captured $id", baseAmount = 100.0,
        baseUnit = FoodUnit.GRAM, gramsPerBase = 100.0, nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", kcal),
            NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 10.0), NutrientValue("fiber", "Fiber", "g", null)))
    private fun food(id: String, name: String, kcal: Double = 200.0) = FoodDetail(
        FoodEntity(id = id, name = name, baseAmount = 100.0, baseUnit = FoodUnit.GRAM, gramsPerBase = 100.0),
        snapshot(id, kcal).nutrients.map { FoodNutrientEntity(id, it.code, it.name, it.unit, it.value) })
    private suspend fun saveRecipe(id: String, name: String = "Stored bowl", snapshot: FoodSnapshot = snapshot("ingredient")) {
        val draft = RecipeDraft(id = id, name = name, servings = "1", cookedWeightGrams = "300", ingredients = listOf(
            RecipeIngredientDraft(key = "fixture", snapshot = snapshot,
                choice = FoodServingChoice("portion:cup", "Cup", FoodUnit.SERVING, 150.0, 1.0, "cup"), quantity = "2")))
        val result = RecipeFactory.validate(draft)
        assertTrue(result.invalidFields.isEmpty())
        repository.saveRecipe(result.recipe!!, result.food!!, result.ingredients)
    }
}

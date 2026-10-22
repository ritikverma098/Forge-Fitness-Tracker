package com.forge.workout.nutrition.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.viewmodel.FoodMutationResult
import com.forge.workout.viewmodel.FoodMutationViewModel
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecipePersistenceHostTest {
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

    @After fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `recipe edits retain ingredient snapshots and row IDs while diary history and other recipes survive deletion`() = runTest(dispatcher) {
        repository.upsertFood(sourceFood())
        val source = repository.food("source")!!.snapshot()
        val created = validated("recipe:main", "Original recipe", source, servings = "2", cooked = "400")
        save(created)
        val originalFood = repository.food("recipe:main")!!
        val original = repository.recipe("recipe:main")!!
        assertEquals(100.0, originalFood.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertEquals(200.0, originalFood.food.gramsPerBase!!, 0.0)
        val diaryId = repository.saveDiary(DiaryEntity(day = "2026-10-13", meal = MealType.DINNER, foodId = original.recipe.id,
            snapshot = originalFood.snapshot(), quantity = 1.0, unit = FoodUnit.SERVING, factor = 1.0, loggedAt = 1))
        val diary = repository.diaryEntry(diaryId)!!
        repository.saveFavorite(FavoriteEntity(original.recipe.id, "Original recipe", 1))
        save(validated("recipe:other", "Unrelated recipe", source))
        val other = repository.recipe("recipe:other")!!
        repository.upsertFood(sourceFood().copy(food = sourceFood().food.copy(name = "Changed source"),
            nutrients = listOf(FoodNutrientEntity("source", NutrientCodes.ENERGY, "Energy", "kcal", 900.0))))
        repository.deleteFood("source")
        assertEquals(original, repository.recipe(original.recipe.id))

        val editedDraft = RecipeFactory.fromRecipe(original, originalFood).copy(name = "Edited recipe", servings = "4", cookedWeightGrams = "600")
        val edited = RecipeFactory.validate(editedDraft, originalFood)
        save(edited)
        val current = repository.recipe(original.recipe.id)!!
        val food = repository.food(original.recipe.id)!!
        assertEquals(originalFood.food.rowId, food.food.rowId)
        assertEquals(original.sortedIngredients.map { it.id }, current.sortedIngredients.map { it.id })
        assertEquals(original.sortedIngredients.map { it.snapshot }, current.sortedIngredients.map { it.snapshot })
        assertEquals("Original source", current.ingredients.single().snapshot.name)
        assertEquals(50.0, food.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertEquals(150.0, food.food.gramsPerBase!!, 0.0)
        assertEquals(diary, repository.diaryEntry(diaryId))
        repository.deleteRecipe(original.recipe.id)
        assertNull(repository.food(original.recipe.id))
        assertNull(repository.recipe(original.recipe.id))
        assertEquals(0, countIngredients(original.recipe.id))
        assertEquals(diary, repository.diaryEntry(diaryId))
        assertNotNull(repository.observeFavorite(original.recipe.id).first())
        assertEquals(other, repository.recipe("recipe:other"))
        assertForeignKeys()
    }

    @Test fun `transactional recipe type guards reject reference and custom collisions even with a forged recipe child`() = runTest(dispatcher) {
        for (type in listOf(FoodSourceType.REFERENCE, FoodSourceType.CUSTOM)) {
            val id = "protected-${type.name}"
            val protected = sourceFood(id).copy(food = sourceFood(id).food.copy(sourceType = type))
            repository.upsertFood(protected)
            val before = repository.food(id)!!
            val replacement = validated(id, "Overwrite", sourceFood().snapshot())
            expectInvalid { save(replacement) }
            assertEquals(before, repository.food(id))
            assertNull(repository.recipe(id))
            database.nutritionDao().upsertRecipe(RecipeEntity(id, before.food.name, 1.0))
            val child = repository.recipe(id)!!
            expectInvalid { repository.deleteRecipe(id) }
            assertEquals(before, repository.food(id))
            assertEquals(child, repository.recipe(id))
        }
        expectInvalid { repository.deleteRecipe("missing-recipe") }
        assertForeignKeys()
    }

    @Test fun `ingredient insertion failure rolls parent food nutrients recipe and prior child rows back together`() = runTest(dispatcher) {
        val source = sourceFood().snapshot()
        val initial = RecipeDraft(id = "recipe:rollback", name = "Original recipe", servings = "2", ingredients =
            listOf(ingredient(source, "100", "first"), ingredient(source, "150", "second")))
        save(RecipeFactory.validate(initial))
        val beforeFood = repository.food(initial.id)!!
        val beforeRecipe = repository.recipe(initial.id)!!
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_recipe BEFORE INSERT ON nutrition_recipe_ingredients WHEN NEW.recipeId = 'recipe:rollback' AND NEW.orderIndex = 1 BEGIN SELECT RAISE(ABORT, 'Injected recipe storage failure'); END")
        val changed = RecipeFactory.fromRecipe(beforeRecipe, beforeFood).copy(name = "Rejected recipe", servings = "1")
        val update = RecipeFactory.validate(changed, beforeFood)
        try { save(update); throw AssertionError("Injected storage failure must abort") }
        catch (_: SQLiteConstraintException) { }
        assertEquals(beforeFood, repository.food(initial.id))
        assertEquals(beforeRecipe, repository.recipe(initial.id))
        assertEquals(listOf(initial.id), repository.searchFoods("Original recipe").map { it.id })
        assertTrue(repository.searchFoods("Rejected").isEmpty())
        assertForeignKeys()
    }

    @Test fun `activity writer freezes every ingredient snapshot and ignores rapid duplicate saves while flush waits`() = runTest(dispatcher) {
        val controlled = HeldRecipeRepository(repository)
        val writer = keepWriter(controlled)
        val result = validated("recipe:held", "Held save", sourceFood().snapshot())
        val mutableNutrients = result.ingredients.single().snapshot.nutrients.toMutableList()
        val mutableIngredients = result.ingredients.map {
            it.copy(snapshot = it.snapshot.copy(nutrients = mutableNutrients))
        }.toMutableList()
        val mutableFoodNutrients = result.food!!.nutrients.toMutableList()
        writer.saveRecipe("first", result.recipe!!, result.food.copy(nutrients = mutableFoodNutrients), mutableIngredients)
        mutableIngredients.clear()
        mutableNutrients.clear()
        mutableFoodNutrients.clear()
        writer.saveRecipe("duplicate", result.recipe, result.food, result.ingredients)
        controlled.started.await()
        val barrier = async { writer.flush() }
        runCurrent()
        assertTrue(writer.busy.value)
        assertFalse(barrier.isCompleted)
        assertNull(writer.saveResult.value)
        controlled.release.complete(Unit)
        barrier.await()
        assertFalse(writer.busy.value)
        assertEquals(1, controlled.saves)
        assertEquals(FoodMutationResult("first", result.recipe.id), writer.saveResult.value?.copy(operationId = 0))
        val persisted = repository.recipe(result.recipe.id)!!
        assertEquals(result.ingredients.single().snapshot, persisted.ingredients.single().snapshot)
        assertEquals(result.food.nutrients.sortedBy { it.code }, repository.food(result.recipe.id)!!.nutrients.sortedBy { it.code })
        val retained = writer.saveResult.value
        writer.acknowledge("different")
        assertSame(retained, writer.saveResult.value)
        writer.acknowledge("first")
        assertNull(writer.saveResult.value)
    }

    @Test fun `old failure acknowledgement preserves a newer successful retry for the same editor request`() = runTest(dispatcher) {
        var fail = true
        val controlled = object : NutritionRepository by repository {
            override suspend fun saveRecipe(recipe: RecipeEntity, food: FoodDetail, ingredients: List<RecipeIngredientEntity>): String {
                if (fail) throw IOException("Recipe storage failure")
                return repository.saveRecipe(recipe, food, ingredients)
            }
        }
        val writer = keepWriter(controlled)
        val result = validated("recipe:retry", "Recipe retry", sourceFood().snapshot())
        writer.saveRecipe("editor-request", result.recipe!!, result.food!!, result.ingredients)
        writer.flush()
        assertFalse(writer.busy.value)
        val oldFailure = writer.saveResult.value!!
        assertEquals("editor-request", oldFailure.requestId)
        assertEquals(result.recipe.id, oldFailure.foodId)
        assertEquals("Error: Recipe storage failure", oldFailure.error)
        assertNull(repository.recipe(result.recipe.id))
        fail = false
        writer.saveRecipe("editor-request", result.recipe, result.food, result.ingredients)
        writer.flush()
        val success = writer.saveResult.value!!
        assertEquals(FoodMutationResult("editor-request", result.recipe.id), success.copy(operationId = 0))
        assertTrue(success.operationId > oldFailure.operationId)
        writer.acknowledge(oldFailure)
        assertSame(success, writer.saveResult.value)
        writer.acknowledge(success.copy())
        assertSame(success, writer.saveResult.value)
        writer.acknowledge(success)
        assertNull(writer.saveResult.value)
        assertNull(writer.error.value)
        writer.deleteRecipe("delete", result.recipe.id)
        writer.flush()
        assertEquals(FoodMutationResult("delete", result.recipe.id, deleted = true), writer.saveResult.value?.copy(operationId = 0))
        assertNull(repository.recipe(result.recipe.id))
        assertNull(repository.food(result.recipe.id))
    }

    @Test fun `new user recipes enforce namespace while existing legacy recipe IDs still edit through the writer`() = runTest(dispatcher) {
        val legacy = validated("legacy-recipe", "Legacy recipe", sourceFood().snapshot())
        val writer = keepWriter()
        writer.saveRecipe("new-foreign", legacy.recipe!!, legacy.food!!, legacy.ingredients)
        writer.flush()
        assertNotNull(writer.saveResult.value!!.error)
        assertNull(repository.recipe(legacy.recipe.id))
        save(legacy) // Generic repository compatibility for prior import/test callers is intentional.
        val previous = repository.recipe(legacy.recipe.id)!!
        val previousFood = repository.food(legacy.recipe.id)!!
        val updated = RecipeFactory.validate(RecipeFactory.fromRecipe(previous, previousFood).copy(name = "Updated legacy"), previousFood)
        writer.saveRecipe("edit-legacy", updated.recipe!!, updated.food!!, updated.ingredients)
        writer.flush()
        assertEquals(FoodMutationResult("edit-legacy", legacy.recipe.id), writer.saveResult.value?.copy(operationId = 0))
        assertEquals("Updated legacy", repository.recipe(legacy.recipe.id)!!.recipe.name)
        assertEquals(previousFood.food.rowId, repository.food(legacy.recipe.id)!!.food.rowId)
    }

    private fun keepWriter(source: NutritionRepository = repository) =
        FoodMutationViewModel(source) { "Error: ${it.message}" }.also { store.put("writer", it) }
    private suspend fun save(result: RecipeValidation) {
        assertTrue("Recipe fixture must be valid: ${result.invalidFields}", result.invalidFields.isEmpty())
        repository.saveRecipe(result.recipe!!, result.food!!, result.ingredients)
    }
    private fun validated(id: String, name: String, source: FoodSnapshot, servings: String = "1", cooked: String = "") =
        RecipeFactory.validate(RecipeDraft(id, name, servings, cooked, listOf(ingredient(source))))
    private fun ingredient(snapshot: FoodSnapshot, quantity: String = "100", key: String = java.util.UUID.randomUUID().toString()) =
        RecipeIngredientDraft(key = key, snapshot = snapshot, choice = NutritionMath.choices(snapshot).first(), quantity = quantity)
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected protected recipe rejection") }
        catch (_: IllegalArgumentException) { }
    }
    private fun countIngredients(id: String) = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM nutrition_recipe_ingredients WHERE recipeId = ?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
    private fun assertForeignKeys() = database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    private fun sourceFood(id: String = "source") = FoodDetail(
        FoodEntity(id = id, name = "Original source", sourceType = FoodSourceType.REFERENCE, gramsPerBase = 100.0),
        listOf(FoodNutrientEntity(id, NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
            FoodNutrientEntity(id, NutrientCodes.PROTEIN, "Protein", "g", null),
            FoodNutrientEntity(id, "usda:291", "Fiber", "g", 3.0)),
    )
    private class HeldRecipeRepository(private val delegate: NutritionRepository) : NutritionRepository by delegate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var saves = 0
        override suspend fun saveRecipe(recipe: RecipeEntity, food: FoodDetail, ingredients: List<RecipeIngredientEntity>): String {
            saves++
            started.complete(Unit)
            release.await()
            return delegate.saveRecipe(recipe, food, ingredients)
        }
    }
}

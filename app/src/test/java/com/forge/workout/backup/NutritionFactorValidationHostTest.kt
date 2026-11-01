package com.forge.workout.backup

import android.app.Application
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Workout
import com.forge.workout.nutrition.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Frozen quantities must agree with their factors without consulting a current catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionFactorValidationHostTest {
    @Test fun `encode rejects native quantity with mutually consistent but incorrect factor and caches`() {
        val row = diary().copy(factor = 2.0, kcal = 400.0)
        reject { BackupCodec.encode(backup(row)) }
    }

    @Test fun `decode rejects quantity-only changes to a native diary row`() {
        val json = encodedDiary()
        json.diary().put("quantity", 50.0)
        reject { BackupCodec.decode(json.toString()) }
    }

    @Test fun `decode rejects unsupported unit changes even when factors and caches agree`() {
        val json = encodedDiary()
        json.diary().put("unit", FoodUnit.MILLILITER.name)
        reject { BackupCodec.decode(json.toString()) }
    }

    @Test fun `decode rejects edited factor even when cached calories were edited to agree`() {
        val json = encodedDiary()
        json.diary().put("factor", 2.0).put("kcal", 400.0)
        reject { BackupCodec.decode(json.toString()) }
    }

    @Test fun `recipe ingredient quantity factor and unsupported unit changes are rejected before import`() {
        val valid = recipeBackup()
        val row = valid.nutrition!!.recipes.single().ingredients.single()
        for (changed in listOf(row.copy(quantity = 50.0), row.copy(factor = 2.0), row.copy(unit = FoodUnit.MILLILITER))) {
            val changedRecipe = valid.nutrition.recipes.single().copy(ingredients = listOf(changed))
            reject { BackupCodec.encode(valid.copy(nutrition = valid.nutrition.copy(recipes = listOf(changedRecipe)))) }
        }
    }

    @Test fun `decode validates recipe ingredients independently of stored recipe totals`() {
        val encoded = BackupCodec.encode(recipeBackup())
        for ((field, value) in listOf("quantity" to 50.0, "factor" to 2.0, "unit" to FoodUnit.MILLILITER.name)) {
            val json = JSONObject(encoded)
            json.getJSONObject("nutrition").getJSONArray("recipes").getJSONObject(0)
                .getJSONArray("ingredients").getJSONObject(0).put(field, value)
            reject { BackupCodec.decode(json.toString()) }
        }
    }

    @Test fun `native grams milliliters servings and explicit cross-unit conversions round trip`() {
        val grams = snapshot()
        val milliliters = snapshot().copy(baseUnit = FoodUnit.MILLILITER, gramsPerBase = null, millilitersPerBase = 100.0)
        val serving = snapshot().copy(baseAmount = 1.0, baseUnit = FoodUnit.SERVING, gramsPerBase = 80.0,
            millilitersPerBase = 200.0)
        val cases = listOf(
            Triple(grams, FoodUnit.GRAM, 250.0 to 2.5),
            Triple(milliliters, FoodUnit.MILLILITER, 250.0 to 2.5),
            Triple(serving, FoodUnit.SERVING, 3.0 to 3.0),
            Triple(serving, FoodUnit.GRAM, 40.0 to 0.5),
            Triple(serving, FoodUnit.MILLILITER, 500.0 to 2.5),
            Triple(grams.copy(millilitersPerBase = 250.0), FoodUnit.MILLILITER, 125.0 to 0.5),
            Triple(milliliters.copy(gramsPerBase = 80.0), FoodUnit.GRAM, 40.0 to 0.5),
        )
        for ((snapshot, unit, amount) in cases) {
            val row = cached(diary().copy(snapshot = snapshot, unit = unit, quantity = amount.first, factor = amount.second))
            assertEquals(backup(row), BackupCodec.decode(BackupCodec.encode(backup(row))))
            val recipe = recipeBackup(ingredient(row))
            assertEquals(recipe, BackupCodec.decode(BackupCodec.encode(recipe)))
        }
        // Repository/backup limits predate the UI's smaller quantity limit and stay compatible.
        val large = cached(diary().copy(quantity = 2_000_000.0, factor = 20_000.0))
        assertEquals(backup(large), BackupCodec.decode(BackupCodec.encode(backup(large))))
    }

    @Test fun `historical named portions retain their frozen conversion without a live food or portion`() {
        val row = cached(diary().copy(unit = FoodUnit.SERVING, quantity = 2.0, factor = 1.7,
            portionId = "deleted-source-portion", portionLabel = "Old source bowl"))
        val original = recipeBackup(ingredient(row)).copy(nutrition = recipeBackup(ingredient(row)).nutrition!!.copy(diary = listOf(row)))
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, restored)
        assertEquals(340.0, RecipeMath.totals(restored.nutrition!!.recipes.single().ingredients)
            .single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
    }

    @Test fun `a doubled tiny native factor is rejected rather than hidden by an absolute tolerance floor`() {
        val row = cached(diary().copy(quantity = 1e-10, factor = 2e-12))
        reject { BackupCodec.encode(backup(row)) }
    }

    @Test fun `floating point rounding is tolerated relatively but material factor changes are rejected`() {
        for (quantity in listOf(123.456789, 1e-10)) {
            val expected = quantity / 100.0
            val rounded = cached(diary().copy(quantity = quantity, factor = expected * (1.0 + 5e-10)))
            assertEquals(backup(rounded), BackupCodec.decode(BackupCodec.encode(backup(rounded))))
            reject { BackupCodec.encode(backup(cached(rounded.copy(factor = expected * (1.0 + 1e-6))))) }
        }
    }

    @Test fun `recipe calculations reject a mismatched native quantity factor pair`() {
        try {
            RecipeMath.totals(listOf(ingredient(diary().copy(factor = 2.0))))
            fail("Expected invalid recipe amount")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `invalid restore is rejected before current database data or preference callback changes`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        try {
            val repository = RoomBackupRepository(database)
            database.workoutDao().insertWorkout(Workout(id = 10, name = "Keep existing workout", startTime = 1, endTime = 2))
            val before = repository.snapshot().copy(createdAt = 0)
            var callbackRan = false
            try {
                repository.restore(backup(diary().copy(factor = 2.0, kcal = 400.0))) { callbackRan = true }
                fail("Expected inconsistent backup to be rejected")
            } catch (failure: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, failure.issue) }
            assertFalse(callbackRan)
            assertEquals(before, repository.snapshot().copy(createdAt = 0))
        } finally { database.close() }
    }

    private fun snapshot() = FoodSnapshot(foodId = "reference:no-longer-installed", name = "Frozen source food",
        sourceType = FoodSourceType.REFERENCE, baseAmount = 100.0, baseUnit = FoodUnit.GRAM, gramsPerBase = 100.0,
        nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 200.0)))

    private fun diary() = DiaryEntity(id = 1, day = "2026-10-30", meal = MealType.LUNCH,
        foodId = snapshot().foodId, snapshot = snapshot(), quantity = 100.0, unit = FoodUnit.GRAM,
        factor = 1.0, loggedAt = 1, kcal = 200.0)

    private fun backup(row: DiaryEntity) = ForgeBackup(createdAt = 0, nutrition = NutritionBackup(diary = listOf(row)))

    private fun cached(row: DiaryEntity): DiaryEntity = row.copy(kcal = 200.0 * row.factor)

    private fun ingredient(row: DiaryEntity) = RecipeIngredientEntity(id = 2, recipeId = "recipe:f04", orderIndex = 0,
        foodId = row.foodId, snapshot = row.snapshot, quantity = row.quantity, unit = row.unit,
        portionId = row.portionId, portionLabel = row.portionLabel, factor = row.factor)

    private fun recipeBackup(row: RecipeIngredientEntity = ingredient(diary())): ForgeBackup {
        val food = FoodDetail(FoodEntity(id = "recipe:f04", name = "Frozen ingredient recipe", sourceType = FoodSourceType.RECIPE,
            baseAmount = 1.0, baseUnit = FoodUnit.SERVING),
            listOf(FoodNutrientEntity("recipe:f04", NutrientCodes.ENERGY, "Energy", "kcal", 200.0 * row.factor)))
        return ForgeBackup(createdAt = 0, nutrition = NutritionBackup(foods = listOf(food),
            recipes = listOf(RecipeDetail(RecipeEntity("recipe:f04", food.food.name, servings = 1.0), listOf(row)))))
    }

    private fun encodedDiary() = JSONObject(BackupCodec.encode(backup(diary())))
    private fun JSONObject.diary() = getJSONObject("nutrition").getJSONArray("diary").getJSONObject(0)
    private fun reject(action: () -> Unit) {
        try { action(); fail("Expected inconsistent nutrition amount to be rejected") }
        catch (failure: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, failure.issue) }
    }
}

package com.forge.workout.backup

import android.app.Application
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.preferences.UserPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecipeBackupConsistencyHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var backups: RoomBackupRepository
    private lateinit var foods: RoomNutritionRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        backups = RoomBackupRepository(database)
        foods = RoomNutritionRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun `changing only recipe servings in a portable backup is rejected`() = runBlocking<Unit> {
        val root = encoded()
        recipe(root).put("servings", 2.0)
        expectInvalid { BackupCodec.decode(root.toString()) }
    }

    @Test fun `changing only cooked weight in a portable backup is rejected`() = runBlocking<Unit> {
        val root = encoded()
        recipe(root).put("cookedWeightGrams", 400.0)
        expectInvalid { BackupCodec.decode(root.toString()) }
    }

    @Test fun `changing only stored recipe nutrition in a portable backup is rejected`() = runBlocking<Unit> {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { root -> nutrient(root, NutrientCodes.ENERGY).put("value", 300.0) },
            { root -> nutrient(root, NutrientCodes.PROTEIN).put("value", 0.0) },
            { root -> nutrient(root, VITAMIN_C).put("unit", "g") },
            { root -> nutrient(root, VITAMIN_C).put("value", JSONObject.NULL) },
        )
        for (edit in edits) {
            val root = encoded()
            edit(root)
            expectInvalid { BackupCodec.decode(root.toString()) }
        }
    }

    @Test fun `direct restores reject independent recipe contradictions before callback or database mutation`() = runBlocking<Unit> {
        val original = fixture()
        backups.restore(original)
        val before = snapshot()
        val nutrition = requireNotNull(original.nutrition)
        val detail = nutrition.recipes.single()
        val food = nutrition.foods.single()
        fun withFood(value: FoodDetail) = original.copy(nutrition = nutrition.copy(foods = listOf(value)))
        val invalid = listOf(
            original.copy(nutrition = nutrition.copy(recipes = listOf(detail.copy(recipe = detail.recipe.copy(servings = 2.0))))),
            original.copy(nutrition = nutrition.copy(recipes = listOf(detail.copy(recipe = detail.recipe.copy(cookedWeightGrams = 400.0))))),
            withFood(food.copy(nutrients = food.nutrients.map { if (it.code == NutrientCodes.ENERGY) it.copy(value = 300.0) else it })),
            withFood(food.copy(nutrients = food.nutrients.map { if (it.code == NutrientCodes.PROTEIN) it.copy(value = 0.0) else it })),
            withFood(food.copy(nutrients = food.nutrients.filterNot { it.code == VITAMIN_C })),
            withFood(food.copy(nutrients = food.nutrients + FoodNutrientEntity(RECIPE_ID, "invented", "Invented", "mg", 1.0))),
            withFood(food.copy(food = food.food.copy(gramsPerBase = null))),
            withFood(food.copy(food = food.food.copy(millilitersPerBase = 200.0))),
        )
        for (incoming in invalid) {
            var callbackRan = false
            expectInvalid { backups.restore(incoming) { callbackRan = true } }
            assertFalse(callbackRan)
            assertEquals(before, snapshot())
            assertEquals(475.0, requireNotNull(foods.diaryEntry(DIARY_ID)?.kcal), 0.0)
        }
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun `valid recipe round trip uses frozen ingredients and preserves historical diary nutrition without its catalog`() = runBlocking<Unit> {
        val incoming = fixture()
        val portable = BackupCodec.decode(BackupCodec.encode(incoming))
        assertEquals(incoming, portable)
        backups.restore(portable)
        assertNull(foods.food("catalog:missing"))
        val recipeFood = requireNotNull(foods.food(RECIPE_ID))
        assertEquals(200.0, recipeFood.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertNull(recipeFood.nutrients.single { it.code == NutrientCodes.PROTEIN }.value)
        assertEquals(0.0, recipeFood.nutrients.single { it.code == NutrientCodes.FAT }.value!!, 0.0)
        assertEquals(475.0, foods.diaryEntry(DIARY_ID)!!.kcal!!, 0.0)
        assertEquals(incoming, snapshot())
    }

    @Test fun `missing and explicit unknown nutrients remain unknown and equivalent microgram units round trip`() = runBlocking<Unit> {
        val incoming = fixture(twoIngredients = true)
        val n = requireNotNull(incoming.nutrition)
        val food = n.foods.single()
        val unknown = food.nutrients.single { it.code == UNKNOWN_MICRO }
        assertNull(unknown.value)
        val compatible = incoming.copy(nutrition = n.copy(foods = listOf(food.copy(nutrients = food.nutrients
            .filterNot { it.code == UNKNOWN_MICRO }
            .map { if (it.code == FOLATE) it.copy(unit = "µg") else it }))))
        val decoded = BackupCodec.decode(BackupCodec.encode(compatible))
        backups.restore(decoded)
        assertEquals(decoded, snapshot())
        assertNull(foods.food(RECIPE_ID)!!.nutrients.single { it.code == NutrientCodes.PROTEIN }.value)
    }

    @Test fun `rounding tolerance accepts tiny numeric differences but rejects material recipe changes`() = runBlocking<Unit> {
        val incoming = fixture()
        val n = requireNotNull(incoming.nutrition)
        val food = n.foods.single()
        val roundoff = incoming.copy(nutrition = n.copy(foods = listOf(food.copy(
            food = food.food.copy(gramsPerBase = food.food.gramsPerBase!! + 1e-10),
            nutrients = food.nutrients.map { it.copy(value = it.value?.plus(1e-10)) },
        ))))
        val portable = BackupCodec.decode(BackupCodec.encode(roundoff))
        backups.restore(portable)
        assertEquals(portable, snapshot())

        val material = incoming.copy(nutrition = n.copy(foods = listOf(food.copy(
            nutrients = food.nutrients.map { if (it.code == NutrientCodes.ENERGY) it.copy(value = it.value!! + 1e-5) else it },
        ))))
        expectInvalid { backups.restore(material) }
        assertEquals(portable, snapshot())
    }

    @Test fun `legacy entirely unreported recipe nutrition can remain omitted`() = runBlocking<Unit> {
        val incoming = fixture()
        val n = requireNotNull(incoming.nutrition)
        val unreported = incoming.copy(nutrition = n.copy(
            foods = n.foods.map { it.copy(nutrients = emptyList()) },
            recipes = n.recipes.map { detail -> detail.copy(ingredients = detail.ingredients.map {
                it.copy(snapshot = it.snapshot.copy(nutrients = emptyList()))
            }) },
        ))
        val portable = BackupCodec.decode(BackupCodec.encode(unreported))
        backups.restore(portable)
        assertEquals(portable, snapshot())
        assertEquals(emptyList<FoodNutrientEntity>(), foods.food(RECIPE_ID)!!.nutrients)
        assertEquals(475.0, foods.diaryEntry(DIARY_ID)!!.kcal!!, 0.0)
    }

    private fun fixture(twoIngredients: Boolean = false): ForgeBackup {
        val ingredient = FoodSnapshot(foodId = "catalog:missing", name = "Frozen ingredient", sourceType = FoodSourceType.REFERENCE,
            baseAmount = 100.0, baseUnit = FoodUnit.GRAM, gramsPerBase = 100.0,
            nutrients = listOf(
                NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
                NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", null),
                NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", 30.0),
                NutrientValue(NutrientCodes.FAT, "Fat", "g", 0.0),
                NutrientValue(VITAMIN_C, "Vitamin C", "mg", 12.0),
                NutrientValue(FOLATE, "Folate", "ug", 20.0),
                NutrientValue(UNKNOWN_MICRO, "Zinc", "mg", 1.0),
            ))
        val rows = mutableListOf(RecipeIngredientDraft("first", rowId = 701, snapshot = ingredient,
            choice = NutritionMath.choices(ingredient).single(), quantity = "100"))
        if (twoIngredients) {
            val sparse = ingredient.copy(foodId = "catalog:also-missing", nutrients = ingredient.nutrients.filterNot { it.code == UNKNOWN_MICRO })
            rows += RecipeIngredientDraft("second", rowId = 702, snapshot = sparse,
                choice = NutritionMath.choices(sparse).single(), quantity = "50")
        }
        val derived = RecipeFactory.validate(RecipeDraft(RECIPE_ID, "Frozen recipe", servings = "1", cookedWeightGrams = "200", ingredients = rows))
        check(derived.invalidFields.isEmpty())
        val food = requireNotNull(derived.food).let { it.copy(nutrients = it.nutrients.sortedBy { nutrient -> nutrient.code }) }
        val history = food.snapshot().copy(name = "Earlier recipe version", nutrients = food.snapshot().nutrients.map {
            if (it.code == NutrientCodes.ENERGY) it.copy(value = 475.0) else it
        })
        val diary = DiaryEntity(DIARY_ID, "2026-11-10", MealType.LUNCH, RECIPE_ID, history,
            quantity = 1.0, unit = FoodUnit.SERVING, factor = 1.0, loggedAt = 1234,
            kcal = 475.0, protein = null,
            carbs = history.nutrients.single { it.code == NutrientCodes.CARBS }.value, fat = 0.0)
        return completeBackup().copy(preferences = UserPreferences(), nutrition = NutritionBackup(foods = listOf(food),
            recipes = listOf(RecipeDetail(requireNotNull(derived.recipe), derived.ingredients)), diary = listOf(diary)))
    }

    private fun encoded() = JSONObject(BackupCodec.encode(fixture()))
    private fun recipe(root: JSONObject) = root.getJSONObject("nutrition").getJSONArray("recipes").getJSONObject(0).getJSONObject("recipe")
    private fun nutrient(root: JSONObject, code: String): JSONObject {
        val nutrients = root.getJSONObject("nutrition").getJSONArray("foods").getJSONObject(0).getJSONArray("nutrients")
        return (0 until nutrients.length()).map(nutrients::getJSONObject).single { it.getString("code") == code }
    }
    private suspend fun snapshot() = backups.snapshot().copy(createdAt = 10000)
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected inconsistent recipe backup rejection")
        } catch (error: BackupValidationException) {
            assertEquals(BackupIssue.INVALID_DATA, error.issue)
        }
    }

    private companion object {
        const val RECIPE_ID = "recipe:frozen"
        const val DIARY_ID = 901L
        const val VITAMIN_C = "usda:401"
        const val FOLATE = "usda:417"
        const val UNKNOWN_MICRO = "usda:309"
    }
}

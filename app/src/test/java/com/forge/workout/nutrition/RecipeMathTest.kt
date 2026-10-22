package com.forge.workout.nutrition

import com.forge.workout.nutrition.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMathTest {
    @Test fun `weighted ingredients and milliliter bases produce exact totals and per serving without assuming cooked mass`() {
        val rice = snapshot("rice", "Rice, cooked", 100.0, FoodUnit.GRAM, 120.0, 2.5, 26.0, .4)
        val yogurt = snapshot("yogurt", "Cultured yogurt", 200.0, FoodUnit.MILLILITER, 150.0, 10.0, 8.0, 5.0)
        val draft = RecipeDraft(name = "Rice bowl", servings = "3", ingredients = listOf(
            ingredient(rice, "150"), ingredient(yogurt, "100")))
        val result = RecipeFactory.validate(draft)
        assertTrue(result.invalidFields.isEmpty())
        assertEquals(85.0, value(result.perServing, NutrientCodes.ENERGY)!!, 0.000001)
        assertEquals(8.75 / 3, value(result.perServing, NutrientCodes.PROTEIN)!!, 0.000001)
        assertEquals(43.0 / 3, value(result.perServing, NutrientCodes.CARBS)!!, 0.000001)
        assertEquals(3.1 / 3, value(result.perServing, NutrientCodes.FAT)!!, 0.000001)
        assertEquals(255.0, value(RecipeMath.totals(result.ingredients), NutrientCodes.ENERGY)!!, 0.0)
        assertEquals(1.0, result.food!!.food.baseAmount, 0.0)
        assertEquals(FoodUnit.SERVING, result.food.food.baseUnit)
        assertEquals(FoodSourceType.RECIPE, result.food.food.sourceType)
        assertNull(result.food.food.packId)
        assertNull(result.food.food.gramsPerBase)
        assertNull(result.food.food.millilitersPerBase)
        assertEquals(listOf("Rice, cooked", "Cultured yogurt"), result.ingredients.map { it.snapshot.name })
    }

    @Test fun `every missing or null measurement propagates unknown while actual zero remains zero`() {
        val first = FoodSnapshot(foodId = "one", name = "One", nutrients = listOf(
            NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 100.0),
            NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", null),
            NutrientValue(NutrientCodes.FAT, "Fat", "g", 0.0),
            NutrientValue("usda:401", "Vitamin C", "mg", 30.0)))
        val second = FoodSnapshot(foodId = "two", name = "Two", nutrients = listOf(
            NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 4.0),
            NutrientValue(NutrientCodes.FAT, "Fat", "g", 0.0)))
        val result = RecipeFactory.validate(RecipeDraft(name = "Unknowns", ingredients = listOf(ingredient(first), ingredient(second))))
        assertTrue(result.invalidFields.isEmpty())
        assertEquals(5, result.perServing.size)
        assertNull(value(result.perServing, NutrientCodes.ENERGY))
        assertNull(value(result.perServing, NutrientCodes.PROTEIN))
        assertNull(value(result.perServing, NutrientCodes.CARBS))
        assertNull(value(result.perServing, "usda:401"))
        assertEquals(0.0, value(result.perServing, NutrientCodes.FAT)!!, 0.0)
        val entirelyUnreported = RecipeFactory.validate(RecipeDraft(name = "No reported values",
            ingredients = listOf(ingredient(FoodSnapshot(name = "Unreported")))))
        assertEquals(4, entirelyUnreported.perServing.size)
        assertTrue(entirelyUnreported.perServing.all { it.value == null })
    }

    @Test fun `USDA microgram spellings normalize without changing numeric units or source snapshots`() {
        val units = listOf("ug", "µg", "μg")
        val snapshots = units.mapIndexed { index, unit -> FoodSnapshot(foodId = "source-$index", name = "Vitamin source",
            nutrients = listOf(NutrientValue("usda:320", "Vitamin A, RAE", unit, (index + 1) * 100.0))) }
        val result = RecipeFactory.validate(RecipeDraft(name = "Vitamins", servings = "2",
            ingredients = snapshots.map { ingredient(it) }))
        assertTrue(result.invalidFields.isEmpty())
        val nutrient = result.perServing.single { it.code == "usda:320" }
        assertEquals("ug", nutrient.unit)
        assertEquals(300.0, nutrient.value!!, 0.0)
        assertEquals(units, result.ingredients.map { it.snapshot.nutrients.single().unit })
        assertEquals(units, snapshots.map { it.nutrients.single().unit })
    }

    @Test fun `incompatible units reject even unknown values instead of silently combining g with mg`() {
        val first = FoodSnapshot(name = "Calcium grams", nutrients = listOf(NutrientValue("usda:301", "Calcium", "g", 1.0)))
        val second = FoodSnapshot(name = "Calcium milligrams", nutrients = listOf(NutrientValue("usda:301", "Calcium", "mg", null)))
        val rows = listOf(ingredient(first, key = "first"), ingredient(second, key = "second"))
        val result = RecipeFactory.validate(RecipeDraft(name = "Mixed units", ingredients = rows))
        assertNull(result.recipe)
        assertNull(result.food)
        assertTrue(result.perServing.isEmpty())
        assertTrue(result.invalidFields.containsAll(listOf("ingredients", "units:usda:301", "ingredient:first:units", "ingredient:second:units")))
        assertThrows(RecipeUnitMismatchException::class.java) { RecipeMath.totals(result.ingredients) }
    }

    @Test fun `only supplied finished weight enables gram servings and fractional servings remain supported`() {
        val source = snapshot("food", "Raw ingredient", energy = 120.0)
        val result = RecipeFactory.validate(RecipeDraft(name = "Cooked result", servings = "2,5", cookedWeightGrams = "750",
            ingredients = listOf(ingredient(source))))
        assertTrue(result.invalidFields.isEmpty())
        assertEquals(48.0, value(result.perServing, NutrientCodes.ENERGY)!!, 0.0)
        assertEquals(300.0, result.food!!.food.gramsPerBase!!, 0.0)
        val snapshot = result.food.snapshot()
        val grams = NutritionMath.choices(snapshot).single { it.unit == FoodUnit.GRAM }
        assertEquals(1.0, NutritionMath.factor(snapshot, grams, 300.0), 0.000001)
        assertEquals(120.0, value(NutritionMath.scaled(snapshot, NutritionMath.factor(snapshot, grams, 750.0)), NutrientCodes.ENERGY)!!, 0.000001)
        val withoutWeight = RecipeFactory.validate(RecipeDraft(name = "Unweighed", ingredients = listOf(ingredient(source))))
        assertNull(withoutWeight.food!!.food.gramsPerBase)
        assertFalse(NutritionMath.choices(withoutWeight.food.snapshot()).any { it.unit == FoodUnit.GRAM })
    }

    @Test fun `invalid numeric inputs empty ingredients duplicate rows and self references return explicit fields`() {
        val base = RecipeDraft(id = "recipe:test", name = "Valid", ingredients = listOf(ingredient(snapshot("food", "Food"), key = "row")))
        for (value in listOf("", "0", "-1", "NaN", "Infinity", "1000000001")) {
            assertTrue("servings" in RecipeFactory.validate(base.copy(servings = value)).invalidFields)
        }
        for (value in listOf("0", "-1", "NaN", "Infinity", "1000000001")) {
            assertTrue("cookedWeightGrams" in RecipeFactory.validate(base.copy(cookedWeightGrams = value)).invalidFields)
        }
        for (value in listOf("", "0", "-1", "NaN", "Infinity", "1000001")) {
            assertTrue("ingredient:row" in RecipeFactory.validate(base.copy(ingredients = listOf(base.ingredients.single().copy(quantity = value)))).invalidFields)
        }
        assertTrue("name" in RecipeFactory.validate(base.copy(name = " ")).invalidFields)
        assertTrue("ingredients" in RecipeFactory.validate(base.copy(ingredients = emptyList())).invalidFields)
        assertTrue("ingredients" in RecipeFactory.validate(base.copy(ingredients = base.ingredients + base.ingredients)).invalidFields)
        val self = base.ingredients.single().copy(snapshot = snapshot(base.id, "Self"))
        assertTrue("ingredient:row" in RecipeFactory.validate(base.copy(ingredients = listOf(self))).invalidFields)
        val guessed = base.ingredients.single().copy(choice = FoodServingChoice("MILLILITER", unit = FoodUnit.MILLILITER,
            baseQuantity = 1.0, suggestedQuantity = 100.0))
        assertTrue("ingredient:row" in RecipeFactory.validate(base.copy(ingredients = listOf(guessed))).invalidFields)
    }

    @Test fun `historical portion factors row IDs and recipe metadata survive rebuilding and reordering`() {
        val source = snapshot("deleted-source", "Original source name", energy = 194.0)
        val recipe = RecipeEntity("recipe:history", "Recipe", 2.0, 170.0)
        val rows = listOf(
            RecipeIngredientEntity(41, recipe.id, 0, source.foodId, source, 2.0, FoodUnit.SERVING, "label", "5 nuggets (85g)", 1.7),
            RecipeIngredientEntity(42, recipe.id, 1, source.foodId, source, 100.0, FoodUnit.GRAM, factor = 1.0))
        val existing = FoodDetail(FoodEntity(rowId = 71, id = recipe.id, name = recipe.name, sourceType = FoodSourceType.RECIPE,
            brand = "Home", aliases = "family meal", source = "Family recipe", sourceId = "book-1", sourceUrl = "https://example.com/book",
            notes = "Cook slowly", region = "IN", tags = "home", archived = true, baseAmount = 1.0, baseUnit = FoodUnit.SERVING))
        val detail = RecipeDetail(recipe, rows)
        val draft = RecipeFactory.fromRecipe(detail, existing)
        assertEquals(listOf("row:41", "row:42"), draft.ingredients.map { it.key })
        assertEquals(draft.ingredients.map { it.key }, RecipeFactory.fromRecipe(detail, existing).ingredients.map { it.key })
        val result = RecipeFactory.validate(draft.copy(name = "Renamed recipe", ingredients = draft.ingredients.reversed()), existing)
        assertTrue(result.invalidFields.isEmpty())
        assertEquals(listOf(42L, 41L), result.ingredients.map { it.id })
        assertEquals(listOf(0, 1), result.ingredients.map { it.orderIndex })
        assertEquals(1.7, result.ingredients.last().factor, 0.000001)
        assertEquals("5 nuggets (85g)", result.ingredients.last().portionLabel)
        assertEquals("Original source name", result.ingredients.last().snapshot.name)
        val food = result.food!!.food
        assertEquals(71L, food.rowId)
        assertEquals(existing.food.brand, food.brand)
        assertEquals(existing.food.aliases, food.aliases)
        assertEquals(existing.food.source, food.source)
        assertEquals(existing.food.sourceUrl, food.sourceUrl)
        assertEquals(existing.food.notes, food.notes)
        assertEquals(existing.food.region, food.region)
        assertEquals(existing.food.tags, food.tags)
        assertTrue(food.archived)
        assertEquals(rows, detail.ingredients)
    }

    @Test fun `validation freezes ingredient nutrient lists and handles the supported five hundred ingredient bound`() {
        val mutable = snapshot("source", "Source", energy = 1.0).nutrients.toMutableList()
        val source = FoodSnapshot(foodId = "source", name = "Source", nutrients = mutable)
        val result = RecipeFactory.validate(RecipeDraft(name = "Many ingredients", servings = "50", ingredients =
            (0 until 500).map { ingredient(source, key = "ingredient-$it") }))
        assertTrue(result.invalidFields.isEmpty())
        assertEquals(10.0, value(result.perServing, NutrientCodes.ENERGY)!!, 0.0)
        mutable.clear()
        assertEquals(4, result.ingredients.first().snapshot.nutrients.size)
        assertEquals(1.0, value(result.ingredients.first().snapshot.nutrients, NutrientCodes.ENERGY)!!, 0.0)
        assertTrue("ingredients" in RecipeFactory.validate(RecipeDraft(name = "Too many", ingredients =
            (0 until 501).map { ingredient(FoodSnapshot(name = "Food"), key = "ingredient-$it") })).invalidFields)
    }

    @Test fun `maximum length source portion IDs remain valid when prefixed into serving choice IDs`() {
        val source = snapshot("source", "Source", energy = 200.0)
        val portionId = "p".repeat(200)
        val choice = FoodServingChoice("portion:$portionId", "Measured portion", FoodUnit.SERVING,
            baseQuantity = 85.0, suggestedQuantity = 1.0, portionId = portionId)
        val draft = RecipeDraft(name = "Long portion ID", ingredients = listOf(
            RecipeIngredientDraft(key = "portion-row", snapshot = source, choice = choice, quantity = "2")))
        val valid = RecipeFactory.validate(draft)
        assertTrue(valid.invalidFields.isEmpty())
        assertEquals(portionId, valid.ingredients.single().portionId)
        assertEquals("Measured portion", valid.ingredients.single().portionLabel)
        assertEquals(1.7, valid.ingredients.single().factor, 0.000001)
        assertEquals(340.0, value(valid.perServing, NutrientCodes.ENERGY)!!, 0.000001)
        val tooLongChoice = draft.ingredients.single().copy(choice = choice.copy(id = "p".repeat(301)))
        assertTrue("ingredient:portion-row" in RecipeFactory.validate(draft.copy(ingredients = listOf(tooLongChoice))).invalidFields)
        val tooLongPortion = draft.ingredients.single().copy(choice = choice.copy(portionId = "p".repeat(201)))
        assertTrue("ingredient:portion-row" in RecipeFactory.validate(draft.copy(ingredients = listOf(tooLongPortion))).invalidFields)
    }

    private fun ingredient(snapshot: FoodSnapshot, quantity: String = "100", key: String = java.util.UUID.randomUUID().toString()) =
        RecipeIngredientDraft(key = key, snapshot = snapshot, choice = NutritionMath.choices(snapshot).first(), quantity = quantity)
    private fun snapshot(id: String, name: String, amount: Double = 100.0, unit: FoodUnit = FoodUnit.GRAM,
        energy: Double = 100.0, protein: Double = 0.0, carbs: Double = 0.0, fat: Double = 0.0) =
        FoodSnapshot(id, name, baseAmount = amount, baseUnit = unit, gramsPerBase = if (unit == FoodUnit.GRAM) amount else null,
            millilitersPerBase = if (unit == FoodUnit.MILLILITER) amount else null,
            nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", energy),
                NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", protein),
                NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", carbs),
                NutrientValue(NutrientCodes.FAT, "Fat", "g", fat)))
    private fun value(nutrients: List<NutrientValue>, code: String) = nutrients.single { it.code == code }.value
}

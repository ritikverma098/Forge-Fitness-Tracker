package com.forge.workout.nutrition

import com.forge.workout.nutrition.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFoodDraftTest {
    @Test fun `blank nutrients are unknown while explicit zero and comma decimals remain actual values`() {
        val draft = CustomFoodDraft("custom:values", name = "  Homemade food  ", brand = "  Home  ", baseAmount = "250,5",
            nutrients = CustomFoodDraft.coreNutrients().map { row -> when (row.code) {
                NutrientCodes.ENERGY -> row.copy(value = "0")
                NutrientCodes.PROTEIN -> row.copy(value = "1,25")
                else -> row
            } })
        val result = CustomFoodFactory.validate(draft)
        assertTrue(result.invalidFields.isEmpty())
        val detail = result.food!!
        assertEquals("Homemade food", detail.food.name)
        assertEquals("Home", detail.food.brand)
        assertEquals(FoodSourceType.CUSTOM, detail.food.sourceType)
        assertEquals(250.5, detail.food.baseAmount, 0.0)
        assertEquals(250.5, detail.food.gramsPerBase!!, 0.0)
        assertEquals(0.0, detail.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertEquals(1.25, detail.nutrients.single { it.code == NutrientCodes.PROTEIN }.value!!, 0.0)
        assertNull(detail.nutrients.single { it.code == NutrientCodes.CARBS }.value)
        assertNull(detail.nutrients.single { it.code == NutrientCodes.FAT }.value)
    }

    @Test fun `empty names invalid finite bounds and invalid metadata report fields instead of throwing`() {
        val valid = CustomFoodDraft("custom:validation", name = "Food")
        assertEquals(setOf("name"), CustomFoodFactory.validate(valid.copy(name = " ")).invalidFields)
        for (value in listOf("", "0", "-1", "NaN", "Infinity", "1000000001", "abc")) {
            val result = CustomFoodFactory.validate(valid.copy(baseAmount = value))
            assertNull(result.food)
            assertTrue("Base '$value' must be invalid", "baseAmount" in result.invalidFields)
        }
        for (value in listOf("-1", "NaN", "Infinity", "1000000001", "abc")) {
            val result = CustomFoodFactory.validate(valid.copy(nutrients = valid.nutrients.map {
                if (it.code == NutrientCodes.PROTEIN) it.copy(value = value) else it
            }))
            assertNull(result.food)
            assertTrue("Nutrient '$value' must be invalid", "nutrient:protein" in result.invalidFields)
        }
        assertTrue("gramsPerBase" in CustomFoodFactory.validate(valid.copy(baseUnit = FoodUnit.SERVING, gramsPerBase = "0")).invalidFields)
        assertTrue("millilitersPerBase" in CustomFoodFactory.validate(valid.copy(millilitersPerBase = "-1")).invalidFields)
        assertTrue("nutrients" in CustomFoodFactory.validate(valid.copy(nutrients = valid.nutrients + valid.nutrients.first())).invalidFields)
        assertTrue("nutrients" in CustomFoodFactory.validate(valid.copy(nutrients = valid.nutrients.map {
            if (it.code == NutrientCodes.PROTEIN) it.copy(unit = "mg") else it
        })).invalidFields)
    }

    @Test fun `editing retains row identity source fields arbitrary nutrient metadata known equivalents and portions`() {
        val original = existingFood()
        val before = original.snapshot()
        val draft = CustomFoodFactory.fromFood(original).copy(name = "Renamed food", notes = "Updated notes")
        val edited = CustomFoodFactory.validate(draft, original).food!!
        assertEquals(original.food.rowId, edited.food.rowId)
        assertEquals(original.food.id, edited.food.id)
        assertEquals(original.food.aliases, edited.food.aliases)
        assertEquals(original.food.source, edited.food.source)
        assertEquals(original.food.sourceId, edited.food.sourceId)
        assertEquals(original.food.sourceUrl, edited.food.sourceUrl)
        assertEquals(original.food.region, edited.food.region)
        assertEquals(original.food.tags, edited.food.tags)
        assertEquals(80.0, edited.food.millilitersPerBase!!, 0.0)
        assertEquals(original.portions, edited.portions)
        assertEquals(original.nutrients.single { it.code == "laboratory:iron" }, edited.nutrients.single { it.code == "laboratory:iron" })
        assertNull(edited.nutrients.single { it.code == "usda:291" }.value)
        assertEquals(before, original.snapshot())
        assertEquals("Original food", original.food.name)
    }

    @Test fun `legacy optional nutrients survive omitted fields and only deliberate removals delete them`() {
        val original = existingFood()
        val coreOnly = CustomFoodFactory.fromFood(original).copy(nutrients = CustomFoodDraft.coreNutrients())
        val preserved = CustomFoodFactory.validate(coreOnly, original).food!!
        assertTrue(preserved.nutrients.any { it.code == "laboratory:iron" })
        assertTrue(preserved.nutrients.any { it.code == "usda:291" })
        val removed = CustomFoodFactory.validate(coreOnly.copy(removedNutrientCodes = listOf("laboratory:iron")), original).food!!
        assertTrue(removed.nutrients.none { it.code == "laboratory:iron" })
        assertNull(removed.nutrients.single { it.code == "usda:291" }.value)
        assertTrue("nutrients" in CustomFoodFactory.validate(coreOnly.copy(removedNutrientCodes = listOf(NutrientCodes.ENERGY)), original).invalidFields)
    }

    @Test fun `basis changes derive native equivalents and clear stale conversions unless explicitly updated`() {
        val original = CustomFoodFactory.fromFood(existingFood())
        assertEquals("80", CustomFoodFactory.normalizeUpdate(original, original.copy(notes = "More notes")).millilitersPerBase)
        assertEquals("80", CustomFoodFactory.normalizeUpdate(original, original.copy(baseAmount = "100.0")).millilitersPerBase)
        val resized = CustomFoodFactory.normalizeUpdate(original, original.copy(baseAmount = "200"))
        assertEquals("200", resized.gramsPerBase)
        assertEquals("", resized.millilitersPerBase)
        val explicit = CustomFoodFactory.normalizeUpdate(original, original.copy(baseAmount = "200", millilitersPerBase = "160"))
        assertEquals("160", explicit.millilitersPerBase)
        val liquid = CustomFoodFactory.normalizeUpdate(original, original.copy(baseUnit = FoodUnit.MILLILITER, baseAmount = "250"))
        assertEquals("250", liquid.millilitersPerBase)
        assertEquals("", liquid.gramsPerBase)
        val converted = CustomFoodFactory.validate(liquid, existingFood()).food!!
        assertEquals(250.0, converted.food.millilitersPerBase!!, 0.0)
        assertEquals(120.0, converted.portions.single().baseQuantity, 0.0)
    }

    @Test fun `custom identity validation prevents reference and recipe conversion and foreign new IDs`() {
        val draft = CustomFoodFactory.fromFood(existingFood())
        for (type in listOf(FoodSourceType.REFERENCE, FoodSourceType.RECIPE)) {
            val protected = existingFood().copy(food = existingFood().food.copy(sourceType = type))
            assertTrue("food" in CustomFoodFactory.validate(draft, protected).invalidFields)
        }
        assertTrue("food" in CustomFoodFactory.validate(CustomFoodDraft("usda:fdc:1", name = "New food")).invalidFields)
        val first = CustomFoodDraft.new()
        val second = CustomFoodDraft.new()
        assertTrue(first.id.startsWith("custom:"))
        assertTrue(first.id != second.id)
    }

    private fun existingFood() = FoodDetail(
        FoodEntity(rowId = 71, id = "legacy-custom", name = "Original food", aliases = "home staple",
            source = "User analysis", sourceId = "sample-1", sourceUrl = "https://example.com/analysis", region = "IN",
            tags = "home", baseAmount = 100.0, gramsPerBase = 100.0, millilitersPerBase = 80.0, notes = "Original notes"),
        listOf(FoodNutrientEntity("legacy-custom", NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
            FoodNutrientEntity("legacy-custom", "laboratory:iron", "Measured iron", "mg", 2.75),
            FoodNutrientEntity("legacy-custom", "usda:291", "Fiber", "g", null)),
        listOf(FoodPortionEntity("legacy-custom", "cup", "One cup", 150.0, grams = 150.0, milliliters = 120.0, source = "Measured")),
    )
}

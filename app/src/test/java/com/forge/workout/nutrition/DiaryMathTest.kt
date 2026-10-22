package com.forge.workout.nutrition

import com.forge.workout.nutrition.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryMathTest {
    @Test fun `an empty day is a complete zero total for every macro`() {
        val totals = DiaryMath.totals(emptyList())
        assertEquals(DailyNutritionTotals(), totals)
        for (total in listOf(totals.kcal, totals.protein, totals.carbs, totals.fat)) {
            assertEquals(0.0, total.knownValue, 0.0)
            assertEquals(0, total.missingCount)
            assertTrue(total.complete)
        }
        assertEquals(2000.0, DiaryMath.remaining(2000.0, totals.kcal)!!, 0.0)
    }

    @Test fun `known subtotals actual zero and unknown counts stay independent for each macro`() {
        val rows = listOf(entry(kcal = 100.0, protein = 4.0, carbs = null, fat = 0.0),
            entry(kcal = 200.0, protein = null, carbs = 30.0, fat = 0.0),
            entry(kcal = null, protein = null, carbs = 10.0, fat = 5.0))
        val totals = DiaryMath.totals(rows)
        assertEquals(NutrientTotal(300.0, 1), totals.kcal)
        assertEquals(NutrientTotal(4.0, 2), totals.protein)
        assertEquals(NutrientTotal(40.0, 1), totals.carbs)
        assertEquals(NutrientTotal(5.0, 0), totals.fat)
        assertFalse(totals.kcal.complete)
        assertTrue(totals.fat.complete)
        // Cached values are already scaled, so the recorded factor is not applied a second time.
        assertEquals(totals, DiaryMath.totals(rows.map { it.copy(factor = 2.0) }))
    }

    @Test fun `remaining is unavailable for unset goals or missing values and retains over goal negatives`() {
        assertNull(DiaryMath.remaining(null, NutrientTotal(150.0)))
        assertNull(DiaryMath.remaining(2000.0, NutrientTotal(150.0, 1), 100.0))
        assertEquals(-100.0, DiaryMath.remaining(2000.0, NutrientTotal(2100.0))!!, 0.0)
        assertEquals(50.0, DiaryMath.remaining(2000.0, NutrientTotal(2100.0), 150.0)!!, 0.0)
        assertEquals(0.0, DiaryMath.remaining(0.0, NutrientTotal())!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) { DiaryMath.remaining(Double.NaN, NutrientTotal()) }
        assertThrows(IllegalArgumentException::class.java) { DiaryMath.remaining(2000.0, NutrientTotal(), -1.0) }
    }

    @Test fun `a historical portion reconstructs its exact factor and known equivalents without catalog data`() {
        val snapshot = FoodSnapshot(foodId = "removed-food", name = "Original measured food", brand = "Old brand",
            source = "Original source", sourceId = "source-id", sourceUrl = "https://example.com/original",
            sourceType = FoodSourceType.REFERENCE, baseAmount = 200.0, baseUnit = FoodUnit.MILLILITER,
            gramsPerBase = 240.0, millilitersPerBase = 200.0,
            nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 100.0),
                NutrientValue("vitamin", "Original vitamin name", "µg", null)))
        val recorded = entry().copy(foodId = snapshot.foodId, snapshot = snapshot, quantity = 3.0,
            unit = FoodUnit.SERVING, portionId = "old-portion", portionLabel = "Original 85mL portion", factor = 1.275)
        val detail = DiaryMath.snapshotDetails(recorded)
        assertEquals(snapshot, detail.snapshot())
        val portion = detail.portions.single()
        assertEquals(85.0, portion.baseQuantity, 0.000001)
        assertEquals(102.0, portion.grams!!, 0.000001)
        assertEquals(85.0, portion.milliliters!!, 0.000001)
        val choice = NutritionMath.choices(detail).single { it.portionId == recorded.portionId }
        assertEquals(recorded.portionLabel, choice.label)
        val factor = NutritionMath.factor(detail.snapshot(), choice, recorded.quantity)
        assertEquals(recorded.factor, factor, 0.000001)
        val scaled = NutritionMath.scaled(detail.snapshot(), factor)
        assertEquals(127.5, scaled.single { it.code == NutrientCodes.ENERGY }.value!!, 0.000001)
        assertNull(scaled.single { it.code == "vitamin" }.value)
    }

    @Test fun `snapshot details retain unknown nutrients do not invent density and freeze mutable source lists`() {
        val nutrients = mutableListOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", null))
        val snapshot = FoodSnapshot(name = "Anonymous original", nutrients = nutrients)
        val recorded = entry().copy(snapshot = snapshot)
        val detail = DiaryMath.snapshotDetails(recorded)
        nutrients.clear()
        assertEquals("diary:1", detail.food.id)
        assertEquals("Anonymous original", detail.food.name)
        assertNull(detail.nutrients.single().value)
        assertTrue(detail.portions.isEmpty())
        assertFalse(NutritionMath.choices(detail).any { it.unit == FoodUnit.MILLILITER })
        assertEquals(1.0, NutritionMath.factor(detail.snapshot(), NutritionMath.choices(detail).single(), 100.0), 0.0)
    }

    @Test fun `invalid cached data or totals fail clearly rather than contaminating daily totals`() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { DiaryMath.totals(listOf(entry(kcal = value))) }
            assertThrows(IllegalArgumentException::class.java) { NutrientTotal(value) }
        }
        assertThrows(IllegalArgumentException::class.java) { NutrientTotal(0.0, -1) }
        assertThrows(IllegalArgumentException::class.java) {
            DiaryMath.totals(listOf(entry(kcal = Double.MAX_VALUE), entry(kcal = Double.MAX_VALUE)))
        }
    }

    private fun entry(kcal: Double? = null, protein: Double? = null, carbs: Double? = null, fat: Double? = null) =
        DiaryEntity(id = 1, day = "2026-10-13", meal = MealType.LUNCH, snapshot = FoodSnapshot(name = "Food"),
            quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 1,
            kcal = kcal, protein = protein, carbs = carbs, fat = fat)
}

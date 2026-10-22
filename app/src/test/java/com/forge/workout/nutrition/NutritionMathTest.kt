package com.forge.workout.nutrition

import com.forge.workout.nutrition.model.*
import org.junit.Assert.*
import org.junit.Test

class NutritionMathTest {
    private val nutrients = listOf(NutrientValue("energy", "Energy", "kcal", 89.0),
        NutrientValue("protein", "Protein", "g", 1.09), NutrientValue("iron", "Iron", "mg", null),
        NutrientValue("sodium", "Sodium", "mg", 0.0))
    private val banana = FoodSnapshot(name = "Banana", nutrients = nutrients)

    @Test fun `native mass and source portion scale actual basis`() {
        val portions = listOf(FoodPortionEntity("banana", "cup", "1 cup mashed", 225.0, grams = 225.0))
        val choices = NutritionMath.choices(banana, portions)
        assertEquals(2, choices.size)
        assertEquals(1.5, NutritionMath.factor(banana, choices[0], 150.0), 0.000001)
        assertEquals(2.25, NutritionMath.factor(banana, choices[1], 1.0), 0.000001)
        assertEquals(100.125, NutritionMath.scaled(banana, NutritionMath.factor(banana, choices[1], 0.5))[0].value!!, 0.000001)
    }
    @Test fun `missing remains null while explicit zero remains zero`() {
        val values = NutritionMath.scaled(banana, 2.0)
        assertNull(values[2].value)
        assertEquals(0.0, values[3].value!!, 0.0)
        assertEquals(2.18, values[1].value!!, 0.000001)
        assertEquals("mg", values[2].unit)
    }
    @Test fun `milliliters require native volume or explicit equivalent`() {
        assertFalse(NutritionMath.choices(banana).any { it.unit == FoodUnit.MILLILITER })
        val volume = banana.copy(baseAmount = 200.0, baseUnit = FoodUnit.MILLILITER)
        assertEquals(0.5, NutritionMath.factor(volume, NutritionMath.choices(volume).single(), 100.0), 0.000001)
        val known = banana.copy(millilitersPerBase = 120.0)
        val ml = NutritionMath.choices(known).single { it.unit == FoodUnit.MILLILITER }
        assertEquals(1.0, NutritionMath.factor(known, ml, 120.0), 0.000001)
    }
    @Test fun `non100gram custom serving keeps correct reference`() {
        val serving = banana.copy(baseAmount = 30.0)
        assertEquals(0.5, NutritionMath.factor(serving, NutritionMath.choices(serving).single(), 15.0), 0.000001)
        val counted = banana.copy(baseAmount = 2.0, baseUnit = FoodUnit.SERVING, gramsPerBase = 500.0)
        val choices = NutritionMath.choices(counted)
        assertEquals(0.5, NutritionMath.factor(counted, choices.first(), 1.0), 0.000001)
        assertEquals(0.5, NutritionMath.factor(counted, choices.single { it.unit == FoodUnit.GRAM }, 250.0), 0.000001)
    }
    @Test fun `invalid quantities and overflow never produce plausible nutrient values`() {
        val choice = NutritionMath.choices(banana).first()
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { NutritionMath.factor(banana, choice, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { NutritionMath.scaled(banana, Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { NutritionMath.scaled(banana.copy(nutrients = listOf(
            NutrientValue("energy", "Energy", "kcal", Double.MAX_VALUE))), 2.0) }
    }
}

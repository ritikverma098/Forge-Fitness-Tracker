package com.forge.workout.nutrition.model

import com.forge.workout.nutrition.data.NutritionValidation
import java.util.Locale

class RecipeUnitMismatchException(val code: String) : IllegalArgumentException("Recipe ingredients use different units for $code.")

/** Missing measurements propagate as unknown, including micronutrients reported by only some foods. */
object RecipeMath {
    private val core = listOf(
        NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", null),
        NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", null),
        NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", null),
        NutrientValue(NutrientCodes.FAT, "Fat", "g", null),
    )

    fun totals(ingredients: List<RecipeIngredientEntity>): List<NutrientValue> {
        require(ingredients.isNotEmpty() && ingredients.size <= 500) { "Add between 1 and 500 recipe ingredients." }
        val snapshots = ingredients.map { ingredient ->
            NutritionValidation.amount(ingredient.snapshot, ingredient.quantity, ingredient.factor,
                ingredient.portionId, ingredient.portionLabel)
            ingredient.snapshot.nutrients.associateBy { it.code }
        }
        val codes = linkedSetOf<String>().apply {
            addAll(core.map { it.code })
            snapshots.forEach { addAll(it.keys) }
        }
        require(codes.size <= 300) { "Recipe has too many nutrient types." }
        return codes.map { code ->
            val measurements = snapshots.map { it[code] }
            val metadata = measurements.firstOrNull { it != null } ?: core.single { it.code == code }
            val unit = normalizeUnit(requireNotNull(metadata).unit)
            if (measurements.filterNotNull().any { normalizeUnit(it.unit) != unit }) throw RecipeUnitMismatchException(code)
            val value = if (measurements.any { it?.value == null }) null else {
                measurements.indices.fold(0.0) { total, index ->
                    (total + requireNotNull(measurements[index]?.value) * ingredients[index].factor).also {
                        require(it.isFinite() && it >= 0 && it <= MAX_TOTAL) { "Recipe nutrient total is too large." }
                    }
                }
            }
            metadata.copy(unit = unit, value = value)
        }
    }

    fun perServing(totals: List<NutrientValue>, servings: Double): List<NutrientValue> {
        NutritionValidation.number(servings, "recipe servings", positive = true)
        return totals.map { nutrient ->
            nutrient.copy(value = nutrient.value?.let { total ->
                (total / servings).also { NutritionValidation.number(it, "recipe serving nutrient") }
            })
        }
    }

    fun normalizeUnit(unit: String): String = when (val normalized = unit.trim().lowercase(Locale.ROOT)) {
        "ug", "µg", "μg" -> "ug"
        else -> normalized
    }

    private const val MAX_TOTAL = 1_000_000_000_000.0
}

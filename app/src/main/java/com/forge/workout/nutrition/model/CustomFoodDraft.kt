package com.forge.workout.nutrition.model

import com.forge.workout.nutrition.data.NutritionValidation
import java.util.UUID

data class CustomNutrientDraft(val code: String, val name: String, val unit: String, val value: String = "")

data class CustomFoodDraft(
    val id: String,
    val name: String = "",
    val brand: String = "",
    val baseAmount: String = "100",
    val baseUnit: FoodUnit = FoodUnit.GRAM,
    val notes: String = "",
    val nutrients: List<CustomNutrientDraft> = coreNutrients(),
    val gramsPerBase: String = "",
    val millilitersPerBase: String = "",
    val removedNutrientCodes: List<String> = emptyList(),
) {
    companion object {
        fun new() = CustomFoodDraft("custom:${UUID.randomUUID()}")
        fun coreNutrients() = listOf(
            CustomNutrientDraft(NutrientCodes.ENERGY, "Energy", "kcal"),
            CustomNutrientDraft(NutrientCodes.PROTEIN, "Protein", "g"),
            CustomNutrientDraft(NutrientCodes.CARBS, "Carbohydrates", "g"),
            CustomNutrientDraft(NutrientCodes.FAT, "Fat", "g"),
        )
    }
}

data class CustomFoodValidation(val food: FoodDetail? = null, val invalidFields: Set<String> = emptySet())

/** Blank nutrients stay unknown; fields outside the editor retain their original source metadata. */
object CustomFoodFactory {
    fun fromFood(detail: FoodDetail): CustomFoodDraft {
        require(detail.food.sourceType == FoodSourceType.CUSTOM) { "Only custom foods can be edited." }
        val food = detail.food
        val source = detail.nutrients.associateBy { it.code }
        val core = CustomFoodDraft.coreNutrients().map { draft ->
            source[draft.code]?.let { CustomNutrientDraft(it.code, it.name, it.unit, text(it.value)) } ?: draft
        }
        val optional = detail.nutrients.filter { row -> core.none { it.code == row.code } }
            .map { CustomNutrientDraft(it.code, it.name, it.unit, text(it.value)) }
        return CustomFoodDraft(food.id, food.name, food.brand, text(food.baseAmount), food.baseUnit, food.notes,
            core + optional, text(food.gramsPerBase), text(food.millilitersPerBase))
    }

    fun normalizeUpdate(previous: CustomFoodDraft, next: CustomFoodDraft): CustomFoodDraft {
        fun amount(value: String) = value.trim().replace(',', '.').toDoubleOrNull()
        val basisChanged = amount(previous.baseAmount) != amount(next.baseAmount) || previous.baseUnit != next.baseUnit
        val grams = when {
            next.baseUnit == FoodUnit.GRAM -> next.baseAmount
            basisChanged && next.gramsPerBase == previous.gramsPerBase -> ""
            else -> next.gramsPerBase
        }
        val ml = when {
            next.baseUnit == FoodUnit.MILLILITER -> next.baseAmount
            basisChanged && next.millilitersPerBase == previous.millilitersPerBase -> ""
            else -> next.millilitersPerBase
        }
        return next.copy(nutrients = next.nutrients.toList(), removedNutrientCodes = next.removedNutrientCodes.toList(),
            gramsPerBase = grams, millilitersPerBase = ml)
    }

    fun validate(draft: CustomFoodDraft, existing: FoodDetail? = null): CustomFoodValidation {
        val invalid = linkedSetOf<String>()
        fun check(field: String, operation: () -> Unit) {
            if (runCatching(operation).isFailure) invalid += field
        }
        check("food") {
            NutritionValidation.id(draft.id)
            require(existing == null || (existing.food.id == draft.id && existing.food.sourceType == FoodSourceType.CUSTOM))
            require(existing != null || draft.id.startsWith("custom:"))
        }
        check("name") { NutritionValidation.text(draft.name.trim(), 250, "Food name") }
        check("brand") { NutritionValidation.text(draft.brand.trim(), 250, "Brand", true) }
        check("notes") { NutritionValidation.text(draft.notes, 5000, "Food notes", true) }
        fun number(value: String, field: String, positive: Boolean): Double? {
            val parsed = value.trim().replace(',', '.').toDoubleOrNull()
            if (parsed == null || runCatching { NutritionValidation.number(parsed, field, positive) }.isFailure) {
                invalid += field
                return null
            }
            return parsed
        }
        fun optional(value: String, field: String): Double? =
            if (value.isBlank()) null else number(value, field, true)
        val basis = number(draft.baseAmount, "baseAmount", true)
        val grams = if (draft.baseUnit == FoodUnit.GRAM) basis else optional(draft.gramsPerBase, "gramsPerBase")
        val ml = if (draft.baseUnit == FoodUnit.MILLILITER) basis else optional(draft.millilitersPerBase, "millilitersPerBase")
        val suppliedCodes = draft.nutrients.map { it.code }
        if (suppliedCodes.size > 300 || suppliedCodes.toSet().size != suppliedCodes.size) invalid += "nutrients"
        val removed = draft.removedNutrientCodes.toSet()
        check("nutrients") {
            require(removed.size <= 300 && removed.none { code -> CustomFoodDraft.coreNutrients().any { it.code == code } })
            removed.forEach(NutritionValidation::id)
            require(removed.none { it in suppliedCodes })
        }
        // Older custom foods may contain arbitrary micronutrients that the core form does not expose.
        val retained = existing?.nutrients.orEmpty().filter { it.code !in suppliedCodes && it.code !in removed }
            .map { CustomNutrientDraft(it.code, it.name, it.unit, text(it.value)) }
        val complete = draft.nutrients + retained + CustomFoodDraft.coreNutrients().filter {
            it.code !in suppliedCodes && retained.none { row -> row.code == it.code }
        }
        if (complete.size > 300) invalid += "nutrients"
        val nutrients = complete.map { row ->
            val code = row.code
            val value = if (row.value.isBlank()) null else number(row.value, "nutrient:$code", false)
            val nutrient = FoodNutrientEntity(draft.id, code, row.name, row.unit, value)
            check("nutrients") {
                NutritionValidation.snapshot(FoodSnapshot(name = "Validation", nutrients =
                    listOf(NutrientValue(nutrient.code, nutrient.name, nutrient.unit, nutrient.value))))
            }
            nutrient
        }
        if (invalid.isNotEmpty() || basis == null) return CustomFoodValidation(invalidFields = invalid.toSet())
        val food = (existing?.food ?: FoodEntity(id = draft.id, name = draft.name.trim())).copy(
            name = draft.name.trim(), brand = draft.brand.trim(), sourceType = FoodSourceType.CUSTOM, packId = null,
            baseAmount = basis, baseUnit = draft.baseUnit, gramsPerBase = grams, millilitersPerBase = ml, notes = draft.notes.trim())
        // Portion quantities remain valid in the same native unit. Unit changes retain only explicit equivalents.
        val portions = existing?.portions.orEmpty().mapNotNull { portion ->
            if (existing?.food?.baseUnit == draft.baseUnit) portion
            else when (draft.baseUnit) {
                FoodUnit.GRAM -> portion.grams?.let { portion.copy(baseQuantity = it) }
                FoodUnit.MILLILITER -> portion.milliliters?.let { portion.copy(baseQuantity = it) }
                FoodUnit.SERVING -> null
            }
        }
        val detail = FoodDetail(food, nutrients, portions)
        check("food") { NutritionValidation.food(detail) }
        return if (invalid.isEmpty()) CustomFoodValidation(detail) else CustomFoodValidation(invalidFields = invalid.toSet())
    }

    private fun text(value: Double?): String = value?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty()
}

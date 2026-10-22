package com.forge.workout.nutrition.model

import com.forge.workout.nutrition.data.NutritionValidation
import java.util.UUID

data class RecipeDraft(
    val id: String = "recipe:${UUID.randomUUID()}",
    val name: String = "",
    val servings: String = "1",
    val cookedWeightGrams: String = "",
    val ingredients: List<RecipeIngredientDraft> = emptyList(),
)

data class RecipeIngredientDraft(
    val key: String = UUID.randomUUID().toString(),
    val rowId: Long = 0,
    val snapshot: FoodSnapshot,
    val choice: FoodServingChoice,
    val quantity: String,
)

data class RecipeValidation(
    val recipe: RecipeEntity? = null,
    val food: FoodDetail? = null,
    val ingredients: List<RecipeIngredientEntity> = emptyList(),
    val invalidFields: Set<String> = emptySet(),
    val perServing: List<NutrientValue> = emptyList(),
)

object RecipeFactory {
    fun fromRecipe(detail: RecipeDetail, food: FoodDetail): RecipeDraft {
        require(detail.recipe.id == food.food.id && food.food.sourceType == FoodSourceType.RECIPE) { "Recipe food identity must match." }
        return RecipeDraft(detail.recipe.id, detail.recipe.name, text(detail.recipe.servings),
            text(detail.recipe.cookedWeightGrams), detail.sortedIngredients.map { row ->
                val snapshot = row.snapshot.copy(nutrients = row.snapshot.nutrients.toList())
                // Stored quantity and factor preserve a source portion even after its catalog record changes.
                val choice = FoodServingChoice(row.portionId?.let { "portion:$it" } ?: row.unit.name,
                    row.portionLabel, row.unit, row.factor * snapshot.baseAmount / row.quantity, row.quantity, row.portionId)
                RecipeIngredientDraft(if (row.id > 0) "row:${row.id}" else UUID.randomUUID().toString(),
                    row.id, snapshot, choice, text(row.quantity))
            })
    }

    fun validate(draft: RecipeDraft, existingFood: FoodDetail? = null): RecipeValidation {
        val invalid = linkedSetOf<String>()
        fun check(field: String, operation: () -> Unit) {
            if (runCatching(operation).isFailure) invalid += field
        }
        check("food") {
            NutritionValidation.id(draft.id)
            require(existingFood == null || (existingFood.food.id == draft.id && existingFood.food.sourceType == FoodSourceType.RECIPE)) {
                "Recipe food identity must match."
            }
        }
        check("name") { NutritionValidation.text(draft.name.trim(), 250, "Recipe name") }
        fun number(raw: String, field: String): Double? {
            val parsed = raw.trim().replace(',', '.').toDoubleOrNull()
            if (parsed == null || runCatching { NutritionValidation.number(parsed, field, positive = true) }.isFailure) {
                invalid += field
                return null
            }
            return parsed
        }
        val servings = number(draft.servings, "servings")
        val cooked = if (draft.cookedWeightGrams.isBlank()) null else number(draft.cookedWeightGrams, "cookedWeightGrams")
        check("ingredients") {
            require(draft.ingredients.isNotEmpty() && draft.ingredients.size <= 500)
            require(draft.ingredients.map { it.key }.distinct().size == draft.ingredients.size)
            val persistedIds = draft.ingredients.filter { it.rowId != 0L }.map { it.rowId }
            require(persistedIds.distinct().size == persistedIds.size)
        }
        val ingredients = draft.ingredients.mapIndexedNotNull { index, row ->
            val field = "ingredient:${row.key}"
            val quantity = number(row.quantity, field)
            if (quantity == null) return@mapIndexedNotNull null
            try {
                NutritionValidation.id(row.key)
                require(row.rowId >= 0 && row.snapshot.foodId != draft.id) { "A recipe cannot include itself." }
                val snapshot = row.snapshot.copy(nutrients = row.snapshot.nutrients.toList())
                NutritionValidation.snapshot(snapshot)
                NutritionValidation.text(row.choice.id, 300, "Serving choice ID")
                require((row.choice.portionId == null) == (row.choice.label == null)) { "Selected portion needs both ID and label." }
                if (row.choice.portionId == null) {
                    val conversion = NutritionMath.choices(snapshot).firstOrNull { it.unit == row.choice.unit }
                    require(conversion != null) { "This ingredient has no conversion for the selected unit." }
                    val tolerance = maxOf(1.0, conversion.baseQuantity) * 1e-9
                    require(kotlin.math.abs(conversion.baseQuantity - row.choice.baseQuantity) <= tolerance) {
                        "Ingredient conversion must match its saved snapshot."
                    }
                }
                val factor = NutritionMath.factor(snapshot, row.choice, quantity)
                NutritionValidation.amount(snapshot, quantity, factor, row.choice.portionId, row.choice.label)
                RecipeIngredientEntity(row.rowId, draft.id, index, snapshot.foodId, snapshot, quantity, row.choice.unit,
                    row.choice.portionId, row.choice.label, factor)
            } catch (_: IllegalArgumentException) {
                invalid += field
                null
            }
        }
        if (servings == null || invalid.any { it == "ingredients" || it.startsWith("ingredient:") }) {
            return RecipeValidation(ingredients = ingredients, invalidFields = invalid.toSet())
        }
        val totals = try { RecipeMath.totals(ingredients) }
        catch (failure: RecipeUnitMismatchException) {
            invalid += "ingredients"
            invalid += "units:${failure.code}"
            draft.ingredients.filter { row -> row.snapshot.nutrients.any { it.code == failure.code } }.forEach {
                invalid += "ingredient:${it.key}:units"
            }
            return RecipeValidation(ingredients = ingredients, invalidFields = invalid.toSet())
        } catch (_: IllegalArgumentException) {
            invalid += "ingredients"
            return RecipeValidation(ingredients = ingredients, invalidFields = invalid.toSet())
        }
        val perServing = try { RecipeMath.perServing(totals, servings) } catch (_: IllegalArgumentException) {
            invalid += "servings"
            return RecipeValidation(ingredients = ingredients, invalidFields = invalid.toSet())
        }
        val grams = cooked?.div(servings)
        if (grams != null) check("cookedWeightGrams") { NutritionValidation.number(grams, "cooked serving weight", positive = true) }
        if (invalid.isNotEmpty()) return RecipeValidation(ingredients = ingredients, invalidFields = invalid.toSet(), perServing = perServing)
        val recipe = RecipeEntity(draft.id, draft.name.trim(), servings, cooked)
        val food = FoodDetail((existingFood?.food ?: FoodEntity(id = draft.id, name = recipe.name)).copy(
            name = recipe.name, sourceType = FoodSourceType.RECIPE, packId = null,
            baseAmount = 1.0, baseUnit = FoodUnit.SERVING, gramsPerBase = grams, millilitersPerBase = null),
            perServing.map { FoodNutrientEntity(draft.id, it.code, it.name, it.unit, it.value) })
        check("food") { NutritionValidation.recipe(recipe, food, ingredients) }
        return if (invalid.isEmpty()) RecipeValidation(recipe, food, ingredients, perServing = perServing)
        else RecipeValidation(ingredients = ingredients, invalidFields = invalid.toSet(), perServing = perServing)
    }

    private fun text(value: Double?): String = value?.toBigDecimal()?.stripTrailingZeros()?.toPlainString().orEmpty()
}

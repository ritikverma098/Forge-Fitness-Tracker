package com.forge.workout.nutrition.model

data class FoodServingChoice(
    val id: String,
    val label: String? = null,
    val unit: FoodUnit,
    val baseQuantity: Double,
    val suggestedQuantity: Double,
    val portionId: String? = null,
)

/** A factor always scales the food's stated reference basis, never an assumed 100g serving. */
object NutritionMath {
    fun choices(detail: FoodDetail): List<FoodServingChoice> = choices(detail.snapshot(), detail.portions)

    fun choices(snapshot: FoodSnapshot, portions: List<FoodPortionEntity> = emptyList()): List<FoodServingChoice> {
        require(snapshot.baseAmount.isFinite() && snapshot.baseAmount > 0)
        val native = FoodServingChoice(snapshot.baseUnit.name, unit = snapshot.baseUnit, baseQuantity = 1.0,
            suggestedQuantity = if(snapshot.baseUnit == FoodUnit.SERVING) 1.0 else snapshot.baseAmount)
        return buildList {
            add(native)
            if(snapshot.baseUnit != FoodUnit.GRAM) snapshot.gramsPerBase?.let { grams ->
                require(grams.isFinite() && grams > 0)
                add(FoodServingChoice(FoodUnit.GRAM.name, unit = FoodUnit.GRAM,
                    baseQuantity = snapshot.baseAmount / grams, suggestedQuantity = 100.0))
            }
            if(snapshot.baseUnit != FoodUnit.MILLILITER) snapshot.millilitersPerBase?.let { ml ->
                require(ml.isFinite() && ml > 0)
                add(FoodServingChoice(FoodUnit.MILLILITER.name, unit = FoodUnit.MILLILITER,
                    baseQuantity = snapshot.baseAmount / ml, suggestedQuantity = 100.0))
            }
            portions.forEach { portion ->
                require(portion.baseQuantity.isFinite() && portion.baseQuantity > 0)
                add(FoodServingChoice("portion:${portion.id}", portion.label, FoodUnit.SERVING,
                    portion.baseQuantity, 1.0, portion.id))
            }
        }
    }

    fun factor(snapshot: FoodSnapshot, choice: FoodServingChoice, quantity: Double): Double {
        require(quantity.isFinite() && quantity > 0 && quantity <= 1_000_000.0) { "Enter a positive food quantity." }
        require(snapshot.baseAmount.isFinite() && snapshot.baseAmount > 0 && choice.baseQuantity.isFinite() && choice.baseQuantity > 0)
        return (quantity * choice.baseQuantity / snapshot.baseAmount).also {
            require(it.isFinite() && it > 0) { "Food quantity cannot be converted." }
        }
    }

    fun scaled(snapshot: FoodSnapshot, factor: Double): List<NutrientValue> {
        require(factor.isFinite() && factor >= 0)
        return snapshot.nutrients.map { nutrient ->
            nutrient.copy(value = nutrient.value?.let { value ->
                (value * factor).also { require(it.isFinite() && it >= 0 && it <= 1_000_000_000_000.0) { "Food quantity is too large." } }
            })
        }
    }
}

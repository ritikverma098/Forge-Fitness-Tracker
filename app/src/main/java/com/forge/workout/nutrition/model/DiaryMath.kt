package com.forge.workout.nutrition.model

data class NutrientTotal(val knownValue: Double = 0.0, val missingCount: Int = 0) {
    init {
        require(knownValue.isFinite() && knownValue >= 0 && missingCount >= 0)
    }
    val complete: Boolean get() = missingCount == 0
}

data class DailyNutritionTotals(
    val kcal: NutrientTotal = NutrientTotal(),
    val protein: NutrientTotal = NutrientTotal(),
    val carbs: NutrientTotal = NutrientTotal(),
    val fat: NutrientTotal = NutrientTotal(),
)

object DiaryMath {
    fun totals(entries: List<DiaryEntity>): DailyNutritionTotals {
        val sums = DoubleArray(4)
        val missing = IntArray(4)
        entries.forEach { entry ->
            listOf(entry.kcal, entry.protein, entry.carbs, entry.fat).forEachIndexed { index, value ->
                if (value == null) missing[index]++ else {
                    require(value.isFinite() && value >= 0) { "Invalid diary nutrition value." }
                    sums[index] += value
                    require(sums[index].isFinite()) { "Daily nutrition total is too large." }
                }
            }
        }
        return DailyNutritionTotals(NutrientTotal(sums[0], missing[0]), NutrientTotal(sums[1], missing[1]),
            NutrientTotal(sums[2], missing[2]), NutrientTotal(sums[3], missing[3]))
    }

    fun remaining(goal: Double?, total: NutrientTotal, extraKcal: Double = 0.0): Double? {
        if (goal == null || !total.complete) return null
        require(goal.isFinite() && goal >= 0 && extraKcal.isFinite() && extraKcal >= 0)
        return (goal + extraKcal - total.knownValue).also { require(it.isFinite()) }
    }

    /** Historical servings are rebuilt from the recorded factor, without consulting a current catalog. */
    fun snapshotDetails(entry: DiaryEntity): FoodDetail {
        val snapshot = entry.snapshot.copy(nutrients = entry.snapshot.nutrients.toList())
        require(entry.quantity.isFinite() && entry.quantity > 0 && entry.factor.isFinite() && entry.factor > 0)
        require(snapshot.baseAmount.isFinite() && snapshot.baseAmount > 0)
        val id = snapshot.foodId ?: entry.foodId ?: "diary:${entry.id}"
        val food = FoodEntity(id = id, name = snapshot.name, brand = snapshot.brand, source = snapshot.source,
            sourceId = snapshot.sourceId, sourceUrl = snapshot.sourceUrl, sourceType = snapshot.sourceType,
            baseAmount = snapshot.baseAmount, baseUnit = snapshot.baseUnit, gramsPerBase = snapshot.gramsPerBase,
            millilitersPerBase = snapshot.millilitersPerBase)
        val portions = entry.portionId?.let { portionId ->
            val label = requireNotNull(entry.portionLabel) { "Selected portion needs a label." }
            val scale = entry.factor / entry.quantity
            val baseQuantity = snapshot.baseAmount * scale
            require(baseQuantity.isFinite() && baseQuantity > 0)
            listOf(FoodPortionEntity(id, portionId, label, baseQuantity,
                grams = (snapshot.gramsPerBase ?: snapshot.baseAmount.takeIf { snapshot.baseUnit == FoodUnit.GRAM })?.times(scale),
                milliliters = (snapshot.millilitersPerBase ?: snapshot.baseAmount.takeIf { snapshot.baseUnit == FoodUnit.MILLILITER })?.times(scale),
                source = snapshot.source.takeIf { it.isNotBlank() }))
        } ?: emptyList()
        return FoodDetail(food, snapshot.nutrients.map { FoodNutrientEntity(id, it.code, it.name, it.unit, it.value) }, portions)
    }
}

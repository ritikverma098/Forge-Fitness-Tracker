package com.forge.workout.nutrition.data

import com.forge.workout.nutrition.model.*
import java.time.LocalDate

internal object NutritionValidation {
    fun id(value: String) = text(value, 200, "ID")
    fun day(value: String) {
        val date = try { LocalDate.parse(value) } catch (_: Exception) { throw IllegalArgumentException("Use a valid calendar date.") }
        require(date.year in 1..9999 && date.toString() == value) { "Use an ISO calendar date." }
    }
    fun page(limit: Int, offset: Int) {
        require(limit in 1..200 && offset >= 0) { "Invalid food page." }
    }
    fun text(value: String, max: Int, label: String, blankAllowed: Boolean = false) {
        require(value.length <= max && (blankAllowed || value.isNotBlank())) { "$label is empty or too long." }
    }
    fun number(value: Double, label: String, positive: Boolean = false, max: Double = 1_000_000_000.0) {
        require(value.isFinite() && value <= max && (if (positive) value > 0 else value >= 0)) { "Enter a valid $label." }
    }
    private fun optionalNumber(value: Double?, label: String, positive: Boolean = false, max: Double = 1_000_000_000.0) {
        value?.let { number(it, label, positive, max) }
    }
    private fun nutrient(value: NutrientValue) {
        text(value.code, 100, "Nutrient code")
        text(value.name, 200, "Nutrient name")
        text(value.unit, 32, "Nutrient unit")
        optionalNumber(value.value, "nutrient value")
        if (value.code == NutrientCodes.ENERGY) require(value.unit.equals("kcal", true)) { "Energy must use kcal." }
        if (value.code in setOf(NutrientCodes.PROTEIN, NutrientCodes.CARBS, NutrientCodes.FAT)) {
            require(value.unit.equals("g", true)) { "Macronutrients must use grams." }
        }
    }
    fun snapshot(value: FoodSnapshot) {
        value.foodId?.let(::id)
        text(value.name, 1000, "Food name")
        text(value.brand, 250, "Brand", true)
        text(value.source, 250, "Source", true)
        value.sourceId?.let { text(it, 200, "Source ID") }
        value.sourceUrl?.let { text(it, 2048, "Source URL") }
        number(value.baseAmount, "base amount", true)
        optionalNumber(value.gramsPerBase, "gram equivalent", true)
        optionalNumber(value.millilitersPerBase, "milliliter equivalent", true)
        if (value.baseUnit == FoodUnit.GRAM && value.gramsPerBase != null) {
            require(value.gramsPerBase == value.baseAmount) { "Gram equivalent must match the gram base amount." }
        }
        if (value.baseUnit == FoodUnit.MILLILITER && value.millilitersPerBase != null) {
            require(value.millilitersPerBase == value.baseAmount) { "Milliliter equivalent must match the milliliter base amount." }
        }
        require(value.nutrients.size <= 300 && value.nutrients.map { it.code }.distinct().size == value.nutrients.size) { "Duplicate or excessive nutrients." }
        value.nutrients.forEach(::nutrient)
    }
    fun food(value: FoodDetail) {
        id(value.food.id)
        require(value.food.rowId >= 0) { "Invalid food row ID." }
        snapshot(value.snapshot())
        text(value.food.aliases, 5000, "Food aliases", true)
        text(value.food.tags, 1000, "Food tags", true)
        text(value.food.notes, 5000, "Food notes", true)
        value.food.region?.let { text(it, 100, "Region") }
        value.food.packId?.let(::id)
        require(value.food.sourceType == FoodSourceType.REFERENCE || value.food.packId == null) { "User foods cannot belong to a catalog pack." }
        require(value.nutrients.all { it.foodId == value.food.id }) { "Nutrients must belong to this food." }
        require(value.portions.size <= 100 && value.portions.map { it.id }.distinct().size == value.portions.size) { "Duplicate or excessive portions." }
        value.portions.forEach {
            require(it.foodId == value.food.id) { "Portion must belong to this food." }
            id(it.id)
            text(it.label, 250, "Portion label")
            number(it.baseQuantity, "portion amount", true)
            optionalNumber(it.grams, "portion grams", true)
            optionalNumber(it.milliliters, "portion milliliters", true)
            it.source?.let { source -> text(source, 250, "Portion source") }
        }
    }
    fun pack(value: CatalogPackEntity) {
        id(value.id)
        text(value.name, 250, "Catalog name")
        text(value.version, 100, "Catalog version")
        text(value.sourceUrl, 2048, "Catalog source")
        text(value.license, 250, "Catalog license")
        text(value.citation, 2000, "Catalog citation")
        require(value.installedAt >= 0 && value.foodCount >= 0 && value.payloadBytes >= 0) { "Invalid catalog metadata." }
        require(value.sha256.isEmpty() || value.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid catalog checksum." }
    }
    fun amount(snapshot: FoodSnapshot, quantity: Double, factor: Double, portionId: String?, portionLabel: String?) {
        NutritionValidation.snapshot(snapshot)
        number(quantity, "quantity", true)
        number(factor, "nutrition factor", true)
        portionId?.let(::id)
        portionLabel?.let { text(it, 250, "Selected portion") }
        require((portionId == null) == (portionLabel == null)) { "Selected portion needs both ID and label." }
        snapshot.nutrients.forEach { optionalNumber(it.value?.times(factor), "scaled nutrient value", max = 1_000_000_000_000.0) }
    }
    fun diary(value: DiaryEntity) {
        require(value.id >= 0 && value.loggedAt >= 0) { "Invalid diary ID or date." }
        day(value.day)
        value.foodId?.let(::id)
        require(value.foodId == null || value.snapshot.foodId == null || value.foodId == value.snapshot.foodId) { "Diary food identity must match its snapshot." }
        amount(value.snapshot, value.quantity, value.factor, value.portionId, value.portionLabel)
    }
    fun recipe(recipe: RecipeEntity, food: FoodDetail, ingredients: List<RecipeIngredientEntity>) {
        id(recipe.id)
        text(recipe.name, 250, "Recipe name")
        number(recipe.servings, "recipe servings", true)
        optionalNumber(recipe.cookedWeightGrams, "cooked weight", true)
        require(food.food.id == recipe.id && food.food.sourceType == FoodSourceType.RECIPE) { "Recipe food identity must match." }
        require(food.food.name == recipe.name) { "Recipe and food names must match." }
        NutritionValidation.food(food)
        require(ingredients.isNotEmpty() && ingredients.size <= 500) { "Add between 1 and 500 recipe ingredients." }
        require(ingredients.map { it.orderIndex }.sorted() == ingredients.indices.toList()) { "Recipe ingredient order must be consecutive." }
        require(ingredients.filter { it.id != 0L }.map { it.id }.distinct().size == ingredients.count { it.id != 0L }) { "Duplicate ingredient ID." }
        ingredients.forEach {
            require(it.id >= 0 && it.recipeId == recipe.id && it.foodId != recipe.id && it.snapshot.foodId != recipe.id) { "Invalid recipe ingredient." }
            it.foodId?.let(::id)
            require(it.foodId == null || it.snapshot.foodId == null || it.foodId == it.snapshot.foodId) { "Ingredient food identity must match its snapshot." }
            amount(it.snapshot, it.quantity, it.factor, it.portionId, it.portionLabel)
        }
    }
    fun goal(value: NutritionGoalEntity) {
        day(value.effectiveDay)
        listOf(value.kcal, value.protein, value.carbs, value.fat).forEach { optionalNumber(it, "goal") }
    }
    fun settings(value: NutritionSettingsEntity) {
        require(value.id == 1) { "Invalid settings ID." }
        text(value.defaultActivity, 100, "Default activity")
        require(value.defaultActiveFraction.isFinite() && value.defaultActiveFraction in 0.0..1.0) { "Invalid active fraction." }
    }
    fun favorite(value: FavoriteEntity) {
        id(value.foodId)
        value.name?.let { text(it, 250, "Favorite name") }
        require(value.savedAt >= 0) { "Invalid favorite date." }
    }
    fun bodyWeight(value: BodyWeightEntity) {
        day(value.day)
        require(value.id >= 0 && value.measuredAt >= 0) { "Invalid body weight ID or date." }
        number(value.kg, "body weight", true, 1000.0)
    }
    fun energy(value: WorkoutEnergyEntity) {
        day(value.day)
        require(value.workoutId > 0 && (value.bodyWeightId == null || value.bodyWeightId > 0)) { "Invalid workout energy ID." }
        require(value.startTime >= 0 && value.endTime >= value.startTime && value.durationSeconds > 0 &&
            value.durationSeconds == (value.endTime - value.startTime) / 1000) { "Invalid workout energy duration." }
        number(value.bodyWeightKg, "body weight", true, 1000.0)
        number(value.met, "MET", true, 100.0)
        text(value.activity, 100, "Activity")
        require(value.activeFraction.isFinite() && value.activeFraction in 0.0..1.0) { "Invalid active fraction." }
        number(value.calculatedKcal, "workout calories")
        optionalNumber(value.manualKcal, "manual workout calories")
    }
}

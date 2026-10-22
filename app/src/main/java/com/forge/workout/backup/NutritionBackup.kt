package com.forge.workout.backup

import com.forge.workout.model.Workout
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.*
import kotlin.math.abs

data class NutritionBackup(
    val foods: List<FoodDetail> = emptyList(),
    val recipes: List<RecipeDetail> = emptyList(),
    val diary: List<DiaryEntity> = emptyList(),
    val goals: List<NutritionGoalEntity> = emptyList(),
    val bodyWeights: List<BodyWeightEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
    val settings: NutritionSettingsEntity = NutritionSettingsEntity(),
    val workoutEnergy: List<WorkoutEnergyEntity> = emptyList(),
) {
    fun freeze() = copy(foods = foods.map { it.copy(nutrients = it.nutrients.toList(), portions = it.portions.toList()) },
        recipes = recipes.map { it.copy(ingredients = it.ingredients.map { row -> row.copy(snapshot = row.snapshot.frozen()) }) },
        diary = diary.map { it.copy(snapshot = it.snapshot.frozen()) }, goals = goals.toList(), bodyWeights = bodyWeights.toList(),
        favorites = favorites.toList(), workoutEnergy = workoutEnergy.toList())
}

private fun FoodSnapshot.frozen() = copy(nutrients = nutrients.toList())

object NutritionBackupValidation {
    const val MAX_ROWS = 100_000

    fun validate(data: NutritionBackup, workouts: List<Workout>) = BackupValidation.checked {
        val sizes = listOf(data.foods.size, data.recipes.size, data.diary.size, data.goals.size,
            data.bodyWeights.size, data.favorites.size, data.workoutEnergy.size)
        require(sizes.all { it <= MAX_ROWS }) { "Nutrition backup has too many rows." }
        require(data.foods.sumOf { it.nutrients.size.toLong() + it.portions.size } <= 1_000_000 &&
            data.recipes.sumOf { it.ingredients.size.toLong() } <= MAX_ROWS) { "Nutrition backup has too many child rows." }
        uniqueStrings(data.foods.map { it.food.id })
        uniqueStrings(data.recipes.map { it.recipe.id })
        uniqueLongs(data.diary.map { it.id })
        uniqueLongs(data.bodyWeights.map { it.id })
        uniqueLongs(data.recipes.flatMap { it.ingredients }.map { it.id })
        uniqueLongs(data.workoutEnergy.map { it.workoutId })
        require(data.goals.map { it.effectiveDay }.distinct().size == data.goals.size) { "Duplicate goal date." }
        require(data.bodyWeights.map { it.day }.distinct().size == data.bodyWeights.size) { "Duplicate body weight date." }
        uniqueStrings(data.favorites.map { it.foodId })
        val foods = data.foods.associateBy { it.food.id }
        data.foods.forEach { detail ->
            NutritionValidation.food(detail)
            require(detail.food.sourceType != FoodSourceType.REFERENCE && detail.food.packId == null) { "Reference catalogs cannot be restored as personal foods." }
        }
        val recipeIds = data.recipes.map { it.recipe.id }.toSet()
        require(foods.values.filter { it.food.sourceType == FoodSourceType.RECIPE }.map { it.food.id }.toSet() == recipeIds) { "Missing recipe or recipe food." }
        data.recipes.forEach { detail ->
            val food = requireNotNull(foods[detail.recipe.id]) { "Recipe food was not found." }
            NutritionValidation.recipe(detail.recipe, food, detail.ingredients)
        }
        data.diary.forEach { row ->
            NutritionValidation.diary(row)
            fun cache(code: String, value: Double?) {
                val expected = row.snapshot.nutrients.firstOrNull { it.code == code }?.value?.times(row.factor)
                if (expected == null) require(value == null) { "Unknown diary nutrition cannot be replaced with zero." }
                else require(value != null && value.isFinite() && value in 0.0..1_000_000_000_000.0 &&
                    abs(value - expected) <= maxOf(1.0, abs(expected)) * 1e-9) { "Diary totals must match their recorded snapshot." }
            }
            cache(NutrientCodes.ENERGY, row.kcal); cache(NutrientCodes.PROTEIN, row.protein)
            cache(NutrientCodes.CARBS, row.carbs); cache(NutrientCodes.FAT, row.fat)
        }
        data.goals.forEach(NutritionValidation::goal)
        data.bodyWeights.forEach(NutritionValidation::bodyWeight)
        data.favorites.forEach(NutritionValidation::favorite)
        NutritionValidation.settings(data.settings)
        val parents = workouts.associateBy { it.id }
        val bodyIds = data.bodyWeights.map { it.id }.toSet()
        data.workoutEnergy.forEach { energy ->
            NutritionValidation.energy(energy)
            val workout = requireNotNull(parents[energy.workoutId]) { "Workout estimate has no parent workout." }
            require(workout.endTime != null && energy.startTime == workout.startTime && energy.endTime == workout.endTime) { "Workout estimate timing must match its completed workout." }
            require(energy.bodyWeightId == null || energy.bodyWeightId in bodyIds) { "Workout estimate has no body weight record." }
        }
    }

    private fun uniqueStrings(values: List<String>) {
        values.forEach(NutritionValidation::id)
        require(values.distinct().size == values.size) { "Duplicate nutrition ID." }
    }
    private fun uniqueLongs(values: List<Long>) {
        require(values.all { it > 0 } && values.distinct().size == values.size) { "Invalid or duplicate nutrition row ID." }
    }
}

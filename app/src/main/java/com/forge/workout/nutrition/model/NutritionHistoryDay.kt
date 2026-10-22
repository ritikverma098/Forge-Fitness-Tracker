package com.forge.workout.nutrition.model

data class NutritionHistoryDay(
    val day: String,
    val entryCount: Int,
    val totals: DailyNutritionTotals,
    val goal: NutritionGoalEntity? = null,
)

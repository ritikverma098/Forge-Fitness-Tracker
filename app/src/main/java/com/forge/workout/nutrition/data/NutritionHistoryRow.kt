package com.forge.workout.nutrition.data

import com.forge.workout.nutrition.model.DailyNutritionTotals
import com.forge.workout.nutrition.model.NutrientTotal
import com.forge.workout.nutrition.model.NutritionGoalEntity
import com.forge.workout.nutrition.model.NutritionHistoryDay

/** Flat projection excludes the diary's snapshot JSON and performs no relation reads. */
data class NutritionHistoryRow(
    val day: String,
    val entryCount: Int,
    val kcalKnown: Double,
    val kcalMissing: Int,
    val proteinKnown: Double,
    val proteinMissing: Int,
    val carbsKnown: Double,
    val carbsMissing: Int,
    val fatKnown: Double,
    val fatMissing: Int,
    val goalEffectiveDay: String?,
    val goalKcal: Double?,
    val goalProtein: Double?,
    val goalCarbs: Double?,
    val goalFat: Double?,
) {
    fun summary() = NutritionHistoryDay(day, entryCount,
        DailyNutritionTotals(NutrientTotal(kcalKnown, kcalMissing), NutrientTotal(proteinKnown, proteinMissing),
            NutrientTotal(carbsKnown, carbsMissing), NutrientTotal(fatKnown, fatMissing)),
        goalEffectiveDay?.let { NutritionGoalEntity(it, goalKcal, goalProtein, goalCarbs, goalFat) })
}

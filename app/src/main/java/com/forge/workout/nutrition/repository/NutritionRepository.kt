package com.forge.workout.nutrition.repository

import com.forge.workout.nutrition.model.*
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

interface NutritionRepository {
    fun observeFoodPage(query: String = "", filter: FoodBrowseFilter = FoodBrowseFilter.ALL,
        limit: Int = 50, offset: Int = 0): Flow<List<FoodEntity>>
    fun observeFoods(query: String = "", sourceType: FoodSourceType? = null, region: String? = null,
        favoritesOnly: Boolean = false, limit: Int = 50, offset: Int = 0): Flow<List<FoodEntity>>
    suspend fun searchFoods(query: String = "", sourceType: FoodSourceType? = null, region: String? = null,
        favoritesOnly: Boolean = false, limit: Int = 50, offset: Int = 0): List<FoodEntity>
    fun observeFood(id: String): Flow<FoodDetail?>
    suspend fun food(id: String): FoodDetail?
    suspend fun upsertFood(detail: FoodDetail): String
    suspend fun saveCustomFood(detail: FoodDetail): String
    suspend fun deleteCustomFood(id: String)
    suspend fun deleteFood(id: String)
    fun observeCatalogPacks(): Flow<List<CatalogPackEntity>>
    suspend fun upsertCatalogPack(pack: CatalogPackEntity)
    suspend fun deleteCatalogPack(id: String)

    fun observeDiary(day: String): Flow<List<DiaryEntity>>
    fun observeNutritionHistory(fromDay: String = "0001-01-01", toDay: String = "9999-12-31",
        limit: Int = 50, offset: Int = 0): Flow<List<NutritionHistoryDay>>
    fun observeDiaryEntry(id: Long): Flow<DiaryEntity?>
    suspend fun diaryEntry(id: Long): DiaryEntity?
    suspend fun saveDiary(entry: DiaryEntity): Long
    suspend fun deleteDiary(id: Long)
    suspend fun repeatDiary(ids: List<Long>, day: String, meal: MealType): List<Long>
    suspend fun moveDiary(id: Long, day: String, meal: MealType)
    fun observeRecipe(id: String): Flow<RecipeDetail?>
    suspend fun recipe(id: String): RecipeDetail?
    suspend fun saveRecipe(recipe: RecipeEntity, food: FoodDetail, ingredients: List<RecipeIngredientEntity>): String
    suspend fun deleteRecipe(id: String)

    fun observeGoal(day: String): Flow<NutritionGoalEntity?>
    suspend fun saveGoal(goal: NutritionGoalEntity)
    suspend fun deleteGoal(effectiveDay: String)
    fun observeSettings(): Flow<NutritionSettingsEntity>
    suspend fun saveSettings(settings: NutritionSettingsEntity)
    fun observeFavorites(): Flow<List<FavoriteEntity>>
    fun observeFavorite(id: String): Flow<FavoriteEntity?>
    suspend fun saveFavorite(favorite: FavoriteEntity)
    suspend fun deleteFavorite(foodId: String)

    fun observeBodyWeights(fromDay: String, toDay: String, limit: Int = 365): Flow<List<BodyWeightEntity>>
    suspend fun bodyWeight(day: String): BodyWeightEntity?
    fun observeLatestBodyWeight(day: String): Flow<BodyWeightEntity?>
    suspend fun saveBodyWeight(weight: BodyWeightEntity): Long
    suspend fun deleteBodyWeight(id: Long)
    fun observeWorkoutEnergy(day: String): Flow<List<WorkoutEnergyEntity>>
    suspend fun workoutEnergy(workoutId: Long): WorkoutEnergyEntity?
    suspend fun saveWorkoutEnergy(energy: WorkoutEnergyEntity)
    suspend fun deleteWorkoutEnergy(workoutId: Long)
    fun observeEnergyWorkout(id: Long): Flow<EnergyWorkout?>
    fun observeWorkoutEnergyById(id: Long): Flow<WorkoutEnergyEntity?>
    fun observeEnergySources(): Flow<WorkoutEnergySources>
    suspend fun refreshWorkoutEstimate(id: Long, zone: ZoneId = ZoneId.systemDefault()): WorkoutEnergyEntity?
    suspend fun updateWorkoutEstimate(id: Long, preset: WorkoutActivityPreset, activeFraction: Double,
        manualKcal: Double?, zone: ZoneId = ZoneId.systemDefault()): WorkoutEnergyEntity?
}

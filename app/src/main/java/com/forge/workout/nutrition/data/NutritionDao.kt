package com.forge.workout.nutrition.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.forge.workout.nutrition.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NutritionDao {
    @Query("""
        SELECT f.* FROM nutrition_foods f
        WHERE f.archived = 0 AND (
            :filter = 'ALL'
            OR (:filter = 'FAVORITES' AND EXISTS (SELECT 1 FROM nutrition_favorites favorite WHERE favorite.foodId = f.id))
            OR (:filter = 'INDIAN' AND f.region = 'IN' COLLATE NOCASE)
            OR (:filter = 'BRANDED' AND LENGTH(TRIM(f.brand)) > 0)
        )
        ORDER BY f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset
    """)
    fun observeFoodPage(filter: String, limit: Int, offset: Int): Flow<List<FoodEntity>>

    @Query("""
        SELECT f.* FROM nutrition_foods f
        INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid
        WHERE nutrition_food_search MATCH :match AND f.archived = 0 AND (
            :filter = 'ALL'
            OR (:filter = 'FAVORITES' AND EXISTS (SELECT 1 FROM nutrition_favorites favorite WHERE favorite.foodId = f.id))
            OR (:filter = 'INDIAN' AND f.region = 'IN' COLLATE NOCASE)
            OR (:filter = 'BRANDED' AND LENGTH(TRIM(f.brand)) > 0)
        )
        ORDER BY f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset
    """)
    fun observeFoodSearchPage(match: String, filter: String, limit: Int, offset: Int): Flow<List<FoodEntity>>

    @Query("""
        SELECT f.* FROM nutrition_foods f
        INNER JOIN (SELECT foodId, COUNT(*) AS logCount, MAX(loggedAt) AS lastLoggedAt
                    FROM nutrition_diary WHERE foodId IS NOT NULL GROUP BY foodId) usage ON usage.foodId = f.id
        WHERE f.archived = 0
        ORDER BY CASE WHEN :frequent THEN usage.logCount ELSE 0 END DESC,
                 usage.lastLoggedAt DESC, f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset
    """)
    fun observeUsedFoodPage(frequent: Boolean, limit: Int, offset: Int): Flow<List<FoodEntity>>

    @Query("""
        SELECT f.* FROM nutrition_foods f
        INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid
        INNER JOIN (SELECT foodId, COUNT(*) AS logCount, MAX(loggedAt) AS lastLoggedAt
                    FROM nutrition_diary WHERE foodId IS NOT NULL GROUP BY foodId) usage ON usage.foodId = f.id
        WHERE nutrition_food_search MATCH :match AND f.archived = 0
        ORDER BY CASE WHEN :frequent THEN usage.logCount ELSE 0 END DESC,
                 usage.lastLoggedAt DESC, f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset
    """)
    fun observeUsedFoodSearchPage(match: String, frequent: Boolean, limit: Int, offset: Int): Flow<List<FoodEntity>>

    @Query("SELECT f.* FROM nutrition_foods f WHERE f.archived = 0 AND (:sourceType IS NULL OR f.sourceType = :sourceType) AND (:region IS NULL OR f.region = :region COLLATE NOCASE) AND (:favoritesOnly = 0 OR EXISTS (SELECT 1 FROM nutrition_favorites favorite WHERE favorite.foodId = f.id)) ORDER BY f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset")
    fun observeFoods(sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean, limit: Int, offset: Int): Flow<List<FoodEntity>>
    @Query("SELECT f.* FROM nutrition_foods f WHERE f.archived = 0 AND (:sourceType IS NULL OR f.sourceType = :sourceType) AND (:region IS NULL OR f.region = :region COLLATE NOCASE) AND (:favoritesOnly = 0 OR EXISTS (SELECT 1 FROM nutrition_favorites favorite WHERE favorite.foodId = f.id)) ORDER BY f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset")
    suspend fun foods(sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean, limit: Int, offset: Int): List<FoodEntity>
    @Query("SELECT f.* FROM nutrition_foods f INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid WHERE nutrition_food_search MATCH :match AND f.archived = 0 AND (:sourceType IS NULL OR f.sourceType = :sourceType) AND (:region IS NULL OR f.region = :region COLLATE NOCASE) AND (:favoritesOnly = 0 OR EXISTS (SELECT 1 FROM nutrition_favorites favorite WHERE favorite.foodId = f.id)) ORDER BY f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset")
    fun observeSearch(match: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean, limit: Int, offset: Int): Flow<List<FoodEntity>>
    @Query("SELECT f.* FROM nutrition_foods f INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid WHERE nutrition_food_search MATCH :match AND f.archived = 0 AND (:sourceType IS NULL OR f.sourceType = :sourceType) AND (:region IS NULL OR f.region = :region COLLATE NOCASE) AND (:favoritesOnly = 0 OR EXISTS (SELECT 1 FROM nutrition_favorites favorite WHERE favorite.foodId = f.id)) ORDER BY f.name COLLATE NOCASE, f.id LIMIT :limit OFFSET :offset")
    suspend fun search(match: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean, limit: Int, offset: Int): List<FoodEntity>
    @Transaction @Query("SELECT * FROM nutrition_foods WHERE id = :id") fun observeFood(id: String): Flow<FoodDetail?>
    @Transaction @Query("SELECT * FROM nutrition_foods WHERE id = :id") suspend fun food(id: String): FoodDetail?
    @Query("SELECT * FROM nutrition_foods WHERE id = :id") suspend fun foodRow(id: String): FoodEntity?
    @Insert suspend fun insertFood(food: FoodEntity): Long
    @Update suspend fun updateFood(food: FoodEntity)
    @Query("DELETE FROM nutrition_foods WHERE id = :id") suspend fun deleteFood(id: String)
    @Insert suspend fun insertNutrients(rows: List<FoodNutrientEntity>)
    @Insert suspend fun insertPortions(rows: List<FoodPortionEntity>)
    @Query("DELETE FROM nutrition_food_nutrients WHERE foodId = :id") suspend fun deleteNutrients(id: String)
    @Query("DELETE FROM nutrition_food_portions WHERE foodId = :id") suspend fun deletePortions(id: String)

    @Query("SELECT * FROM nutrition_catalog_packs ORDER BY installedAt DESC, id") fun observeCatalogPacks(): Flow<List<CatalogPackEntity>>
    @Query("SELECT * FROM nutrition_catalog_packs WHERE id = :id") suspend fun catalogPack(id: String): CatalogPackEntity?
    @Query("SELECT COUNT(*) FROM nutrition_foods WHERE packId = :packId") suspend fun catalogFoodCount(packId: String): Int
    @Query("SELECT * FROM nutrition_foods WHERE id IN (:ids)") suspend fun foodRowsByIds(ids: List<String>): List<FoodEntity>
    @Insert suspend fun insertFoods(foods: List<FoodEntity>): List<Long>
    @Update suspend fun updateFoods(foods: List<FoodEntity>)
    @Query("DELETE FROM nutrition_food_nutrients WHERE foodId IN (:ids)") suspend fun deleteNutrientsForFoodIds(ids: List<String>)
    @Query("DELETE FROM nutrition_food_portions WHERE foodId IN (:ids)") suspend fun deletePortionsForFoodIds(ids: List<String>)
    @Query("SELECT * FROM nutrition_foods WHERE packId = :packId AND rowid > :afterRowId ORDER BY rowid LIMIT :limit")
    suspend fun catalogFoodPage(packId: String, afterRowId: Long, limit: Int): List<FoodEntity>
    @Query("DELETE FROM nutrition_foods WHERE packId = :packId AND id IN (:ids)") suspend fun deletePackFoodIds(packId: String, ids: List<String>)
    @Upsert suspend fun upsertCatalogPack(pack: CatalogPackEntity)
    @Query("DELETE FROM nutrition_catalog_packs WHERE id = :id") suspend fun deleteCatalogPack(id: String)
    @Query("SELECT * FROM nutrition_diary WHERE day = :day ORDER BY loggedAt, id") fun observeDiary(day: String): Flow<List<DiaryEntity>>
    @Query("""
        WITH daily AS (
            SELECT day, COUNT(*) AS entryCount,
                COALESCE(SUM(kcal), 0.0) AS kcalKnown, SUM(CASE WHEN kcal IS NULL THEN 1 ELSE 0 END) AS kcalMissing,
                COALESCE(SUM(protein), 0.0) AS proteinKnown, SUM(CASE WHEN protein IS NULL THEN 1 ELSE 0 END) AS proteinMissing,
                COALESCE(SUM(carbs), 0.0) AS carbsKnown, SUM(CASE WHEN carbs IS NULL THEN 1 ELSE 0 END) AS carbsMissing,
                COALESCE(SUM(fat), 0.0) AS fatKnown, SUM(CASE WHEN fat IS NULL THEN 1 ELSE 0 END) AS fatMissing
            FROM nutrition_diary WHERE day >= :fromDay AND day <= :toDay
            GROUP BY day ORDER BY day DESC LIMIT :limit OFFSET :offset
        )
        SELECT daily.day, daily.entryCount, daily.kcalKnown, daily.kcalMissing, daily.proteinKnown, daily.proteinMissing,
            daily.carbsKnown, daily.carbsMissing, daily.fatKnown, daily.fatMissing,
            g.effectiveDay AS goalEffectiveDay, g.kcal AS goalKcal, g.protein AS goalProtein,
            g.carbs AS goalCarbs, g.fat AS goalFat
        FROM daily LEFT JOIN nutrition_goals g ON g.effectiveDay = (
            SELECT effectiveDay FROM nutrition_goals WHERE effectiveDay <= daily.day ORDER BY effectiveDay DESC LIMIT 1
        ) ORDER BY daily.day DESC
    """)
    fun observeNutritionHistory(fromDay: String, toDay: String, limit: Int, offset: Int): Flow<List<NutritionHistoryRow>>
    @Query("SELECT * FROM nutrition_diary WHERE id = :id") fun observeDiaryEntry(id: Long): Flow<DiaryEntity?>
    @Query("SELECT * FROM nutrition_diary WHERE id = :id") suspend fun diaryEntry(id: Long): DiaryEntity?
    @Query("SELECT * FROM nutrition_diary WHERE id IN (:ids)") suspend fun diaryEntries(ids: List<Long>): List<DiaryEntity>
    @Insert suspend fun insertDiary(entry: DiaryEntity): Long
    @Insert suspend fun insertDiaryEntries(entries: List<DiaryEntity>): List<Long>
    @Update suspend fun updateDiary(entry: DiaryEntity)
    @Query("DELETE FROM nutrition_diary WHERE id = :id") suspend fun deleteDiary(id: Long)

    @Transaction @Query("SELECT * FROM nutrition_recipes WHERE id = :id") fun observeRecipe(id: String): Flow<RecipeDetail?>
    @Transaction @Query("SELECT * FROM nutrition_recipes WHERE id = :id") suspend fun recipe(id: String): RecipeDetail?
    @Query("SELECT * FROM nutrition_recipes WHERE id = :id") suspend fun recipeRow(id: String): RecipeEntity?
    @Upsert suspend fun upsertRecipe(recipe: RecipeEntity)
    @Query("DELETE FROM nutrition_recipe_ingredients WHERE recipeId = :id") suspend fun deleteIngredients(id: String)
    @Insert suspend fun insertIngredients(rows: List<RecipeIngredientEntity>)

    @Query("SELECT * FROM nutrition_goals WHERE effectiveDay <= :day ORDER BY effectiveDay DESC LIMIT 1")
    fun observeGoal(day: String): Flow<NutritionGoalEntity?>
    @Upsert suspend fun upsertGoal(goal: NutritionGoalEntity)
    @Query("DELETE FROM nutrition_goals WHERE effectiveDay = :day") suspend fun deleteGoal(day: String)
    @Query("SELECT * FROM nutrition_settings WHERE id = 1") fun observeSettings(): Flow<NutritionSettingsEntity?>
    @Upsert suspend fun upsertSettings(settings: NutritionSettingsEntity)
    @Query("SELECT * FROM nutrition_favorites ORDER BY savedAt DESC, foodId") fun observeFavorites(): Flow<List<FavoriteEntity>>
    @Query("SELECT * FROM nutrition_favorites WHERE foodId = :id") fun observeFavorite(id: String): Flow<FavoriteEntity?>
    @Upsert suspend fun upsertFavorite(favorite: FavoriteEntity)
    @Query("DELETE FROM nutrition_favorites WHERE foodId = :id") suspend fun deleteFavorite(id: String)

    @Query("SELECT * FROM nutrition_body_weights WHERE day >= :fromDay AND day <= :toDay ORDER BY day DESC LIMIT :limit")
    fun observeBodyWeights(fromDay: String, toDay: String, limit: Int): Flow<List<BodyWeightEntity>>
    @Query("SELECT * FROM nutrition_body_weights WHERE day = :day") suspend fun bodyWeight(day: String): BodyWeightEntity?
    @Query("SELECT * FROM nutrition_body_weights WHERE id = :id") suspend fun bodyWeightById(id: Long): BodyWeightEntity?
    @Query("SELECT * FROM nutrition_body_weights WHERE day <= :day ORDER BY day DESC LIMIT 1") suspend fun closestBodyWeight(day: String): BodyWeightEntity?
    @Query("SELECT * FROM nutrition_body_weights WHERE day <= :day ORDER BY day DESC LIMIT 1") fun observeLatestBodyWeight(day: String): Flow<BodyWeightEntity?>
    @Query("SELECT * FROM nutrition_body_weights ORDER BY day, id") fun observeEnergyBodyWeights(): Flow<List<BodyWeightEntity>>
    @Insert suspend fun insertBodyWeight(weight: BodyWeightEntity): Long
    @Update suspend fun updateBodyWeight(weight: BodyWeightEntity)
    @Query("DELETE FROM nutrition_body_weights WHERE id = :id") suspend fun deleteBodyWeight(id: Long)
    @Query("SELECT * FROM nutrition_workout_energy WHERE day = :day ORDER BY startTime, workoutId") fun observeWorkoutEnergy(day: String): Flow<List<WorkoutEnergyEntity>>
    @Query("SELECT * FROM nutrition_workout_energy WHERE workoutId = :id") suspend fun workoutEnergy(id: Long): WorkoutEnergyEntity?
    @Query("SELECT * FROM nutrition_workout_energy WHERE workoutId = :id") fun observeWorkoutEnergyById(id: Long): Flow<WorkoutEnergyEntity?>
    @Query("SELECT * FROM nutrition_workout_energy ORDER BY workoutId") fun observeAllWorkoutEnergy(): Flow<List<WorkoutEnergyEntity>>
    @Query("SELECT id, startTime, endTime FROM workouts WHERE endTime IS NOT NULL ORDER BY id") fun observeEnergyWorkouts(): Flow<List<EnergyWorkout>>
    @Query("SELECT id, startTime, endTime FROM workouts WHERE id = :id AND endTime IS NOT NULL") fun observeEnergyWorkout(id: Long): Flow<EnergyWorkout?>
    @Query("SELECT id, startTime, endTime FROM workouts WHERE id = :id AND endTime IS NOT NULL") suspend fun energyWorkout(id: Long): EnergyWorkout?
    @Query("SELECT * FROM nutrition_settings WHERE id = 1") suspend fun energySettings(): NutritionSettingsEntity?
    @Upsert suspend fun upsertWorkoutEnergy(energy: WorkoutEnergyEntity)
    @Query("DELETE FROM nutrition_workout_energy WHERE workoutId = :id") suspend fun deleteWorkoutEnergy(id: Long)

    @Transaction @Query("SELECT * FROM nutrition_foods WHERE sourceType != 'REFERENCE' ORDER BY id") suspend fun backupPersonalFoods(): List<FoodDetail>
    @Query("SELECT id FROM nutrition_foods WHERE sourceType = 'REFERENCE' AND id IN (:ids)") suspend fun backupReferenceCollisions(ids: List<String>): List<String>
    @Transaction @Query("SELECT * FROM nutrition_recipes ORDER BY id") suspend fun backupRecipes(): List<RecipeDetail>
    @Query("SELECT * FROM nutrition_diary ORDER BY id") suspend fun backupDiary(): List<DiaryEntity>
    @Query("SELECT * FROM nutrition_goals ORDER BY effectiveDay") suspend fun backupGoals(): List<NutritionGoalEntity>
    @Query("SELECT * FROM nutrition_body_weights ORDER BY id") suspend fun backupBodyWeights(): List<BodyWeightEntity>
    @Query("SELECT * FROM nutrition_favorites ORDER BY foodId") suspend fun backupFavorites(): List<FavoriteEntity>
    @Query("SELECT * FROM nutrition_workout_energy ORDER BY workoutId") suspend fun backupWorkoutEnergy(): List<WorkoutEnergyEntity>
    @Query("DELETE FROM nutrition_workout_energy") suspend fun deleteBackupWorkoutEnergy()
    @Query("DELETE FROM nutrition_diary") suspend fun deleteBackupDiary()
    @Query("DELETE FROM nutrition_recipe_ingredients WHERE recipeId IN (SELECT id FROM nutrition_foods WHERE sourceType != 'REFERENCE')") suspend fun deleteBackupIngredients()
    @Query("DELETE FROM nutrition_recipes WHERE id IN (SELECT id FROM nutrition_foods WHERE sourceType != 'REFERENCE')") suspend fun deleteBackupRecipes()
    @Query("DELETE FROM nutrition_foods WHERE sourceType != 'REFERENCE'") suspend fun deleteBackupPersonalFoods()
    @Query("DELETE FROM nutrition_goals") suspend fun deleteBackupGoals()
    @Query("DELETE FROM nutrition_body_weights") suspend fun deleteBackupBodyWeights()
    @Query("DELETE FROM nutrition_favorites") suspend fun deleteBackupFavorites()
    @Query("DELETE FROM nutrition_settings") suspend fun deleteBackupSettings()
    @Insert suspend fun insertBackupRecipes(rows: List<RecipeEntity>)
    @Insert suspend fun insertBackupBodyWeights(rows: List<BodyWeightEntity>)
    @Insert suspend fun insertBackupGoals(rows: List<NutritionGoalEntity>)
    @Insert suspend fun insertBackupFavorites(rows: List<FavoriteEntity>)
    @Insert suspend fun insertBackupSettings(settings: NutritionSettingsEntity)
    @Insert suspend fun insertBackupWorkoutEnergy(rows: List<WorkoutEnergyEntity>)
}

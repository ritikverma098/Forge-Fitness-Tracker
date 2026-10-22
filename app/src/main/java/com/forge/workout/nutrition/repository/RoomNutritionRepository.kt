package com.forge.workout.nutrition.repository

import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.*
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomNutritionRepository(private val database: ForgeDatabase) : NutritionRepository {
    private val dao = database.nutritionDao()

    override fun observeFoodPage(query: String, filter: FoodBrowseFilter, limit: Int, offset: Int): Flow<List<FoodEntity>> {
        val match = searchExpression(query, limit, offset)
        if (query.isNotBlank() && match.isEmpty()) return flowOf(emptyList())
        val usage = filter == FoodBrowseFilter.RECENT || filter == FoodBrowseFilter.FREQUENT
        return when {
            usage && match.isEmpty() -> dao.observeUsedFoodPage(filter == FoodBrowseFilter.FREQUENT, limit, offset)
            usage -> dao.observeUsedFoodSearchPage(match, filter == FoodBrowseFilter.FREQUENT, limit, offset)
            match.isEmpty() -> dao.observeFoodPage(filter.name, limit, offset)
            else -> dao.observeFoodSearchPage(match, filter.name, limit, offset)
        }
    }

    override fun observeFoods(query: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean,
        limit: Int, offset: Int): Flow<List<FoodEntity>> {
        val match = searchExpression(query, limit, offset)
        return when {
            query.isNotBlank() && match.isEmpty() -> flowOf(emptyList())
            match.isEmpty() -> dao.observeFoods(sourceType, region, favoritesOnly, limit, offset)
            else -> dao.observeSearch(match, sourceType, region, favoritesOnly, limit, offset)
        }
    }
    override suspend fun searchFoods(query: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean,
        limit: Int, offset: Int): List<FoodEntity> {
        val match = searchExpression(query, limit, offset)
        return when {
            query.isNotBlank() && match.isEmpty() -> emptyList()
            match.isEmpty() -> dao.foods(sourceType, region, favoritesOnly, limit, offset)
            else -> dao.search(match, sourceType, region, favoritesOnly, limit, offset)
        }
    }
    private fun searchExpression(query: String, limit: Int, offset: Int): String {
        NutritionValidation.page(limit, offset)
        NutritionValidation.text(query, 250, "Search", true)
        // Only literal Unicode word prefixes reach MATCH; operators/punctuation cannot become FTS syntax.
        return Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.take(32).joinToString(" ") { "\"$it*\"" }
    }
    override fun observeFood(id: String): Flow<FoodDetail?> { NutritionValidation.id(id); return dao.observeFood(id) }
    override suspend fun food(id: String): FoodDetail? { NutritionValidation.id(id); return dao.food(id) }
    override suspend fun upsertFood(detail: FoodDetail): String {
        val stable = freeze(detail)
        NutritionValidation.food(stable)
        return database.withTransaction { saveFood(stable) }
    }
    override suspend fun saveCustomFood(detail: FoodDetail): String {
        val stable = freeze(detail)
        NutritionValidation.food(stable)
        require(stable.food.sourceType == FoodSourceType.CUSTOM) { "Only custom foods can be edited." }
        return database.withTransaction {
            val previous = dao.foodRow(stable.food.id)
            require(previous == null || previous.sourceType == FoodSourceType.CUSTOM) { "Only custom foods can be edited." }
            require(previous != null || stable.food.id.startsWith("custom:")) { "New custom foods need a custom food ID." }
            saveFood(stable)
        }
    }
    override suspend fun deleteCustomFood(id: String) {
        NutritionValidation.id(id)
        database.withTransaction {
            val previous = requireNotNull(dao.foodRow(id)) { "Custom food was not found." }
            require(previous.sourceType == FoodSourceType.CUSTOM) { "Only custom foods can be deleted." }
            dao.deleteFood(id)
        }
    }
    private suspend fun saveFood(detail: FoodDetail): String {
        NutritionValidation.food(detail)
        val old = dao.foodRow(detail.food.id)
        val cleaned = detail.food.copy(name = detail.food.name.trim(), brand = detail.food.brand.trim())
        if (old == null) dao.insertFood(cleaned.copy(rowId = 0))
        else dao.updateFood(cleaned.copy(rowId = old.rowId))
        // Update the parent in place: REPLACE would cascade-delete recipes and change the FTS rowid.
        dao.deleteNutrients(cleaned.id)
        dao.deletePortions(cleaned.id)
        dao.insertNutrients(detail.nutrients)
        dao.insertPortions(detail.portions)
        return cleaned.id
    }
    override suspend fun deleteFood(id: String) { NutritionValidation.id(id); dao.deleteFood(id) }
    override fun observeCatalogPacks(): Flow<List<CatalogPackEntity>> = dao.observeCatalogPacks()
    override suspend fun upsertCatalogPack(pack: CatalogPackEntity) { NutritionValidation.pack(pack); dao.upsertCatalogPack(pack) }
    override suspend fun deleteCatalogPack(id: String) { NutritionValidation.id(id); dao.deleteCatalogPack(id) }

    override fun observeDiary(day: String): Flow<List<DiaryEntity>> { NutritionValidation.day(day); return dao.observeDiary(day) }
    override fun observeNutritionHistory(fromDay: String, toDay: String, limit: Int, offset: Int): Flow<List<NutritionHistoryDay>> {
        NutritionValidation.day(fromDay); NutritionValidation.day(toDay)
        require(fromDay <= toDay) { "Invalid nutrition history date range." }
        NutritionValidation.page(limit, offset)
        return dao.observeNutritionHistory(fromDay, toDay, limit, offset).map { rows -> rows.map { it.summary() } }
            .flowOn(Dispatchers.Default).distinctUntilChanged()
    }
    override fun observeDiaryEntry(id: Long): Flow<DiaryEntity?> { require(id > 0); return dao.observeDiaryEntry(id) }
    override suspend fun diaryEntry(id: Long): DiaryEntity? { require(id > 0); return dao.diaryEntry(id) }
    override suspend fun saveDiary(entry: DiaryEntity): Long {
        val stable = entry.copy(snapshot = freeze(entry.snapshot))
        NutritionValidation.diary(stable)
        return database.withTransaction {
            if (stable.id == 0L) dao.insertDiary(withDiaryMacros(stable)) else {
                val current = requireNotNull(dao.diaryEntry(stable.id)) { "Diary entry was not found." }
                require(stable.foodId == current.foodId && stable.snapshot == current.snapshot) { "Diary food snapshots cannot be replaced." }
                val saved = withDiaryMacros(current.copy(day = stable.day, meal = stable.meal,
                    quantity = stable.quantity, unit = stable.unit, portionId = stable.portionId,
                    portionLabel = stable.portionLabel, factor = stable.factor))
                dao.updateDiary(saved)
                saved.id
            }
        }
    }
    override suspend fun deleteDiary(id: Long) { require(id > 0); dao.deleteDiary(id) }
    override suspend fun repeatDiary(ids: List<Long>, day: String, meal: MealType): List<Long> {
        val stableIds = ids.toList()
        require(stableIds.isNotEmpty() && stableIds.size <= 1000 && stableIds.all { it > 0 }) { "Select between 1 and 1000 diary entries." }
        NutritionValidation.day(day)
        val uniqueIds = stableIds.distinct()
        return database.withTransaction {
            // Stay below older SQLite bind limits while resolving every source before inserting anything.
            val current = uniqueIds.chunked(900).flatMap { dao.diaryEntries(it) }.associateBy { it.id }
            require(current.size == uniqueIds.size) { "Diary entry was not found." }
            val loggedAt = System.currentTimeMillis()
            val copies = uniqueIds.map { id ->
                val source = current.getValue(id)
                val copy = source.copy(id = 0, day = day, meal = meal, loggedAt = loggedAt, snapshot = freeze(source.snapshot))
                NutritionValidation.diary(copy)
                withDiaryMacros(copy)
            }
            dao.insertDiaryEntries(copies)
        }
    }
    override suspend fun moveDiary(id: Long, day: String, meal: MealType) {
        require(id > 0)
        NutritionValidation.day(day)
        database.withTransaction {
            val current = requireNotNull(dao.diaryEntry(id)) { "Diary entry was not found." }
            dao.updateDiary(current.copy(day = day, meal = meal))
        }
    }
    private fun withDiaryMacros(entry: DiaryEntity): DiaryEntity {
        fun macro(code: String) = entry.snapshot.nutrients.firstOrNull { it.code == code }?.value?.times(entry.factor)
        return entry.copy(kcal = macro(NutrientCodes.ENERGY), protein = macro(NutrientCodes.PROTEIN),
            carbs = macro(NutrientCodes.CARBS), fat = macro(NutrientCodes.FAT))
    }
    override fun observeRecipe(id: String): Flow<RecipeDetail?> { NutritionValidation.id(id); return dao.observeRecipe(id) }
    override suspend fun recipe(id: String): RecipeDetail? { NutritionValidation.id(id); return dao.recipe(id) }
    override suspend fun saveRecipe(recipe: RecipeEntity, food: FoodDetail, ingredients: List<RecipeIngredientEntity>): String {
        val stableRecipe = recipe.copy(name = recipe.name.trim())
        val stableFood = freeze(food).let { it.copy(food = it.food.copy(name = it.food.name.trim())) }
        val stableIngredients = ingredients.map { it.copy(snapshot = freeze(it.snapshot)) }
        NutritionValidation.recipe(stableRecipe, stableFood, stableIngredients)
        return database.withTransaction {
            val previous = dao.foodRow(stableRecipe.id)
            require(previous == null || previous.sourceType == FoodSourceType.RECIPE) { "Only recipes can be edited here." }
            saveFood(stableFood)
            dao.upsertRecipe(stableRecipe)
            dao.deleteIngredients(stableRecipe.id)
            dao.insertIngredients(stableIngredients)
            stableRecipe.id
        }
    }
    override suspend fun deleteRecipe(id: String) = database.withTransaction {
        NutritionValidation.id(id)
        val previous = requireNotNull(dao.foodRow(id)) { "Recipe was not found." }
        require(previous.sourceType == FoodSourceType.RECIPE) { "Only recipes can be deleted here." }
        requireNotNull(dao.recipeRow(id)) { "Recipe was not found." }
        dao.deleteFood(id)
    }
    override fun observeGoal(day: String): Flow<NutritionGoalEntity?> { NutritionValidation.day(day); return dao.observeGoal(day) }
    override suspend fun saveGoal(goal: NutritionGoalEntity) { NutritionValidation.goal(goal); dao.upsertGoal(goal) }
    override suspend fun deleteGoal(effectiveDay: String) { NutritionValidation.day(effectiveDay); dao.deleteGoal(effectiveDay) }
    override fun observeSettings(): Flow<NutritionSettingsEntity> = dao.observeSettings().map { it ?: NutritionSettingsEntity() }
    override suspend fun saveSettings(settings: NutritionSettingsEntity) { NutritionValidation.settings(settings); dao.upsertSettings(settings) }
    override fun observeFavorites(): Flow<List<FavoriteEntity>> = dao.observeFavorites()
    override fun observeFavorite(id: String): Flow<FavoriteEntity?> { NutritionValidation.id(id); return dao.observeFavorite(id) }
    override suspend fun saveFavorite(favorite: FavoriteEntity) { NutritionValidation.favorite(favorite); dao.upsertFavorite(favorite) }
    override suspend fun deleteFavorite(foodId: String) { NutritionValidation.id(foodId); dao.deleteFavorite(foodId) }
    override fun observeBodyWeights(fromDay: String, toDay: String, limit: Int): Flow<List<BodyWeightEntity>> {
        NutritionValidation.day(fromDay); NutritionValidation.day(toDay)
        require(fromDay <= toDay && limit in 1..10_000) { "Invalid body weight range." }
        return dao.observeBodyWeights(fromDay, toDay, limit)
    }
    override suspend fun bodyWeight(day: String): BodyWeightEntity? { NutritionValidation.day(day); return dao.bodyWeight(day) }
    override fun observeLatestBodyWeight(day: String): Flow<BodyWeightEntity?> { NutritionValidation.day(day); return dao.observeLatestBodyWeight(day).distinctUntilChanged() }
    override suspend fun saveBodyWeight(weight: BodyWeightEntity): Long = database.withTransaction {
        NutritionValidation.bodyWeight(weight)
        val sameDay = dao.bodyWeight(weight.day)
        val old = if (weight.id == 0L) sameDay else requireNotNull(dao.bodyWeightById(weight.id)) { "Body weight was not found." }
        require(sameDay == null || old?.id == sameDay.id) { "A body weight already exists for this day." }
        if (old == null) dao.insertBodyWeight(weight) else {
            dao.updateBodyWeight(weight.copy(id = old.id))
            old.id
        }
    }
    override suspend fun deleteBodyWeight(id: Long) { require(id > 0); dao.deleteBodyWeight(id) }
    override fun observeWorkoutEnergy(day: String): Flow<List<WorkoutEnergyEntity>> { NutritionValidation.day(day); return dao.observeWorkoutEnergy(day) }
    override suspend fun workoutEnergy(workoutId: Long): WorkoutEnergyEntity? { require(workoutId > 0); return dao.workoutEnergy(workoutId) }
    override suspend fun saveWorkoutEnergy(energy: WorkoutEnergyEntity) = database.withTransaction {
        NutritionValidation.energy(energy)
        val workout = requireNotNull(database.workoutDao().workout(energy.workoutId)) { "Workout was not found." }
        require(workout.endTime != null) { "Workout energy requires a completed workout." }
        require(energy.startTime == workout.startTime && energy.endTime == workout.endTime) { "Workout energy timing must match the workout." }
        dao.upsertWorkoutEnergy(energy)
    }
    override suspend fun deleteWorkoutEnergy(workoutId: Long) { require(workoutId > 0); dao.deleteWorkoutEnergy(workoutId) }

    override fun observeEnergyWorkout(id: Long): Flow<EnergyWorkout?> { require(id > 0); return dao.observeEnergyWorkout(id).distinctUntilChanged() }
    override fun observeWorkoutEnergyById(id: Long): Flow<WorkoutEnergyEntity?> { require(id > 0); return dao.observeWorkoutEnergyById(id).distinctUntilChanged() }
    override fun observeEnergySources(): Flow<WorkoutEnergySources> = combine(dao.observeEnergyWorkouts(),
        dao.observeEnergyBodyWeights(), observeSettings(), dao.observeAllWorkoutEnergy()) { workouts, weights, settings, estimates ->
        WorkoutEnergySources(workouts.toList(), weights.toList(), settings, estimates.toList())
    }.distinctUntilChanged()

    override suspend fun refreshWorkoutEstimate(id: Long, zone: ZoneId): WorkoutEnergyEntity? {
        require(id > 0)
        return database.withTransaction { recalculateEnergy(id, zone) }
    }

    override suspend fun updateWorkoutEstimate(id: Long, preset: WorkoutActivityPreset, activeFraction: Double,
        manualKcal: Double?, zone: ZoneId): WorkoutEnergyEntity? {
        require(id > 0)
        require(activeFraction.isFinite() && activeFraction in 0.0..1.0) { "Invalid active fraction." }
        manualKcal?.let { NutritionValidation.number(it, "manual workout calories") }
        return database.withTransaction { recalculateEnergy(id, zone, preset, activeFraction, manualKcal) }
    }

    private suspend fun recalculateEnergy(id: Long, zone: ZoneId, preset: WorkoutActivityPreset? = null,
        activeFraction: Double? = null, manualKcal: Double? = null): WorkoutEnergyEntity? {
        val current = dao.workoutEnergy(id)
        val workout = dao.energyWorkout(id)
        if (workout == null) {
            if (current != null) dao.deleteWorkoutEnergy(id)
            return null
        }
        val day = WorkoutEnergyMath.day(workout, current, zone)
        val weight = dao.closestBodyWeight(day)
        val settings = dao.energySettings() ?: NutritionSettingsEntity()
        val base = if (preset == null) current else current?.copy(activity = preset.id, met = preset.met,
            intensity = preset.intensity, activeFraction = requireNotNull(activeFraction), manualKcal = manualKcal)
        var estimated = WorkoutEnergyMath.estimate(workout, weight, settings, base, zone)
        if (preset != null && current == null && estimated != null) {
            val fraction = requireNotNull(activeFraction)
            estimated = estimated.copy(activity = preset.id, met = preset.met, intensity = preset.intensity,
                activeFraction = fraction, manualKcal = manualKcal,
                calculatedKcal = WorkoutEnergyMath.activeKcal(estimated.durationSeconds, estimated.bodyWeightKg, preset.met, fraction))
        }
        if (estimated == null) {
            if (current != null) dao.deleteWorkoutEnergy(id)
        } else {
            NutritionValidation.energy(estimated)
            if (estimated != current) dao.upsertWorkoutEnergy(estimated)
        }
        return estimated
    }

    private fun freeze(snapshot: FoodSnapshot) = snapshot.copy(nutrients = snapshot.nutrients.toList())
    private fun freeze(detail: FoodDetail) = detail.copy(nutrients = detail.nutrients.toList(), portions = detail.portions.toList())
}

package com.forge.workout.nutrition.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Workout
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real generated Room DAO, FTS triggers and repository transactions, with an isolated catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionDatabaseHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
    }

    @After fun closeDatabase() { database.close() }

    @Test fun `food relations and FTS track edits while stable identity indices and foreign keys are enforced`() = runBlocking {
        val id = repository.upsertFood(food("rice", "Brown rice"))
        val original = repository.food(id)!!
        assertTrue(original.food.rowId > 0)
        assertEquals(5, original.nutrients.size)
        assertEquals(1, original.portions.size)
        assertEquals(listOf(id), repository.searchFoods("arroz").map { it.id })
        assertEquals(listOf(id), repository.searchFoods("Harvest").map { it.id })
        assertEquals(listOf(id), repository.searchFoods("brow").map { it.id })

        repository.upsertFood(original.copy(food = original.food.copy(name = "Quinoa bowl", brand = "Local", aliases = "grain mix")))
        assertEquals(original.food.rowId, repository.food(id)!!.food.rowId)
        assertTrue(repository.searchFoods("arroz").isEmpty())
        assertEquals(listOf(id), repository.searchFoods("quinoa").map { it.id })
        expectConstraint { database.nutritionDao().insertNutrients(listOf(FoodNutrientEntity("missing", "energy", "Energy", "kcal", 10.0))) }
        expectConstraint { database.nutritionDao().insertFood(original.food.copy(rowId = 0)) }
        assertIndex("nutrition_foods", "index_nutrition_foods_id", unique = true)
        assertIndex("nutrition_foods", "index_nutrition_foods_packId")
        assertIndex("nutrition_foods", "index_nutrition_foods_region_archived_name")
        assertIndex("nutrition_diary", "index_nutrition_diary_day_loggedAt")
        assertIndex("nutrition_diary", "index_nutrition_diary_foodId")
        assertIndex("nutrition_recipe_ingredients", "index_nutrition_recipe_ingredients_recipeId_orderIndex", unique = true)
        assertForeignKeys()
    }

    @Test fun `diary freezes all food metadata and nutrients recalculating caches even after food edit and deletion`() = runBlocking {
        val id = repository.upsertFood(food("rice", "Brown rice"))
        val snapshot = repository.food(id)!!.snapshot()
        val diaryId = repository.saveDiary(diary(snapshot).copy(kcal = 999.0, protein = 999.0, carbs = 999.0, fat = 999.0))
        val frozen = repository.diaryEntry(diaryId)!!
        assertEquals(snapshot, frozen.snapshot)
        assertEquals(180.0, frozen.kcal!!, 0.000001)
        assertEquals(4.5, frozen.protein!!, 0.000001)
        assertEquals(37.5, frozen.carbs!!, 0.000001)
        assertEquals(1.5, frozen.fat!!, 0.000001)
        assertNull(frozen.snapshot.nutrients.single { it.code == "fiber" }.value)

        val current = repository.food(id)!!
        repository.upsertFood(current.copy(food = current.food.copy(name = "Changed rice", brand = "Another brand"),
            nutrients = current.nutrients.map { it.copy(value = it.value?.times(2)) }))
        assertEquals(frozen, repository.diaryEntry(diaryId))
        repository.deleteFood(id)
        assertNull(repository.food(id))
        assertEquals(frozen, repository.observeDiary(DAY).first().single())
        assertTrue(repository.searchFoods("rice").isEmpty())
        assertForeignKeys()
    }

    @Test fun `pack deletion cascades live catalog relations but preserves diary recipe and favorite snapshots`() = runBlocking {
        val pack = CatalogPackEntity("fixture-pack", "Test reference", "1", "https://example.invalid/source",
            "Test license", "Host test reference", installedAt = 1, sha256 = "a".repeat(64), foodCount = 1, payloadBytes = 100)
        repository.upsertCatalogPack(pack)
        val reference = food("reference-rice", "Catalog rice").let {
            it.copy(food = it.food.copy(sourceType = FoodSourceType.REFERENCE, packId = pack.id, region = "IN"))
        }
        repository.upsertFood(reference)
        val snapshot = repository.food(reference.food.id)!!.snapshot()
        val diaryId = repository.saveDiary(diary(snapshot))
        repository.saveFavorite(FavoriteEntity(reference.food.id, "Favorite rice", 1))
        saveRecipe("recipe", "Rice bowl", listOf(snapshot))
        val recipeBefore = repository.recipe("recipe")!!
        val diaryBefore = repository.diaryEntry(diaryId)!!

        repository.deleteCatalogPack(pack.id)
        assertTrue(repository.observeCatalogPacks().first().isEmpty())
        assertNull(repository.food(reference.food.id))
        assertEquals(0, rowCount("nutrition_food_nutrients", "foodId = 'reference-rice'"))
        assertEquals(0, rowCount("nutrition_food_portions", "foodId = 'reference-rice'"))
        assertTrue(repository.searchFoods("Catalog").isEmpty())
        assertEquals(diaryBefore, repository.diaryEntry(diaryId))
        assertEquals(recipeBefore, repository.recipe("recipe"))
        assertEquals(reference.food.id, repository.observeFavorites().first().single().foodId)
        assertForeignKeys()
    }

    @Test fun `recipe ingredient order and snapshots survive source edits and deleting a recipe cascades only its live graph`() = runBlocking {
        val riceId = repository.upsertFood(food("rice", "Brown rice"))
        val beanId = repository.upsertFood(food("beans", "Beans"))
        val snapshots = listOf(repository.food(beanId)!!.snapshot(), repository.food(riceId)!!.snapshot())
        saveRecipe("bowl", "Bean rice bowl", snapshots)
        val recipe = repository.recipe("bowl")!!
        assertEquals(listOf(0, 1), recipe.sortedIngredients.map { it.orderIndex })
        assertEquals(snapshots, recipe.sortedIngredients.map { it.snapshot })
        val recipeDiaryId = repository.saveDiary(diary(repository.food("bowl")!!.snapshot()).copy(quantity = 1.0, unit = FoodUnit.SERVING, factor = 1.0))
        val frozenDiary = repository.diaryEntry(recipeDiaryId)!!
        val source = repository.food(riceId)!!
        repository.upsertFood(source.copy(food = source.food.copy(name = "Renamed rice")))
        repository.deleteFood(beanId)
        assertEquals(recipe, repository.recipe("bowl"))
        expectConstraint { database.nutritionDao().insertIngredients(listOf(recipe.ingredients.first().copy(id = 0, recipeId = "missing"))) }

        repository.deleteRecipe("bowl")
        assertNull(repository.recipe("bowl"))
        assertNull(repository.food("bowl"))
        assertEquals(0, rowCount("nutrition_recipe_ingredients", "recipeId = 'bowl'"))
        assertEquals(frozenDiary, repository.diaryEntry(recipeDiaryId))
        assertNotNull(repository.food(riceId))
        assertForeignKeys()
    }

    @Test fun `failed nutrient replacement rolls food relations and FTS contents back atomically`() = runBlocking {
        val id = repository.upsertFood(food("rice", "Brown rice"))
        val before = repository.food(id)!!
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_nutrient BEFORE INSERT ON nutrition_food_nutrients WHEN NEW.code = 'protein' BEGIN SELECT RAISE(ABORT, 'Reject test nutrient'); END")
        expectConstraint {
            repository.upsertFood(before.copy(food = before.food.copy(name = "Replacement food", aliases = "replacement"),
                nutrients = before.nutrients.map { it.copy(value = it.value?.times(2)) }, portions = emptyList()))
        }
        assertEquals(before, repository.food(id))
        assertEquals(listOf(id), repository.searchFoods("arroz").map { it.id })
        assertTrue(repository.searchFoods("replacement").isEmpty())
        assertForeignKeys()
    }

    @Test fun `failed ingredient replacement rolls recipe and food changes back atomically`() = runBlocking {
        val id = repository.upsertFood(food("rice", "Brown rice"))
        val snapshot = repository.food(id)!!.snapshot()
        saveRecipe("bowl", "Original bowl", listOf(snapshot, snapshot))
        val recipeBefore = repository.recipe("bowl")!!
        val foodBefore = repository.food("bowl")!!
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_ingredient BEFORE INSERT ON nutrition_recipe_ingredients WHEN NEW.orderIndex = 1 BEGIN SELECT RAISE(ABORT, 'Reject test ingredient'); END")
        expectConstraint { saveRecipe("bowl", "Replacement bowl", listOf(snapshot, snapshot), calories = 900.0) }
        assertEquals(recipeBefore, repository.recipe("bowl"))
        assertEquals(foodBefore, repository.food("bowl"))
        assertEquals(listOf("bowl"), repository.searchFoods("Original").map { it.id })
        assertTrue(repository.searchFoods("Replacement").isEmpty())
        assertForeignKeys()
    }

    @Test fun `diary dates isolate a local midnight boundary and goals apply from their effective day`() = runBlocking {
        val snapshot = FoodSnapshot(name = "Unknown nutrition", nutrients = listOf(NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 0.0)))
        val zone = ZoneId.of("Asia/Kolkata")
        val midnight = LocalDate.parse(DAY).atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterdayId = repository.saveDiary(diary(snapshot).copy(day = "2026-10-11", loggedAt = midnight - 1))
        val todayId = repository.saveDiary(diary(snapshot).copy(loggedAt = midnight))
        assertEquals(listOf(yesterdayId), repository.observeDiary("2026-10-11").first().map { it.id })
        assertEquals(listOf(todayId), repository.observeDiary(DAY).first().map { it.id })
        assertNull(repository.diaryEntry(todayId)!!.kcal)
        assertEquals(0.0, repository.diaryEntry(todayId)!!.protein!!, 0.0)
        val first = NutritionGoalEntity("2026-10-10", kcal = 2100.0, protein = 120.0)
        val later = NutritionGoalEntity(DAY, kcal = 2400.0, protein = 150.0, carbs = 250.0, fat = 70.0)
        repository.saveGoal(first)
        repository.saveGoal(later)
        assertNull(repository.observeGoal("2026-10-09").first())
        assertEquals(first, repository.observeGoal("2026-10-11").first())
        assertEquals(later, repository.observeGoal(DAY).first())
        assertEquals(later, repository.observeGoal("2026-10-13").first())
        repository.deleteGoal(DAY)
        assertEquals(first, repository.observeGoal(DAY).first())
        expectInvalid { repository.saveGoal(first.copy(kcal = Double.NaN)) }
        expectInvalid { repository.saveDiary(diary(snapshot).copy(day = "2026-02-30")) }
        assertEquals(1, repository.observeDiary(DAY).first().size)
        assertEquals(NutritionSettingsEntity(), repository.observeSettings().first())
        val settings = NutritionSettingsEntity(includeWorkoutCalories = true, defaultActiveFraction = 0.75)
        repository.saveSettings(settings)
        assertEquals(settings, repository.observeSettings().first())
        expectInvalid { repository.saveSettings(settings.copy(defaultActiveFraction = Double.NaN)) }
        assertEquals(settings, repository.observeSettings().first())
    }

    @Test fun `one body weight per day updates stable identity and bounded observations retain chronological ordering`() = runBlocking {
        val firstId = repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-11", kg = 80.3, measuredAt = 1))
        val sameId = repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-11", kg = 81.0, measuredAt = 2))
        assertEquals(firstId, sameId)
        assertEquals(81.0, repository.bodyWeight("2026-10-11")!!.kg, 0.000001)
        val latestId = repository.saveBodyWeight(BodyWeightEntity(day = DAY, kg = 82.2, measuredAt = 3))
        assertTrue(latestId != firstId)
        assertEquals(listOf(latestId, firstId), repository.observeBodyWeights("2026-10-11", DAY).first().map { it.id })
        assertEquals(listOf(latestId), repository.observeBodyWeights("2026-10-11", DAY, limit = 1).first().map { it.id })
        expectConstraint { database.nutritionDao().insertBodyWeight(BodyWeightEntity(day = DAY, kg = 70.0)) }
        expectInvalid { repository.saveBodyWeight(BodyWeightEntity(day = DAY, kg = -1.0)) }
        assertEquals(82.2, repository.bodyWeight(DAY)!!.kg, 0.000001)
        repository.deleteBodyWeight(firstId)
        assertNull(repository.bodyWeight("2026-10-11"))
        assertIndex("nutrition_body_weights", "index_nutrition_body_weights_day", unique = true)
    }

    @Test fun `workout energy keeps captured inputs manual zero override and body weight deletion then cascades with workout`() = runBlocking {
        val workoutId = completedWorkout()
        val bodyId = repository.saveBodyWeight(BodyWeightEntity(day = DAY, kg = 80.0, measuredAt = 1))
        val original = energy(workoutId, bodyId)
        repository.saveWorkoutEnergy(original)
        assertEquals(original, repository.workoutEnergy(workoutId))
        assertEquals(8.4, repository.workoutEnergy(workoutId)!!.effectiveKcal, 0.000001)
        repository.saveWorkoutEnergy(original.copy(manualKcal = 0.0))
        assertEquals(0.0, repository.workoutEnergy(workoutId)!!.effectiveKcal, 0.0)
        repository.saveWorkoutEnergy(original.copy(manualKcal = 25.0))
        assertEquals(25.0, repository.observeWorkoutEnergy(DAY).first().single().effectiveKcal, 0.0)
        repository.saveBodyWeight(BodyWeightEntity(day = DAY, kg = 90.0, measuredAt = 2))
        assertEquals(80.0, repository.workoutEnergy(workoutId)!!.bodyWeightKg, 0.0)
        repository.deleteBodyWeight(bodyId)
        val detached = repository.workoutEnergy(workoutId)!!
        assertNull(detached.bodyWeightId)
        assertEquals(80.0, detached.bodyWeightKg, 0.0)
        assertEquals(25.0, detached.effectiveKcal, 0.0)
        database.workoutDao().deleteWorkout(workoutId)
        assertNull(repository.workoutEnergy(workoutId))
        assertTrue(repository.observeWorkoutEnergy(DAY).first().isEmpty())
        assertForeignKeys()
    }

    @Test fun `invalid or active workout energy never replaces an existing completed estimate`() = runBlocking {
        val workoutId = completedWorkout()
        val original = energy(workoutId)
        repository.saveWorkoutEnergy(original)
        listOf(original.copy(calculatedKcal = Double.NaN), original.copy(manualKcal = -1.0),
            original.copy(bodyWeightKg = 0.0), original.copy(met = Double.POSITIVE_INFINITY),
            original.copy(activeFraction = 1.1), original.copy(durationSeconds = 0),
            original.copy(startTime = 2_000, endTime = 62_000), original.copy(endTime = 62_000),
            original.copy(day = "2026-99-99")).forEach { invalid ->
            expectInvalid { repository.saveWorkoutEnergy(invalid) }
            assertEquals(original, repository.workoutEnergy(workoutId))
        }
        val activeId = database.workoutDao().insertWorkout(Workout(name = "Active", startTime = 1_000))
        expectInvalid { repository.saveWorkoutEnergy(energy(activeId)) }
        assertNull(repository.workoutEnergy(activeId))
        expectConstraint { database.nutritionDao().upsertWorkoutEnergy(energy(workoutId + 1000)) }
        assertForeignKeys()
    }

    private fun food(id: String, name: String, calories: Double = 120.0) = FoodDetail(
        FoodEntity(id = id, name = name, brand = "Harvest", aliases = "arroz integral", source = "Test source",
            sourceId = "source-$id", sourceUrl = "https://example.invalid/$id", gramsPerBase = 100.0),
        listOf(FoodNutrientEntity(id, NutrientCodes.ENERGY, "Energy", "kcal", calories),
            FoodNutrientEntity(id, NutrientCodes.PROTEIN, "Protein", "g", 3.0),
            FoodNutrientEntity(id, NutrientCodes.CARBS, "Carbohydrate", "g", 25.0),
            FoodNutrientEntity(id, NutrientCodes.FAT, "Fat", "g", 1.0),
            FoodNutrientEntity(id, "fiber", "Fiber", "g", null)),
        listOf(FoodPortionEntity(id, "cup", "Cup", baseQuantity = 150.0, grams = 150.0)),
    )

    private fun diary(snapshot: FoodSnapshot) = DiaryEntity(day = DAY, meal = MealType.LUNCH,
        foodId = snapshot.foodId, snapshot = snapshot, quantity = 150.0, unit = FoodUnit.GRAM,
        factor = 1.5, loggedAt = 1000)

    private suspend fun saveRecipe(id: String, name: String, snapshots: List<FoodSnapshot>, calories: Double = 110.0) {
        val recipeFood = food(id, name, calories).let {
            it.copy(food = it.food.copy(sourceType = FoodSourceType.RECIPE, baseAmount = 1.0,
                baseUnit = FoodUnit.SERVING, gramsPerBase = 200.0), portions = emptyList())
        }
        repository.saveRecipe(RecipeEntity(id, name, servings = 2.0, cookedWeightGrams = 400.0), recipeFood,
            snapshots.mapIndexed { index, snapshot -> RecipeIngredientEntity(recipeId = id, orderIndex = index,
                foodId = snapshot.foodId, snapshot = snapshot, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0) })
    }

    private suspend fun completedWorkout() = database.workoutDao().insertWorkout(
        Workout(name = "Completed", startTime = 1000, endTime = 61_000),
    )

    private fun energy(id: Long, bodyId: Long? = null) = WorkoutEnergyEntity(workoutId = id, day = DAY,
        startTime = 1000, endTime = 61_000, durationSeconds = 60, bodyWeightId = bodyId, bodyWeightKg = 80.0,
        met = 6.0, activity = "resistance_training", intensity = WorkoutIntensity.MODERATE, calculatedKcal = 8.4)

    private fun rowCount(table: String, condition: String): Int = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM $table WHERE $condition").use { it.moveToFirst(); it.getInt(0) }

    private fun assertIndex(table: String, name: String, unique: Boolean = false) {
        database.openHelper.writableDatabase.query("PRAGMA index_list($table)").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == name) {
                found = true
                assertEquals(if (unique) 1 else 0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
            }
            assertTrue("Missing index $name", found)
        }
    }

    private fun assertForeignKeys() {
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private suspend fun expectConstraint(operation: suspend () -> Unit) {
        try { operation(); fail("Expected SQLite relationship or transaction constraint") }
        catch (_: SQLiteConstraintException) { }
    }

    private suspend fun expectInvalid(operation: suspend () -> Unit) {
        try { operation(); fail("Expected validation failure") }
        catch (_: IllegalArgumentException) { }
    }

    private companion object { const val DAY = "2026-10-12" }
}

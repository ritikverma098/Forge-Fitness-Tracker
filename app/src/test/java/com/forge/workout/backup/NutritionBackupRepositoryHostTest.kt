package com.forge.workout.backup

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.preferences.UserPreferences
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionBackupRepositoryHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var backups: RoomBackupRepository
    private lateinit var foods: RoomNutritionRepository
    private var preferences = UserPreferences()
    private val start = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        bindRepositories()
    }
    @After fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(DATABASE_NAME)
    }

    @Test fun `nutrition backup persists after reopen`() = runBlocking<Unit> {
        database.close()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        val path = context.getDatabasePath(DATABASE_NAME)
        check(path.parentFile!!.mkdirs() || path.parentFile!!.isDirectory)
        println("Nutrition backup file fixture path length=${path.absolutePath.length}")
        database = Room.databaseBuilder(context, ForgeDatabase::class.java, DATABASE_NAME).build()
        bindRepositories()
        val incoming = fixture()
        val liveDiary = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10000) { foods.observeDiary(DAY).first { it.map { row -> row.id } == listOf(901L, 902L) } }
        }
        restore(incoming)
        assertEquals(listOf(901L, 902L), liveDiary.await().map { it.id })
        assertEquals(incoming, snapshot())
        restore(incoming)
        assertEquals(incoming, snapshot())
        assertNull(foods.food("catalog:missing"))
        assertEquals(0.0, foods.diaryEntry(901)!!.fat!!, 0.0)
        assertNull(foods.diaryEntry(901)!!.protein)
        val portable = BackupCodec.decode(BackupCodec.encode(snapshot()))
        assertEquals(incoming, portable)
        database.close()
        database = Room.databaseBuilder(context, ForgeDatabase::class.java, DATABASE_NAME).build()
        bindRepositories()
        assertEquals(incoming, snapshot())
        assertEquals(listOf(701L), foods.recipe("recipe:bowl")!!.ingredients.map { it.id })
        val afterId = foods.saveDiary(foods.diaryEntry(901)!!.copy(id = 0, loggedAt = 9))
        assertTrue(afterId > 902)
        val afterWeight = foods.saveBodyWeight(BodyWeightEntity(day = "2026-10-14", kg = 81.0))
        assertTrue(afterWeight > 801)
        assertForeignKeys()
    }

    @Test fun `installed reference packs remain untouched internal rowid collisions regenerate and stable reference ID collisions reject before deletion`() = runBlocking<Unit> {
        val reference = installReference()
        val packBefore = foods.observeCatalogPacks().first()
        val incoming = fixture()
        val nutrition = incoming.nutrition!!
        val rowCollision = incoming.copy(nutrition = nutrition.copy(foods = nutrition.foods.map {
            it.copy(food = it.food.copy(rowId = reference.food.rowId))
        }))
        restore(rowCollision)
        assertEquals(incoming, snapshot())
        assertEquals(reference, foods.food(reference.food.id))
        assertEquals(packBefore, foods.observeCatalogPacks().first())
        assertNotEquals(reference.food.rowId, foods.food("custom:soup")!!.food.rowId)
        assertTrue(foods.searchFoods("Personal soup").isEmpty())
        assertEquals(listOf("custom:soup"), ftsIds("\"Personal*\" \"soup*\""))
        val personal = nutrition.foods.first()
        val clash = personal.copy(food = personal.food.copy(id = reference.food.id),
            nutrients = personal.nutrients.map { it.copy(foodId = reference.food.id) },
            portions = personal.portions.map { it.copy(foodId = reference.food.id) })
        val before = snapshot()
        var callbackRan = false
        expectInvalid { backups.restore(incoming.copy(nutrition = nutrition.copy(foods = listOf(clash) + nutrition.foods.drop(1)))) { callbackRan = true } }
        assertFalse(callbackRan)
        assertEquals(before, snapshot())
        assertEquals(reference, foods.food(reference.food.id))
        assertEquals(packBefore, foods.observeCatalogPacks().first())
        assertForeignKeys()
    }

    @Test fun `preference callback failure after every inserted table rolls all personal nutrition and workout rows back`() = runBlocking<Unit> {
        val reference = installReference()
        restore(fixture())
        val before = snapshot()
        val changed = changed(before)
        var callbackRan = false
        try {
            backups.restore(changed) {
                callbackRan = true
                assertEquals(changed, snapshot())
                throw IOException("Injected preference failure")
            }
            throw AssertionError("Expected callback failure")
        } catch (error: IOException) { assertEquals("Injected preference failure", error.message) }
        assertTrue(callbackRan)
        assertEquals(before, snapshot())
        assertEquals(reference, foods.food(reference.food.id))
        assertForeignKeys()
    }

    @Test fun `SQL abort after personal food insertion restores previous recipes diary weights goals favorites energy and FTS`() = runBlocking<Unit> {
        val reference = installReference()
        restore(fixture())
        val before = snapshot()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_nutrition_restore BEFORE INSERT ON nutrition_recipe_ingredients WHEN NEW.id = 701 BEGIN SELECT RAISE(ABORT, 'Injected nutrition import failure'); END")
        var callbackRan = false
        try {
            backups.restore(changed(before)) { callbackRan = true }
            throw AssertionError("Expected SQL failure")
        } catch (_: SQLiteConstraintException) { }
        assertFalse(callbackRan)
        assertEquals(before, snapshot())
        assertEquals(reference, foods.food(reference.food.id))
        assertTrue(foods.searchFoods("Personal soup").isEmpty())
        assertEquals(listOf("custom:soup"), ftsIds("\"Personal*\" \"soup*\""))
        assertTrue(ftsIds("\"Replacement*\"").isEmpty())
        assertForeignKeys()
    }

    @Test fun `malformed snapshots duplicate IDs wrong recipe types and missing foreign parents never alter stored data`() = runBlocking<Unit> {
        val incoming = fixture()
        restore(incoming)
        val original = snapshot()
        val n = incoming.nutrition!!
        val malformed = listOf(
            n.copy(diary = n.diary + n.diary.first()),
            n.copy(foods = n.foods.filter { it.food.sourceType != FoodSourceType.RECIPE }),
            n.copy(recipes = emptyList()),
            n.copy(bodyWeights = n.bodyWeights.map { it.copy(id = 0) }),
            n.copy(bodyWeights = n.bodyWeights + n.bodyWeights.single().copy(id = 802)),
            n.copy(workoutEnergy = n.workoutEnergy.map { it.copy(bodyWeightId = 999) }),
            n.copy(workoutEnergy = n.workoutEnergy.map { it.copy(workoutId = 999) }),
            n.copy(workoutEnergy = n.workoutEnergy.map { it.copy(workoutId = 202) }),
            n.copy(diary = n.diary.map { it.copy(protein = 0.0) }),
            n.copy(diary = n.diary.map { it.copy(kcal = Double.NaN) }),
            n.copy(diary = n.diary.map { it.copy(snapshot = it.snapshot.copy(name = " ")) }),
            n.copy(foods = n.foods.map { it.copy(food = it.food.copy(sourceType = FoodSourceType.REFERENCE)) }),
            n.copy(recipes = n.recipes.map { it.copy(ingredients = it.ingredients.map { row -> row.copy(recipeId = "wrong") }) }),
        )
        for (invalid in malformed) {
            expectInvalid { backups.restore(incoming.copy(nutrition = invalid)) }
            assertEquals(original, snapshot())
        }
        assertForeignKeys()
    }

    @Test fun `legacy workout only restore retains independent nutrition records and removes old workout energy before regeneration`() = runBlocking<Unit> {
        val incoming = fixture()
        restore(incoming)
        val personalRowId = foods.food("custom:soup")!!.food.rowId
        val oldNutrition = snapshot().nutrition!!
        val legacy = incoming.copy(nutrition = null, workouts = incoming.workouts.map { row ->
            if (row.id == 201L) row.copy(startTime = start + 86_400_000, endTime = start + 90_000_000, name = "Legacy restored") else row
        })
        backups.restore(legacy)
        assertEquals(oldNutrition.copy(workoutEnergy = emptyList()), snapshot().nutrition)
        assertEquals(personalRowId, foods.food("custom:soup")!!.food.rowId)
        assertEquals(legacy.workouts, snapshot().workouts)
        val rebuilt = foods.refreshWorkoutEstimate(201, ZoneId.of("UTC"))!!
        assertNull(rebuilt.manualKcal)
        assertEquals(oldNutrition.settings.defaultActivity, rebuilt.activity)
        assertEquals(oldNutrition.settings.defaultActiveFraction, rebuilt.activeFraction, 0.0)
        assertForeignKeys()
    }

    @Test fun `restore freezes caller owned food ingredient and diary nutrient collections before transaction suspension`() = runBlocking<Unit> {
        val incoming = fixture()
        val n = incoming.nutrition!!
        val foodNutrients = n.foods.first().nutrients.toMutableList()
        val ingredientNutrients = n.recipes.single().ingredients.single().snapshot.nutrients.toMutableList()
        val diaryNutrients = n.diary.first().snapshot.nutrients.toMutableList()
        val ingredients = n.recipes.single().ingredients.map { it.copy(snapshot = it.snapshot.copy(nutrients = ingredientNutrients)) }.toMutableList()
        val personalFoods = n.foods.mapIndexed { index, food -> if (index == 0) food.copy(nutrients = foodNutrients) else food }.toMutableList()
        val diary = n.diary.mapIndexed { index, row -> if (index == 0) row.copy(snapshot = row.snapshot.copy(nutrients = diaryNutrients)) else row }.toMutableList()
        val mutable = incoming.copy(nutrition = n.copy(foods = personalFoods,
            recipes = listOf(n.recipes.single().copy(ingredients = ingredients)), diary = diary))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.Default) { database.withTransaction { entered.complete(Unit); release.await() } }
        entered.await()
        val restore = async(start = CoroutineStart.UNDISPATCHED) { backups.restore(mutable) { preferences = incoming.preferences } }
        assertFalse(restore.isCompleted)
        foodNutrients.clear(); ingredientNutrients.clear(); diaryNutrients.clear(); ingredients.clear(); personalFoods.clear(); diary.clear()
        release.complete(Unit)
        blocker.join()
        withTimeout(10000) { restore.await() }
        assertEquals(incoming, snapshot())
        assertForeignKeys()
    }

    private fun bindRepositories() {
        backups = RoomBackupRepository(database) { preferences }
        foods = RoomNutritionRepository(database)
    }
    private suspend fun restore(backup: ForgeBackup) = backups.restore(backup) { preferences = backup.preferences }
    private suspend fun snapshot() = backups.snapshot().copy(createdAt = 10000)
    private fun fixture(): ForgeBackup {
        val missing = FoodSnapshot(foodId = "catalog:missing", name = "Original beans — राजमा", brand = "Old source",
            source = "USDA", sourceId = "1234", sourceUrl = "https://fdc.nal.usda.gov/food-details/1234/nutrients",
            sourceType = FoodSourceType.REFERENCE, gramsPerBase = 100.0,
            nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 100.0),
                NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", null), NutrientValue(NutrientCodes.CARBS, "Carbs", "g", 20.0),
                NutrientValue(NutrientCodes.FAT, "Fat", "g", 0.0), NutrientValue("usda:401", "Vitamin C", "mg", null)))
        val custom = FoodDetail(FoodEntity(id = "custom:soup", name = "Personal soup", brand = "Home", notes = "Frozen source notes\nहिन्दी",
            aliases = "family soup", region = "IN", tags = "home", archived = true, gramsPerBase = 100.0),
            missing.nutrients.map { FoodNutrientEntity("custom:soup", it.code, it.name, it.unit, it.value) }.sortedBy { it.code },
            listOf(FoodPortionEntity("custom:soup", "bowl", "Saved bowl", 150.0, grams = 150.0)))
        val calculated = RecipeFactory.validate(RecipeDraft(id = "recipe:bowl", name = "Personal bowl", servings = "2", cookedWeightGrams = "300",
            ingredients = listOf(RecipeIngredientDraft(key = "row", snapshot = missing,
                choice = NutritionMath.choices(missing).single(), quantity = "150"))))
        check(calculated.invalidFields.isEmpty())
        val recipeFood = calculated.food!!.let { it.copy(nutrients = it.nutrients.sortedBy { row -> row.code }) }
        val recipe = RecipeDetail(calculated.recipe!!, calculated.ingredients.map { it.copy(id = 701) })
        fun row(id: Long, snapshot: FoodSnapshot, quantity: Double, unit: FoodUnit, factor: Double) =
            DiaryEntity(id, DAY, MealType.LUNCH, snapshot.foodId, snapshot, quantity, unit, factor = factor, loggedAt = id,
                kcal = snapshot.nutrients.single { it.code == NutrientCodes.ENERGY }.value?.times(factor),
                protein = snapshot.nutrients.single { it.code == NutrientCodes.PROTEIN }.value?.times(factor),
                carbs = snapshot.nutrients.single { it.code == NutrientCodes.CARBS }.value?.times(factor),
                fat = snapshot.nutrients.single { it.code == NutrientCodes.FAT }.value?.times(factor))
        val nutrition = NutritionBackup(foods = listOf(custom, recipeFood), recipes = listOf(recipe),
            diary = listOf(row(901, missing, 85.0, FoodUnit.GRAM, .85), row(902, recipeFood.snapshot(), 2.0, FoodUnit.SERVING, 2.0)),
            goals = listOf(NutritionGoalEntity("2026-10-01", 2000.0, 120.0), NutritionGoalEntity(DAY, kcal = 0.0)),
            bodyWeights = listOf(BodyWeightEntity(801, "2026-10-12", 80.0, 1)),
            favorites = listOf(FavoriteEntity("catalog:missing", "Original beans", 1), FavoriteEntity("custom:soup", "Personal soup", 2)),
            settings = NutritionSettingsEntity(includeWorkoutCalories = true, defaultActivity = WorkoutActivityPreset.BODYWEIGHT.id, defaultActiveFraction = .25),
            workoutEnergy = listOf(WorkoutEnergyEntity(201, DAY, start, start + 3_600_000, 3600,
                bodyWeightId = 801, bodyWeightKg = 80.0, met = 3.5, activity = WorkoutActivityPreset.RESISTANCE.id,
                intensity = WorkoutIntensity.MODERATE, activeFraction = 0.0, calculatedKcal = 0.0, manualKcal = 0.0)))
        val core = completeBackup()
        return core.copy(workouts = core.workouts.map { row ->
            if (row.id == 201L) row.copy(startTime = start, endTime = start + 3_600_000)
            else row.copy(startTime = start + 7_200_000)
        }, nutrition = nutrition)
    }
    private fun changed(source: ForgeBackup): ForgeBackup {
        val n = source.nutrition!!
        return source.copy(workouts = source.workouts.map { it.copy(name = "Replacement ${it.id}") },
            nutrition = n.copy(foods = n.foods.map { food -> if (food.food.sourceType == FoodSourceType.CUSTOM)
                food.copy(food = food.food.copy(name = "Replacement soup")) else food },
                diary = n.diary.map { it.copy(meal = MealType.DINNER) }, goals = n.goals.map { it.copy(kcal = 2100.0) },
                bodyWeights = n.bodyWeights.map { it.copy(kg = 90.0) }, favorites = n.favorites.map { it.copy(name = "Replacement favorite") },
                settings = n.settings.copy(includeWorkoutCalories = false), workoutEnergy = n.workoutEnergy.map { it.copy(manualKcal = 55.0) }))
    }
    private suspend fun installReference(): FoodDetail {
        foods.upsertCatalogPack(CatalogPackEntity("pack", "Reference pack", "1", "https://example.com/pack", "CC0", "Source", installedAt = 1,
            sha256 = "f".repeat(64), foodCount = 1, payloadBytes = 100))
        foods.upsertFood(FoodDetail(FoodEntity(id = "catalog:installed", name = "Installed rice", sourceType = FoodSourceType.REFERENCE, packId = "pack"),
            listOf(FoodNutrientEntity("catalog:installed", NutrientCodes.ENERGY, "Energy", "kcal", 100.0))))
        return foods.food("catalog:installed")!!
    }
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected invalid nutrition backup") }
        catch (error: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, error.issue) }
    }
    private fun assertForeignKeys() = database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    private fun ftsIds(expression: String) = database.openHelper.writableDatabase.query(
        "SELECT f.id FROM nutrition_foods f INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid WHERE nutrition_food_search MATCH ? ORDER BY f.id",
        arrayOf(expression),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    private companion object {
        const val DAY = "2026-10-13"
        const val DATABASE_NAME = "nbackup.db"
    }
}

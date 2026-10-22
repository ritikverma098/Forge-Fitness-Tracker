package com.forge.workout.nutrition.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.SetType
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.RoomWorkoutRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Opens a real shipped SQLite schema through the production Room migration chain. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionMigrationHostTest {
    private var database: ForgeDatabase? = null

    @Before fun removeFixture() { RuntimeEnvironment.getApplication().deleteDatabase("forge.db") }
    @After fun closeFixture() {
        database?.close()
        RuntimeEnvironment.getApplication().deleteDatabase("forge.db")
    }

    @Test fun `version3 migrates with unchanged workout graph settings and valid new nutrition schema`() = runBlocking {
        createVersionThreeFixture()
        val context = RuntimeEnvironment.getApplication()
        val preferences = PreferencesRepository(context, "nutrition-upgrade-${UUID.randomUUID()}")
        val settings = UserPreferences(ThemeMode.DARK, WeightUnit.LB, 120, false)
        preferences.replace(settings)
        val migrated = ForgeDatabase.create(context)
        database = migrated
        // Opening forces Room's generated validation of all entity columns, indices and foreign keys.
        assertEquals(4, migrated.openHelper.writableDatabase.version)
        val workouts = RoomWorkoutRepository(migrated)
        workouts.ensureSeeded()
        val original = migrated.workoutDao().workoutDetail(21)!!
        val set = original.exercises.single().sets.single()
        assertEquals("Historical strength", original.workout.name)
        assertEquals("Keep workout notes", original.workout.notes)
        assertEquals(1_000L, original.workout.startTime)
        assertEquals(5_000L, original.workout.endTime)
        assertEquals("Paused press", original.exercises.single().exercise.name)
        assertEquals("Keep entry notes", original.exercises.single().entry.notes)
        assertEquals(72.5, set.weight, 0.000001)
        assertEquals(9, set.reps)
        assertTrue(set.completed)
        assertEquals(SetType.DROP_SET, set.setType)
        assertEquals(11L, migrated.workoutDao().routineDetail(51)!!.exercises.single().exercise.id)
        assertEquals("Keep metadata", migrated.workoutDao().metadata("user_fixture"))
        assertEquals(1, workouts.observeExercises().first().size)
        assertEquals(settings, preferences.preferences.first())
        assertCurrentSchema(migrated)
        val nutrition = RoomNutritionRepository(migrated)
        val foodId = nutrition.upsertFood(FoodDetail(FoodEntity(id = "upgrade-food", name = "Upgrade oats", aliases = "porridge"),
            listOf(FoodNutrientEntity("upgrade-food", NutrientCodes.ENERGY, "Energy", "kcal", 120.0))))
        assertEquals(listOf(foodId), nutrition.searchFoods("porridge").map { it.id })
        val currentFood = nutrition.food(foodId)!!
        nutrition.upsertFood(currentFood.copy(food = currentFood.food.copy(name = "Upgraded grain", aliases = "cereal")))
        assertTrue(nutrition.searchFoods("porridge").isEmpty())
        assertEquals(listOf(foodId), nutrition.searchFoods("cereal").map { it.id })
        val diaryId = nutrition.saveDiary(DiaryEntity(day = "2026-10-12", meal = MealType.BREAKFAST,
            foodId = foodId, snapshot = nutrition.food(foodId)!!.snapshot(), quantity = 50.0,
            unit = FoodUnit.GRAM, factor = 0.5, loggedAt = 1000))
        val diary = nutrition.diaryEntry(diaryId)!!
        val bodyId = nutrition.saveBodyWeight(BodyWeightEntity(day = "2026-10-12", kg = 80.0, measuredAt = 1000))
        val goal = NutritionGoalEntity("2026-10-12", kcal = 2200.0, protein = 120.0)
        nutrition.saveGoal(goal)
        migrated.close()

        val reopened = ForgeDatabase.create(context)
        database = reopened
        assertEquals(original, reopened.workoutDao().workoutDetail(21))
        assertEquals(settings, preferences.preferences.first())
        val reopenedNutrition = RoomNutritionRepository(reopened)
        assertEquals(diary, reopenedNutrition.diaryEntry(diaryId))
        assertEquals(bodyId, reopenedNutrition.bodyWeight("2026-10-12")!!.id)
        assertEquals(goal, reopenedNutrition.observeGoal("2026-10-12").first())
        assertEquals(listOf(foodId), reopenedNutrition.searchFoods("cereal").map { it.id })
        assertCurrentSchema(reopened)
    }

    private fun assertCurrentSchema(database: ForgeDatabase) {
        val oldTables = tables(schema(3)).toSet()
        val current = schema(4)
        val newTables = tables(current).filterNot { it in oldTables }
        assertTrue("Schema 4 must add nutrition storage", newTables.isNotEmpty())
        database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            val actual = mutableSetOf<String>()
            while (cursor.moveToNext()) actual += cursor.getString(0)
            assertTrue(actual.containsAll(newTables))
        }
        val entities = current.getJSONArray("entities")
        assertTrue("Food search must have its declared virtual FTS table",
            (0 until entities.length()).any { entities.getJSONObject(it).getString("createSql").contains("VIRTUAL TABLE", true) })
        database.openHelper.writableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            assertTrue(it.moveToFirst())
            assertEquals(current.getString("identityHash"), it.getString(0))
        }
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun createVersionThreeFixture() {
        val legacy = schema(3)
        val path = RuntimeEnvironment.getApplication().getDatabasePath("forge.db")
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            sqlite.execSQL("PRAGMA foreign_keys = ON")
            val entities = legacy.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (number in 0 until (indices?.length() ?: 0)) {
                    sqlite.execSQL(indices!!.getJSONObject(number).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = legacy.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
            sqlite.execSQL("INSERT INTO exercises VALUES (11, 'Paused press', 'CHEST', 'TRICEPS', 'WEIGHT_REPS', 'Keep exercise notes', 1, 0)")
            sqlite.execSQL("INSERT INTO workouts VALUES (21, 'Historical strength', 1000, 5000, 'Keep workout notes', NULL, 'CHEST,TRICEPS')")
            sqlite.execSQL("INSERT INTO workout_exercises VALUES (31, 21, 11, 0, 'Keep entry notes')")
            sqlite.execSQL("INSERT INTO workout_sets VALUES (41, 31, 1, 72.5, 9, 0, 0.0, 1, 'DROP_SET')")
            sqlite.execSQL("INSERT INTO routines VALUES (51, 'Upper plan', 500)")
            sqlite.execSQL("INSERT INTO routine_exercises VALUES (61, 51, 11, 0)")
            sqlite.execSQL("INSERT INTO app_metadata VALUES ('default_exercises_v1', 'seeded')")
            sqlite.execSQL("INSERT INTO app_metadata VALUES ('user_fixture', 'Keep metadata')")
            sqlite.version = 3
        }
    }

    private fun tables(schema: JSONObject): List<String> {
        val entities = schema.getJSONArray("entities")
        return (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
    }

    private fun schema(version: Int): JSONObject {
        val relative = "schemas/com.forge.workout.data.database.ForgeDatabase/$version.json"
        val file = listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
        return JSONObject(requireNotNull(file) { "Missing exported schema $version" }.readText())
            .getJSONObject("database").also { assertEquals(version, it.getInt("version")) }
    }
}

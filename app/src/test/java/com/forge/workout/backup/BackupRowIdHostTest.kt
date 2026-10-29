package com.forge.workout.backup

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.*
import com.forge.workout.nutrition.model.*
import com.forge.workout.preferences.UserPreferences
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupRowIdHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var backups: RoomBackupRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        backups = RoomBackupRepository(database)
    }

    @After fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(DATABASE_NAME)
    }

    @Test fun `supported boundary IDs preserve every relationship through portable serialization`() {
        for (field in ID_FIELDS) {
            val source = withId(fixture(), field, BackupValidation.MAX_ROW_ID)
            assertEquals(field, source, BackupCodec.decode(BackupCodec.encode(source)))
        }
    }

    @Test fun `extreme IDs reject encode decode and direct restore before changing any rows or sequence`() = runBlocking {
        val original = fixture()
        backups.restore(original)
        val before = snapshot()
        val sequences = sequences()
        val invalid = listOf(0L, -1L, BackupValidation.MAX_ROW_ID + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        for (field in ID_FIELDS) {
            val portable = BackupCodec.encode(withId(original, field, BackupValidation.MAX_ROW_ID))
            for (id in invalid) {
                val incoming = withId(original, field, id)
                expectInvalid { BackupCodec.encode(incoming) }
                expectInvalid { BackupCodec.decode(portable.replace(BackupValidation.MAX_ROW_ID.toString(), id.toString())) }
                var callbackRan = false
                expectInvalid { backups.restore(incoming) { callbackRan = true } }
                assertFalse("$field $id", callbackRan)
                assertEquals("$field $id", before, snapshot())
                assertEquals("$field $id", sequences, sequences())
            }
        }
    }

    @Test fun `valid restore recovers actual maximum ID rows and allocation after persistent reopen without touching reference foods`() = runBlocking {
        database.close()
        openPersistent()
        val reference = FoodEntity(rowId = 2500, id = "catalog:retained", name = "Retained reference", sourceType = FoodSourceType.REFERENCE)
        database.nutritionDao().insertFood(reference)
        val imported = fixture()
        backups.restore(imported)
        insertMaximumRows(imported)
        assertTrue(sequences().values.all { it == Long.MAX_VALUE })
        backups.restore(imported)
        database.close()
        openPersistent()
        assertEquals(imported.copy(createdAt = 1, preferences = UserPreferences()), snapshot())
        assertEquals(reference, database.nutritionDao().foodRow(reference.id))
        assertAllAllocations()
        assertForeignKeys()
    }

    @Test fun `empty restore also clears exhausted high water marks for future new records`() = runBlocking {
        backups.restore(fixture())
        poisonSequences()
        backups.restore(ForgeBackup(nutrition = NutritionBackup()))
        assertEquals(TABLES.associateWith { 0L }, sequences())
        assertAllAllocations()
        assertForeignKeys()
    }

    @Test fun `legacy workout only restore leaves independent nutrition rows and sequences unchanged`() = runBlocking {
        val incoming = fixture()
        backups.restore(incoming)
        poisonSequences(NUTRITION_TABLES)
        val before = snapshot().nutrition!!.copy(workoutEnergy = emptyList())
        val rowIds = database.nutritionDao().backupPersonalFoods().map { it.food.rowId }
        val priorSequences = sequences().filterKeys { it in NUTRITION_TABLES }
        backups.restore(incoming.copy(nutrition = null))
        assertEquals(before, snapshot().nutrition)
        assertEquals(rowIds, database.nutritionDao().backupPersonalFoods().map { it.food.rowId })
        assertEquals(priorSequences, sequences().filterKeys { it in NUTRITION_TABLES })
        assertForeignKeys()
    }

    @Test fun `callback and SQL failures roll back sequence repairs together with stored rows`() = runBlocking {
        val original = fixture()
        for (sqlFailure in listOf(false, true)) {
            backups.restore(original)
            poisonSequences()
            val before = snapshot()
            val priorSequences = sequences()
            if (sqlFailure) database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_row_id_restore BEFORE INSERT ON workout_sets WHEN NEW.id = 401 BEGIN SELECT RAISE(ABORT, 'Rejected import'); END")
            try {
                backups.restore(original.copy(workouts = original.workouts.map { it.copy(name = "Replacement ${it.id}") })) {
                    if (!sqlFailure) throw IOException("Rejected callback")
                }
                fail("Expected restore failure")
            } catch (failure: IOException) {
                assertFalse(sqlFailure)
            } catch (failure: SQLiteConstraintException) {
                assertTrue(sqlFailure)
            } finally {
                if (sqlFailure) database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_row_id_restore")
            }
            assertEquals(before, snapshot())
            assertEquals(priorSequences, sequences())
            assertForeignKeys()
        }
        backups.restore(original)
        assertAllAllocations()
    }

    private suspend fun assertAllAllocations() {
        val maxima = maxima()
        val dao = database.workoutDao()
        val foodDao = database.nutritionDao()
        val exercise = dao.insertExercise(Exercise(name = "After restored IDs", primaryMuscleGroup = MuscleGroup.CORE))
        val workout = dao.insertWorkout(Workout(name = "After restored IDs", startTime = 1, endTime = 2))
        val routine = dao.insertRoutine(Routine(name = "After restored IDs"))
        val entry = dao.insertWorkoutExercise(WorkoutExercise(workoutId = workout, exerciseId = exercise, orderIndex = 0))
        val set = dao.insertSet(WorkoutSet(workoutExerciseId = entry, setNumber = 1, reps = 10))
        val routineEntry = dao.insertRoutineExercise(RoutineExercise(routineId = routine, exerciseId = exercise, orderIndex = 0))
        val food = foodDao.insertFood(FoodEntity(id = "recipe:allocated", name = "Allocated recipe", sourceType = FoodSourceType.RECIPE))
        foodDao.upsertRecipe(RecipeEntity("recipe:allocated", "Allocated recipe", 1.0))
        val snapshot = FoodSnapshot(name = "Ingredient", gramsPerBase = 100.0)
        foodDao.insertIngredients(listOf(RecipeIngredientEntity(recipeId = "recipe:allocated", orderIndex = 0,
            snapshot = snapshot, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0)))
        val ingredient = foodDao.recipe("recipe:allocated")!!.ingredients.single().id
        val diary = foodDao.insertDiary(DiaryEntity(day = "2026-11-01", meal = MealType.SNACK,
            snapshot = snapshot, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0))
        val weight = foodDao.insertBodyWeight(BodyWeightEntity(day = "2026-11-01", kg = 80.0))
        val allocated = mapOf("exercises" to exercise, "workouts" to workout, "workout_exercises" to entry, "workout_sets" to set,
            "routines" to routine, "routine_exercises" to routineEntry, "nutrition_foods" to food,
            "nutrition_diary" to diary, "nutrition_body_weights" to weight, "nutrition_recipe_ingredients" to ingredient)
        assertEquals(maxima.mapValues { it.value + 1 }, allocated)
    }

    private suspend fun insertMaximumRows(source: ForgeBackup) {
        val dao = database.workoutDao()
        dao.insertExercise(source.exercises.first().copy(id = Long.MAX_VALUE, name = "Old extreme ID"))
        dao.insertWorkout(source.workouts.first().copy(id = Long.MAX_VALUE))
        dao.insertWorkoutExercise(WorkoutExercise(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0))
        dao.insertSet(WorkoutSet(Long.MAX_VALUE, Long.MAX_VALUE, 1))
        dao.insertRoutine(Routine(Long.MAX_VALUE, "Old extreme ID"))
        dao.insertRoutineExercise(RoutineExercise(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0))
        val n = source.nutrition!!
        val foods = database.nutritionDao()
        foods.insertFood(FoodEntity(rowId = Long.MAX_VALUE, id = "custom:old-extreme", name = "Old extreme ID"))
        foods.insertDiary(n.diary.single().copy(id = Long.MAX_VALUE))
        foods.insertBodyWeight(n.bodyWeights.single().copy(id = Long.MAX_VALUE, day = "2026-10-29"))
        foods.insertIngredients(listOf(n.recipes.single().ingredients.single().copy(id = Long.MAX_VALUE, orderIndex = 1)))
        assertForeignKeys()
    }

    private fun poisonSequences(tables: List<String> = TABLES) {
        for (table in tables) database.openHelper.writableDatabase.execSQL(
            "UPDATE sqlite_sequence SET seq = ? WHERE name = ?", arrayOf<Any>(Long.MAX_VALUE, table))
    }

    private fun sequences(): Map<String, Long> = database.openHelper.writableDatabase.query(
        "SELECT name, seq FROM sqlite_sequence").use { cursor -> buildMap {
            while (cursor.moveToNext()) if (cursor.getString(0) in TABLES) put(cursor.getString(0), cursor.getLong(1))
        } }

    private fun maxima(): Map<String, Long> = TABLES.associateWith { table ->
        database.openHelper.writableDatabase.query("SELECT COALESCE(MAX(rowid), 0) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }
    }

    private suspend fun snapshot() = backups.snapshot().copy(createdAt = 1)
    private fun assertForeignKeys() = database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

    private fun openPersistent() {
        val context = RuntimeEnvironment.getApplication()
        val parent = context.getDatabasePath(DATABASE_NAME).parentFile!!
        check(parent.isDirectory || parent.mkdirs())
        database = Room.databaseBuilder(context, ForgeDatabase::class.java, DATABASE_NAME).build()
        backups = RoomBackupRepository(database)
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); fail("Expected unsupported row ID rejection") }
        catch (error: BackupValidationException) { assertEquals(BackupIssue.INVALID_DATA, error.issue) }
    }

    private fun fixture(): ForgeBackup {
        val food = FoodDetail(FoodEntity(id = "custom:ingredient", name = "Ingredient", gramsPerBase = 100.0))
        val recipeFood = FoodDetail(FoodEntity(id = "recipe:original", name = "Recipe", sourceType = FoodSourceType.RECIPE,
            baseAmount = 1.0, baseUnit = FoodUnit.SERVING))
        val nutrition = NutritionBackup(foods = listOf(food, recipeFood),
            recipes = listOf(RecipeDetail(RecipeEntity(recipeFood.food.id, "Recipe", 1.0),
                listOf(RecipeIngredientEntity(701, recipeFood.food.id, 0, food.food.id, food.snapshot(), 100.0, FoodUnit.GRAM, factor = 1.0)))),
            diary = listOf(DiaryEntity(901, "2026-10-28", MealType.LUNCH, food.food.id, food.snapshot(), 100.0, FoodUnit.GRAM, factor = 1.0, loggedAt = 1)),
            bodyWeights = listOf(BodyWeightEntity(801, "2026-10-28", 80.0, 1)),
            workoutEnergy = listOf(WorkoutEnergyEntity(201, "1970-01-01", 1000, 2000, 1, 801, 80.0, 3.5,
                WorkoutActivityPreset.RESISTANCE.id, WorkoutIntensity.MODERATE, calculatedKcal = 0.0)))
        return completeBackup().copy(nutrition = nutrition, preferences = com.forge.workout.preferences.UserPreferences())
    }

    private fun withId(source: ForgeBackup, field: String, id: Long): ForgeBackup = when (field) {
        "exercise" -> source.copy(exercises = source.exercises.map { if (it.id == 101L) it.copy(id = id) else it },
            workoutExercises = source.workoutExercises.map { if (it.exerciseId == 101L) it.copy(exerciseId = id) else it },
            routineExercises = source.routineExercises.map { if (it.exerciseId == 101L) it.copy(exerciseId = id) else it })
        "workout" -> source.copy(workouts = source.workouts.map { if (it.id == 201L) it.copy(id = id) else it },
            workoutExercises = source.workoutExercises.map { if (it.workoutId == 201L) it.copy(workoutId = id) else it },
            nutrition = source.nutrition!!.copy(workoutEnergy = source.nutrition.workoutEnergy.map { it.copy(workoutId = id) }))
        "workoutExercise" -> source.copy(workoutExercises = source.workoutExercises.map { if (it.id == 301L) it.copy(id = id) else it },
            sets = source.sets.map { if (it.workoutExerciseId == 301L) it.copy(workoutExerciseId = id) else it })
        "set" -> source.copy(sets = source.sets.map { if (it.id == 401L) it.copy(id = id) else it })
        "routine" -> source.copy(routines = source.routines.map { it.copy(id = id) },
            routineExercises = source.routineExercises.map { it.copy(routineId = id) })
        "routineExercise" -> source.copy(routineExercises = source.routineExercises.map { if (it.id == 601L) it.copy(id = id) else it })
        "diary" -> source.copy(nutrition = source.nutrition!!.copy(diary = source.nutrition.diary.map { it.copy(id = id) }))
        "bodyWeight" -> source.copy(nutrition = source.nutrition!!.copy(bodyWeights = source.nutrition.bodyWeights.map { it.copy(id = id) },
            workoutEnergy = source.nutrition.workoutEnergy.map { it.copy(bodyWeightId = id) }))
        "ingredient" -> source.copy(nutrition = source.nutrition!!.copy(recipes = source.nutrition.recipes.map {
            it.copy(ingredients = it.ingredients.map { row -> row.copy(id = id) }) }))
        else -> error("Unknown test ID field: $field")
    }

    private companion object {
        const val DATABASE_NAME = "row_ids.db"
        val ID_FIELDS = listOf("exercise", "workout", "workoutExercise", "set", "routine", "routineExercise", "diary", "bodyWeight", "ingredient")
        val CORE_TABLES = listOf("exercises", "workouts", "workout_exercises", "workout_sets", "routines", "routine_exercises")
        val NUTRITION_TABLES = listOf("nutrition_diary", "nutrition_body_weights", "nutrition_recipe_ingredients", "nutrition_foods")
        val TABLES = CORE_TABLES + NUTRITION_TABLES
    }
}

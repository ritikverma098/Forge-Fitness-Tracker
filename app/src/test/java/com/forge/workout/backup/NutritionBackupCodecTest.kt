package com.forge.workout.backup

import android.app.Application
import com.forge.workout.model.SetType
import com.forge.workout.nutrition.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionBackupCodecTest {
    @Test fun `format3 preserves the complete personal nutrition graph captured source units unknowns and energy alongside workouts`() {
        val original = fixture()
        val source = BackupCodec.encode(original)
        val json = JSONObject(source)
        assertEquals(3, json.getInt("formatVersion"))
        assertEquals(4, json.getInt("databaseVersion"))
        val nutrition = json.getJSONObject("nutrition")
        assertEquals(3, nutrition.getJSONArray("snapshots").length())
        assertEquals(2, nutrition.getJSONArray("foods").length())
        for (index in 0 until nutrition.getJSONArray("foods").length()) {
            assertFalse(nutrition.getJSONArray("foods").getJSONObject(index).getJSONObject("food").has("rowId"))
        }
        val restored = BackupCodec.decode(source)
        assertEquals(original, restored)
        assertTrue(restored.nutrition!!.foods.none { it.food.sourceType == FoodSourceType.REFERENCE })
        val historical = restored.nutrition!!.diary.first()
        assertEquals("usda:missing", historical.snapshot.foodId)
        assertEquals("USDA reference pack", historical.snapshot.source)
        assertEquals(FoodSourceType.REFERENCE, historical.snapshot.sourceType)
        assertNull(historical.snapshot.nutrients.single { it.code == "fiber" }.value)
        assertEquals("mg", historical.snapshot.nutrients.single { it.code == "iron" }.unit)
        assertEquals(FoodUnit.MILLILITER, restored.nutrition!!.foods.first().food.baseUnit)
        assertEquals(200.0, restored.nutrition!!.foods.first().food.gramsPerBase!!, 0.0)
        assertEquals(0.0, restored.nutrition!!.workoutEnergy.single().manualKcal!!, 0.0)
        assertEquals(80.5, restored.nutrition!!.workoutEnergy.single().bodyWeightKg, 0.0)
        assertEquals("legacy:strength", restored.nutrition!!.workoutEnergy.single().activity)
        assertEquals(2.4, restored.nutrition!!.workoutEnergy.single().calculatedKcal, 0.0)
        assertEquals(0.0, restored.nutrition!!.goals.single().protein!!, 0.0)
        assertNull(restored.nutrition!!.goals.single().carbs)
        assertEquals("legacy-custom", restored.nutrition!!.foods.first().food.id)
    }

    @Test fun `current null scope differs from full empty nutrition and both older formats retain workout only scope`() {
        val original = fixture()
        val workoutOnly = original.copy(nutrition = null)
        val nullJson = JSONObject(BackupCodec.encode(workoutOnly))
        assertTrue(nullJson.has("nutrition"))
        assertTrue(nullJson.isNull("nutrition"))
        assertEquals(workoutOnly, BackupCodec.decode(nullJson.toString()))
        val empty = original.copy(nutrition = NutritionBackup())
        assertFalse(JSONObject(BackupCodec.encode(empty)).isNull("nutrition"))
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
        for ((format, database) in listOf(1 to 2, 2 to 3)) {
            val legacy = JSONObject(BackupCodec.encode(original)).put("formatVersion", format).put("databaseVersion", database)
            legacy.remove("nutrition")
            if (format == 1) for (index in 0 until legacy.getJSONArray("sets").length()) legacy.getJSONArray("sets").getJSONObject(index).remove("setType")
            val decoded = BackupCodec.decode(legacy.toString())
            assertNull(decoded.nutrition)
            assertEquals(original.preferences, decoded.preferences)
            assertEquals(original.workouts, decoded.workouts)
            assertEquals(if (format == 1) original.sets.map { it.copy(setType = SetType.WORKING) } else original.sets, decoded.sets)
            legacy.put("nutrition", JSONObject.NULL)
            reject { BackupCodec.decode(legacy.toString()) }
        }
    }

    @Test fun `shared dense food snapshots keep thousands of absent pack diary rows portable within the file bound`() {
        val common = reference().copy(name = "Snapshot marker 🍎", nutrients = reference().nutrients + List(100) { index ->
            NutrientValue("trace:$index", "Measured nutrient $index " + "x".repeat(150), "mg", if (index % 3 == 0) null else index.toDouble())
        })
        val entries = List(3000) { index -> cached(DiaryEntity(id = index + 1L, day = "2026-10-14", meal = MealType.SNACK,
            foodId = common.foodId, snapshot = common, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = index.toLong())) }
        val original = ForgeBackup(createdAt = 0, nutrition = NutritionBackup(diary = entries))
        val source = BackupCodec.encode(original)
        assertTrue(source.toByteArray(Charsets.UTF_8).size < 2 * 1024 * 1024)
        assertEquals(1, source.split("Snapshot marker").size - 1)
        assertEquals(1, JSONObject(source).getJSONObject("nutrition").getJSONArray("snapshots").length())
        val restored = BackupCodec.decode(source)
        assertEquals(original, restored)
        assertSame(restored.nutrition!!.diary.first().snapshot, restored.nutrition!!.diary.last().snapshot)
        assertNull(restored.nutrition!!.diary.last().snapshot.nutrients.single { it.code == "trace:99" }.value)
    }

    @Test fun `nutrition and snapshot shapes strict numeric types nullable measurements and enum values cannot be coerced`() {
        val source = BackupCodec.encode(fixture())
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("bodyWeights") },
            { it.put("version", 2) },
            { it.put("version", 1.5) },
            { it.put("futureField", true) },
            { it.put("diary", JSONObject()) },
            { it.getJSONArray("foods").getJSONObject(0).getJSONObject("food").put("rowId", 1) },
            { it.getJSONArray("foods").getJSONObject(0).getJSONObject("food").put("archived", "true") },
            { it.getJSONArray("foods").getJSONObject(0).getJSONObject("food").put("baseAmount", "250") },
            { it.getJSONArray("foods").getJSONObject(0).getJSONObject("food").put("sourceType", "FUTURE") },
            { it.getJSONArray("foods").getJSONObject(0).getJSONArray("nutrients").getJSONObject(0).put("value", "140") },
            { it.getJSONArray("foods").getJSONObject(0).getJSONArray("portions").getJSONObject(0).put("baseQuantity", "250") },
            { it.getJSONArray("diary").getJSONObject(0).put("id", "41") },
            { it.getJSONArray("diary").getJSONObject(0).put("id", 41.5) },
            { it.getJSONArray("diary").getJSONObject(0).put("meal", "OTHER") },
            { it.getJSONArray("diary").getJSONObject(0).put("kcal", JSONObject.NULL) },
            { it.getJSONArray("goals").getJSONObject(0).put("kcal", "1800") },
            { it.getJSONArray("bodyWeights").getJSONObject(0).put("kg", true) },
            { it.getJSONObject("settings").put("includeWorkoutCalories", 1) },
            { it.getJSONArray("workoutEnergy").getJSONObject(0).put("bodyWeightId", "51") },
            { it.getJSONArray("workoutEnergy").getJSONObject(0).put("manualKcal", "0") },
            { it.getJSONArray("workoutEnergy").getJSONObject(0).put("intensity", "INVALID") },
            { it.getJSONArray("snapshots").getJSONObject(0).put("version", 2) },
            { it.getJSONArray("snapshots").getJSONObject(0).put("unexpected", false) },
            { it.getJSONArray("snapshots").getJSONObject(0).put("sourceUrl", "x".repeat(2049)) },
            { it.getJSONArray("snapshots").getJSONObject(0).getJSONArray("nutrients").getJSONObject(0).put("value", "89") },
            { it.getJSONArray("snapshots").getJSONObject(0).getJSONArray("nutrients").getJSONObject(0).put("unit", true) },
            { it.getJSONArray("snapshots").getJSONObject(0).getJSONArray("nutrients").getJSONObject(0).put("extra", 0) },
        )
        mutations.forEach { mutate ->
            val json = JSONObject(source)
            mutate(json.getJSONObject("nutrition"))
            reject { BackupCodec.decode(json.toString()) }
        }
    }

    @Test fun `snapshot registry rejects invalid coerced duplicate and unused references without unchecked indexing`() {
        val source = BackupCodec.encode(fixture())
        for (value in listOf(-1, 999, "0", 0.5, 3_000_000_000L, JSONObject.NULL, JSONObject())) {
            val json = JSONObject(source)
            json.getJSONObject("nutrition").getJSONArray("diary").getJSONObject(0).put("snapshot", value)
            reject { BackupCodec.decode(json.toString()) }
        }
        val duplicate = JSONObject(source)
        val duplicateRows = duplicate.getJSONObject("nutrition").getJSONArray("snapshots")
        duplicateRows.put(JSONObject(duplicateRows.getJSONObject(0).toString()))
        reject { BackupCodec.decode(duplicate.toString()) }
        val unused = JSONObject(source)
        val unusedRows = unused.getJSONObject("nutrition").getJSONArray("snapshots")
        unusedRows.put(JSONObject(unusedRows.getJSONObject(0).toString()).put("foodId", "unreferenced"))
        reject { BackupCodec.decode(unused.toString()) }
        val missing = JSONObject(source)
        missing.getJSONObject("nutrition").getJSONArray("snapshots").remove(2)
        reject { BackupCodec.decode(missing.toString()) }
        val mismatch = JSONObject(source)
        mismatch.getJSONObject("nutrition").getJSONArray("diary").getJSONObject(0).put("foodId", "wrong-food")
        reject { BackupCodec.decode(mismatch.toString()) }
    }

    @Test fun `invalid personal table identities snapshots caches recipe references and completed energy parents are rejected`() {
        val original = fixture()
        val nutrition = original.nutrition!!
        val invalid = listOf(
            nutrition.copy(foods = nutrition.foods + nutrition.foods.first()),
            nutrition.copy(foods = nutrition.foods.mapIndexed { index, detail -> if (index == 0) detail.copy(food = detail.food.copy(sourceType = FoodSourceType.REFERENCE)) else detail }),
            nutrition.copy(foods = nutrition.foods.mapIndexed { index, detail -> if (index == 0) detail.copy(food = detail.food.copy(packId = "catalog")) else detail }),
            nutrition.copy(foods = nutrition.foods.filterNot { it.food.sourceType == FoodSourceType.RECIPE }),
            nutrition.copy(recipes = emptyList()),
            nutrition.copy(recipes = nutrition.recipes.map { it.copy(ingredients = it.ingredients.map { row -> row.copy(id = 31) }) }),
            nutrition.copy(recipes = nutrition.recipes.map { it.copy(ingredients = it.ingredients.map { row -> row.copy(orderIndex = 1) }) }),
            nutrition.copy(recipes = nutrition.recipes.map { it.copy(ingredients = it.ingredients.map { row -> row.copy(foodId = "wrong") }) }),
            nutrition.copy(diary = nutrition.diary + nutrition.diary.first()),
            nutrition.copy(diary = nutrition.diary.map { it.copy(id = 0) }),
            nutrition.copy(diary = nutrition.diary.map { it.copy(quantity = -1.0) }),
            nutrition.copy(diary = nutrition.diary.map { it.copy(kcal = 0.0) }),
            nutrition.copy(diary = nutrition.diary.map { it.copy(carbs = 0.0) }),
            nutrition.copy(goals = nutrition.goals + nutrition.goals.first()),
            nutrition.copy(goals = nutrition.goals.map { it.copy(protein = Double.NaN) }),
            nutrition.copy(bodyWeights = nutrition.bodyWeights + nutrition.bodyWeights.first().copy(id = 52)),
            nutrition.copy(bodyWeights = nutrition.bodyWeights.map { it.copy(kg = 1001.0) }),
            nutrition.copy(favorites = nutrition.favorites + nutrition.favorites.first()),
            nutrition.copy(settings = nutrition.settings.copy(defaultActiveFraction = 1.5)),
            nutrition.copy(workoutEnergy = nutrition.workoutEnergy.map { it.copy(workoutId = 202) }),
            nutrition.copy(workoutEnergy = nutrition.workoutEnergy.map { it.copy(bodyWeightId = 999) }),
            nutrition.copy(workoutEnergy = nutrition.workoutEnergy.map { it.copy(endTime = 3000) }),
        )
        invalid.forEach { data -> reject { BackupCodec.encode(original.copy(nutrition = data)) } }
    }

    @Test fun `food rowids are intentionally rebuilt while stable IDs provenance and metadata remain portable`() {
        val original = fixture()
        val withInternalIds = original.copy(nutrition = original.nutrition!!.copy(foods = original.nutrition!!.foods.mapIndexed { index, row ->
            row.copy(food = row.food.copy(rowId = 9000L + index))
        }))
        assertEquals(original, BackupCodec.decode(BackupCodec.encode(withInternalIds)))
        val encoded = BackupCodec.encode(original)
        assertFalse(encoded.contains("\"rowId\""))
        val negative = original.copy(nutrition = original.nutrition!!.copy(foods = original.nutrition!!.foods.map { it.copy(food = it.food.copy(rowId = -1)) }))
        reject { BackupCodec.encode(negative) }
    }

    @Test fun `nutrition shares the existing UTF8 size depth and strict JSON safety envelope`() {
        val source = BackupCodec.encode(fixture())
        val root = JSONObject(source)
        root.put("formatVersion", 99)
        reject(BackupIssue.UNSUPPORTED_VERSION) { BackupCodec.decode(root.toString()) }
        val deep = source.replace("\"nutrition\":", "\"nutrition\":" + "[".repeat(BackupCodec.MAX_DEPTH))
        reject { BackupCodec.decode(deep) }
        val duplicate = source.replace("\"version\":1", "\"version\":1,\"\\u0076ersion\":1")
        reject { BackupCodec.decode(duplicate) }
        val malformedNumber = source.replace("\"defaultActiveFraction\":0.65", "\"defaultActiveFraction\":1e9999")
        reject { BackupCodec.decode(malformedNumber) }
        assertEquals(fixture(), BackupCodec.decode(" \n$source\t"))
    }

    private fun fixture(): ForgeBackup {
        val drink = FoodDetail(FoodEntity(id = "legacy-custom", name = "Homemade drink हिन्दी", brand = "Fresh", aliases = "drink напиток",
            source = "Home", sourceType = FoodSourceType.CUSTOM, tags = "batch", region = "IN", baseAmount = 250.0,
            baseUnit = FoodUnit.MILLILITER, gramsPerBase = 200.0, millilitersPerBase = 250.0, notes = "Keep chilled\n\"Fresh\" batch", archived = true),
            listOf(FoodNutrientEntity("legacy-custom", NutrientCodes.ENERGY, "Energy", "kcal", 140.0),
                FoodNutrientEntity("legacy-custom", NutrientCodes.PROTEIN, "Protein", "g", 4.0),
                FoodNutrientEntity("legacy-custom", NutrientCodes.CARBS, "Carbohydrates", "g", null),
                FoodNutrientEntity("legacy-custom", NutrientCodes.FAT, "Fat", "g", 2.0)),
            listOf(FoodPortionEntity("legacy-custom", "glass", "One glass (250ml)", 250.0, 200.0, 250.0, "Home measure")))
        val recipeId = "recipe:bowl"
        val recipeFood = FoodDetail(FoodEntity(id = recipeId, name = "Banana drink bowl", brand = "Kitchen", source = "Personal recipe",
            sourceType = FoodSourceType.RECIPE, baseAmount = 1.0, baseUnit = FoodUnit.SERVING, gramsPerBase = 300.0, notes = "Two portions"),
            listOf(FoodNutrientEntity(recipeId, NutrientCodes.ENERGY, "Energy", "kcal", 136.75),
                FoodNutrientEntity(recipeId, NutrientCodes.PROTEIN, "Protein", "g", 2.8175),
                FoodNutrientEntity(recipeId, NutrientCodes.CARBS, "Carbohydrates", "g", null),
                FoodNutrientEntity(recipeId, NutrientCodes.FAT, "Fat", "g", 1.2475)))
        val recipe = RecipeDetail(RecipeEntity(recipeId, recipeFood.food.name, 2.0, 600.0), listOf(
            RecipeIngredientEntity(31, recipeId, 0, "usda:missing", reference(), 150.0, FoodUnit.GRAM, factor = 1.5),
            RecipeIngredientEntity(32, recipeId, 1, drink.food.id, drink.snapshot(), 1.0, FoodUnit.SERVING, "glass", "One glass (250ml)", 1.0)))
        val diary = listOf(
            cached(DiaryEntity(41, "2026-10-14", MealType.SNACK, "usda:missing", reference(), 150.0, FoodUnit.GRAM, factor = 1.5, loggedAt = 10_000)),
            cached(DiaryEntity(42, "2026-10-14", MealType.LUNCH, drink.food.id, drink.snapshot(), 1.0, FoodUnit.SERVING,
                "glass", "One glass (250ml)", 1.0, 11_000)),
            cached(DiaryEntity(43, "2026-10-15", MealType.DINNER, recipeId, recipeFood.snapshot(), 2.0, FoodUnit.SERVING, factor = 2.0, loggedAt = 12_000)))
        val nutrition = NutritionBackup(foods = listOf(drink, recipeFood), recipes = listOf(recipe), diary = diary,
            goals = listOf(NutritionGoalEntity("2026-10-01", 1800.0, 0.0, null, 60.0)),
            bodyWeights = listOf(BodyWeightEntity(51, "1970-01-01", 79.5, 10_000)),
            favorites = listOf(FavoriteEntity("usda:missing", null, 1000), FavoriteEntity(drink.food.id, drink.food.name, 2000)),
            settings = NutritionSettingsEntity(includeWorkoutCalories = true, defaultActiveFraction = 0.65),
            workoutEnergy = listOf(WorkoutEnergyEntity(201, "1970-01-01", 1000, 2000, 1, 51, 80.5, 5.7,
                "legacy:strength", WorkoutIntensity.VIGOROUS, 0.65, 2.4, 0.0)))
        return completeBackup().copy(nutrition = nutrition)
    }
    private fun reference() = FoodSnapshot(foodId = "usda:missing", name = "Bananas, raw 🍌", source = "USDA reference pack",
        sourceId = "173944", sourceUrl = "https://fdc.nal.usda.gov/food-details/173944/nutrients", sourceType = FoodSourceType.REFERENCE,
        gramsPerBase = 100.0, nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 89.0),
            NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 1.09), NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", 22.84),
            NutrientValue(NutrientCodes.FAT, "Fat", "g", 0.33), NutrientValue("fiber", "Fiber", "g", null), NutrientValue("iron", "Iron", "mg", 0.26)))
    private fun cached(row: DiaryEntity): DiaryEntity {
        fun value(code: String) = row.snapshot.nutrients.firstOrNull { it.code == code }?.value?.times(row.factor)
        return row.copy(kcal = value(NutrientCodes.ENERGY), protein = value(NutrientCodes.PROTEIN), carbs = value(NutrientCodes.CARBS), fat = value(NutrientCodes.FAT))
    }
    private fun reject(issue: BackupIssue = BackupIssue.INVALID_DATA, action: () -> Unit) {
        try { action(); fail("Expected nutrition backup rejection") }
        catch (failure: BackupValidationException) { assertEquals(issue, failure.issue) }
    }
}

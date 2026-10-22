package com.forge.workout.backup

import com.forge.workout.nutrition.data.NutritionConverters
import com.forge.workout.nutrition.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Stable food IDs are portable; internal food rowids are rebuilt and snapshots are shared by exact value. */
internal object NutritionBackupCodec {
    private val converters = NutritionConverters()

    fun encode(data: NutritionBackup, budget: BackupJsonBudget): JSONObject {
        fun json(vararg pairs: Pair<String, Any?>) = budget.objectValue(*pairs)
        fun array(values: List<Any>) = budget.arrayValue(values)
        val snapshots = linkedMapOf<FoodSnapshot, Int>()
        fun snapshot(value: FoodSnapshot): Int = snapshots.getOrPut(value) { snapshots.size }
        val recipes = data.recipes.map { detail -> json(
            "recipe" to json("id" to detail.recipe.id, "name" to detail.recipe.name, "servings" to detail.recipe.servings,
                "cookedWeightGrams" to detail.recipe.cookedWeightGrams),
            "ingredients" to array(detail.ingredients.map { row -> json("id" to row.id, "recipeId" to row.recipeId,
                "orderIndex" to row.orderIndex, "foodId" to row.foodId, "snapshot" to snapshot(row.snapshot),
                "quantity" to row.quantity, "unit" to row.unit.name, "portionId" to row.portionId,
                "portionLabel" to row.portionLabel, "factor" to row.factor) }),
        ) }
        val diary = data.diary.map { row -> json("id" to row.id, "day" to row.day, "meal" to row.meal.name,
            "foodId" to row.foodId, "snapshot" to snapshot(row.snapshot), "quantity" to row.quantity, "unit" to row.unit.name,
            "portionId" to row.portionId, "portionLabel" to row.portionLabel, "factor" to row.factor, "loggedAt" to row.loggedAt,
            "kcal" to row.kcal, "protein" to row.protein, "carbs" to row.carbs, "fat" to row.fat) }
        return json("version" to 1,
            "snapshots" to array(snapshots.keys.map { budget.snapshotObject(converters.snapshotToString(it)) }),
            "foods" to array(data.foods.map { detail ->
                val food = detail.food
                json("food" to json("id" to food.id, "name" to food.name, "brand" to food.brand, "aliases" to food.aliases,
                    "source" to food.source, "sourceId" to food.sourceId, "sourceUrl" to food.sourceUrl, "sourceType" to food.sourceType.name,
                    "packId" to food.packId, "region" to food.region, "tags" to food.tags, "baseAmount" to food.baseAmount,
                    "baseUnit" to food.baseUnit.name, "gramsPerBase" to food.gramsPerBase, "millilitersPerBase" to food.millilitersPerBase,
                    "notes" to food.notes, "archived" to food.archived),
                    "nutrients" to array(detail.nutrients.map { json("foodId" to it.foodId, "code" to it.code, "name" to it.name, "unit" to it.unit, "value" to it.value) }),
                    "portions" to array(detail.portions.map { json("foodId" to it.foodId, "id" to it.id, "label" to it.label,
                        "baseQuantity" to it.baseQuantity, "grams" to it.grams, "milliliters" to it.milliliters, "source" to it.source) }))
            }),
            "recipes" to array(recipes), "diary" to array(diary),
            "goals" to array(data.goals.map { json("effectiveDay" to it.effectiveDay, "kcal" to it.kcal, "protein" to it.protein, "carbs" to it.carbs, "fat" to it.fat) }),
            "bodyWeights" to array(data.bodyWeights.map { json("id" to it.id, "day" to it.day, "kg" to it.kg, "measuredAt" to it.measuredAt) }),
            "favorites" to array(data.favorites.map { json("foodId" to it.foodId, "name" to it.name, "savedAt" to it.savedAt) }),
            "settings" to json("id" to data.settings.id, "includeWorkoutCalories" to data.settings.includeWorkoutCalories,
                "defaultActivity" to data.settings.defaultActivity, "defaultActiveFraction" to data.settings.defaultActiveFraction),
            "workoutEnergy" to array(data.workoutEnergy.map { row -> json("workoutId" to row.workoutId, "day" to row.day,
                "startTime" to row.startTime, "endTime" to row.endTime, "durationSeconds" to row.durationSeconds,
                "bodyWeightId" to row.bodyWeightId, "bodyWeightKg" to row.bodyWeightKg, "met" to row.met, "activity" to row.activity,
                "intensity" to row.intensity.name, "activeFraction" to row.activeFraction,
                "calculatedKcal" to row.calculatedKcal, "manualKcal" to row.manualKcal) }),
        )
    }

    fun decode(root: JSONObject): NutritionBackup {
        root.shape("version", "snapshots", "foods", "recipes", "diary", "goals", "bodyWeights", "favorites", "settings", "workoutEnergy")
        require(root.integer("version") == 1) { "Unsupported nutrition snapshot version." }
        val snapshots = root.objects("snapshots", ::readSnapshot)
        require(snapshots.distinct().size == snapshots.size) { "Duplicate shared food snapshot." }
        val used = hashSetOf<Int>()
        fun snapshot(row: JSONObject): FoodSnapshot {
            val index = row.integer("snapshot")
            require(index in snapshots.indices) { "Missing shared food snapshot." }
            used += index
            return snapshots[index]
        }
        val result = NutritionBackup(
            foods = root.objects("foods") { detail ->
                detail.shape("food", "nutrients", "portions")
                val food = detail.objectValue("food").also { it.shape("id", "name", "brand", "aliases", "source", "sourceId", "sourceUrl",
                    "sourceType", "packId", "region", "tags", "baseAmount", "baseUnit", "gramsPerBase", "millilitersPerBase", "notes", "archived") }
                FoodDetail(FoodEntity(id = food.string("id"), name = food.string("name"), brand = food.string("brand"), aliases = food.string("aliases"),
                    source = food.string("source"), sourceId = food.nullableString("sourceId"), sourceUrl = food.nullableString("sourceUrl"),
                    sourceType = enumValueOf(food.string("sourceType")), packId = food.nullableString("packId"), region = food.nullableString("region"),
                    tags = food.string("tags"), baseAmount = food.decimal("baseAmount"), baseUnit = enumValueOf(food.string("baseUnit")),
                    gramsPerBase = food.nullableDecimal("gramsPerBase"), millilitersPerBase = food.nullableDecimal("millilitersPerBase"),
                    notes = food.string("notes"), archived = food.boolean("archived")),
                    detail.objects("nutrients", 300) { row ->
                        row.shape("foodId", "code", "name", "unit", "value")
                        FoodNutrientEntity(row.string("foodId"), row.string("code"), row.string("name"), row.string("unit"), row.nullableDecimal("value"))
                    }, detail.objects("portions", 100) { row ->
                        row.shape("foodId", "id", "label", "baseQuantity", "grams", "milliliters", "source")
                        FoodPortionEntity(row.string("foodId"), row.string("id"), row.string("label"), row.decimal("baseQuantity"),
                            row.nullableDecimal("grams"), row.nullableDecimal("milliliters"), row.nullableString("source"))
                    })
            },
            recipes = root.objects("recipes") { detail ->
                detail.shape("recipe", "ingredients")
                val recipe = detail.objectValue("recipe").also { it.shape("id", "name", "servings", "cookedWeightGrams") }
                RecipeDetail(RecipeEntity(recipe.string("id"), recipe.string("name"), recipe.decimal("servings"), recipe.nullableDecimal("cookedWeightGrams")),
                    detail.objects("ingredients", 500) { row ->
                        row.shape("id", "recipeId", "orderIndex", "foodId", "snapshot", "quantity", "unit", "portionId", "portionLabel", "factor")
                        RecipeIngredientEntity(row.long("id"), row.string("recipeId"), row.integer("orderIndex"), row.nullableString("foodId"),
                            snapshot(row), row.decimal("quantity"), enumValueOf(row.string("unit")), row.nullableString("portionId"),
                            row.nullableString("portionLabel"), row.decimal("factor"))
                    })
            },
            diary = root.objects("diary") { row ->
                row.shape("id", "day", "meal", "foodId", "snapshot", "quantity", "unit", "portionId", "portionLabel", "factor", "loggedAt", "kcal", "protein", "carbs", "fat")
                DiaryEntity(row.long("id"), row.string("day"), enumValueOf(row.string("meal")), row.nullableString("foodId"), snapshot(row),
                    row.decimal("quantity"), enumValueOf(row.string("unit")), row.nullableString("portionId"), row.nullableString("portionLabel"),
                    row.decimal("factor"), row.long("loggedAt"), row.nullableDecimal("kcal"), row.nullableDecimal("protein"),
                    row.nullableDecimal("carbs"), row.nullableDecimal("fat"))
            },
            goals = root.objects("goals") { row ->
                row.shape("effectiveDay", "kcal", "protein", "carbs", "fat")
                NutritionGoalEntity(row.string("effectiveDay"), row.nullableDecimal("kcal"), row.nullableDecimal("protein"), row.nullableDecimal("carbs"), row.nullableDecimal("fat"))
            },
            bodyWeights = root.objects("bodyWeights") { row ->
                row.shape("id", "day", "kg", "measuredAt")
                BodyWeightEntity(row.long("id"), row.string("day"), row.decimal("kg"), row.long("measuredAt"))
            },
            favorites = root.objects("favorites") { row ->
                row.shape("foodId", "name", "savedAt")
                FavoriteEntity(row.string("foodId"), row.nullableString("name"), row.long("savedAt"))
            },
            settings = root.objectValue("settings").let { row ->
                row.shape("id", "includeWorkoutCalories", "defaultActivity", "defaultActiveFraction")
                NutritionSettingsEntity(row.integer("id"), row.boolean("includeWorkoutCalories"), row.string("defaultActivity"), row.decimal("defaultActiveFraction"))
            },
            workoutEnergy = root.objects("workoutEnergy") { row ->
                row.shape("workoutId", "day", "startTime", "endTime", "durationSeconds", "bodyWeightId", "bodyWeightKg", "met", "activity", "intensity",
                    "activeFraction", "calculatedKcal", "manualKcal")
                WorkoutEnergyEntity(row.long("workoutId"), row.string("day"), row.long("startTime"), row.long("endTime"), row.long("durationSeconds"),
                    row.nullableLong("bodyWeightId"), row.decimal("bodyWeightKg"), row.decimal("met"), row.string("activity"), enumValueOf(row.string("intensity")),
                    row.decimal("activeFraction"), row.decimal("calculatedKcal"), row.nullableDecimal("manualKcal"))
            },
        )
        require(used.size == snapshots.size) { "Unreferenced shared food snapshot." }
        return result
    }

    private fun readSnapshot(row: JSONObject): FoodSnapshot {
        row.shape("version", "foodId", "name", "brand", "source", "sourceId", "sourceUrl", "sourceType", "baseAmount", "baseUnit",
            "gramsPerBase", "millilitersPerBase", "nutrients")
        require(row.integer("version") == 1) { "Unsupported food snapshot version." }
        row.objects("nutrients", 300) { nutrient ->
            nutrient.shape("code", "name", "unit", "value")
            nutrient.string("code"); nutrient.string("name"); nutrient.string("unit"); nutrient.nullableDecimal("value")
        }
        return converters.stringToSnapshot(row.toString())
    }

    private fun JSONObject.shape(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) { "Invalid nutrition backup structure." } }
    private fun JSONObject.string(key: String): String = get(key).let { require(it is String) { "Expected text." }; it }
    private fun JSONObject.nullableString(key: String): String? = if (get(key) === JSONObject.NULL) null else string(key)
    private fun JSONObject.long(key: String): Long = get(key).let { require(it is Int || it is Long) { "Expected an integer." }; (it as Number).toLong() }
    private fun JSONObject.nullableLong(key: String): Long? = if (get(key) === JSONObject.NULL) null else long(key)
    private fun JSONObject.integer(key: String): Int = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer is out of range." } }.toInt()
    private fun JSONObject.decimal(key: String): Double = get(key).let { require(it is Number && it.toDouble().isFinite()) { "Expected a finite number." }; it.toDouble() }
    private fun JSONObject.nullableDecimal(key: String): Double? = if (get(key) === JSONObject.NULL) null else decimal(key)
    private fun JSONObject.boolean(key: String): Boolean = get(key).let { require(it is Boolean) { "Expected a boolean." }; it }
    private fun JSONObject.objectValue(key: String): JSONObject = get(key).let { require(it is JSONObject) { "Expected an object." }; it }
    private fun <T> JSONObject.objects(key: String, read: (JSONObject) -> T): List<T> = objects(key, NutritionBackupValidation.MAX_ROWS, read)
    private fun <T> JSONObject.objects(key: String, limit: Int, read: (JSONObject) -> T): List<T> {
        val rows = get(key).let { require(it is JSONArray) { "Expected a list." }; it }
        require(rows.length() <= limit) { "Nutrition backup has too many rows." }
        return List(rows.length()) { index -> rows.get(index).let { require(it is JSONObject) { "Expected a row object." }; read(it) } }
    }
}

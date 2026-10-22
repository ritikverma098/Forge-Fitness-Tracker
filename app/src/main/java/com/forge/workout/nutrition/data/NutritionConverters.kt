package com.forge.workout.nutrition.data

import androidx.room.TypeConverter
import com.forge.workout.nutrition.model.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class NutritionConverters {
    @TypeConverter fun unitToString(value: FoodUnit): String = value.name
    @TypeConverter fun stringToUnit(value: String): FoodUnit = FoodUnit.valueOf(value)
    @TypeConverter fun sourceTypeToString(value: FoodSourceType): String = value.name
    @TypeConverter fun stringToSourceType(value: String): FoodSourceType = FoodSourceType.valueOf(value)
    @TypeConverter fun mealToString(value: MealType): String = value.name
    @TypeConverter fun stringToMeal(value: String): MealType = MealType.valueOf(value)
    @TypeConverter fun intensityToString(value: WorkoutIntensity): String = value.name
    @TypeConverter fun stringToIntensity(value: String): WorkoutIntensity = WorkoutIntensity.valueOf(value)

    @TypeConverter fun snapshotToString(value: FoodSnapshot): String {
        NutritionValidation.snapshot(value)
        return objectValue("version" to 1, "foodId" to value.foodId, "name" to value.name, "brand" to value.brand,
            "source" to value.source, "sourceId" to value.sourceId, "sourceUrl" to value.sourceUrl,
            "sourceType" to value.sourceType.name, "baseAmount" to value.baseAmount, "baseUnit" to value.baseUnit.name,
            "gramsPerBase" to value.gramsPerBase, "millilitersPerBase" to value.millilitersPerBase,
            "nutrients" to JSONArray().apply { value.nutrients.forEach { nutrient ->
                put(objectValue("code" to nutrient.code, "name" to nutrient.name, "unit" to nutrient.unit, "value" to nutrient.value))
            } }).toString().also(::checkSize)
    }

    @TypeConverter fun stringToSnapshot(value: String): FoodSnapshot {
        checkSize(value)
        return try {
            val root = JSONObject(value)
            require(root.get("version") == 1) { "Unsupported food snapshot version." }
            val list = root.getJSONArray("nutrients")
            require(list.length() <= 300) { "Too many snapshot nutrients." }
            FoodSnapshot(
                foodId = root.nullableText("foodId"), name = root.text("name"), brand = root.text("brand"),
                source = root.text("source"), sourceId = root.nullableText("sourceId"), sourceUrl = root.nullableText("sourceUrl"),
                sourceType = FoodSourceType.valueOf(root.text("sourceType")), baseAmount = root.number("baseAmount")!!,
                baseUnit = FoodUnit.valueOf(root.text("baseUnit")), gramsPerBase = root.number("gramsPerBase", true),
                millilitersPerBase = root.number("millilitersPerBase", true), nutrients = List(list.length()) { index ->
                    val nutrient = list.getJSONObject(index)
                    NutrientValue(nutrient.text("code"), nutrient.text("name"), nutrient.text("unit"), nutrient.number("value", true))
                },
            ).also(NutritionValidation::snapshot)
        } catch (failure: JSONException) {
            throw IllegalArgumentException("Invalid food snapshot.", failure)
        }
    }

    private fun objectValue(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun JSONObject.text(key: String): String = get(key).let {
        require(it is String) { "Invalid snapshot text." }; it
    }
    private fun JSONObject.nullableText(key: String): String? = if (get(key) === JSONObject.NULL) null else text(key)
    private fun JSONObject.number(key: String, nullable: Boolean = false): Double? {
        val value = get(key)
        if (nullable && value === JSONObject.NULL) return null
        require(value is Number && value.toDouble().isFinite()) { "Invalid snapshot number." }
        return value.toDouble()
    }
    private fun checkSize(value: String) { require(value.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Food snapshot is too large." } }
}

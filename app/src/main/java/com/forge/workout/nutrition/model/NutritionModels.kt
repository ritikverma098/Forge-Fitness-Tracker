package com.forge.workout.nutrition.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.forge.workout.model.Workout

enum class FoodUnit { GRAM, MILLILITER, SERVING }
enum class FoodSourceType { REFERENCE, CUSTOM, RECIPE }
enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }
enum class WorkoutIntensity { LIGHT, MODERATE, VIGOROUS }

object NutrientCodes {
    const val ENERGY = "energy"
    const val PROTEIN = "protein"
    const val CARBS = "carbs"
    const val FAT = "fat"
}

data class NutrientValue(val code: String, val name: String, val unit: String, val value: Double?)

data class FoodSnapshot(
    val foodId: String? = null,
    val name: String,
    val brand: String = "",
    val source: String = "",
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val sourceType: FoodSourceType = FoodSourceType.CUSTOM,
    val baseAmount: Double = 100.0,
    val baseUnit: FoodUnit = FoodUnit.GRAM,
    val gramsPerBase: Double? = null,
    val millilitersPerBase: Double? = null,
    val nutrients: List<NutrientValue> = emptyList(),
)

@Entity(tableName = "nutrition_catalog_packs")
data class CatalogPackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val sourceUrl: String,
    val license: String,
    val citation: String,
    val installedAt: Long = System.currentTimeMillis(),
    val sha256: String = "",
    val foodCount: Int = 0,
    val payloadBytes: Long = 0,
)

@Entity(tableName = "nutrition_foods",
    foreignKeys = [ForeignKey(entity = CatalogPackEntity::class, parentColumns = ["id"], childColumns = ["packId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["id"], unique = true), Index("packId"), Index(value = ["archived", "name"]), Index(value = ["region", "archived", "name"])])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val brand: String = "",
    val aliases: String = "",
    val source: String = "",
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val sourceType: FoodSourceType = FoodSourceType.CUSTOM,
    val packId: String? = null,
    val region: String? = null,
    val tags: String = "",
    val baseAmount: Double = 100.0,
    val baseUnit: FoodUnit = FoodUnit.GRAM,
    val gramsPerBase: Double? = null,
    val millilitersPerBase: Double? = null,
    val notes: String = "",
    val archived: Boolean = false,
)

@Fts4(contentEntity = FoodEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61, prefix = [2, 3, 4])
@Entity(tableName = "nutrition_food_search")
data class FoodSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val name: String,
    val brand: String,
    val aliases: String,
)

@Entity(tableName = "nutrition_food_nutrients", primaryKeys = ["foodId", "code"],
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"], onDelete = ForeignKey.CASCADE)])
data class FoodNutrientEntity(val foodId: String, val code: String, val name: String, val unit: String, val value: Double?)

@Entity(tableName = "nutrition_food_portions", primaryKeys = ["foodId", "id"],
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["foodId"], onDelete = ForeignKey.CASCADE)])
data class FoodPortionEntity(
    val foodId: String,
    val id: String,
    val label: String,
    val baseQuantity: Double,
    val grams: Double? = null,
    val milliliters: Double? = null,
    val source: String? = null,
)

data class FoodDetail(
    @Embedded val food: FoodEntity,
    @Relation(parentColumn = "id", entityColumn = "foodId") val nutrients: List<FoodNutrientEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "foodId") val portions: List<FoodPortionEntity> = emptyList(),
) {
    fun snapshot() = FoodSnapshot(food.id, food.name, food.brand, food.source, food.sourceId, food.sourceUrl,
        food.sourceType, food.baseAmount, food.baseUnit, food.gramsPerBase, food.millilitersPerBase,
        nutrients.map { NutrientValue(it.code, it.name, it.unit, it.value) })
}

@Entity(tableName = "nutrition_diary", indices = [Index(value = ["day", "loggedAt"]), Index("foodId")])
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val meal: MealType,
    val foodId: String? = null,
    val snapshot: FoodSnapshot,
    val quantity: Double,
    val unit: FoodUnit,
    val portionId: String? = null,
    val portionLabel: String? = null,
    val factor: Double,
    val loggedAt: Long = System.currentTimeMillis(),
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
)

@Entity(tableName = "nutrition_recipes",
    foreignKeys = [ForeignKey(entity = FoodEntity::class, parentColumns = ["id"], childColumns = ["id"], onDelete = ForeignKey.CASCADE)])
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val servings: Double,
    val cookedWeightGrams: Double? = null,
)

@Entity(tableName = "nutrition_recipe_ingredients", indices = [Index(value = ["recipeId", "orderIndex"], unique = true)],
    foreignKeys = [ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.CASCADE)])
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: String,
    val orderIndex: Int,
    val foodId: String? = null,
    val snapshot: FoodSnapshot,
    val quantity: Double,
    val unit: FoodUnit,
    val portionId: String? = null,
    val portionLabel: String? = null,
    val factor: Double,
)

data class RecipeDetail(
    @Embedded val recipe: RecipeEntity,
    @Relation(parentColumn = "id", entityColumn = "recipeId") val ingredients: List<RecipeIngredientEntity>,
) { val sortedIngredients get() = ingredients.sortedWith(compareBy<RecipeIngredientEntity> { it.orderIndex }.thenBy { it.id }) }

@Entity(tableName = "nutrition_goals")
data class NutritionGoalEntity(
    @PrimaryKey val effectiveDay: String,
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
)

@Entity(tableName = "nutrition_settings")
data class NutritionSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val includeWorkoutCalories: Boolean = false,
    val defaultActivity: String = "resistance_training",
    val defaultActiveFraction: Double = 0.5,
)

@Entity(tableName = "nutrition_favorites")
data class FavoriteEntity(@PrimaryKey val foodId: String, val name: String? = null, val savedAt: Long = System.currentTimeMillis())

@Entity(tableName = "nutrition_body_weights", indices = [Index(value = ["day"], unique = true)])
data class BodyWeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val kg: Double,
    val measuredAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "nutrition_workout_energy", indices = [Index("bodyWeightId"), Index("day")], foreignKeys = [
    ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = BodyWeightEntity::class, parentColumns = ["id"], childColumns = ["bodyWeightId"], onDelete = ForeignKey.SET_NULL),
])
data class WorkoutEnergyEntity(
    @PrimaryKey val workoutId: Long,
    val day: String,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Long,
    val bodyWeightId: Long? = null,
    val bodyWeightKg: Double,
    val met: Double,
    val activity: String,
    val intensity: WorkoutIntensity,
    val activeFraction: Double = 0.5,
    val calculatedKcal: Double,
    val manualKcal: Double? = null,
) { val effectiveKcal get() = manualKcal ?: calculatedKcal }

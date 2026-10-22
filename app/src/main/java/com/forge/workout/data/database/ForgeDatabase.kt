package com.forge.workout.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.nutrition.data.NutritionConverters
import com.forge.workout.nutrition.data.NutritionDao
import com.forge.workout.nutrition.data.NutritionSchema
import com.forge.workout.nutrition.model.*

@Entity(tableName = "app_metadata")
data class AppMetadata(@PrimaryKey val key: String, val value: String)

@Database(
    entities = [Exercise::class, Workout::class, WorkoutExercise::class, WorkoutSet::class, Routine::class, RoutineExercise::class, AppMetadata::class,
        CatalogPackEntity::class, FoodEntity::class, FoodSearchEntity::class, FoodNutrientEntity::class, FoodPortionEntity::class,
        DiaryEntity::class, RecipeEntity::class, RecipeIngredientEntity::class, NutritionGoalEntity::class, NutritionSettingsEntity::class,
        FavoriteEntity::class, BodyWeightEntity::class, WorkoutEnergyEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(ForgeConverters::class, NutritionConverters::class)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun nutritionDao(): NutritionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workouts` ADD COLUMN `muscleGroups` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workout_sets` ADD COLUMN `setType` TEXT NOT NULL DEFAULT 'WORKING'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) { NutritionSchema.create(db) }
        }

        fun create(context: Context): ForgeDatabase = Room.databaseBuilder(
            context.applicationContext,
            ForgeDatabase::class.java,
            "forge.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}

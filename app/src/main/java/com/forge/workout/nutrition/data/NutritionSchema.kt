package com.forge.workout.nutrition.data

import androidx.sqlite.db.SupportSQLiteDatabase

internal object NutritionSchema {
    fun create(database: SupportSQLiteDatabase) {
        statements.forEach(database::execSQL)
        // Room creates the same content-sync triggers for new databases. Migration also needs them.
        for (operation in listOf("UPDATE", "DELETE")) {
            database.execSQL("CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_nutrition_food_search_BEFORE_$operation` BEFORE $operation ON `nutrition_foods` BEGIN DELETE FROM `nutrition_food_search` WHERE `docid` = OLD.`rowid`; END")
        }
        for (operation in listOf("UPDATE", "INSERT")) {
            database.execSQL("CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_nutrition_food_search_AFTER_$operation` AFTER $operation ON `nutrition_foods` BEGIN INSERT INTO `nutrition_food_search` (`docid`, `name`, `brand`, `aliases`) VALUES (NEW.`rowid`, NEW.`name`, NEW.`brand`, NEW.`aliases`); END")
        }
    }

    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `nutrition_catalog_packs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `version` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `license` TEXT NOT NULL, `citation` TEXT NOT NULL, `installedAt` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `foodCount` INTEGER NOT NULL, `payloadBytes` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `nutrition_foods` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `brand` TEXT NOT NULL, `aliases` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceId` TEXT, `sourceUrl` TEXT, `sourceType` TEXT NOT NULL, `packId` TEXT, `region` TEXT, `tags` TEXT NOT NULL, `baseAmount` REAL NOT NULL, `baseUnit` TEXT NOT NULL, `gramsPerBase` REAL, `millilitersPerBase` REAL, `notes` TEXT NOT NULL, `archived` INTEGER NOT NULL, FOREIGN KEY(`packId`) REFERENCES `nutrition_catalog_packs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_foods_id` ON `nutrition_foods` (`id`)",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_foods_packId` ON `nutrition_foods` (`packId`)",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_foods_archived_name` ON `nutrition_foods` (`archived`, `name`)",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_foods_region_archived_name` ON `nutrition_foods` (`region`, `archived`, `name`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `nutrition_food_search` USING FTS4(`name` TEXT NOT NULL, `brand` TEXT NOT NULL, `aliases` TEXT NOT NULL, content=`nutrition_foods`, tokenize=unicode61, prefix=`2,3,4`)",
        "CREATE TABLE IF NOT EXISTS `nutrition_food_nutrients` (`foodId` TEXT NOT NULL, `code` TEXT NOT NULL, `name` TEXT NOT NULL, `unit` TEXT NOT NULL, `value` REAL, PRIMARY KEY(`foodId`, `code`), FOREIGN KEY(`foodId`) REFERENCES `nutrition_foods`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `nutrition_food_portions` (`foodId` TEXT NOT NULL, `id` TEXT NOT NULL, `label` TEXT NOT NULL, `baseQuantity` REAL NOT NULL, `grams` REAL, `milliliters` REAL, `source` TEXT, PRIMARY KEY(`foodId`, `id`), FOREIGN KEY(`foodId`) REFERENCES `nutrition_foods`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `nutrition_diary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, `meal` TEXT NOT NULL, `foodId` TEXT, `snapshot` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `portionId` TEXT, `portionLabel` TEXT, `factor` REAL NOT NULL, `loggedAt` INTEGER NOT NULL, `kcal` REAL, `protein` REAL, `carbs` REAL, `fat` REAL)",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_diary_day_loggedAt` ON `nutrition_diary` (`day`, `loggedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_diary_foodId` ON `nutrition_diary` (`foodId`)",
        "CREATE TABLE IF NOT EXISTS `nutrition_recipes` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `servings` REAL NOT NULL, `cookedWeightGrams` REAL, PRIMARY KEY(`id`), FOREIGN KEY(`id`) REFERENCES `nutrition_foods`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `nutrition_recipe_ingredients` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `recipeId` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `foodId` TEXT, `snapshot` TEXT NOT NULL, `quantity` REAL NOT NULL, `unit` TEXT NOT NULL, `portionId` TEXT, `portionLabel` TEXT, `factor` REAL NOT NULL, FOREIGN KEY(`recipeId`) REFERENCES `nutrition_recipes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_recipe_ingredients_recipeId_orderIndex` ON `nutrition_recipe_ingredients` (`recipeId`, `orderIndex`)",
        "CREATE TABLE IF NOT EXISTS `nutrition_goals` (`effectiveDay` TEXT NOT NULL, `kcal` REAL, `protein` REAL, `carbs` REAL, `fat` REAL, PRIMARY KEY(`effectiveDay`))",
        "CREATE TABLE IF NOT EXISTS `nutrition_settings` (`id` INTEGER NOT NULL, `includeWorkoutCalories` INTEGER NOT NULL, `defaultActivity` TEXT NOT NULL, `defaultActiveFraction` REAL NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `nutrition_favorites` (`foodId` TEXT NOT NULL, `name` TEXT, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`foodId`))",
        "CREATE TABLE IF NOT EXISTS `nutrition_body_weights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, `kg` REAL NOT NULL, `measuredAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_body_weights_day` ON `nutrition_body_weights` (`day`)",
        "CREATE TABLE IF NOT EXISTS `nutrition_workout_energy` (`workoutId` INTEGER NOT NULL, `day` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `bodyWeightId` INTEGER, `bodyWeightKg` REAL NOT NULL, `met` REAL NOT NULL, `activity` TEXT NOT NULL, `intensity` TEXT NOT NULL, `activeFraction` REAL NOT NULL, `calculatedKcal` REAL NOT NULL, `manualKcal` REAL, PRIMARY KEY(`workoutId`), FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`bodyWeightId`) REFERENCES `nutrition_body_weights`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_workout_energy_bodyWeightId` ON `nutrition_workout_energy` (`bodyWeightId`)",
        "CREATE INDEX IF NOT EXISTS `index_nutrition_workout_energy_day` ON `nutrition_workout_energy` (`day`)",
    )
}

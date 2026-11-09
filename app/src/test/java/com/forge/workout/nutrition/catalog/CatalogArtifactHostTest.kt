package com.forge.workout.nutrition.catalog

import android.app.Application
import androidx.room.Room
import com.forge.workout.backup.BackupCodec
import com.forge.workout.backup.NutritionBackup
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.DiaryEntity
import com.forge.workout.nutrition.model.FoodBrowseFilter
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodNutrientEntity
import com.forge.workout.nutrition.model.FoodPortionEntity
import com.forge.workout.nutrition.model.MealType
import com.forge.workout.nutrition.model.NutrientCodes
import com.forge.workout.nutrition.model.NutritionMath
import com.forge.workout.nutrition.model.RecipeDraft
import com.forge.workout.nutrition.model.RecipeFactory
import com.forge.workout.nutrition.model.RecipeIngredientDraft
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.system.measureNanoTime

/** Optional release check for real generated files, which are kept outside source control. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogArtifactHostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realPacksInstallBesideBundledDataAndSearchAfterReopening() = runBlocking<Unit> {
        val directory = System.getenv("FORGE_TEST_PACK_DIR")
        assumeTrue("Set FORGE_TEST_PACK_DIR to test generated release packs", !directory.isNullOrBlank())
        val input = File(requireNotNull(directory))
        val indiaPack = "forge-india-packaged-1.0.0.forgefoods"
        val usdaPack = "forge-usda-branded-1.0.0.forgefoods"
        val app = RuntimeEnvironment.getApplication()
        val databasePath = File(temporary.root, "artifacts.db").absolutePath
        suspend fun withDatabase(block: suspend (ForgeDatabase) -> Unit) {
            val database = Room.databaseBuilder(app, ForgeDatabase::class.java, databasePath).build()
            try {
                block(database)
            } finally {
                database.close()
            }
        }
        val staging = temporary.newFolder("catalog")
        lateinit var verifiedCounts: Map<String, Int>
        lateinit var nutritionBefore: NutritionBackup
        lateinit var referenceFood: FoodDetail

        withDatabase { database ->
            val repository = RoomNutritionRepository(database)
            val backups = RoomBackupRepository(database)
            val installer = CatalogPackInstaller(database, staging)
            suspend fun install(filename: String): CatalogInstallResult {
                val file = File(input, filename)
                val report = JSONObject(File(input, "$filename.report.json").readText())
                val result = installer.install(file.inputStream(), report.getString("archiveSha256"))
                val count = report.getInt("foodCount")
                assertEquals(count, result.pack.foodCount)
                assertEquals(report.getString("sha256"), result.pack.sha256)
                println("Verified $filename: $count foods, ${result.pack.payloadBytes} payload bytes")
                return result
            }

            val custom = FoodDetail(
                FoodEntity(id = CUSTOM_ID, name = "Personal release food", notes = "Preserve personal notes"),
                listOf(FoodNutrientEntity(CUSTOM_ID, NutrientCodes.ENERGY, "Energy", "kcal", 120.0)),
                listOf(FoodPortionEntity(CUSTOM_ID, "bowl", "Personal bowl", 150.0, grams = 150.0)),
            )
            repository.saveCustomFood(custom)
            val core = installer.install(app.assets.open("nutrition/usda-core.forgefoods"))
            val india = install(indiaPack)
            assertEquals(india.pack.foodCount, india.added)
            assertEquals(custom, repository.food(CUSTOM_ID)?.let { it.copy(food = it.food.copy(rowId = 0)) })

            referenceFood = requireNotNull(repository.food(FARALI_ID))
            val diaryId = saveReferenceSnapshots(repository, referenceFood)
            nutritionBefore = requireNotNull(backups.snapshot().nutrition)

            val usda = install(usdaPack)
            assertEquals(usda.pack.foodCount, usda.added)
            verifiedCounts = listOf(core.pack, india.pack, usda.pack).associate { it.id to it.foodCount }
            assertEquals(nutritionBefore, backups.snapshot().nutrition)

            val installedPacks = repository.observeCatalogPacks().first()
            val replay = install(indiaPack)
            assertTrue(replay.alreadyInstalled)
            assertEquals(listOf(0, 0, 0), listOf(replay.added, replay.updated, replay.removed))
            assertEquals(nutritionBefore, backups.snapshot().nutrition)

            val portable = BackupCodec.decode(BackupCodec.encode(backups.snapshot()))
            assertEquals(nutritionBefore, portable.nutrition)
            repository.deleteDiary(diaryId)
            repository.deleteRecipe(RECIPE_ID)
            repository.deleteCustomFood(CUSTOM_ID)
            assertNull(repository.diaryEntry(diaryId))
            assertNull(repository.recipe(RECIPE_ID))
            assertNull(repository.food(CUSTOM_ID))
            backups.restore(portable)

            assertEquals(nutritionBefore, backups.snapshot().nutrition)
            assertEquals(installedPacks, repository.observeCatalogPacks().first())
            assertEquals(referenceFood, repository.food(FARALI_ID))
            assertTrue(staging.list().orEmpty().isEmpty())
        }

        withDatabase { database ->
            val repository = RoomNutritionRepository(database)
            val installed = repository.observeCatalogPacks().first().associate { it.id to it.foodCount }
            assertEquals(verifiedCounts, installed)
            assertEquals(nutritionBefore, RoomBackupRepository(database).snapshot().nutrition)
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
            val indian = repository.searchFoods("Britannia", region = "IN", limit = 50)
            assertTrue(indian.any { it.packId == "india-packaged" })
            val farali = requireNotNull(repository.observeFood(FARALI_ID).first())
            assertEquals(referenceFood, farali)
            assertTrue(farali.portions.any { it.grams == 45.0 })
            assertTrue(repository.observeFoods(region = "NZ", limit = 50).first()
                .any { it.packId == "usda-branded-expansion" })
            val branded = repository.observeFoodPage(filter = FoodBrowseFilter.BRANDED, limit = 200).first()
            assertTrue(branded.any { it.packId == "usda-branded-expansion" })
            val queries = listOf("brit", "choco", "yogurt", "roti", "rice", "cheddar", "milk", "lentil")
            queries.forEach { repository.observeFoodPage(it, limit = 40).first() }
            val elapsed = List(5) {
                queries.map { query -> measureNanoTime { repository.observeFoodPage(query, limit = 40).first() } }
            }.flatten().sorted()
            println("Offline picker queries over ${installed.values.sum()} foods: " +
                "p50=${elapsed[elapsed.size / 2] / 1_000_000.0}ms " +
                "p95=${elapsed[elapsed.size * 95 / 100 - 1] / 1_000_000.0}ms")
        }
    }

    private suspend fun saveReferenceSnapshots(repository: RoomNutritionRepository, reference: FoodDetail): Long {
        val snapshot = reference.snapshot()
        val choice = NutritionMath.choices(reference).single { it.portionId == "label" }
        val factor = NutritionMath.factor(snapshot, choice, 1.0)
        val diaryId = repository.saveDiary(
            DiaryEntity(
                day = "2026-10-13", meal = MealType.LUNCH, foodId = reference.food.id, snapshot = snapshot,
                quantity = 1.0, unit = choice.unit, portionId = choice.portionId, portionLabel = choice.label,
                factor = factor, loggedAt = 1000,
            ),
        )
        assertEquals(268.0, requireNotNull(repository.diaryEntry(diaryId)?.kcal), 0.0)
        val recipe = RecipeFactory.validate(
            RecipeDraft(
                id = RECIPE_ID, name = "Personal release recipe",
                ingredients = listOf(RecipeIngredientDraft(key = "ingredient", snapshot = snapshot, choice = choice, quantity = "1")),
            ),
        )
        assertTrue(recipe.invalidFields.isEmpty())
        repository.saveRecipe(requireNotNull(recipe.recipe), requireNotNull(recipe.food), recipe.ingredients)
        return diaryId
    }

    private companion object {
        const val CUSTOM_ID = "custom:release-check"
        const val RECIPE_ID = "recipe:release-check"
        const val FARALI_ID = "india-packaged:341"
    }
}

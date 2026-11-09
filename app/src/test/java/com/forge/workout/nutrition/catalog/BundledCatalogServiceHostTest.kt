package com.forge.workout.nutrition.catalog

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.DiaryEntity
import com.forge.workout.nutrition.model.FavoriteEntity
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodNutrientEntity
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.MealType
import com.forge.workout.nutrition.model.NutritionMath
import com.forge.workout.nutrition.model.RecipeEntity
import com.forge.workout.nutrition.model.RecipeIngredientEntity
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real APK assets and Room with controlled document and HTTPS streams. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BundledCatalogServiceHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var application: Application
    private lateinit var database: ForgeDatabase
    private lateinit var foods: RoomNutritionRepository
    private var documentNumber = 0

    @Before fun openDatabase() {
        application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        foods = RoomNutritionRepository(database)
    }

    @After fun closeDatabase() { database.close() }

    @Test fun `known superseded bundle upgrades reference units without changing historical or personal data`() = runBlocking<Unit> {
        val manifest = ZipInputStream(application.assets.open(BUNDLED_ASSET)).use { zip ->
            assertEquals("manifest.json", zip.nextEntry.name)
            CatalogPackCodec.manifest(zip.readBytes(), 0L)
        }
        assertFalse("The corrected asset must have a new payload identity", manifest.pack.sha256 == OLD_BUNDLED_SHA)
        val dao = database.nutritionDao()
        // A compact installed-revision fixture exercises the trusted previous bundle identity.
        dao.upsertCatalogPack(manifest.pack.copy(version = "2026-10-12", sha256 = OLD_BUNDLED_SHA, foodCount = 1))
        dao.insertFoods(listOf(FoodEntity(id = JUICE_ID, name = "Historical juice reference",
            sourceType = FoodSourceType.REFERENCE, packId = PACK_ID, source = "USDA branded label",
            baseAmount = 100.0, baseUnit = FoodUnit.GRAM, gramsPerBase = 100.0)))
        dao.insertNutrients(listOf(FoodNutrientEntity(JUICE_ID, "energy", "Energy", "kcal", 40.0)))
        val original = requireNotNull(foods.food(JUICE_ID))
        val snapshot = original.snapshot()
        val diaryId = foods.saveDiary(DiaryEntity(day = "2026-10-29", meal = MealType.BREAKFAST,
            foodId = JUICE_ID, snapshot = snapshot, quantity = 250.0, unit = FoodUnit.GRAM,
            factor = 2.5, loggedAt = 1234L))
        val recipeId = "custom:historical-juice-recipe"
        val recipeFood = FoodDetail(FoodEntity(id = recipeId, name = "Historical juice recipe",
            sourceType = FoodSourceType.RECIPE, baseAmount = 1.0, baseUnit = FoodUnit.SERVING))
        foods.saveRecipe(RecipeEntity(recipeId, recipeFood.food.name, servings = 1.0), recipeFood,
            listOf(RecipeIngredientEntity(recipeId = recipeId, orderIndex = 0, foodId = JUICE_ID,
                snapshot = snapshot, quantity = 250.0, unit = FoodUnit.GRAM, factor = 2.5)))
        foods.upsertFood(FoodDetail(FoodEntity(id = "custom:personal", name = "Personal food")))
        foods.saveFavorite(FavoriteEntity(JUICE_ID, original.food.name, 1234L))
        val diaryBefore = requireNotNull(foods.diaryEntry(diaryId))
        val recipeBefore = requireNotNull(foods.recipe(recipeId))
        val recipeFoodBefore = requireNotNull(foods.food(recipeId))
        val customBefore = requireNotNull(foods.food("custom:personal"))
        val favoritesBefore = foods.observeFavorites().first()

        val upgraded = service().ensureBundled()
        assertFalse("The known obsolete bundle must be replaced, not treated as an external revision", upgraded.alreadyInstalled)
        assertEquals(manifest.pack.sha256, upgraded.pack.sha256)
        val corrected = requireNotNull(foods.food(JUICE_ID))
        assertEquals(original.food.rowId, corrected.food.rowId)
        assertEquals(FoodUnit.MILLILITER, corrected.food.baseUnit)
        assertEquals(100.0, corrected.food.millilitersPerBase!!, 0.0)
        assertTrue(corrected.food.gramsPerBase == null)
        val choices = NutritionMath.choices(corrected)
        assertFalse(choices.any { it.unit == FoodUnit.GRAM })
        val milliliters = choices.single { it.unit == FoodUnit.MILLILITER }
        val calories = NutritionMath.scaled(corrected.snapshot(), NutritionMath.factor(corrected.snapshot(), milliliters, 250.0))
            .single { it.code == "energy" }.value
        assertEquals(100.0, requireNotNull(calories), 0.000001)
        assertEquals(diaryBefore, foods.diaryEntry(diaryId))
        assertEquals(recipeBefore, foods.recipe(recipeId))
        assertEquals(recipeFoodBefore, foods.food(recipeId))
        assertEquals(customBefore, foods.food("custom:personal"))
        assertEquals(favoritesBefore, foods.observeFavorites().first())
        assertTrue(service().ensureBundled().alreadyInstalled)
        assertForeignKeys()
    }

    @Test fun `missing bundled catalog seeds once and missing rows with a corrupt stored count can be repaired`() = runBlocking<Unit> {
        val manifest = ZipInputStream(application.assets.open(BUNDLED_ASSET)).use { zip ->
            assertEquals("manifest.json", zip.nextEntry.name)
            CatalogPackCodec.manifest(zip.readBytes(), 0L)
        }
        val seeded = service().ensureBundled()
        assertFalse(seeded.alreadyInstalled)
        assertEquals(manifest.pack.copy(installedAt = seeded.pack.installedAt), seeded.pack)
        assertEquals(listOf(manifest.pack.foodCount, 0, 0), listOf(seeded.added, seeded.updated, seeded.removed))
        assertEquals(manifest.pack.foodCount, database.nutritionDao().catalogFoodCount(PACK_ID))
        val banana = requireNotNull(foods.food("usda:fdc:173944"))
        val preservedFood = requireNotNull(foods.food("usda:fdc:2617100"))

        // A fresh service represents app/ViewModel recreation, so no in-memory request flag helps.
        val replay = service().ensureBundled()
        assertTrue(replay.alreadyInstalled)
        assertEquals(seeded.pack, replay.pack)
        assertEquals(listOf(0, 0, 0), listOf(replay.added, replay.updated, replay.removed))
        assertEquals(banana, foods.food(banana.food.id))
        assertEquals(preservedFood, foods.food(preservedFood.food.id))

        database.nutritionDao().deleteFood(banana.food.id)
        assertEquals(manifest.pack.foodCount - 1, database.nutritionDao().catalogFoodCount(PACK_ID))
        // Even when a damaged stored count matches the truncated rows, this exact SHA has
        // an authoritative expected count in the APK manifest and must be repaired.
        database.nutritionDao().upsertCatalogPack(seeded.pack.copy(foodCount = manifest.pack.foodCount - 1))
        val repaired = service().ensureBundled()
        assertFalse(repaired.alreadyInstalled)
        assertEquals(listOf(1, manifest.pack.foodCount - 1, 0), listOf(repaired.added, repaired.updated, repaired.removed))
        val restored = requireNotNull(foods.food(banana.food.id))
        assertEquals(banana.copy(food = banana.food.copy(rowId = restored.food.rowId)), restored)
        assertEquals(preservedFood, foods.food(preservedFood.food.id))
        assertEquals(manifest.pack.foodCount, database.nutritionDao().catalogFoodCount(PACK_ID))
        assertTrue(service().ensureBundled().alreadyInstalled)
        assertForeignKeys()
    }

    @Test fun `explicit same id updates with newer and arbitrary versions survive recreated services`() = runBlocking<Unit> {
        var retainedRowId: Long? = null
        // Version text is intentionally unordered, and each revision has its own complete count.
        listOf("2099-01-01", "release-z", "release-a", "2", "10", "2020-01-01").forEachIndexed { index, version ->
            val rows = listOf("retained", "second", "third").take(1 + index % 3)
            val result = importDocument(service(), archive(version, rows, energy = 100.0 + index))
            assertFalse(result.alreadyInstalled)
            assertEquals(version, result.pack.version)
            assertEquals(rows.size, result.pack.foodCount)
            val retained = requireNotNull(foods.food("regression:retained"))
            if (retainedRowId == null) retainedRowId = retained.food.rowId
            else assertEquals(requireNotNull(retainedRowId), retained.food.rowId)
            val before = databaseGraph()

            repeat(2) {
                val recreated = service()
                val initialized = recreated.ensureBundled()
                assertTrue(initialized.alreadyInstalled)
                assertEquals(result.pack, initialized.pack)
                assertEquals(listOf(0, 0, 0), listOf(initialized.added, initialized.updated, initialized.removed))
                assertEquals(rows.size, recreated.state.value.foodCount)
                assertEquals(before, databaseGraph())
            }
        }
        assertForeignKeys()
    }

    @Test fun `incomplete different payload is preserved until explicit reimport repairs or updates it`() = runBlocking<Unit> {
        val originalArchive = archive("2099-01-01", listOf("retained", "second"))
        val original = importDocument(service(), originalArchive)
        val reference = requireNotNull(foods.food("regression:retained"))
        foods.upsertFood(FoodDetail(FoodEntity(id = "custom:personal", name = "Personal food")))
        foods.saveDiary(DiaryEntity(day = "2026-10-29", meal = MealType.BREAKFAST,
            foodId = reference.food.id, snapshot = reference.snapshot(), quantity = 100.0,
            unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 1234L))
        foods.saveFavorite(FavoriteEntity(reference.food.id, reference.food.name, 1234L))
        database.nutritionDao().deleteFood("regression:second")

        // Bad stored counts and missing rows must never be accepted merely because both are zero.
        for (expectedCount in listOf(2, 0, -1, 3)) {
            database.nutritionDao().upsertCatalogPack(original.pack.copy(foodCount = expectedCount))
            assertIncompletePreserved()
        }
        database.nutritionDao().deleteFood(reference.food.id)
        for (expectedCount in listOf(2, 0)) {
            database.nutritionDao().upsertCatalogPack(original.pack.copy(foodCount = expectedCount))
            assertIncompletePreserved()
        }

        val explicitService = service()
        val repaired = importDocument(explicitService, originalArchive)
        assertFalse(repaired.alreadyInstalled)
        assertEquals(listOf(2, 0, 0), listOf(repaired.added, repaired.updated, repaired.removed))
        assertEquals(2, database.nutritionDao().catalogFoodCount(PACK_ID))
        assertTrue(service().ensureBundled().alreadyInstalled)

        // Choosing another revision is still allowed through the explicit import path.
        val updated = importDocument(explicitService, archive("release-before", listOf("retained"), energy = 240.0))
        assertFalse(updated.alreadyInstalled)
        assertEquals(listOf(0, 1, 1), listOf(updated.added, updated.updated, updated.removed))
        val before = databaseGraph()
        val initialized = service().ensureBundled()
        assertTrue(initialized.alreadyInstalled)
        assertEquals(updated.pack, initialized.pack)
        assertEquals(before, databaseGraph())
        assertNotNull(foods.food("custom:personal"))
        assertEquals(reference.snapshot(), foods.observeDiary("2026-10-29").first().single().snapshot)
        assertForeignKeys()
    }

    @Test fun `interrupted and corrupt HTTPS downloads leave installed foods intact and a retry commits atomically`() = runBlocking<Unit> {
        val original = importDocument(service(), archive("1.0.0", listOf("retained")))
        val before = databaseGraph()
        val updated = archive("1.1.0", listOf("retained", "second"), energy = 240.0)
        val checksum = sha256(updated)
        val interrupted = object : ByteArrayInputStream(updated.copyOf(64)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                if (it == -1) throw IOException("Connection interrupted")
            }
        }
        val url = "https://example.org/catalog.forgefoods"
        val assetUrl = "https://assets.example.org/catalog.forgefoods"
        val responses = ArrayDeque(listOf(
            CatalogResponse(URL(url), input = interrupted),
            CatalogResponse(URL(url), input = updated.copyOf(64).inputStream()),
            CatalogResponse(URL(url), status = 302, location = assetUrl),
            CatalogResponse(URL(assetUrl), input = updated.inputStream()),
        ))
        val disconnected = mutableListOf<CatalogResponse>()
        val downloader = AndroidFoodCatalogService(application, database, openConnection = { address ->
            responses.removeFirst().also { response ->
                assertEquals(response.url.toString(), address.toString())
                disconnected += response
            }
        })
        for (issue in listOf(CatalogIssue.IO_ERROR, CatalogIssue.HASH_MISMATCH)) {
            val failure = runCatching { downloader.installUrl(url, checksum) }.exceptionOrNull() as CatalogException
            assertEquals(issue, failure.issue)
            assertEquals(issue, downloader.state.value.error?.issue)
            assertFalse(downloader.state.value.busy)
            assertEquals(before, databaseGraph())
        }
        val installed = downloader.installUrl(url, checksum)
        assertEquals(original.pack.id, installed.pack.id)
        assertEquals("1.1.0", installed.pack.version)
        assertEquals(2, installed.pack.foodCount)
        assertEquals(null, downloader.state.value.error)
        assertTrue(responses.isEmpty())
        assertEquals(4, disconnected.size)
        assertTrue(disconnected.all { it.closed })
        assertForeignKeys()
    }

    private class CatalogResponse(url: URL, private val input: InputStream? = null, private val status: Int = 200,
        private val location: String? = null) : HttpURLConnection(url) {
        var closed = false
            private set
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = requireNotNull(input)
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    private fun service() = AndroidFoodCatalogService(application, database)

    private suspend fun importDocument(service: AndroidFoodCatalogService, bytes: ByteArray): CatalogInstallResult {
        val document = temporary.newFile("catalog-${documentNumber++}.forgefoods")
        document.writeBytes(bytes)
        return service.installDocument(Uri.fromFile(document))
    }

    private suspend fun assertIncompletePreserved() {
        val before = databaseGraph()
        val recreated = service()
        try {
            recreated.ensureBundled()
            throw AssertionError("Expected the incomplete imported catalog to require explicit reimport")
        } catch (error: CatalogException) {
            assertEquals(CatalogIssue.INVALID_PACK, error.issue)
            assertTrue(error.message.orEmpty().contains("Import"))
            assertTrue(error.message.orEmpty().contains(".forgefoods"))
            assertEquals(error.message, recreated.state.value.error?.detail)
        }
        assertFalse(recreated.state.value.busy)
        assertEquals(CatalogIssue.INVALID_PACK, recreated.state.value.error?.issue)
        assertEquals(before, databaseGraph())
    }

    private suspend fun databaseGraph(): Map<String, List<List<String?>>> = withContext(Dispatchers.IO) {
        listOf("nutrition_catalog_packs", "nutrition_foods", "nutrition_food_nutrients", "nutrition_food_portions",
            "nutrition_diary", "nutrition_favorites", "nutrition_recipes", "nutrition_recipe_ingredients").associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
            }
        }
    }

    private fun assertForeignKeys() {
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private fun archive(version: String, ids: List<String>, energy: Double = 120.0): ByteArray {
        val rows = ids.map { id ->
            JSONObject().put("id", "regression:$id").put("name", "Imported $id").put("brand", "")
                .put("aliases", "").put("source", "Regression source").put("sourceId", id)
                .put("sourceUrl", "https://example.invalid/$id").put("region", JSONObject.NULL).put("tags", "")
                .put("baseAmount", 100.0).put("baseUnit", "GRAM").put("gramsPerBase", 100.0)
                .put("millilitersPerBase", JSONObject.NULL).put("notes", "")
                .put("nutrients", JSONArray().put(JSONObject().put("code", "energy").put("name", "Energy")
                    .put("unit", "kcal").put("value", energy)))
                .put("portions", JSONArray().put(JSONObject().put("id", "cup").put("label", "Cup")
                    .put("baseQuantity", 150.0).put("grams", 150.0).put("milliliters", JSONObject.NULL)
                    .put("source", "Regression source")))
        }
        val payload = rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val digest = sha256(payload)
        val manifest = JSONObject().put("format", "forge-food-catalog").put("formatVersion", 1).put("id", PACK_ID)
            .put("name", "Explicit regression catalog").put("version", version)
            .put("sourceUrl", "https://example.invalid/catalog").put("license", "Regression fixture")
            .put("citation", "Regression source").put("sha256", digest).put("foodCount", rows.size)
            .put("payloadBytes", payload.size.toLong()).put("sourceCounts", JSONObject().put("Regression source", rows.size))
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                mapOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8), "foods.jsonl" to payload).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val PACK_ID = "usda-core"
        const val BUNDLED_ASSET = "nutrition/usda-core.forgefoods"
        const val OLD_BUNDLED_SHA = "5b861595400a6e0a5c2eeb845962c6b2fe191bdb2c5a8138886d473aec93a3fb"
        const val JUICE_ID = "usda:fdc:2598460"
    }
}

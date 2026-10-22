package com.forge.workout.nutrition.catalog

import android.app.Application
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Local ZIP streams and real SQLite only; the full bundled catalog is installed in one test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogImportHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: ForgeDatabase
    private lateinit var foods: RoomNutritionRepository
    private lateinit var installer: CatalogPackInstaller

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        foods = RoomNutritionRepository(database)
        installer = CatalogPackInstaller(database, temporary.newFolder("catalog"), clock = { 1234L }, ioDispatcher = Dispatchers.IO)
    }

    @After fun closeDatabase() { database.close() }

    @Test fun `install update and idempotent replay retain stable rows and immutable diary recipe snapshots`() = runBlocking {
        val originalZip = archive(listOf(foodJson("oats", "Original oats"), foodJson("rice", "Rice")))
        val input = TrackingInput(originalZip)
        val first = installer.install(input, expectedZipSha256 = sha(originalZip))
        assertTrue(input.closed)
        assertEquals(2, first.added)
        assertEquals(0, first.updated)
        assertEquals(0, first.removed)
        assertFalse(first.alreadyInstalled)
        assertNull(foods.food("oats")!!.nutrients.single { it.code == "fiber" }.value)
        assertStagingClean()
        val before = foodGraph()
        val replay = installer.install(ByteArrayInputStream(originalZip))
        assertTrue(replay.alreadyInstalled)
        assertEquals(listOf(0, 0, 0), listOf(replay.added, replay.updated, replay.removed))
        assertEquals(before, foodGraph())
        assertEquals(first.pack, foods.observeCatalogPacks().first().single())

        val oats = foods.food("oats")!!
        val snapshot = oats.snapshot()
        val diaryId = foods.saveDiary(DiaryEntity(day = DAY, meal = MealType.BREAKFAST, foodId = "oats",
            snapshot = snapshot, quantity = 150.0, unit = FoodUnit.GRAM, factor = 1.5, loggedAt = 1000))
        val recipeFood = FoodDetail(FoodEntity(id = "custom-recipe", name = "Oat bowl", sourceType = FoodSourceType.RECIPE,
            baseAmount = 1.0, baseUnit = FoodUnit.SERVING, gramsPerBase = 150.0))
        foods.saveRecipe(RecipeEntity("custom-recipe", "Oat bowl", servings = 1.0), recipeFood,
            listOf(RecipeIngredientEntity(recipeId = "custom-recipe", orderIndex = 0, foodId = "oats", snapshot = snapshot,
                quantity = 150.0, unit = FoodUnit.GRAM, factor = 1.5)))
        foods.saveFavorite(FavoriteEntity("oats", "Oats", 1))
        val diaryBefore = foods.diaryEntry(diaryId)
        val recipeBefore = foods.recipe("custom-recipe")
        val changedZip = archive(listOf(foodJson("oats", "Updated barley", energy = 220.0), foodJson("beans", "Beans")), version = "2")
        val update = installer.install(ByteArrayInputStream(changedZip))
        assertEquals(listOf(1, 1, 1), listOf(update.added, update.updated, update.removed))
        assertFalse(update.alreadyInstalled)
        assertEquals(oats.food.rowId, foods.food("oats")!!.food.rowId)
        assertEquals(220.0, nutrient(foods.food("oats")!!, NutrientCodes.ENERGY), 0.000001)
        assertNull(foods.food("rice"))
        assertNotNull(foods.food("beans"))
        assertEquals(diaryBefore, foods.diaryEntry(diaryId))
        assertEquals(recipeBefore, foods.recipe("custom-recipe"))
        assertEquals("oats", foods.observeFavorites().first().single().foodId)
        assertTrue(foods.searchFoods("Original").isEmpty())
        assertEquals(listOf("oats"), foods.searchFoods("Updated").map { it.id })
        assertEquals(2, foods.observeCatalogPacks().first().single().foodCount)
        assertForeignKeys()
    }

    @Test fun `payload hash format version count and strict food types reject before modifying installed data`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats")))))
        val before = databaseGraph()
        val valid = listOf(foodJson("oats", "Replacement oats"), foodJson("beans", "Beans"))
        val cases = listOf(
            CatalogIssue.HASH_MISMATCH to archive(valid, mutateManifest = { it.put("sha256", "0".repeat(64)) }),
            CatalogIssue.UNSUPPORTED_VERSION to archive(valid, mutateManifest = { it.put("formatVersion", 99) }),
            CatalogIssue.INVALID_PACK to archive(valid, mutateManifest = { it.put("format", "another-format") }),
            CatalogIssue.INVALID_PACK to archive(valid, mutateManifest = { it.put("foodCount", 3); it.put("sourceCounts", JSONObject().put("Test source", 3)) }),
            CatalogIssue.INVALID_PACK to archive(valid, mutateManifest = { it.put("sourceCounts", JSONObject().put("Test source", 1)) }),
            CatalogIssue.INVALID_PACK to archive(valid, mutateManifest = { it.put("payloadBytes", it.getLong("payloadBytes") + 1) }),
            CatalogIssue.INVALID_PACK to archive(listOf(foodJson("oats", "Replacement oats").put("baseUnit", "INVALID"))),
            CatalogIssue.INVALID_PACK to archive(listOf(foodJson("oats", "Replacement oats").put("brand", 17))),
            CatalogIssue.INVALID_PACK to archive(listOf(foodJson("oats", "Replacement oats").apply {
                getJSONArray("nutrients").getJSONObject(0).put("value", true)
            })),
        )
        cases.forEach { (issue, zip) ->
            reject(issue) { installer.install(ByteArrayInputStream(zip)) }
            assertEquals(before, databaseGraph())
            assertStagingClean()
        }
        val zip = archive(valid)
        reject(CatalogIssue.HASH_MISMATCH) { installer.install(ByteArrayInputStream(zip), expectedZipSha256 = "0".repeat(64)) }
        assertEquals(before, databaseGraph())
    }

    @Test fun `duplicate payload IDs and collisions with custom foods or another pack never overwrite resident data`() = runBlocking {
        val custom = FoodDetail(FoodEntity(id = "user-food", name = "My food"),
            listOf(FoodNutrientEntity("user-food", NutrientCodes.ENERGY, "Energy", "kcal", 400.0)))
        foods.upsertFood(custom)
        val beforeCustom = databaseGraph()
        reject(CatalogIssue.INVALID_PACK) {
            installer.install(ByteArrayInputStream(archive(listOf(foodJson("duplicate", "A"), foodJson("duplicate", "B")))))
        }
        assertEquals(beforeCustom, databaseGraph())
        reject(CatalogIssue.ID_CONFLICT) {
            installer.install(ByteArrayInputStream(archive(listOf(foodJson("new-food", "New"), foodJson("user-food", "Overwrite")))))
        }
        assertEquals(beforeCustom, databaseGraph())
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("shared", "First pack food")), packId = "first-pack")))
        val beforeOtherPack = databaseGraph()
        reject(CatalogIssue.ID_CONFLICT) {
            installer.install(ByteArrayInputStream(archive(listOf(foodJson("other", "Other"), foodJson("shared", "Overwrite")), packId = "second-pack")))
        }
        assertEquals(beforeOtherPack, databaseGraph())
        assertEquals(FoodSourceType.CUSTOM, foods.food("user-food")!!.food.sourceType)
        assertEquals("first-pack", foods.food("shared")!!.food.packId)
    }

    @Test fun `malformed ZIP missing entries untrusted entry names and invalid UTF8 leave installed data intact`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats")))))
        val before = databaseGraph()
        val payload = (foodJson("oats", "Replacement oats").toString() + "\n").toByteArray(Charsets.UTF_8)
        val manifest = manifest(payload, 1).toString().toByteArray(Charsets.UTF_8)
        val invalidUtf8 = byteArrayOf(0xc3.toByte(), 0x28)
        val cases = listOf(
            "not a ZIP".toByteArray(),
            zip(mapOf("manifest.json" to manifest)),
            zip(mapOf("manifest.json" to manifest, "../foods.jsonl" to payload)),
            zip(mapOf("manifest.json" to manifest, "foods.jsonl" to payload, "unexpected.txt" to byteArrayOf(1))),
            zip(mapOf("manifest.json" to manifest(invalidUtf8, 1).toString().toByteArray(Charsets.UTF_8), "foods.jsonl" to invalidUtf8)),
        )
        cases.forEach { archive ->
            reject(CatalogIssue.INVALID_PACK) { installer.install(ByteArrayInputStream(archive)) }
            assertEquals(before, databaseGraph())
        }
    }

    @Test fun `storage abort rolls updated rows deleted children pack metadata and FTS back together`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats"), foodJson("rice", "Rice")))))
        val before = databaseGraph()
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_catalog BEFORE INSERT ON nutrition_food_nutrients WHEN NEW.foodId = 'new-food' BEGIN SELECT RAISE(ABORT, 'Reject imported nutrient'); END")
        reject(CatalogIssue.IO_ERROR) {
            installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Replacement barley", 900.0), foodJson("new-food", "New food")), version = "2")))
        }
        assertEquals(before, databaseGraph())
        assertEquals(listOf("oats"), foods.searchFoods("Original").map { it.id })
        assertTrue(foods.searchFoods("Replacement").isEmpty())
        assertNotNull(foods.food("rice"))
        assertNull(foods.food("new-food"))
        assertForeignKeys()
    }

    @Test fun `input stream failure is closed and cannot change previously installed rows`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats")))))
        val before = databaseGraph()
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Injected local stream failure")
            override fun close() { closed = true }
        }
        reject(CatalogIssue.IO_ERROR) { installer.install(input) }
        assertTrue(closed)
        assertEquals(before, databaseGraph())
        assertStagingClean()
    }

    @Test fun `insufficient available storage fails before mutation and closes and cleans the input`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats")))))
        val before = databaseGraph()
        val constrained = CatalogPackInstaller(database, temporary.root.resolve("catalog"), clock = { 1234L },
            ioDispatcher = Dispatchers.IO, availableBytes = { 0L })
        val input = TrackingInput(archive(listOf(foodJson("oats", "Replacement oats")), version = "2"))
        reject(CatalogIssue.INSUFFICIENT_STORAGE) { constrained.install(input) }
        assertTrue(input.closed)
        assertEquals(before, databaseGraph())
        assertStagingClean()
    }

    @Test fun `cancellation after the first written batch rolls metadata food children and FTS back`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats"), foodJson("rice", "Rice")))))
        val before = databaseGraph()
        val rows = listOf(foodJson("oats", "Replacement oats", energy = 900.0)) +
            (1..100).map { foodJson("new-$it", "New food $it") }
        val input = TrackingInput(archive(rows, version = "2"))
        val firstBatchWritten = AtomicBoolean(false)
        val operation = async {
            val ownJob = currentCoroutineContext()[Job]!!
            installer.install(input, onProgress = { progress, count ->
                // Validation progress stays below .55; this callback follows the first SQL batch.
                if (progress > 0.55f && count == 100) {
                    firstBatchWritten.set(true)
                    ownJob.cancel(CancellationException("Cancel after first written batch"))
                }
            })
        }
        try { operation.await(); fail("Expected the import operation to be cancelled") }
        catch (_: CancellationException) { }
        currentCoroutineContext().ensureActive()
        assertTrue("Cancellation must happen after writes, not just during ZIP validation", firstBatchWritten.get())
        assertTrue(input.closed)
        assertEquals(before, databaseGraph())
        assertEquals(listOf("oats"), foods.searchFoods("Original").map { it.id })
        assertTrue(foods.searchFoods("Replacement").isEmpty())
        assertTrue(foods.searchFoods("New food").isEmpty())
        assertStagingClean()
        assertForeignKeys()
    }

    @Test fun `strict JSON rejects duplicate fields trailing content deep nesting and bounded oversized records`() = runBlocking {
        installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Original oats")))))
        val before = databaseGraph()
        val original = foodJson("oats", "Replacement oats").toString()
        val cases = listOf(
            original.replace("\"name\":", "\"name\":\"Duplicate field\",\"name\":") to "Duplicate JSON field",
            "$original{}" to "Trailing JSON content",
            original.replace("\"notes\":\"\"", "\"notes\":" + "[".repeat(32) + "0" + "]".repeat(32)) to "too deeply nested",
        )
        cases.forEach { (row, diagnostic) ->
            val payload = (row + "\n").toByteArray(Charsets.UTF_8)
            val invalidArchive = zip(mapOf("manifest.json" to manifest(payload, 1).toString().toByteArray(Charsets.UTF_8),
                "foods.jsonl" to payload))
            val failure = reject(CatalogIssue.INVALID_PACK) { installer.install(ByteArrayInputStream(invalidArchive)) }
            assertTrue(failure.message.orEmpty().contains(diagnostic))
            assertEquals(before, databaseGraph())
            assertStagingClean()
        }
        reject(CatalogIssue.TOO_LARGE) {
            installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Replacement oats")
                .put("notes", "x".repeat(CatalogPackInstaller.MAX_RECORD_BYTES + 1))))))
        }
        reject(CatalogIssue.TOO_LARGE) {
            installer.install(ByteArrayInputStream(archive(listOf(foodJson("oats", "Replacement oats")),
                mutateManifest = { it.put("payloadBytes", CatalogPackInstaller.MAX_PAYLOAD_BYTES + 1) })))
        }
        assertEquals(before, databaseGraph())
        assertStagingClean()
    }

    @Test fun `bundled USDA catalog retains official values portions provenance full counts and bounded indexed search`() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as Application
        val result = installer.install(application.assets.open("nutrition/usda-core.forgefoods"))
        assertEquals("usda-core", result.pack.id)
        assertEquals(13_324, result.pack.foodCount)
        assertEquals(13_324, result.added)
        assertEquals(0, result.updated)
        assertEquals(0, result.removed)
        assertEquals("5b861595400a6e0a5c2eeb845962c6b2fe191bdb2c5a8138886d473aec93a3fb", result.pack.sha256)
        assertEquals(76_935_451L, result.pack.payloadBytes)
        assertEquals(13_324, count("nutrition_foods"))
        assertEquals(993_304, count("nutrition_food_nutrients"))
        assertEquals(36_142, count("nutrition_food_portions"))
        assertEquals(7_793, sourceCount("USDA SR Legacy (2018)"))
        assertEquals(5_332, sourceCount("USDA FNDDS 2021–2023"))
        assertEquals(199, sourceCount("USDA branded label"))
        val banana = foods.food("usda:fdc:173944")!!
        assertEquals("Bananas, raw", banana.food.name)
        assertEquals("173944", banana.food.sourceId)
        assertEquals("https://fdc.nal.usda.gov/food-details/173944/nutrients", banana.food.sourceUrl)
        assertEquals(FoodSourceType.REFERENCE, banana.food.sourceType)
        assertEquals("usda-core", banana.food.packId)
        assertEquals(100.0, banana.food.baseAmount, 0.0)
        assertEquals(FoodUnit.GRAM, banana.food.baseUnit)
        assertEquals(100.0, banana.food.gramsPerBase!!, 0.0)
        assertEquals(89.0, nutrient(banana, NutrientCodes.ENERGY), 0.0)
        assertEquals(1.09, nutrient(banana, NutrientCodes.PROTEIN), 0.000001)
        assertEquals(22.84, nutrient(banana, NutrientCodes.CARBS), 0.000001)
        assertEquals(0.33, nutrient(banana, NutrientCodes.FAT), 0.000001)
        assertTrue("Detailed source nutrients must not be reduced to just macros", banana.nutrients.size > 100)
        val mashed = banana.portions.single { it.id == "sr:93511" }
        assertEquals("1 cup, mashed", mashed.label)
        assertEquals(225.0, mashed.baseQuantity, 0.0)
        assertEquals(225.0, mashed.grams!!, 0.0)
        assertEquals(150.0, banana.portions.single { it.id == "sr:93512" }.grams!!, 0.0)
        val branded = foods.food("usda:fdc:2617100")!!
        assertEquals("GOLDEN PLATTER", branded.food.brand)
        assertEquals(194.0, nutrient(branded, NutrientCodes.ENERGY), 0.0)
        assertEquals(18.82, nutrient(branded, NutrientCodes.PROTEIN), 0.000001)
        assertEquals(8.24, nutrient(branded, NutrientCodes.CARBS), 0.000001)
        assertEquals(9.41, nutrient(branded, NutrientCodes.FAT), 0.000001)
        val label = branded.portions.single { it.id == "label" }
        assertEquals("5 nuggets (85g)", label.label)
        assertEquals(85.0, label.grams!!, 0.0)
        assertEquals(164.9, nutrient(branded, NutrientCodes.ENERGY) * label.baseQuantity / branded.food.baseAmount, 0.000001)
        assertTrue(foods.searchFoods("chapati", region = "IN").any { it.id == "usda:fdc:2707713" })

        // These names/aliases and stable IDs are verified in the packaged USDA payload itself.
        val aliases = listOf(
            "rOtI" to "usda:fdc:171844",
            "CHAPATI" to "usda:fdc:2707713",
            "chana mature raw" to "usda:fdc:173756",
            "CHICKPEA mature raw" to "usda:fdc:173756",
            "DaHi plain whole" to "usda:fdc:171284",
            "yogurt plain whole" to "usda:fdc:171284",
        )
        for ((query, expectedId) in aliases) {
            val matches = foods.observeFoodPage(query, FoodBrowseFilter.INDIAN).first()
            assertTrue("Real catalog query '$query' must include $expectedId", matches.any { it.id == expectedId })
            assertTrue(matches.size <= 50)
            assertTrue(matches.all { it.region.equals("IN", ignoreCase = true) && !it.archived })
        }
        val allPage = foods.observeFoodPage().first()
        assertEquals(50, allPage.size)
        assertEquals(50, allPage.distinctBy { it.id }.size)
        val indianPage = foods.observeFoodPage(filter = FoodBrowseFilter.INDIAN).first()
        assertEquals(50, indianPage.size)
        assertTrue(indianPage.all { it.region.equals("IN", ignoreCase = true) })
        val brandedPage = foods.observeFoodPage(filter = FoodBrowseFilter.BRANDED).first()
        assertEquals(50, brandedPage.size)
        assertTrue(brandedPage.all { it.brand.isNotBlank() && !it.archived })
        assertTrue(foods.observeFoodPage("gOlDeN pLaTtEr", FoodBrowseFilter.BRANDED).first()
            .any { it.id == "usda:fdc:2617100" })

        val queryStarted = System.nanoTime()
        val first = foods.searchFoods("rice", limit = 50)
        val second = foods.searchFoods("rice", limit = 50, offset = 50)
        assertEquals(50, first.size)
        assertEquals(50, second.size)
        assertTrue(first.map { it.id }.toSet().intersect(second.map { it.id }.toSet()).isEmpty())
        assertEquals(first, foods.observeFoods("rice", limit = 50).first())
        val plan = queryPlan("EXPLAIN QUERY PLAN SELECT f.id FROM nutrition_foods f INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid WHERE nutrition_food_search MATCH '\"rice*\"' AND f.archived = 0 ORDER BY f.name COLLATE NOCASE, f.id LIMIT 50")
        assertTrue("Food search must use FTS", plan.any { it.contains("VIRTUAL TABLE", true) })
        val browsePlan = queryPlan("EXPLAIN QUERY PLAN SELECT id FROM nutrition_foods WHERE archived = 0 ORDER BY name COLLATE NOCASE, id LIMIT 50")
        assertTrue("Bounded browsing must use the archived/name index", browsePlan.any { it.contains("index_nutrition_foods_archived_name") })
        // Host durations are diagnostic, not a frame or physical-device benchmark.
        println("USDA bounded catalog query diagnostic: ${(System.nanoTime() - queryStarted) / 1_000_000} ms; FTS plan=$plan; browse plan=$browsePlan")
        assertForeignKeys()
    }

    private suspend fun foodGraph() = foods.searchFoods(limit = 200).map { foods.food(it.id)!! }.sortedBy { it.food.id }
    private suspend fun databaseGraph() = foods.observeCatalogPacks().first() to foodGraph()
    private fun nutrient(detail: FoodDetail, code: String) = detail.nutrients.single { it.code == code }.value!!
    private fun count(table: String) = database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table")
        .use { it.moveToFirst(); it.getInt(0) }
    private fun sourceCount(source: String) = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM nutrition_foods WHERE source = ?", arrayOf(source)).use { it.moveToFirst(); it.getInt(0) }
    private fun queryPlan(sql: String) = database.openHelper.writableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }
    private fun assertForeignKeys() = database.openHelper.writableDatabase.query("PRAGMA foreign_key_check")
        .use { assertFalse(it.moveToFirst()) }
    private fun assertStagingClean() {
        assertTrue("Importer must clean its private temporary staging directories",
            temporary.root.resolve("catalog").listFiles()!!.isEmpty())
    }

    private suspend fun reject(issue: CatalogIssue, action: suspend () -> Unit): CatalogException {
        try { action(); throw AssertionError("Expected $issue") }
        catch (failure: CatalogException) { assertEquals(issue, failure.issue); return failure }
    }

    private fun archive(rows: List<JSONObject>, version: String = "1", packId: String = "fixture-pack",
        mutateManifest: (JSONObject) -> Unit = {}): ByteArray {
        val payload = rows.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val manifest = manifest(payload, rows.size, version, packId).also(mutateManifest)
        return zip(mapOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8), "foods.jsonl" to payload))
    }

    private fun manifest(payload: ByteArray, foodCount: Int, version: String = "1", packId: String = "fixture-pack") = JSONObject()
        .put("format", "forge-food-catalog").put("formatVersion", 1).put("id", packId)
        .put("name", "Test catalog").put("version", version).put("sourceUrl", "https://example.invalid/catalog")
        .put("license", "Test license").put("citation", "Host test source").put("sha256", sha(payload))
        .put("foodCount", foodCount).put("payloadBytes", payload.size.toLong())
        .put("sourceCounts", JSONObject().put("Test source", foodCount))

    private fun foodJson(id: String, name: String, energy: Double = 120.0) = JSONObject()
        .put("id", id).put("name", name).put("brand", "Test brand").put("aliases", "")
        .put("source", "Test source").put("sourceId", id).put("sourceUrl", "https://example.invalid/$id")
        .put("region", "IN").put("tags", "test").put("baseAmount", 100.0).put("baseUnit", "GRAM")
        .put("gramsPerBase", 100.0).put("millilitersPerBase", JSONObject.NULL).put("notes", "")
        .put("nutrients", JSONArray().put(JSONObject().put("code", "energy").put("name", "Energy").put("unit", "kcal").put("value", energy))
            .put(JSONObject().put("code", "protein").put("name", "Protein").put("unit", "g").put("value", 3.0))
            .put(JSONObject().put("code", "fiber").put("name", "Fiber").put("unit", "g").put("value", JSONObject.NULL)))
        .put("portions", JSONArray().put(JSONObject().put("id", "cup").put("label", "Cup").put("baseQuantity", 150.0)
            .put("grams", 150.0).put("milliliters", JSONObject.NULL).put("source", "Test source portion")))

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private class TrackingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private companion object { const val DAY = "2026-10-12" }
}

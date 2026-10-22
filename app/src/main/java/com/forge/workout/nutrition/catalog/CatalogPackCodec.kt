package com.forge.workout.nutrition.catalog

import com.forge.workout.nutrition.data.NutritionValidation
import com.forge.workout.nutrition.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal data class CatalogManifest(val pack: CatalogPackEntity, val sourceCounts: Map<String, Int>)

internal object CatalogPackCodec {
    fun manifest(bytes: ByteArray, installedAt: Long): CatalogManifest = parse {
        val json = objectJson(bytes)
        json.fields("format", "formatVersion", "id", "name", "version", "sourceUrl", "license", "citation",
            "sha256", "foodCount", "payloadBytes", "sourceCounts")
        require(json.string("format") == "forge-food-catalog") { "Not a Forge food catalog." }
        if (json.integer("formatVersion") != 1L) {
            throw CatalogException(CatalogIssue.UNSUPPORTED_VERSION, "Unsupported catalog format version.")
        }
        val count = json.integer("foodCount")
        val bytesCount = json.integer("payloadBytes")
        require(count > 0) { "Invalid catalog food count." }
        if (count > CatalogPackInstaller.MAX_FOODS) tooLarge("Catalog contains too many foods.")
        require(bytesCount > 0) { "Invalid catalog payload size." }
        if (bytesCount > CatalogPackInstaller.MAX_PAYLOAD_BYTES) tooLarge("Catalog payload is too large.")
        val checksum = json.string("sha256").lowercase(Locale.ROOT)
        require(checksum.matches(Regex("[0-9a-f]{64}"))) { "Invalid catalog checksum." }
        val counts = json.get("sourceCounts") as? JSONObject ?: error("Invalid source counts.")
        require(counts.length() in 1..100) { "Invalid source counts." }
        val sourceCounts = counts.keys().asSequence().associateWith { key ->
            NutritionValidation.text(key, 250, "Source label")
            val number = counts.integer(key)
            require(number in 0..CatalogPackInstaller.MAX_FOODS.toLong()) { "Invalid source count." }
            number.toInt()
        }
        require(sourceCounts.values.sumOf { it.toLong() } == count) { "Source counts do not match food count." }
        val pack = CatalogPackEntity(json.string("id"), json.string("name"), json.string("version"),
            json.string("sourceUrl"), json.string("license"), json.string("citation"), installedAt,
            checksum, count.toInt(), bytesCount)
        NutritionValidation.pack(pack)
        CatalogManifest(pack, sourceCounts)
    }

    fun food(bytes: ByteArray, packId: String): FoodDetail = parse {
        val json = objectJson(bytes)
        json.fields("id", "name", "brand", "aliases", "source", "sourceId", "sourceUrl", "region", "tags",
            "baseAmount", "baseUnit", "gramsPerBase", "millilitersPerBase", "notes", "nutrients", "portions")
        val food = FoodEntity(id = json.string("id"), name = json.string("name"), brand = json.string("brand"),
            aliases = json.string("aliases"), source = json.string("source"), sourceId = json.nullableString("sourceId"),
            sourceUrl = json.nullableString("sourceUrl"), sourceType = FoodSourceType.REFERENCE, packId = packId,
            region = json.nullableString("region"), tags = json.string("tags"), baseAmount = json.number("baseAmount"),
            baseUnit = FoodUnit.valueOf(json.string("baseUnit")), gramsPerBase = json.nullableNumber("gramsPerBase"),
            millilitersPerBase = json.nullableNumber("millilitersPerBase"), notes = json.string("notes"))
        val nutrients = json.array("nutrients", 300).objects().map { item ->
            item.fields("code", "name", "unit", "value")
            FoodNutrientEntity(food.id, item.string("code"), item.string("name"), item.string("unit"), item.nullableNumber("value"))
        }
        val portions = json.array("portions", 100).objects().map { item ->
            item.fields("id", "label", "baseQuantity", "grams", "milliliters", "source")
            FoodPortionEntity(food.id, item.string("id"), item.string("label"), item.number("baseQuantity"),
                item.nullableNumber("grams"), item.nullableNumber("milliliters"), item.nullableString("source"))
        }
        FoodDetail(food, nutrients, portions).also(NutritionValidation::food)
    }

    private fun objectJson(bytes: ByteArray): JSONObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        CatalogJsonSyntax(text).validate()
        return JSONObject(text)
    }

    private inline fun <T> parse(block: () -> T): T = try { block() }
    catch (error: CatalogException) { throw error }
    catch (error: Exception) { throw CatalogException(CatalogIssue.INVALID_PACK, error.message ?: "Invalid catalog data.", error) }

    private fun JSONObject.fields(vararg fields: String) {
        require(keys().asSequence().toSet() == fields.toSet()) { "Missing or unknown catalog fields." }
    }
    private fun JSONObject.string(key: String) = get(key) as? String ?: error("$key must be a string.")
    private fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "$key must be an integer." }
        return (value as Number).toLong()
    }
    private fun JSONObject.number(key: String): Double {
        val value = get(key) as? Number ?: error("$key must be a number.")
        return value.toDouble().also { require(it.isFinite()) { "$key must be finite." } }
    }
    private fun JSONObject.nullableNumber(key: String): Double? = if (get(key) == JSONObject.NULL) null else number(key)
    private fun JSONObject.array(key: String, max: Int): JSONArray =
        (get(key) as? JSONArray ?: error("$key must be an array.")).also { require(it.length() <= max) { "Excessive $key." } }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { index ->
        get(index) as? JSONObject ?: error("Expected a catalog object.")
    }
    private fun tooLarge(message: String): Nothing = throw CatalogException(CatalogIssue.TOO_LARGE, message)
}

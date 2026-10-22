package com.forge.workout.nutrition.catalog

import android.annotation.SuppressLint
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Two streaming passes keep the catalog bounded in memory; only the second pass writes to Room. */
// Staging and Room writes use currently free space; they do not reserve reclaimable cache bytes.
@SuppressLint("UsableSpace")
class CatalogPackInstaller(
    private val database: ForgeDatabase,
    private val cacheDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val availableBytes: () -> Long = { cacheDirectory.usableSpace },
) {
    private val dao = database.nutritionDao()

    /** Takes ownership of input. expectedZipSha256, when present, covers the entire ZIP file. */
    suspend fun install(
        input: InputStream,
        expectedZipSha256: String? = null,
        onProgress: (Float, Int) -> Unit = { _, _ -> },
    ): CatalogInstallResult {
        var sourceClosed = false
        try {
            return withContext(ioDispatcher) {
                var staging: File? = null
                try {
                    if (expectedZipSha256 != null && !expectedZipSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                        invalid("Expected ZIP checksum must be SHA-256.")
                    }
                    if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw IOException("Catalog cache is unavailable.")
                    val directory = Files.createTempDirectory(cacheDirectory.toPath(), "forge-catalog-").toFile()
                    staging = directory
                    val archive = File(directory, "pack.zip")
                    val copied = input.use { source ->
                        try {
                            copyBounded(source, archive, MAX_ZIP_BYTES, onBytes = { bytes ->
                                onProgress((bytes.toDouble() / MAX_ZIP_BYTES * 0.1).toFloat(), 0)
                            })
                        } finally { sourceClosed = true }
                    }
                    if (expectedZipSha256 != null && !copied.sha256.equals(expectedZipSha256, true)) {
                        throw CatalogException(CatalogIssue.HASH_MISMATCH, "Downloaded catalog checksum does not match.")
                    }
                    currentCoroutineContext().ensureActive()
                    onProgress(0.1f, 0)
                    val payload = File(directory, "foods.jsonl")
                    val manifest = extract(archive, payload, directory)
                    val ids = validate(payload, manifest, onProgress)
                    checkCollisions(ids, manifest.pack.id)
                    checkStorage(manifest.pack.payloadBytes)
                    onProgress(0.55f, manifest.pack.foodCount)
                    commit(payload, manifest, ids, onProgress).also { onProgress(1f, it.pack.foodCount) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CatalogException) {
                    throw error
                } catch (error: ZipException) {
                    throw CatalogException(CatalogIssue.INVALID_PACK, "Catalog archive is damaged.", error)
                } catch (error: Exception) {
                    throw CatalogException(CatalogIssue.IO_ERROR, "Catalog could not be stored or read.", error)
                } finally {
                    // Only fixed files inside this operation's private directory are removed.
                    staging?.let { directory ->
                        File(directory, "pack.zip").delete()
                        File(directory, "manifest.json").delete()
                        File(directory, "foods.jsonl").delete()
                        directory.delete()
                    }
                }
            }
        } finally {
            // Covers cancellation before the dispatcher starts as well as failures before copying.
            if (!sourceClosed) runCatching { input.close() }
        }
    }

    private suspend fun extract(archive: File, payload: File, directory: File): CatalogManifest {
        validateZipDirectory(archive)
        return ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().take(3).toList()
            if (entries.size != 2 || entries.map { it.name }.toSet() != setOf("manifest.json", "foods.jsonl") || entries.any { it.isDirectory }) {
                invalid("Catalog must contain only manifest.json and foods.jsonl.")
            }
            val metadataEntry = entries.single { it.name == "manifest.json" }
            val foodEntry = entries.single { it.name == "foods.jsonl" }
            if (metadataEntry.size > MAX_MANIFEST_BYTES || foodEntry.size > MAX_PAYLOAD_BYTES) tooLarge()
            val metadata = File(directory, "manifest.json")
            val metadataCopy = zip.getInputStream(metadataEntry).use { copyBounded(it, metadata, MAX_MANIFEST_BYTES.toLong()) }
            if (metadataCopy.crc32 != metadataEntry.crc) invalid("Catalog manifest checksum is invalid.")
            val manifest = CatalogPackCodec.manifest(metadata.readBytes(), clock())
            if (foodEntry.size != manifest.pack.payloadBytes) invalid("Catalog payload size does not match its manifest.")
            checkStorage(manifest.pack.payloadBytes)
            val copied = zip.getInputStream(foodEntry).use { copyBounded(it, payload, MAX_PAYLOAD_BYTES) }
            if (copied.crc32 != foodEntry.crc) invalid("Catalog payload checksum is invalid.")
            if (copied.bytes != manifest.pack.payloadBytes) invalid("Catalog payload size does not match its manifest.")
            if (copied.sha256 != manifest.pack.sha256) {
                throw CatalogException(CatalogIssue.HASH_MISMATCH, "Catalog payload checksum does not match.")
            }
            manifest
        }
    }

    private fun validateZipDirectory(archive: File) {
        // Bound the central directory before ZipFile allocates an object for every advertised entry.
        RandomAccessFile(archive, "r").use { file ->
            val length = file.length()
            if (length < 22) invalid("Catalog archive is damaged.")
            val tailLength = minOf(length, 65_557L).toInt()
            val tail = ByteArray(tailLength)
            file.seek(length - tailLength)
            file.readFully(tail)
            fun short(offset: Int) = (tail[offset].toInt() and 255) or ((tail[offset + 1].toInt() and 255) shl 8)
            fun word(offset: Int): Long = (0..3).fold(0L) { value, index ->
                value or ((tail[offset + index].toLong() and 255) shl (8 * index))
            }
            val end = (tail.size - 22 downTo 0).firstOrNull { index ->
                word(index) == 0x06054b50L && index + 22 + short(index + 20) == tail.size
            } ?: invalid("Catalog archive is truncated.")
            if (short(end + 4) != 0 || short(end + 6) != 0 || short(end + 8) != 2 || short(end + 10) != 2) {
                invalid("Catalog must contain exactly two entries in one ZIP archive.")
            }
            val directorySize = word(end + 12)
            val directoryOffset = word(end + 16)
            if (directorySize > 256 * 1024L || directoryOffset + directorySize != length - tailLength + end) {
                invalid("Catalog ZIP directory is invalid.")
            }
        }
    }

    private suspend fun validate(payload: File, manifest: CatalogManifest, progress: (Float, Int) -> Unit): Set<String> {
        val ids = HashSet<String>()
        var count = 0
        readFoods(payload, manifest.pack.id) { detail ->
            if (!ids.add(detail.food.id)) invalid("Catalog contains duplicate food IDs.")
            count++
            if (count > MAX_FOODS) tooLarge()
            if (count > manifest.pack.foodCount) invalid("Catalog contains more foods than its manifest.")
            if (count % BATCH_SIZE == 0) progress(0.15f + 0.35f * count / manifest.pack.foodCount, count)
        }
        if (count != manifest.pack.foodCount) invalid("Catalog food count does not match its manifest.")
        return ids
    }

    private suspend fun checkCollisions(ids: Set<String>, packId: String) {
        for (batch in ids.asSequence().chunked(LOOKUP_SIZE)) {
            currentCoroutineContext().ensureActive()
            dao.foodRowsByIds(batch).forEach { food ->
                if (food.packId != packId || food.sourceType != FoodSourceType.REFERENCE) {
                    throw CatalogException(CatalogIssue.ID_CONFLICT, "Catalog food ID already belongs to another food: ${food.id}")
                }
            }
        }
    }

    private suspend fun commit(
        payload: File,
        manifest: CatalogManifest,
        ids: Set<String>,
        progress: (Float, Int) -> Unit,
    ): CatalogInstallResult = database.withTransaction {
        // Recheck under the transaction: a custom food may have been saved during validation.
        checkCollisions(ids, manifest.pack.id)
        val previous = dao.catalogPack(manifest.pack.id)
        val sameMetadata = previous?.copy(installedAt = manifest.pack.installedAt) == manifest.pack
        if (sameMetadata && dao.catalogFoodCount(manifest.pack.id) == manifest.pack.foodCount) {
            return@withTransaction CatalogInstallResult(requireNotNull(previous), 0, 0, 0, true)
        }
        dao.upsertCatalogPack(manifest.pack)
        var added = 0
        var updated = 0
        var processed = 0
        val batch = ArrayList<FoodDetail>(BATCH_SIZE)
        suspend fun saveBatch() {
            if (batch.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val batchIds = batch.map { it.food.id }
            val old = dao.foodRowsByIds(batchIds).associateBy { it.id }
            val newFoods = ArrayList<FoodEntity>()
            val oldFoods = ArrayList<FoodEntity>()
            batch.forEach { detail ->
                val existing = old[detail.food.id]
                if (existing == null) newFoods.add(detail.food)
                else oldFoods.add(detail.food.copy(rowId = existing.rowId))
            }
            if (newFoods.isNotEmpty()) dao.insertFoods(newFoods)
            if (oldFoods.isNotEmpty()) dao.updateFoods(oldFoods)
            dao.deleteNutrientsForFoodIds(batchIds)
            dao.deletePortionsForFoodIds(batchIds)
            dao.insertNutrients(batch.flatMap { it.nutrients })
            dao.insertPortions(batch.flatMap { it.portions })
            added += newFoods.size
            updated += oldFoods.size
            processed += batch.size
            progress(0.55f + 0.4f * processed / manifest.pack.foodCount, processed)
            batch.clear()
        }
        readFoods(payload, manifest.pack.id) { detail ->
            batch.add(detail)
            if (batch.size == BATCH_SIZE) saveBatch()
        }
        saveBatch()
        var removed = 0
        var afterRowId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.catalogFoodPage(manifest.pack.id, afterRowId, LOOKUP_SIZE)
            if (page.isEmpty()) break
            afterRowId = page.last().rowId
            val stale = page.filter { it.id !in ids }.map { it.id }
            if (stale.isNotEmpty()) {
                dao.deletePackFoodIds(manifest.pack.id, stale)
                removed += stale.size
            }
        }
        currentCoroutineContext().ensureActive()
        CatalogInstallResult(manifest.pack, added, updated, removed, false)
    }

    private suspend fun readFoods(payload: File, packId: String, consume: suspend (FoodDetail) -> Unit) {
        payload.inputStream().buffered().use { source ->
            val line = ByteArrayOutputStream()
            while (true) {
                currentCoroutineContext().ensureActive()
                val bytes = readLine(source, line) ?: break
                if (bytes.isEmpty()) invalid("Catalog contains an empty food row.")
                consume(CatalogPackCodec.food(bytes, packId))
            }
        }
    }

    private fun readLine(source: InputStream, buffer: ByteArrayOutputStream): ByteArray? {
        buffer.reset()
        while (true) {
            val byte = source.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toByteArray()
            if (byte == '\n'.code) {
                val bytes = buffer.toByteArray()
                return if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1) else bytes
            }
            if (buffer.size() >= MAX_RECORD_BYTES) tooLarge()
            buffer.write(byte)
        }
    }

    private data class CopyResult(val bytes: Long, val sha256: String, val crc32: Long)
    private suspend fun copyBounded(source: InputStream, destination: File, max: Long, onBytes: (Long) -> Unit = {}): CopyResult {
        val hash = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var total = 0L
        destination.outputStream().buffered().use { target ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > max) tooLarge()
                hash.update(buffer, 0, read)
                crc.update(buffer, 0, read)
                target.write(buffer, 0, read)
                onBytes(total)
            }
        }
        return CopyResult(total, hash.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }, crc.value)
    }

    private fun invalid(message: String): Nothing = throw CatalogException(CatalogIssue.INVALID_PACK, message)
    private fun tooLarge(): Nothing = throw CatalogException(CatalogIssue.TOO_LARGE, "Catalog exceeds the supported size.")
    private fun checkStorage(payloadBytes: Long) {
        val required = payloadBytes * 4 + 16L * 1024 * 1024
        if (availableBytes() < required) {
            throw CatalogException(CatalogIssue.INSUFFICIENT_STORAGE,
                "Free at least ${(required + 1024 * 1024 - 1) / (1024 * 1024)} MB to install this catalog.")
        }
    }

    companion object {
        const val MAX_ZIP_BYTES = 100L * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 512L * 1024 * 1024
        const val MAX_RECORD_BYTES = 512 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_FOODS = 250_000
        private const val BATCH_SIZE = 100
        private const val LOOKUP_SIZE = 900
    }
}

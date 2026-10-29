package com.forge.workout.nutrition.catalog

import android.content.Context
import android.net.Uri
import com.forge.workout.data.database.ForgeDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

/** Android document/assets/network access is confined to this service; the importer accepts streams. */
class AndroidFoodCatalogService(
    context: Context,
    database: ForgeDatabase,
    private val bundledAsset: String = "nutrition/usda-core.forgefoods",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FoodCatalogService {
    private val context = context.applicationContext
    private val dao = database.nutritionDao()
    private val installer = CatalogPackInstaller(database, File(this.context.cacheDir, "food-catalogs"), ioDispatcher = ioDispatcher)
    private val mutex = Mutex()
    private val operation = AtomicReference<Job?>()
    private val mutableState = MutableStateFlow(CatalogLoadState())
    override val state: StateFlow<CatalogLoadState> = mutableState.asStateFlow()

    override suspend fun ensureBundled(): CatalogInstallResult = runInstall {
        val manifest = bundledManifest()
        val installed = dao.catalogPack(manifest.pack.id)
        if (installed != null) {
            val bundledPayload = installed.sha256.equals(manifest.pack.sha256, ignoreCase = true)
            // Initialization seeds the catalog; explicit imports choose its revision. Version
            // labels have no ordering, and a different complete pack may have its own count.
            // For this exact bundled payload, the manifest also exposes a damaged stored count.
            if (installed.foodCount > 0 && dao.catalogFoodCount(installed.id) == installed.foodCount &&
                (!bundledPayload || installed.foodCount == manifest.pack.foodCount)) {
                return@runInstall CatalogInstallResult(installed, 0, 0, 0, true)
            }
            // The APK can repair its own payload, but cannot reconstruct a different import.
            // Preserve that pack until the user explicitly imports its source again.
            if (!bundledPayload) {
                throw CatalogException(CatalogIssue.INVALID_PACK,
                    "The installed catalog is incomplete. Import its .forgefoods file again from a trusted source.")
            }
        }
        installer.install(context.assets.open(bundledAsset), onProgress = ::progress)
    }

    private fun bundledManifest(): CatalogManifest {
        // Only the trusted APK asset takes this fast path; external documents always get full validation.
        ZipInputStream(context.assets.open(bundledAsset).buffered()).use { zip ->
            val entry = zip.nextEntry
            if (entry == null || entry.name != "manifest.json" || entry.isDirectory) {
                throw CatalogException(CatalogIssue.INVALID_PACK, "Bundled catalog manifest is unavailable.")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val size = zip.read(buffer)
                if (size < 0) break
                if (output.size() + size > CatalogPackInstaller.MAX_MANIFEST_BYTES) {
                    throw CatalogException(CatalogIssue.TOO_LARGE, "Bundled catalog manifest is too large.")
                }
                output.write(buffer, 0, size)
            }
            return CatalogPackCodec.manifest(output.toByteArray(), 0L)
        }
    }

    override suspend fun installDocument(uri: Uri): CatalogInstallResult = runInstall {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Catalog document is unavailable.")
        installer.install(stream, onProgress = ::progress)
    }

    override suspend fun installUrl(url: String, expectedSha256: String): CatalogInstallResult = runInstall {
        if (!expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw CatalogException(CatalogIssue.INVALID_PACK, "Enter a valid SHA-256 checksum for the ZIP.")
        }
        download(url, expectedSha256)
    }

    override fun cancel() { operation.get()?.cancel() }
    override fun clearError() { mutableState.value = mutableState.value.copy(error = null) }

    private fun progress(value: Float, count: Int) {
        mutableState.value = mutableState.value.copy(progress = value.coerceIn(0f, 1f), foodCount = count)
    }

    private suspend fun runInstall(block: suspend () -> CatalogInstallResult): CatalogInstallResult = mutex.withLock {
        withContext(ioDispatcher) {
            val job = currentCoroutineContext()[Job]
            operation.set(job)
            val oldCount = mutableState.value.foodCount
            mutableState.value = CatalogLoadState(busy = true, foodCount = oldCount)
            try {
                block().also { mutableState.value = CatalogLoadState(progress = 1f, foodCount = it.pack.foodCount) }
            } catch (error: CancellationException) {
                mutableState.value = CatalogLoadState(foodCount = oldCount)
                throw error
            } catch (error: Exception) {
                val catalogError = error as? CatalogException
                    ?: CatalogException(CatalogIssue.IO_ERROR, "Catalog could not be downloaded or read.", error)
                mutableState.value = CatalogLoadState(foodCount = oldCount,
                    error = CatalogFailure(catalogError.issue, catalogError.message))
                throw catalogError
            } finally {
                operation.compareAndSet(job, null)
            }
        }
    }

    private suspend fun download(address: String, expectedSha256: String): CatalogInstallResult {
        var url = secureUrl(address)
        repeat(MAX_REDIRECTS + 1) { attempt ->
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        if (connection.contentLengthLong > CatalogPackInstaller.MAX_ZIP_BYTES) {
                            throw CatalogException(CatalogIssue.TOO_LARGE, "Catalog download is too large.")
                        }
                        return installer.install(connection.inputStream, expectedSha256, ::progress)
                    }
                    301, 302, 303, 307, 308 -> {
                        if (attempt == MAX_REDIRECTS) throw IOException("Too many catalog redirects.")
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing catalog redirect.")
                        url = secureUrl(URL(url, location).toString())
                    }
                    else -> throw IOException("Catalog download returned HTTP $status.")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Catalog download did not complete.")
    }

    private fun secureUrl(address: String): URL = try {
        require(address.length <= 2048)
        URL(address).also { url ->
            require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.ref == null)
        }
    } catch (error: Exception) {
        throw CatalogException(CatalogIssue.INVALID_PACK, "Catalog download requires a valid HTTPS URL.", error)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val MAX_REDIRECTS = 5
    }
}

package com.forge.workout.nutrition.catalog

import android.net.Uri
import com.forge.workout.nutrition.model.CatalogPackEntity
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

enum class CatalogIssue { INVALID_PACK, UNSUPPORTED_VERSION, HASH_MISMATCH, ID_CONFLICT, TOO_LARGE, INSUFFICIENT_STORAGE, IO_ERROR }

data class CatalogFailure(val issue: CatalogIssue, val detail: String? = null)

class CatalogException(val issue: CatalogIssue, message: String, cause: Throwable? = null) : IOException(message, cause)

data class CatalogLoadState(
    val busy: Boolean = false,
    val progress: Float = 0f,
    val foodCount: Int = 0,
    val error: CatalogFailure? = null,
)

data class CatalogInstallResult(
    val pack: CatalogPackEntity,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val alreadyInstalled: Boolean,
)

interface FoodCatalogService {
    val state: StateFlow<CatalogLoadState>
    suspend fun ensureBundled(): CatalogInstallResult
    suspend fun installDocument(uri: Uri): CatalogInstallResult
    /** The supplied hash covers the downloaded ZIP; the manifest separately hashes foods.jsonl. */
    suspend fun installUrl(url: String, expectedSha256: String): CatalogInstallResult
    fun cancel()
    fun clearError()
}

package com.forge.workout.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.catalog.CatalogInstallResult
import com.forge.workout.nutrition.catalog.CatalogLoadState
import com.forge.workout.nutrition.catalog.FoodCatalogService
import com.forge.workout.nutrition.catalog.OfficialCatalogPacks
import com.forge.workout.nutrition.model.CatalogPackEntity
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.repository.NutritionRepository
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CatalogFeedback { INSTALLED, ALREADY_INSTALLED, CANCELLED, FAILED, INVALID_URL, INVALID_HASH }

data class CatalogUiState(
    val packs: List<CatalogPackEntity> = emptyList(),
    val foods: List<FoodEntity> = emptyList(),
    val operation: CatalogLoadState = CatalogLoadState(),
    val busy: Boolean = false,
    val feedback: CatalogFeedback? = null,
    val readFailed: Boolean = false,
    val packsReady: Boolean = false,
    val officialPackId: String? = null,
) {
    val totalFoods: Int get() = packs.sumOf { it.foodCount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** The catalog is loaded only when ensureBundled is called by a Nutrition destination. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val service: FoodCatalogService,
    private val repository: NutritionRepository,
) : ViewModel() {
    private val feedback = MutableStateFlow<CatalogFeedback?>(null)
    private val working = MutableStateFlow(false)
    private val readFailed = MutableStateFlow(false)
    private val officialPackId = MutableStateFlow<String?>(null)
    private val queryGeneration = MutableStateFlow(0)
    private var operation: Job? = null
    private var bundledRequested = false
    private val packs = queryGeneration.flatMapLatest {
        flow<List<CatalogPackEntity>?> {
            emit(null)
            emitAll(repository.observeCatalogPacks())
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            readFailed.value = true
            emit(null)
        }
    }
    private val foods = queryGeneration.flatMapLatest {
        repository.observeFoods(sourceType = FoodSourceType.REFERENCE, limit = 50).onStart { emit(emptyList()) }.catch { failure ->
            if (failure is CancellationException) throw failure
            readFailed.value = true
            emit(emptyList())
        }
    }

    // Room's first read may wait behind the atomic import. Seed the read flows so
    // service progress and cancellation remain observable during that wait.
    private val operationFlags = combine(working, readFailed, officialPackId) { busy, failed, packId ->
        Triple(busy, failed, packId)
    }
    val state = combine(packs, foods, service.state, feedback, operationFlags) {
        packs, foods, progress, message, (busy, failed, packId) ->
        CatalogUiState(packs.orEmpty(), foods, progress, busy || progress.busy, message, failed,
            packsReady = packs != null, officialPackId = packId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUiState())

    fun ensureBundled() {
        if (bundledRequested || isBusy()) return
        bundledRequested = true
        perform(showAlreadyInstalled = false) { service.ensureBundled() }
    }

    fun retryBundled() {
        if (isBusy()) return
        bundledRequested = true
        perform { service.ensureBundled() }
    }

    fun installDocument(uri: Uri) = perform { service.installDocument(uri) }

    fun installOfficialPack(id: String) {
        val pack = OfficialCatalogPacks.available.firstOrNull { it.id == id } ?: return
        val current = state.value
        if (!current.packsReady || !pack.status(current.packs.firstOrNull { it.id == id }).canInstall) return
        perform(packId = id) {
            // A completed import may have committed before the screen receives Room invalidation.
            val installed = repository.observeCatalogPacks().first().firstOrNull { it.id == id }
            if (pack.status(installed).canInstall) service.installUrl(pack.downloadUrl, pack.archiveSha256) else null
        }
    }

    fun installUrl(url: String, expectedSha256: String) {
        if (isBusy()) return
        officialPackId.value = null
        val address = url.trim()
        val checksum = expectedSha256.trim().lowercase(Locale.ROOT)
        if (!validUrl(address)) {
            feedback.value = CatalogFeedback.INVALID_URL
            return
        }
        if (!validSha256(checksum)) {
            feedback.value = CatalogFeedback.INVALID_HASH
            return
        }
        perform { service.installUrl(address, checksum) }
    }

    fun cancel() {
        if (!isBusy()) return
        feedback.value = CatalogFeedback.CANCELLED
        service.cancel()
        operation?.cancel()
    }

    fun clearFeedback() {
        feedback.value = null
        service.clearError()
        if (!isBusy()) officialPackId.value = null
    }

    fun retryRead() {
        readFailed.value = false
        queryGeneration.value++
    }

    private fun isBusy() = working.value || service.state.value.busy

    private fun perform(
        showAlreadyInstalled: Boolean = true,
        packId: String? = null,
        block: suspend () -> CatalogInstallResult?,
    ) {
        if (isBusy()) return
        working.value = true
        feedback.value = null
        officialPackId.value = packId
        service.clearError()
        // Completion also handles cancellation before a queued coroutine starts executing.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = block()
                feedback.value = when {
                    result == null -> null
                    result.alreadyInstalled && !showAlreadyInstalled -> null
                    result.alreadyInstalled -> CatalogFeedback.ALREADY_INSTALLED
                    else -> CatalogFeedback.INSTALLED
                }
                officialPackId.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = CatalogFeedback.FAILED
            }
        }
        operation = job
        job.invokeOnCompletion {
            if (operation === job) {
                operation = null
                working.value = false
            }
        }
        job.start()
    }

    companion object {
        fun validUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.fragment == null
        }.getOrDefault(false)

        fun validSha256(value: String): Boolean = value.matches(Regex("[a-fA-F0-9]{64}"))
    }
}

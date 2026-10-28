package com.forge.workout.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.nutrition.catalog.CatalogInstallResult
import com.forge.workout.nutrition.catalog.CatalogLoadState
import com.forge.workout.nutrition.catalog.FoodCatalogService
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
import kotlinx.coroutines.flow.flatMapLatest
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
) {
    val totalFoods: Int get() = packs.sumOf { it.foodCount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** The catalog is loaded only when ensureBundled is called by a Nutrition destination. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(
    private val service: FoodCatalogService,
    repository: NutritionRepository,
) : ViewModel() {
    private val feedback = MutableStateFlow<CatalogFeedback?>(null)
    private val working = MutableStateFlow(false)
    private val readFailed = MutableStateFlow(false)
    private val queryGeneration = MutableStateFlow(0)
    private var operation: Job? = null
    private var bundledRequested = false
    private val packs = queryGeneration.flatMapLatest {
        repository.observeCatalogPacks().onStart { emit(emptyList()) }.catch { failure ->
            if (failure is CancellationException) throw failure
            readFailed.value = true
            emit(emptyList())
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
    val state = combine(packs, foods, service.state, feedback, combine(working, readFailed) { busy, failed -> busy to failed }) {
        packs, foods, progress, message, flags ->
        CatalogUiState(packs, foods, progress, flags.first || progress.busy, message, flags.second)
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

    fun installUrl(url: String, expectedSha256: String) {
        if (isBusy()) return
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
    }

    fun retryRead() {
        readFailed.value = false
        queryGeneration.value++
    }

    private fun isBusy() = working.value || service.state.value.busy

    private fun perform(showAlreadyInstalled: Boolean = true, block: suspend () -> CatalogInstallResult) {
        if (isBusy()) return
        working.value = true
        feedback.value = null
        service.clearError()
        // Completion also handles cancellation before a queued coroutine starts executing.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = block()
                feedback.value = when {
                    result.alreadyInstalled && !showAlreadyInstalled -> null
                    result.alreadyInstalled -> CatalogFeedback.ALREADY_INSTALLED
                    else -> CatalogFeedback.INSTALLED
                }
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

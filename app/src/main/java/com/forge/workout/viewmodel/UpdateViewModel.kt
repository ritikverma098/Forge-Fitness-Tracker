package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.updates.AvailableUpdate
import com.forge.workout.updates.UpdateCheckResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

internal data class UpdateUiState(
    val checking: Boolean = false,
    val manualResult: UpdateCheckResult? = null,
    val pending: UpdateOffer? = null,
    val dialog: UpdateOffer? = null
)

internal data class UpdateOffer(val release: AvailableUpdate, val settingsEntryId: String?)

internal class UpdateViewModel(
    private val preferences: PreferencesRepository,
    private val connected: () -> Boolean,
    private val fetch: suspend () -> UpdateCheckResult,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()
    private val shownVersions = mutableSetOf<String>()
    private var manualEntryId: String? = null

    fun checkAutomatically() = check(entryId = null)
    fun checkManually(entryId: String) = check(entryId)

    private fun check(entryId: String?) {
        if (entryId != null) manualEntryId = entryId
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, manualResult = if (entryId != null) null else it.manualResult) }
        viewModelScope.launch {
            try {
                if (!connected()) {
                    report(UpdateCheckResult.Unavailable)
                    return@launch
                }
                // Record the attempt before I/O so failures and process recreation still obey the interval.
                val now = clock()
                val interval = if (manualEntryId == null) CHECK_INTERVAL else 0L
                if (!preferences.recordUpdateCheck(now, interval)) {
                    if (manualEntryId == null) return@launch
                    preferences.recordUpdateCheck(now)
                }
                val result = fetch()
                val release = (result as? UpdateCheckResult.Available)?.release
                val pending = release?.takeIf {
                    val saved = preferences.readUpdatePreferences()
                    it.version !in shownVersions && it.version != saved.skippedVersion &&
                        (it.version != saved.dismissedVersion || !isRecent(saved.dismissedAt, clock()))
                }
                val origin = manualEntryId
                _state.update {
                    it.copy(pending = pending?.let { release -> UpdateOffer(release, origin) },
                        manualResult = if (origin != null) result else it.manualResult)
                }
            } catch (_: IOException) {
                report(UpdateCheckResult.Unavailable)
            } finally {
                manualEntryId = null
                _state.update { it.copy(checking = false) }
            }
        }
    }

    private fun report(result: UpdateCheckResult) {
        if (manualEntryId != null) _state.update { it.copy(manualResult = result) }
    }

    fun presentPending(offer: UpdateOffer) {
        if (_state.value.pending != offer || _state.value.dialog != null ||
            !shownVersions.add(offer.release.version)) return
        _state.update { it.copy(pending = null, dialog = offer) }
    }

    fun dismiss(skip: Boolean = false) {
        val release = _state.value.dialog?.release ?: return
        recordDecision(release, skip)
    }

    fun downloaded(release: AvailableUpdate) = recordDecision(release, skip = false)

    private fun recordDecision(release: AvailableUpdate, skip: Boolean) {
        shownVersions.add(release.version)
        _state.update {
            it.copy(pending = it.pending?.takeUnless { pending -> pending.release.version == release.version },
                dialog = it.dialog?.takeUnless { dialog -> dialog.release.version == release.version })
        }
        val now = clock()
        viewModelScope.launch {
            try { preferences.dismissUpdate(release.version, now, skip) }
            catch (_: IOException) { /* The session still remembers that this version was shown. */ }
        }
    }

    private fun isRecent(then: Long?, now: Long): Boolean =
        then != null && then >= 0 && now >= then && now - then < CHECK_INTERVAL

    private companion object {
        const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    }
}

package com.forge.workout.viewmodel

import android.app.Application
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UpdatePreferences
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.updates.AvailableUpdate
import com.forge.workout.updates.UpdateCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var preferences: PreferencesRepository
    private var now = 1_800_000_000_000L
    private var connected = true
    private var requests = 0
    private val latest = update("1.4.0")
    private var result: UpdateCheckResult = UpdateCheckResult.Available(latest)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        preferences = PreferencesRepository(RuntimeEnvironment.getApplication(), "update-test-${UUID.randomUUID()}")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun onlyOneAutomaticRequestAcrossViewModelsUntilTwentyFourHours() = runTest(dispatcher) {
        val first = model()
        val second = model()
        first.checkAutomatically()
        second.checkAutomatically()
        first.awaitIdle()
        second.awaitIdle()
        assertEquals(1, requests)
        assertEquals(now, preferences.readUpdatePreferences().lastCheckAt)
        now += DAY - 1
        model().apply { checkAutomatically(); awaitIdle() }
        assertEquals(1, requests)
        now++
        model().apply { checkAutomatically(); awaitIdle() }
        assertEquals(2, requests)
    }

    @Test fun manualCheckJoinsInflightRequestAndBypassesInterval() = runTest(dispatcher) {
        val fetching = CompletableDeferred<Unit>()
        val response = CompletableDeferred<UpdateCheckResult>()
        val model = model { fetching.complete(Unit); response.await() }
        model.checkAutomatically()
        fetching.await()
        model.checkManually("settings-entry")
        response.complete(result)
        model.awaitIdle()
        assertEquals(1, requests)
        assertEquals(result, model.state.value.manualResult)
        assertEquals("settings-entry", model.state.value.pending?.settingsEntryId)
        model.checkManually("another-settings-entry")
        model.awaitIdle()
        assertEquals(2, requests)
        assertEquals("another-settings-entry", model.state.value.pending?.settingsEntryId)
    }

    @Test fun offlineDoesNotConsumeIntervalAndManualFeedbackIsAvailable() = runTest(dispatcher) {
        val model = model()
        connected = false
        model.checkAutomatically()
        model.awaitIdle()
        assertNull(model.state.value.manualResult)
        model.checkManually("settings")
        model.awaitIdle()
        assertEquals(UpdateCheckResult.Unavailable, model.state.value.manualResult)
        assertNull(preferences.readUpdatePreferences().lastCheckAt)
        assertEquals(0, requests)
        connected = true
        model.checkAutomatically()
        model.awaitIdle()
        assertEquals(1, requests)
        assertNull(model.state.value.pending?.settingsEntryId)
    }

    @Test fun failedOnlineAttemptIsThrottledButManualCanRetry() = runTest(dispatcher) {
        result = UpdateCheckResult.Unavailable
        model().apply { checkAutomatically(); awaitIdle() }
        model().apply { checkAutomatically(); awaitIdle() }
        assertEquals(1, requests)
        result = UpdateCheckResult.NoValidRelease
        val manual = model()
        manual.checkManually("settings")
        manual.awaitIdle()
        assertEquals(2, requests)
        assertEquals(UpdateCheckResult.NoValidRelease, manual.state.value.manualResult)
    }

    @Test fun laterPersistsForADayAndSuppressesTheVersionForThisSession() = runTest(dispatcher) {
        val session = model()
        session.checkAutomatically()
        session.awaitIdle()
        val offer = requireNotNull(session.state.value.pending)
        session.presentPending(offer)
        session.dismiss()
        session.presentPending(offer)
        assertNull(session.state.value.dialog)
        val later = awaitPreferences { it.dismissedVersion == latest.version }
        assertEquals(now, later.dismissedAt)
        model().apply {
            checkManually("settings")
            awaitIdle()
            assertNull(state.value.pending)
            assertEquals(result, state.value.manualResult)
        }
        now += DAY
        session.checkManually("settings")
        session.awaitIdle()
        assertNull(session.state.value.pending)
        model().apply {
            checkManually("settings")
            awaitIdle()
            assertEquals(latest, state.value.pending?.release)
        }
    }

    @Test fun skippedVersionStaysQuietAcrossSessionsAndNewVersionStillOffers() = runTest(dispatcher) {
        val session = model()
        session.checkAutomatically()
        session.awaitIdle()
        session.presentPending(requireNotNull(session.state.value.pending))
        session.dismiss(skip = true)
        awaitPreferences { it.skippedVersion == latest.version }
        now += DAY
        val reopened = model()
        reopened.checkAutomatically()
        reopened.awaitIdle()
        assertNull(reopened.state.value.pending)
        reopened.checkManually("settings")
        reopened.awaitIdle()
        assertEquals(result, reopened.state.value.manualResult)
        assertNull(reopened.state.value.pending)
        val newer = update("1.4.1")
        result = UpdateCheckResult.Available(newer)
        reopened.checkManually("settings")
        reopened.awaitIdle()
        assertEquals(newer, reopened.state.value.pending?.release)
    }

    @Test fun downloadConsumesPendingOfferAndPreferenceRestoreKeepsUpdateBookkeeping() = runTest(dispatcher) {
        val session = model()
        session.checkManually("settings")
        session.awaitIdle()
        session.downloaded(latest)
        assertNull(session.state.value.pending)
        val saved = awaitPreferences { it.dismissedVersion == latest.version }
        val restored = UserPreferences(themeMode = ThemeMode.DARK)
        preferences.replace(restored)
        assertEquals(saved, preferences.readUpdatePreferences())
        assertEquals(restored, preferences.preferences.first())
    }

    private fun model(fetch: suspend () -> UpdateCheckResult = { result }) =
        UpdateViewModel(preferences, { connected }, { requests++; fetch() }, { now })

    private suspend fun UpdateViewModel.awaitIdle() { state.first { !it.checking } }

    private suspend fun awaitPreferences(matches: (UpdatePreferences) -> Boolean): UpdatePreferences =
        withContext(Dispatchers.IO) {
            withTimeout(5_000) {
                var saved = preferences.readUpdatePreferences()
                while (!matches(saved)) {
                    delay(10)
                    saved = preferences.readUpdatePreferences()
                }
                saved
            }
        }

    private fun update(version: String) = AvailableUpdate(
        version, "v$version", "Forge $version", "Reliability improvements", LocalDate.of(2026, 11, 4),
        "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v$version/Forge-$version.apk"
    )

    private companion object { const val DAY = 24 * 60 * 60 * 1_000L }
}

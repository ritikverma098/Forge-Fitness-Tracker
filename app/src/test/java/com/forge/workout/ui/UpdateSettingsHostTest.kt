package com.forge.workout.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import com.forge.workout.BuildConfig
import com.forge.workout.R
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.ui.screens.settings.UpdateSettings
import com.forge.workout.updates.AvailableUpdate
import com.forge.workout.updates.UpdateCheckResult
import com.forge.workout.viewmodel.UpdateViewModel
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateSettingsHostTest {
    @get:Rule val compose = createComposeRule()

    private val application get() = RuntimeEnvironment.getApplication()
    private val preferences by lazy { PreferencesRepository(application, "update-settings-${UUID.randomUUID()}") }
    private val store = ViewModelStore()
    private val now = 1_790_000_000_000L

    @After fun tearDown() { store.clear() }

    @Test fun `manual check bypasses recent check time and shows progress and up to date`() {
        runBlocking { preferences.recordUpdateCheck(now) }
        val response = CompletableDeferred<UpdateCheckResult>()
        val calls = AtomicInteger()
        show(fetch = {
            calls.incrementAndGet()
            response.await()
        })

        checkButton().assertIsEnabled().performClick()
        awaitMessage(text(R.string.update_checking))
        compose.onNodeWithText(text(R.string.update_checking)).assertIsNotEnabled()

        response.complete(UpdateCheckResult.UpToDate)
        awaitMessage(text(R.string.update_current))
        checkButton().assertIsEnabled()
        assertEquals(1, calls.get())
    }

    @Test fun `manual checks show offline invalid and available outcomes with the verified download action`() {
        val release = AvailableUpdate(
            version = "1.4.0",
            tag = "v1.4.0",
            title = "Forge 1.4.0",
            changelog = "Release notes",
            publishedOn = LocalDate.of(2026, 11, 4),
            apkUrl = "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.0/Forge-1.4.0.apk"
        )
        val responses = ArrayDeque(listOf(UpdateCheckResult.NoValidRelease, UpdateCheckResult.Available(release)))
        val calls = AtomicInteger()
        val handler = RecordingUriHandler()
        var connected = false
        show(handler, connected = { connected }, fetch = {
            calls.incrementAndGet()
            responses.removeFirst()
        })

        checkButton().performClick()
        awaitMessage(text(R.string.update_unavailable))
        assertEquals(0, calls.get())
        compose.runOnIdle { connected = true }

        checkButton().performClick()
        awaitMessage(text(R.string.update_no_valid_release))
        compose.onNodeWithText(text(R.string.update_download)).assertDoesNotExist()

        checkButton().performClick()
        awaitMessage(text(R.string.update_found, release.version))
        compose.onNodeWithText(text(R.string.update_download)).assertIsEnabled().performClick()
        assertEquals(listOf(release.apkUrl), handler.uris)
        assertEquals(2, calls.get())
    }

    @Test fun `About uses the installed version and opens the fixed GitHub destinations`() {
        val handler = RecordingUriHandler()
        show(handler)

        compose.onNodeWithText(text(R.string.update_installed_version, BuildConfig.VERSION_NAME)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_view_repository)).performClick()
        compose.onNodeWithText(text(R.string.update_view_release)).performClick()

        assertEquals(listOf(
            "https://github.com/ritikverma098/Forge-Fitness-Tracker",
            "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/latest"
        ), handler.uris)
    }

    private fun show(
        handler: RecordingUriHandler = RecordingUriHandler(),
        connected: () -> Boolean = { true },
        fetch: suspend () -> UpdateCheckResult = { error("Unexpected check") }
    ) {
        val model = UpdateViewModel(preferences, connected, fetch, { now }).also { store.put("updates", it) }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    Column {
                        UpdateSettings(model, enabled = true, onCheck = { model.checkManually("test-settings") })
                    }
                }
            }
        }
    }

    private fun checkButton() = compose.onNodeWithText(text(R.string.update_check))

    private fun awaitMessage(message: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    private fun text(resource: Int, vararg args: Any): String = application.getString(resource, *args)

    private class RecordingUriHandler : UriHandler {
        val uris = mutableListOf<String>()
        override fun openUri(uri: String) { uris += uri }
    }
}

package com.forge.workout.ui

import android.app.Application
import android.content.ActivityNotFoundException
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.forge.workout.R
import com.forge.workout.ui.components.UpdateAvailableDialog
import com.forge.workout.updates.AvailableUpdate
import java.time.LocalDate
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
class UpdateDialogHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `long release notes scroll while download remains visible`() {
        val lastChange = "Last change keeps saved workouts available offline."
        val notes = (List(100) { index -> "Release note ${index + 1}: a description of a Forge change." } +
            lastChange).joinToString("\n\n")
        show(update(changelog = notes))

        compose.onNodeWithText(text(R.string.update_available)).assertIsDisplayed()
        compose.onNodeWithText("1.3.2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1.4.0", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2026", substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_whats_new)).assertIsDisplayed()
        compose.onNodeWithTag("update_release_notes").performScrollToNode(hasText(lastChange, substring = true))
        compose.onNodeWithText(lastChange, substring = true).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_download)).assertIsDisplayed()
    }

    @Test fun `external release notes render literally with no link action`() {
        val externalText = "[Unexpected link](intent://example.invalid) <b>literal HTML</b>"
        val handler = RecordingUriHandler()
        show(update(changelog = externalText), handler)

        compose.onNodeWithText(externalText, substring = true).assertIsDisplayed().assert(!hasClickAction())
        assertEquals(emptyList<String>(), handler.uris)
    }

    @Test fun `blank notes have a fallback and Later dismisses without opening a URL`() {
        val handler = RecordingUriHandler()
        var laterCount = 0
        show(update(changelog = " \n "), handler, onLater = { laterCount++ })

        compose.onNodeWithText(text(R.string.update_no_notes)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_later)).performClick()

        compose.onNodeWithText(text(R.string.update_available)).assertDoesNotExist()
        assertEquals(1, laterCount)
        assertEquals(emptyList<String>(), handler.uris)
    }

    @Test fun `Skip invokes its own action and dismisses without opening a URL`() {
        val handler = RecordingUriHandler()
        var laterCount = 0
        var skipCount = 0
        show(uriHandler = handler, onLater = { laterCount++ }, onSkip = { skipCount++ })

        compose.onNodeWithText(text(R.string.update_skip)).performClick()

        compose.onNodeWithText(text(R.string.update_available)).assertDoesNotExist()
        assertEquals(1, skipCount)
        assertEquals(0, laterCount)
        assertEquals(emptyList<String>(), handler.uris)
    }

    @Test fun `Download Now delegates the exact release asset and dismisses after opening`() {
        val release = update(changelog = "The text mentions https://example.invalid/other.apk.")
        val handler = RecordingUriHandler()
        var downloaded = 0
        show(release, handler, onDownloaded = { downloaded++ })

        compose.onNodeWithText(text(R.string.update_download)).performClick()

        assertEquals(listOf(release.apkUrl), handler.uris)
        assertEquals(1, downloaded)
        compose.onNodeWithText(text(R.string.update_available)).assertDoesNotExist()
    }

    @Test fun `missing URL handler shows feedback and leaves the update dialog available`() {
        val release = update()
        val handler = RecordingUriHandler(IllegalArgumentException("No activity for URL", ActivityNotFoundException()))
        var downloaded = 0
        show(release, handler, onDownloaded = { downloaded++ })

        compose.onNodeWithText(text(R.string.update_download)).performClick()

        compose.onNodeWithText(text(R.string.update_open_link_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_available)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.update_later)).assertIsDisplayed()
        assertEquals(listOf(release.apkUrl), handler.uris)
        assertEquals(0, downloaded)
    }

    private fun show(
        release: AvailableUpdate = update(),
        uriHandler: RecordingUriHandler = RecordingUriHandler(),
        onLater: () -> Unit = {},
        onSkip: () -> Unit = {},
        onDownloaded: () -> Unit = {}
    ) {
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    if (visible) UpdateAvailableDialog(
                        release = release,
                        currentVersion = "1.3.2",
                        onLater = {
                            onLater()
                            visible = false
                        },
                        onSkip = {
                            onSkip()
                            visible = false
                        },
                        onDownloaded = {
                            onDownloaded()
                            visible = false
                        }
                    )
                }
            }
        }
    }

    private fun update(changelog: String = "Forge fixes saved workout recovery.") = AvailableUpdate(
        version = "1.4.0",
        tag = "v1.4.0",
        title = "Forge workout and nutrition release",
        changelog = changelog,
        publishedOn = LocalDate.of(2026, 11, 3),
        apkUrl = "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v1.4.0/Forge-1.4.0.apk"
    )

    private fun text(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)

    private class RecordingUriHandler(private val failure: RuntimeException? = null) : UriHandler {
        val uris = mutableListOf<String>()

        override fun openUri(uri: String) {
            uris += uri
            failure?.let { throw it }
        }
    }
}

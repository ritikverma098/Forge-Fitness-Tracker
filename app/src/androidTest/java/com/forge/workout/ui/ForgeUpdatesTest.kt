package com.forge.workout.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeTestApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.backup.BackupCodec
import com.forge.workout.updates.AvailableUpdate
import com.forge.workout.updates.UpdateCheckResult
import com.forge.workout.viewmodel.BackupViewModel
import com.forge.workout.viewmodel.UpdateViewModel
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForgeUpdatesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<ForgeTestApplication>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var release: AvailableUpdate
    private var previousCheckAt: Long? = null
    private var ownedWorkout: Long? = null
    private var document: File? = null

    @After fun cleanUp() {
        app.mockReleaseCheck = null
        if (::scenario.isInitialized) scenario.close()
        runBlocking {
            ownedWorkout?.let { app.repository.deleteWorkout(it) }
            app.preferences.recordUpdateCheck(previousCheckAt ?: 0L)
        }
        document?.delete()
    }

    @Test fun workoutAndManualSettingsStayUsableUntilSafeHomePrompt() {
        val calls = AtomicInteger()
        val response = CompletableDeferred<UpdateCheckResult>()
        launch {
            calls.incrementAndGet()
            response.await()
        }
        compose.waitUntil(10_000) { calls.get() == 1 }
        val id = runBlocking { app.repository.startWorkout("Update checker test workout") }
        ownedWorkout = id
        awaitText(text(R.string.home_resume_workout))
        node(R.string.home_resume_workout).performClick()
        awaitText(text(R.string.workout_name))

        val updates = model<UpdateViewModel>()
        response.complete(UpdateCheckResult.Available(release))
        compose.waitUntil(10_000) { updates.state.value.pending?.release == release }
        node(R.string.update_available).assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.add_exercise)).onFirst().performScrollTo().performClick()
        awaitText(text(R.string.library_picker_subtitle))
        back()
        awaitText(text(R.string.workout_name))
        compose.onNode(hasText(text(R.string.home)) and hasClickAction()).performClick()
        awaitText(text(R.string.home_resume_workout))
        node(R.string.update_available).assertDoesNotExist()

        checkFromSettings()
        awaitText(text(R.string.update_found, release.version))
        node(R.string.update_available).assertDoesNotExist()
        node(R.string.update_download).performScrollTo().assertIsDisplayed()
        assertEquals(2, calls.get())
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitText(text(R.string.home_resume_workout))
        runBlocking { app.repository.deleteWorkout(id) }
        ownedWorkout = null
        awaitText(text(R.string.update_available))
        node(R.string.update_later).performClick()
        compose.waitUntil(10_000) { updates.state.value.dialog == null }

        scenario.recreate()
        awaitText(text(R.string.home_ready))
        node(R.string.update_available).assertDoesNotExist()
        assertEquals(2, calls.get())
    }

    @Test fun manualPromptWaitsUntilRestorePreviewIsClosed() {
        val calls = AtomicInteger()
        val manual = CompletableDeferred<UpdateCheckResult>()
        launch { if (calls.incrementAndGet() == 1) UpdateCheckResult.UpToDate else manual.await() }
        val updates = model<UpdateViewModel>()
        compose.waitUntil(10_000) { calls.get() == 1 && !updates.state.value.checking }
        checkFromSettings()
        compose.waitUntil(10_000) { calls.get() == 2 }

        val backup = runBlocking { app.backups.snapshot() }
        val file = File.createTempFile("update-restore-preview-", ".json", app.cacheDir)
        document = file
        file.writeText(BackupCodec.encode(backup))
        val restore = model<BackupViewModel>()
        compose.runOnIdle { restore.readBackup(Uri.fromFile(file)) }
        awaitText(text(R.string.backup_restore_confirm_title))
        manual.complete(UpdateCheckResult.Available(release))
        compose.waitUntil(10_000) { updates.state.value.pending?.release == release }
        node(R.string.backup_restore_confirm_title).assertIsDisplayed()
        node(R.string.update_available).assertDoesNotExist()

        node(R.string.backup_restore_cancel).performClick()
        awaitText(text(R.string.update_available))
        node(R.string.update_later).performClick()
    }

    private fun launch(source: suspend () -> UpdateCheckResult) {
        val saved = runBlocking {
            check(app.repository.observeWorkouts().first().none { it.workout.endTime == null })
            app.preferences.readUpdatePreferences().also { app.preferences.recordUpdateCheck(0L) }
        }
        previousCheckAt = saved.lastCheckAt
        val version = (1..3).map { "99.$it.0" }.first { it != saved.dismissedVersion && it != saved.skippedVersion }
        release = AvailableUpdate(version, "v$version", "Forge test release", "Mock release notes",
            LocalDate.of(2026, 11, 4),
            "https://github.com/ritikverma098/Forge-Fitness-Tracker/releases/download/v$version/Forge-$version.apk")
        app.mockReleaseCheck = source
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitText(text(R.string.home_ready))
    }

    private inline fun <reified T : ViewModel> model(): T {
        lateinit var result: T
        scenario.onActivity { result = ViewModelProvider(it)[T::class.java] }
        return result
    }

    private fun checkFromSettings() {
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        awaitText(text(R.string.settings_subtitle))
        node(R.string.update_check).performScrollTo().performClick()
    }

    private fun back() = compose.onNodeWithContentDescription(text(R.string.back)).performClick()
    private fun node(resource: Int) = compose.onNodeWithText(text(resource))
    private fun text(resource: Int, vararg args: Any) = app.getString(resource, *args)
    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

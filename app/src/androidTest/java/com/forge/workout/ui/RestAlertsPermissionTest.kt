package com.forge.workout.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue

/** Runs on API 33+ when notification permission has not been granted. */
@RunWith(AndroidJUnit4::class)
class RestAlertsPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ForgeApplication
    private val repository get() = app.repository

    @Before
    fun prepare() = runBlocking {
        repository.ensureSeeded()
        removeTestData()
        app.preferences.setTheme(ThemeMode.SYSTEM)
        app.preferences.setUnit(WeightUnit.KG)
        app.preferences.setRest(0)
        waitForText(text(R.string.home_ready))
    }

    @After
    fun cleanup() = runBlocking { removeTestData() }

    @Test
    fun deniedPermissionShowsExplicitActionsAndKeepsRestAdjustmentAndSkipWorking() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assumeTrue(notificationPermission() == PackageManager.PERMISSION_DENIED)
        val (id, initialDeadline) = runBlocking {
            val exerciseId = repository.observeExercises().first().single { it.name == "Bench Press" }.id
            val id = repository.startWorkout("Rest permission test")
            repository.addExercise(id, exerciseId)
            val deadline = System.currentTimeMillis() + 300_000L
            repository.setRestEndTime(id, deadline)
            id to deadline
        }
        navigate(R.string.workout)
        waitForTag("rest_notification_hint")
        compose.onNodeWithTag("rest_hint_enable_notifications").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("rest_plus_15").performClick()
        compose.onNodeWithTag("rest_plus_15").performClick()
        compose.onNodeWithTag("rest_minus_15").performClick()
        awaitRepository { repository.observeWorkouts().first().single { it.workout.id == id }.workout.restEndTime == initialDeadline + 15_000L }
        assertEquals(PackageManager.PERMISSION_DENIED, notificationPermission())

        navigate(R.string.home)
        waitForText(text(R.string.home_in_progress))
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        waitForText(text(R.string.settings_subtitle))
        compose.onNodeWithTag("rest_enable_notifications").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("rest_channel_settings").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertEquals(PackageManager.PERMISSION_DENIED, notificationPermission())
        compose.onNodeWithContentDescription(text(R.string.back)).performScrollTo().performClick()
        waitForText(text(R.string.home_subtitle))
        navigate(R.string.workout)
        waitForTag("rest_notification_hint")
        compose.onNodeWithText(text(R.string.skip_rest)).performClick()
        awaitRepository { repository.observeWorkouts().first().single { it.workout.id == id }.workout.restEndTime == null }
        compose.waitUntil(45_000) { compose.onAllNodesWithTag("rest_timer_bar").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("rest_notification_hint").assertDoesNotExist()
        assertEquals(PackageManager.PERMISSION_DENIED, notificationPermission())
    }

    private fun notificationPermission() = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)

    private fun navigate(destination: Int) {
        compose.onNode(hasText(text(destination), substring = false) and hasClickAction()).performClick()
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitRepository(predicate: suspend () -> Boolean) {
        compose.waitUntil(45_000) { runBlocking { predicate() } }
    }

    private suspend fun removeTestData() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
        repository.observeRoutines().first().forEach { repository.deleteRoutine(it.routine.id) }
        repository.observeExercises().first().filter { it.isCustom }.forEach { repository.deleteExercise(it.id) }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}

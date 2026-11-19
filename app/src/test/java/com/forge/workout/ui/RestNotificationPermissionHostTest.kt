package com.forge.workout.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import com.forge.workout.R
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.RestNotificationPromptState
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.ui.components.RestAlertPermissionHost
import com.forge.workout.ui.components.RestAlertSettings
import com.forge.workout.ui.theme.ForgeTheme
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RestNotificationPermissionHostTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val preferences by lazy { PreferencesRepository(app, "rest-offer-${UUID.randomUUID()}") }
    private val owner = PermissionRegistryOwner()
    private val safe = mutableStateOf(false)
    private val showHost = mutableStateOf(true)

    @Test fun firstOfferWaitsForReadyHomeAndNotNowSurvivesStateRestorationAndPreferenceReplace() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }
        node(PROMPT).assertDoesNotExist()
        compose.runOnIdle { safe.value = true }
        awaitPrompt()
        assertEquals(0, owner.requests)
        restoration.emulateSavedInstanceStateRestore()
        awaitPrompt()
        node("rest_startup_later").performClick()
        node(PROMPT).assertDoesNotExist()
        assertEquals(0, owner.requests)
        runBlocking {
            assertEquals(RestNotificationPromptState.OFFERED, preferences.restNotificationPrompt.first())
            preferences.replace(UserPreferences(themeMode = ThemeMode.DARK))
            assertFalse(preferences.claimRestNotificationOffer())
        }
        recreateHost()
        node(PROMPT).assertDoesNotExist()
    }

    @Test fun deniedNativeResultIsRememberedAcrossHostRecreationAndNotificationsCanStillBeEnabledInSettings() {
        safe.value = true
        compose.setContent { Content() }
        awaitPrompt()
        node("rest_startup_enable").performClick()
        assertEquals(1, owner.requests)
        node(PROMPT).assertDoesNotExist()
        compose.runOnIdle { owner.respond(false) }
        compose.onNodeWithText(app.getString(R.string.rest_alert_notifications_disabled)).assertIsDisplayed()
        waitForHandled()
        recreateHost()
        node(PROMPT).assertDoesNotExist()
        node("rest_enable_notifications").assertIsDisplayed()
        assertEquals(1, owner.requests)
    }

    @Test fun grantUpdatesSettingsImmediatelyAndRevocationDoesNotTriggerAnotherAutomaticOffer() {
        safe.value = true
        val notifications = app.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationsEnabled(true)
        compose.setContent { Content() }
        awaitPrompt()
        node("rest_startup_enable").performClick()
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            owner.respond(true)
        }
        assertEquals(PERMISSION_GRANTED, ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS))
        compose.onNodeWithText(app.getString(R.string.rest_alert_notifications_enabled)).assertIsDisplayed()
        node("rest_enable_notifications").assertDoesNotExist()
        waitForHandled()
        compose.runOnIdle { shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) }
        recreateHost()
        compose.onNodeWithText(app.getString(R.string.rest_alert_notifications_disabled)).assertIsDisplayed()
        node(PROMPT).assertDoesNotExist()
        assertEquals(1, owner.requests)
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        ForgeTheme(ThemeMode.DARK) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                if (showHost.value) RestAlertPermissionHost(preferences, safe.value, { safe.value }) {
                    Column {
                        Text("Working app")
                        RestAlertSettings()
                    }
                }
            }
        }
    }

    private fun awaitPrompt() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(PROMPT).fetchSemanticsNodes().isNotEmpty() }
        node(PROMPT).assertIsDisplayed()
    }

    private fun waitForHandled() = compose.waitUntil(10_000) {
        runBlocking { preferences.restNotificationPrompt.first() == RestNotificationPromptState.RUNTIME_HANDLED }
    }

    private fun recreateHost() {
        compose.runOnIdle { showHost.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showHost.value = true }
        compose.waitForIdle()
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag)

    private class PermissionRegistryOwner : ActivityResultRegistryOwner {
        private var code = 0
        var requests = 0
            private set
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                assertEquals(Manifest.permission.POST_NOTIFICATIONS, input)
                code = requestCode
                requests++
            }
        }

        fun respond(granted: Boolean) { activityResultRegistry.dispatchResult(code, granted) }
    }

    private companion object { const val PROMPT = "rest_startup_prompt" }
}

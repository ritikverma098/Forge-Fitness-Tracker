package com.forge.workout.navigation

import android.app.Application
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.forge.workout.ui.navigation.RestoreNavigation
import com.forge.workout.ui.navigation.canActFrom
import com.forge.workout.ui.navigation.popFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RestoreNavigationHostTest {
    @get:Rule val compose = createComposeRule()

    private var generation by mutableIntStateOf(0)
    private lateinit var controller: NavHostController

    @Test fun `resetting only the controller leaves guarded navigation inert`() {
        compose.setContent {
            MaterialTheme {
                // Reproduce the former ForgeApp boundary: the host survives the controller key.
                val nav = key(generation) { rememberNavController() }
                SideEffect { controller = nav }
                TestHost(nav)
            }
        }
        assertResumed("home")
        compose.onNodeWithTag("open_settings").performClick()
        assertResumed("settings")
        val previous = controller

        compose.runOnIdle { generation++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotSame(previous, controller)
            val entry = controller.currentBackStackEntry!!
            assertEquals("home", entry.destination.route)
            assertEquals(Lifecycle.State.CREATED, entry.lifecycle.currentState)
            assertFalse(controller.canActFrom(entry))
            if (controller.canActFrom(entry)) controller.navigate("history")
            assertEquals("home", controller.currentBackStackEntry!!.destination.route)
        }
    }

    @Test fun `resetting the whole host binds the fresh lifecycle and discards previous drafts`() {
        compose.setContent {
            MaterialTheme {
                RestoreNavigation(generation) { nav ->
                    SideEffect { controller = nav }
                    TestHost(nav)
                }
            }
        }
        assertResumed("home")
        compose.onNodeWithTag("open_settings").performClick()
        assertResumed("settings")
        compose.onNodeWithTag("settings_draft").performTextInput("Pre-restore draft")
        val previous = controller
        val previousSettings = previous.currentBackStackEntry!!

        compose.runOnIdle { generation++ }
        assertResumed("home")
        compose.runOnIdle {
            assertNotSame(previous, controller)
            assertFalse(controller.canActFrom(previousSettings))
        }
        compose.onNodeWithTag("open_settings").performClick()
        assertResumed("settings")
        compose.onNodeWithTag("settings_draft").assertTextEquals("")

        // A second restore in the same Activity must not reuse the prior host binding either.
        compose.runOnIdle { generation++ }
        assertResumed("home")
        listOf("workout", "exercises", "history", "progress", "settings").forEach { route ->
            compose.onNodeWithTag("open_$route").performClick()
            assertResumed(route)
            compose.onNodeWithTag("destination_$route").assertIsDisplayed()
            if (route == "history") {
                compose.onNodeWithTag("open_detail").performClick()
                assertResumed("detail")
                compose.onNodeWithTag("back").performClick()
                assertResumed("history")
            }
            val entry = controller.currentBackStackEntry!!
            compose.runOnIdle {
                assertTrue(controller.popFrom(entry))
                assertFalse(controller.popFrom(entry))
            }
            assertResumed("home")
        }
    }

    private fun assertResumed(route: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val entry = controller.currentBackStackEntry!!
            assertEquals(route, entry.destination.route)
            assertEquals(Lifecycle.State.RESUMED, entry.lifecycle.currentState)
            assertTrue(controller.canActFrom(entry))
        }
    }
}

@Composable
private fun TestHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { fadeIn(tween(300)) },
        exitTransition = { fadeOut(tween(300)) },
        popEnterTransition = { fadeIn(tween(300)) },
        popExitTransition = { fadeOut(tween(300)) },
        predictivePopEnterTransition = { _ -> fadeIn(tween(300)) },
        predictivePopExitTransition = { _ -> fadeOut(tween(300)) },
    ) {
        composable("home") { source ->
            Column {
                Text("Home", Modifier.testTag("destination_home"))
                listOf("workout", "exercises", "history", "progress", "settings").forEach { route ->
                    Button(
                        onClick = { if (nav.canActFrom(source)) nav.navigate(route) },
                        modifier = Modifier.testTag("open_$route"),
                    ) { Text(route) }
                }
            }
        }
        listOf("workout", "exercises", "history", "progress", "settings", "detail").forEach { route ->
            composable(route) { source -> TestDestination(route, source, nav) }
        }
    }
}

@Composable
private fun TestDestination(route: String, source: NavBackStackEntry, nav: NavHostController) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column {
        Text(route, Modifier.testTag("destination_$route"))
        if (route == "settings") {
            TextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.testTag("settings_draft"))
        }
        if (route == "history") {
            Button(
                onClick = { if (nav.canActFrom(source)) nav.navigate("detail") },
                modifier = Modifier.testTag("open_detail"),
            ) { Text("Detail") }
        }
        Button(onClick = { nav.popFrom(source) }, modifier = Modifier.testTag("back")) { Text("Back") }
    }
}

package com.forge.workout.navigation

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.forge.workout.ui.navigation.canActFrom
import com.forge.workout.ui.navigation.popFrom
import com.forge.workout.ui.navigation.openMainTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NavigationActionsHostTest {
    private lateinit var controller: NavHostController
    private lateinit var navigator: ComposeNavigator
    private lateinit var owner: HostLifecycleOwner
    private lateinit var store: ViewModelStore
    private lateinit var home: NavBackStackEntry

    @Before fun setUp() {
        owner = HostLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.RESUMED
        store = ViewModelStore()
        navigator = ComposeNavigator()
        controller = NavHostController(RuntimeEnvironment.getApplication())
        controller.setLifecycleOwner(owner)
        controller.setViewModelStore(store)
        controller.navigatorProvider.addNavigator(navigator)
        controller.graph = controller.createGraph(startDestination = "home") {
            composable("home") {}
            composable("detail") {}
            composable("editor") {}
            composable("workout") {}
            composable("history") {}
            composable("progress") {}
            composable("routines") {}
        }
        home = controller.currentBackStackEntry!!
        settle(home)
    }

    @After fun tearDown() {
        owner.registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    @Test fun `resumed editor pops directly to its exact parent entry`() {
        val detail = navigateAndSettle("detail")
        val editor = navigateAndSettle("editor")

        assertTrue(controller.canActFrom(editor))
        assertTrue(controller.popFrom(editor))
        assertEquals(detail.id, controller.currentBackStackEntry!!.id)
        assertEquals("detail", controller.currentDestination!!.route)
        settle(editor, detail)
        assertTrue(controller.canActFrom(detail))
    }

    @Test fun `rapid second pop from a stale editor cannot pop its parent`() {
        val detail = navigateAndSettle("detail")
        val editor = navigateAndSettle("editor")

        assertTrue(controller.popFrom(editor))
        assertFalse(controller.canActFrom(editor))
        assertFalse(controller.popFrom(editor))
        assertEquals(detail.id, controller.currentBackStackEntry!!.id)
        assertEquals(home.id, controller.previousBackStackEntry!!.id)
    }

    @Test fun `entering entry at STARTED cannot act until its transition completes`() {
        controller.navigate("detail")
        val entering = controller.currentBackStackEntry!!

        assertEquals(Lifecycle.State.STARTED, entering.lifecycle.currentState)
        assertFalse(controller.canActFrom(entering))
        assertFalse(controller.popFrom(entering))
        assertEquals(entering.id, controller.currentBackStackEntry!!.id)

        settle(home, entering)
        assertEquals(Lifecycle.State.RESUMED, entering.lifecycle.currentState)
        assertTrue(controller.canActFrom(entering))
        assertTrue(controller.popFrom(entering))
        assertEquals(home.id, controller.currentBackStackEntry!!.id)
    }

    @Test fun `backgrounded current entry cannot navigate while host is only STARTED`() {
        val detail = navigateAndSettle("detail")
        owner.registry.currentState = Lifecycle.State.STARTED

        assertEquals(Lifecycle.State.STARTED, detail.lifecycle.currentState)
        assertFalse(controller.canActFrom(detail))
        assertFalse(controller.popFrom(detail))
        assertEquals(detail.id, controller.currentBackStackEntry!!.id)

        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(controller.canActFrom(detail))
        assertTrue(controller.popFrom(detail))
    }

    @Test fun `same route with a different entry id cannot authorize a stale action`() {
        val earlierDetail = navigateAndSettle("detail")
        val newerDetail = navigateAndSettle("detail")

        assertEquals(earlierDetail.destination.route, newerDetail.destination.route)
        assertNotEquals(earlierDetail.id, newerDetail.id)
        assertFalse(controller.canActFrom(earlierDetail))
        assertFalse(controller.popFrom(earlierDetail))
        assertEquals(newerDetail.id, controller.currentBackStackEntry!!.id)

        assertTrue(controller.popFrom(newerDetail))
        assertEquals(earlierDetail.id, controller.currentBackStackEntry!!.id)
        settle(newerDetail, earlierDetail)
        assertTrue(controller.canActFrom(earlierDetail))
    }

    @Test fun `restored parent is gated while pop transition is still running`() {
        val detail = navigateAndSettle("detail")
        val editor = navigateAndSettle("editor")
        assertTrue(controller.popFrom(editor))

        assertEquals(detail.id, controller.currentBackStackEntry!!.id)
        assertEquals(Lifecycle.State.STARTED, detail.lifecycle.currentState)
        assertFalse(controller.popFrom(detail))
        assertEquals(detail.id, controller.currentBackStackEntry!!.id)

        settle(editor, detail)
        assertTrue(controller.canActFrom(detail))
        assertTrue(controller.popFrom(detail))
        assertEquals(home.id, controller.currentBackStackEntry!!.id)
    }

    @Test fun `Home remains Home after starting a routine and switching tabs`() {
        navigateAndSettle("routines")
        val outgoing = controller.currentBackStackEntry!!
        // Match AppEvent.OpenWorkout, which removes the routine's child stack.
        controller.navigate("workout") { launchSingleTop = true; popUpTo("home") }
        settle(outgoing, controller.currentBackStackEntry!!)
        listOf("history", "progress", "home", "workout", "home").forEach { route ->
            val previous = controller.currentBackStackEntry!!
            controller.openMainTab(route)
            settle(previous, controller.currentBackStackEntry!!)
            assertEquals(route, controller.currentDestination!!.route)
        }
        val settings = navigateAndSettle("detail")
        assertTrue(controller.popFrom(settings))
        settle(settings, controller.currentBackStackEntry!!)
        assertEquals("home", controller.currentDestination!!.route)
    }

    private fun navigateAndSettle(route: String): NavBackStackEntry {
        val previous = controller.currentBackStackEntry!!
        controller.navigate(route)
        val current = controller.currentBackStackEntry!!
        settle(previous, current)
        return current
    }

    private fun settle(vararg entries: NavBackStackEntry) {
        entries.forEach(navigator::onTransitionComplete)
    }

    private class HostLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}

package com.forge.workout.ui

import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.ForgeTestApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures destination content during gesture progress, not the unscaled outer NavHost. */
@RunWith(AndroidJUnit4::class)
class ForgePredictiveBackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ForgeApplication
    private val repository: WorkoutRepository get() = app.repository
    private lateinit var originalWorkouts: List<WorkoutDetail>

    @Before fun prepareWorkouts() = runBlocking {
        assertTrue("Navigation fixtures require the isolated test application", app is ForgeTestApplication)
        repository.ensureSeeded()
        clearWorkouts()
        app.preferences.setTheme(ThemeMode.SYSTEM)
        app.preferences.setUnit(WeightUnit.KG)
        app.preferences.setRest(0)
        waitForText(text(R.string.home_subtitle))
        val exerciseId = repository.observeExercises().first().single { it.name == "Bench Press" }.id
        val completedId = repository.startWorkout(COMPLETED_NAME)
        repository.addExercise(completedId, exerciseId)
        val completedSet = repository.observeWorkouts().first().single { it.workout.id == completedId }
            .exercises.single().sets.single()
        repository.updateSet(completedSet.copy(weight = 65.0, reps = 8, completed = true))
        repository.finishWorkout(completedId)
        val activeId = repository.startWorkout(ACTIVE_NAME)
        repository.addExercise(activeId, exerciseId)
        val activeSet = repository.observeWorkouts().first().single { it.workout.id == activeId }
            .exercises.single().sets.single()
        repository.updateSet(activeSet.copy(weight = 77.5, reps = 6, completed = false))
        repository.addSet(activeSet.workoutExerciseId)
        originalWorkouts = repository.observeWorkouts().first()
        compose.waitForIdle()
    }

    @After fun cleanup() = runBlocking {
        compose.mainClock.autoAdvance = true
        clearWorkouts()
    }

    @Test fun exercisesChildBackKeepsFullScaleAndRecreationRetainsSearchAndActiveWorkout() {
        assertBothEdges(
            open = { openExerciseLibrary() },
            outgoing = text(R.string.library_subtitle), parent = text(R.string.home_subtitle), parentTab = R.string.home,
        )

        openExerciseLibrary()
        compose.onNode(hasSetTextAction()).performTextInput("Bench Press")
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.library_subtitle))
        compose.onNode(hasSetTextAction()).assertTextContains("Bench Press")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.home_subtitle))
        openTab(R.string.history)
        waitForText(text(R.string.history_subtitle))
        openTab(R.string.nutrition)
        waitForText(text(R.string.nutrition_empty))
        compose.onNode(hasText(text(R.string.nutrition), substring = false) and hasClickAction()).assertIsSelected()
        assertTrainingDataUnchanged()
    }

    @Test fun historyBackKeepsFullScaleAndReturnsHomeFromEitherEdge() {
        assertBothEdges(
            open = { openTab(R.string.history); waitForText(text(R.string.history_subtitle)) },
            outgoing = text(R.string.history_subtitle), parent = text(R.string.home_subtitle), parentTab = R.string.home,
        )
    }

    @Test fun progressBackKeepsFullScaleAndReturnsHomeFromEitherEdge() {
        assertBothEdges(
            open = { openTab(R.string.progress); waitForText(text(R.string.progress_subtitle)) },
            outgoing = text(R.string.progress_subtitle), parent = text(R.string.home_subtitle), parentTab = R.string.home,
        )
    }

    @Test fun historyDetailGestureAndToolbarBackReturnToSameHistoryWithDataIntact() {
        openTab(R.string.history)
        waitForText(text(R.string.history_subtitle))
        val historyBounds = contentBounds(text(R.string.history_subtitle))
        assertBothEdges(
            open = {
                compose.onNodeWithText(COMPLETED_NAME, substring = false).performClick()
                waitForText(text(R.string.detail_duration))
            },
            outgoing = text(R.string.detail_duration), parent = text(R.string.history_subtitle), parentTab = R.string.history,
        )
        assertSameSize(historyBounds, contentBounds(text(R.string.history_subtitle)))
        compose.onNodeWithText(COMPLETED_NAME, substring = false).performClick()
        waitForText(text(R.string.detail_duration))
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.history_subtitle))
        compose.waitForIdle()
        compose.onNodeWithText(COMPLETED_NAME, substring = false).assertIsDisplayed()
        assertSameSize(historyBounds, contentBounds(text(R.string.history_subtitle)))
        compose.onNode(hasText(text(R.string.history), substring = false) and hasClickAction()).assertIsSelected()
        assertTrainingDataUnchanged()
    }

    private fun assertBothEdges(open: () -> Unit, outgoing: String, parent: String, parentTab: Int) {
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            open()
            compose.waitForIdle()
            val baseline = contentBounds(outgoing)
            assertTrue("Outgoing content must have measurable dimensions", baseline.width > 0 && baseline.height > 0)

            gesture(edge, outgoing, baseline, commit = false)
            compose.onNodeWithText(outgoing, substring = false).assertIsDisplayed()
            assertSameSize(baseline, contentBounds(outgoing))
            assertTrainingDataUnchanged()

            gesture(edge, outgoing, baseline, commit = true)
            waitForText(parent)
            compose.onNodeWithText(parent, substring = false).assertIsDisplayed()
            compose.onNode(hasText(text(parentTab), substring = false) and hasClickAction()).assertIsSelected()
            assertTrainingDataUnchanged()
        }
        assertCompletionOnlyBack(open, outgoing, parent, parentTab)
    }

    private fun assertCompletionOnlyBack(open: () -> Unit, outgoing: String, parent: String, parentTab: Int) {
        open()
        compose.waitForIdle()
        // Retain the outgoing node so measurement does not depend on post-pop semantics filtering.
        val outgoingNode = compose.onNodeWithText(outgoing, substring = false).fetchSemanticsNode()
        val baseline = outgoingNode.boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(120)
            compose.waitForIdle()
            assertSameSize(baseline, outgoingNode.boundsInRoot)
            compose.mainClock.advanceTimeBy(900)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        waitForText(parent)
        compose.onNodeWithText(parent, substring = false).assertIsDisplayed()
        compose.onNode(hasText(text(parentTab), substring = false) and hasClickAction()).assertIsSelected()
        assertTrainingDataUnchanged()
    }

    private fun gesture(edge: Int, outgoing: String, baseline: Rect, commit: Boolean) {
        compose.mainClock.autoAdvance = false
        try {
            dispatchGestureEvent(edge, 0f, started = true)
            compose.mainClock.advanceTimeByFrame()
            for (progress in listOf(.25f, .5f, .9f)) {
                dispatchGestureEvent(edge, progress, started = false)
                compose.mainClock.advanceTimeBy(48)
                compose.waitForIdle()
                assertSameSize(baseline, contentBounds(outgoing))
            }
            compose.runOnUiThread {
                val dispatcher = compose.activity.onBackPressedDispatcher
                if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled()
            }
            compose.mainClock.advanceTimeBy(900)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    private fun dispatchGestureEvent(edge: Int, progress: Float, started: Boolean) {
        compose.runOnUiThread {
            val width = compose.activity.window.decorView.width.toFloat()
            val height = compose.activity.window.decorView.height.toFloat()
            val x = if (edge == BackEventCompat.EDGE_LEFT) width * progress else width * (1f - progress)
            val event = BackEventCompat(x, height / 2f, progress, edge)
            val dispatcher = compose.activity.onBackPressedDispatcher
            if (started) dispatcher.dispatchOnBackStarted(event) else dispatcher.dispatchOnBackProgressed(event)
        }
    }

    private fun contentBounds(value: String): Rect =
        compose.onNodeWithText(value, substring = false).fetchSemanticsNode().boundsInRoot

    private fun assertSameSize(expected: Rect, actual: Rect) {
        assertEquals("Outgoing destination width changed during Back", expected.width, actual.width, 1f)
        assertEquals("Outgoing destination height changed during Back", expected.height, actual.height, 1f)
    }

    private fun assertTrainingDataUnchanged() {
        assertEquals(originalWorkouts, runBlocking { repository.observeWorkouts().first() })
    }

    private fun openTab(resource: Int) {
        compose.onNode(hasText(text(resource), substring = false) and hasClickAction()).performClick()
    }

    private fun openExerciseLibrary() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text(R.string.home_exercises), substring = false))
        compose.onNodeWithText(text(R.string.home_exercises)).assertIsDisplayed().performClick()
        waitForText(text(R.string.library_subtitle))
        compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed()
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun text(resource: Int): String = compose.activity.getString(resource)
    private suspend fun clearWorkouts() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
    }

    private companion object {
        const val COMPLETED_NAME = "Predictive Back completed"
        const val ACTIVE_NAME = "Predictive Back active"
    }
}

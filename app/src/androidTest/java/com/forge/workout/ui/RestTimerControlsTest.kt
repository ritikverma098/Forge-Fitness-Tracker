package com.forge.workout.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
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

@RunWith(AndroidJUnit4::class)
class RestTimerControlsTest {
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
    fun restControlsAdjustExistingDeadlineSurviveTabsAndRecreationAndSkipWithoutChangingSets() {
        val original = runBlocking {
            val exerciseIds = repository.observeExercises().first()
                .filter { it.exerciseType == ExerciseType.WEIGHT_REPS }.take(8).map { it.id }
            val id = repository.startWorkout("Rest controls test")
            exerciseIds.forEach { repository.addExercise(id, it) }
            val set = workout(id).sortedExercises.first().sets.single()
            repository.updateSet(set.copy(weight = 60.0, reps = 8, completed = true))
            repository.setRestEndTime(id, System.currentTimeMillis() + 300_000L)
            workout(id)
        }
        val id = original.workout.id
        val originalDeadline = requireNotNull(original.workout.restEndTime)
        waitForText(text(R.string.home_resume_workout))
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        awaitTimerVisible()

        compose.onNodeWithTag("rest_plus_15").performClick()
        compose.onNodeWithTag("rest_plus_15").performClick()
        awaitRepository { workout(id).workout.restEndTime == originalDeadline + 30_000L }
        compose.onNodeWithTag("rest_minus_15").performClick()
        val adjustedDeadline = originalDeadline + 15_000L
        awaitRepository { workout(id).workout.restEndTime == adjustedDeadline }

        navigate(R.string.history)
        waitForText(text(R.string.history_subtitle))
        compose.onNodeWithTag("rest_timer_bar").assertDoesNotExist()
        assertEquals(adjustedDeadline, runBlocking { workout(id).workout.restEndTime })
        navigate(R.string.workout)
        awaitTimerVisible()
        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.workout_name))
        awaitTimerVisible()
        assertEquals(adjustedDeadline, runBlocking { workout(id).workout.restEndTime })

        val lastExercise = original.sortedExercises.last().exercise.name
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText(lastExercise, substring = false))
        compose.onNodeWithText(lastExercise, substring = false).assertIsDisplayed()
        compose.onNodeWithTag("rest_timer_bar").assertIsDisplayed()
        compose.onNodeWithTag("rest_plus_15").assertIsDisplayed()
        compose.onNodeWithTag("rest_minus_15").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.skip_rest)).assertIsDisplayed().performClick()
        awaitRepository { workout(id).workout.restEndTime == null }
        compose.waitUntil(45_000) {
            compose.onAllNodesWithTag("rest_timer_bar").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("rest_timer_bar").assertDoesNotExist()
        compose.onNodeWithTag("rest_plus_15").assertDoesNotExist()
        compose.onNodeWithTag("rest_minus_15").assertDoesNotExist()
        assertEquals(original.copy(workout = original.workout.copy(restEndTime = null)), runBlocking { workout(id) })
    }

    private fun awaitTimerVisible() {
        compose.waitUntil(45_000) {
            compose.onAllNodesWithTag("rest_timer_bar").fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithTag("rest_timer_bar").assertCountEquals(1)
        compose.onNodeWithTag("rest_timer_bar").assertIsDisplayed()
        compose.onNodeWithTag("rest_plus_15").assertIsDisplayed()
        compose.onNodeWithTag("rest_minus_15").assertIsDisplayed()
    }

    private fun navigate(destination: Int) {
        compose.onNode(hasText(text(destination), substring = false) and hasClickAction()).performClick()
    }

    private suspend fun workout(id: Long): WorkoutDetail = repository.observeWorkouts().first()
        .single { it.workout.id == id }

    private fun awaitRepository(predicate: suspend () -> Boolean) {
        compose.waitUntil(45_000) { runBlocking { predicate() } }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private suspend fun removeTestData() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
        repository.observeRoutines().first().forEach { repository.deleteRoutine(it.routine.id) }
        repository.observeExercises().first().filter { it.isCustom }.forEach { repository.deleteExercise(it.id) }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)
}

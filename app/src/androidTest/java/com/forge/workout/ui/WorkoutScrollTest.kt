package com.forge.workout.ui

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.SetType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.viewmodel.SetField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Functional scroll/clock coverage; interactions do not measure rendering frame times. */
@RunWith(AndroidJUnit4::class)
class WorkoutScrollTest {
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
    fun repeatedIdleThenDragPreservesStickyRestAndWorkoutStateInBothThemes() {
        val original = createLargeWorkout()
        waitForText(text(R.string.home_resume_workout))
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        waitForTag("rest_timer_value")

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
            runBlocking { app.preferences.setTheme(theme) }
            compose.waitForIdle()
            repeat(4) { cycle ->
                idleThroughClockUpdates()
                compose.onNodeWithTag("workout_exercise_list").performTouchInput {
                    if (cycle % 2 == 0) swipeUp(durationMillis = 500) else swipeDown(durationMillis = 500)
                }
                compose.waitForIdle()
                assertStickyTimer()
                assertEquals("Scrolling and theme changes must not write workout data",
                    original, runBlocking { workout(original.workout.id) })
            }
        }
        compose.onNodeWithText(text(R.string.skip_rest)).performClick()
        awaitRepository { workout(original.workout.id).workout.restEndTime == null }
        compose.onNodeWithTag("workout_exercise_list").assertIsDisplayed()
    }

    @Test
    fun largeWorkoutRemainsEditableAcrossScrollAndClockCyclesWithStickyRestControls() {
        val original = createLargeWorkout()
        val workoutId = original.workout.id
        val deadline = requireNotNull(original.workout.restEndTime)
        val firstSet = original.sortedExercises.first().sortedSets.first()
        val lastEntry = original.sortedExercises.last()
        val lastSet = lastEntry.sortedSets.last()
        assertEquals(8, original.exercises.size)
        assertEquals(48, original.exercises.sumOf { it.sets.size })

        waitForText(text(R.string.home_resume_workout))
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        waitForTag("workout_elapsed_time")
        waitForTag("rest_timer_value")
        val initialElapsed = clockText("workout_elapsed_time")

        repeat(3) {
            scrollToSet(lastSet.id)
            assertStickyTimer()
            idleThroughClockUpdates()
            scrollToSet(firstSet.id)
            assertStickyTimer()
        }

        compose.onNodeWithTag("rest_plus_15").performClick()
        awaitRepository { workout(workoutId).workout.restEndTime == deadline + 15_000L }
        compose.onNodeWithTag("rest_minus_15").performClick()
        awaitRepository { workout(workoutId).workout.restEndTime == deadline }

        scrollToSet(lastSet.id)
        compose.onNodeWithTag(numericTag(lastSet.id, SetField.WEIGHT)).performTextReplacement("87.5")
        compose.onNodeWithTag(numericTag(lastSet.id, SetField.WEIGHT)).performImeAction()
        compose.onNodeWithTag(numericTag(lastSet.id, SetField.REPS)).performTextReplacement("12")
        compose.onNodeWithTag(numericTag(lastSet.id, SetField.REPS)).performImeAction()
        awaitRepository { savedSet(lastSet.id).let { it.weight == 87.5 && it.reps == 12 } }
        compose.onNodeWithTag("set_done_${lastSet.id}").performClick()
        awaitRepository { savedSet(lastSet.id).completed }
        compose.onNodeWithTag("set_done_${lastSet.id}").assertIsOn()
        compose.onNodeWithTag("set_options_${lastSet.id}").performClick()
        compose.onNodeWithTag("set_type_${lastSet.id}_${SetType.WARM_UP.name}").performClick()
        awaitRepository { savedSet(lastSet.id).setType == SetType.WARM_UP }

        // At the final exercise, the last composed Add Set belongs to this card.
        compose.onAllNodesWithText(text(R.string.add_set)).onLast().performScrollTo().performClick()
        awaitRepository { workout(workoutId).exercises.single { it.entry.id == lastEntry.entry.id }.sets.size == 7 }
        val duplicate = runBlocking {
            workout(workoutId).exercises.single { it.entry.id == lastEntry.entry.id }.sortedSets.last()
        }
        assertEquals(87.5, duplicate.weight, 0.000001)
        assertEquals(12, duplicate.reps)
        assertEquals(SetType.WORKING, duplicate.setType)
        assertFalse(duplicate.completed)
        scrollToSet(duplicate.id)
        compose.onNodeWithTag("set_options_${duplicate.id}").performClick()
        compose.onNodeWithText(text(R.string.delete_set, duplicate.setNumber)).performClick()
        awaitRepository {
            val sets = workout(workoutId).exercises.single { it.entry.id == lastEntry.entry.id }.sets
            sets.size == 6 && sets.none { it.id == duplicate.id }
        }

        val expected = original.copy(exercises = original.exercises.map { entry ->
            entry.copy(sets = entry.sets.map { set ->
                if (set.id == lastSet.id) set.copy(weight = 87.5, reps = 12, completed = true, setType = SetType.WARM_UP)
                else set
            })
        })
        assertEquals(expected, runBlocking { workout(workoutId) })
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasTestTag("workout_elapsed_time"))
        compose.onNodeWithTag("workout_elapsed_time").assertIsDisplayed()
        assertNotEquals(initialElapsed, clockText("workout_elapsed_time"))
        assertStickyTimer()
    }

    private fun createLargeWorkout(): WorkoutDetail = runBlocking {
        val exercises = repository.observeExercises().first()
            .filter { it.exerciseType == ExerciseType.WEIGHT_REPS }.take(8)
        val id = repository.startWorkout("Scroll endurance test")
        exercises.forEach { exercise ->
            repository.addExercise(id, exercise.id)
            val entry = workout(id).exercises.single { it.exercise.id == exercise.id }
            repeat(5) { repository.addSet(entry.entry.id) }
            workout(id).exercises.single { it.entry.id == entry.entry.id }.sortedSets.forEach { set ->
                repository.updateSet(set.copy(weight = 40.0 + entry.entry.orderIndex, reps = 8 + set.setNumber))
            }
        }
        repository.setRestEndTime(id, System.currentTimeMillis() + 300_000L)
        workout(id)
    }

    private fun idleThroughClockUpdates() {
        val initial = clockText("rest_timer_value")
        val started = SystemClock.elapsedRealtime()
        // Deliberately remain idle for three seconds to exercise several wall-clock updates.
        compose.waitUntil(10_000) {
            SystemClock.elapsedRealtime() - started >= 3_000L && clockText("rest_timer_value") != initial
        }
        assertStickyTimer()
    }

    private fun clockText(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { it.text }

    private fun assertStickyTimer() {
        compose.onNodeWithTag("rest_timer_bar").assertIsDisplayed()
        compose.onNodeWithTag("rest_timer_value").assertIsDisplayed()
        compose.onNodeWithTag("rest_plus_15").assertIsDisplayed()
        compose.onNodeWithTag("rest_minus_15").assertIsDisplayed()
    }

    private fun scrollToSet(id: Long) {
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(hasTestTag("workout_set_$id"))
        waitForTag("workout_set_$id")
        compose.onNodeWithTag("workout_set_$id").assertIsDisplayed()
    }

    private suspend fun workout(id: Long): WorkoutDetail = repository.observeWorkouts().first()
        .single { it.workout.id == id }

    private suspend fun savedSet(id: Long): WorkoutSet = repository.observeWorkouts().first()
        .flatMap { it.exercises }.flatMap { it.sets }.single { it.id == id }

    private fun awaitRepository(predicate: suspend () -> Boolean) {
        compose.waitUntil(45_000) { runBlocking { predicate() } }
    }

    private fun waitForTag(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun removeTestData() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
        repository.observeRoutines().first().forEach { repository.deleteRoutine(it.routine.id) }
        repository.observeExercises().first().filter { it.isCustom }.forEach { repository.deleteExercise(it.id) }
    }

    private fun numericTag(id: Long, field: SetField) = "numeric_${id}_${field.name}"
    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}

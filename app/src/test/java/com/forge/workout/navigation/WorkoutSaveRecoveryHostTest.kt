package com.forge.workout.navigation

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.ExerciseType
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.viewmodel.WorkoutViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual navigation, snackbar retry and Room failure recovery on disposable storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NutritionNavigationApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WorkoutSaveRecoveryHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as NutritionNavigationApplication

    @After fun closeData() {
        val application = app
        compose.activityRule.scenario.close()
        application.database.close()
    }

    @Test fun `repeated failures offer retry and Finish preserves the recovered weight`() {
        val repository = app.repository
        val (workoutId, setId) = prepareRejectedEdit()
        val model = ViewModelProvider(compose.activity)[WorkoutViewModel::class.java]
        assertFalse(runBlocking { model.flush() })
        assertFalse(runBlocking { model.flush() })
        assertEquals(60.0, runBlocking { repository.workoutSet(setId)!! }.weight, 0.0)

        compose.waitForIdle()
        val previousNotice = model.errorNotice.value
        compose.onNodeWithText(text(R.string.retry_failed_changes)).performClick()
        compose.waitUntil(15_000) {
            compose.runOnIdle { model.errorNotice.value != null && model.errorNotice.value != previousNotice }
        }
        waitForText(R.string.retry_failed_changes)
        assertFalse(runBlocking { model.flush() })

        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_weight")
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.retry_failed_changes)).performClick()
        awaitRoom { repository.workoutSet(setId)!!.weight == 80.0 }
        assertTrue(runBlocking { model.flush() })
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(hasText(text(R.string.finish_workout)))
        compose.onNodeWithText(text(R.string.finish_workout)).performClick()
        compose.onAllNodesWithText(text(R.string.finish_workout)).onLast().performClick()
        awaitRoom { repository.workout(workoutId)!!.endTime != null }
        assertEquals(80.0, runBlocking { repository.workoutSet(setId)!! }.weight, 0.0)
    }

    @Test fun `a successful correction dismisses its resolved error without requiring Retry`() {
        val (_, setId) = prepareRejectedEdit()
        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_weight")
        compose.onNodeWithTag("numeric_${setId}_WEIGHT").performTextReplacement("75")
        awaitRoom { app.repository.workoutSet(setId)!!.weight == 75.0 }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text(R.string.retry_failed_changes)).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(runBlocking { ViewModelProvider(compose.activity)[WorkoutViewModel::class.java].flush() })
    }

    private fun prepareRejectedEdit(): Pair<Long, Long> {
        val repository = app.repository
        val ids = runBlocking {
            repository.ensureSeeded()
            app.preferences.setUnit(WeightUnit.KG)
            app.preferences.setRest(0)
            val exercise = repository.observeExercises().first().first { it.exerciseType == ExerciseType.WEIGHT_REPS }
            val workout = repository.startWorkout("Save recovery")
            repository.addExercise(workout, exercise.id)
            val set = repository.observeWorkouts().first().single { it.workout.id == workout }
                .exercises.single().sets.single()
            repository.updateSet(set.copy(weight = 60.0, reps = 8, completed = true))
            workout to set.id
        }
        app.database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_weight BEFORE UPDATE OF weight ON workout_sets
            WHEN NEW.weight != OLD.weight
            BEGIN SELECT RAISE(ABORT, 'Injected weight storage failure'); END
        """.trimIndent())
        waitForText(R.string.home_resume_workout)
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("numeric_${ids.second}_WEIGHT"))
        compose.onNodeWithTag("numeric_${ids.second}_WEIGHT").performTextReplacement("80")
        waitForText(R.string.retry_failed_changes)
        return ids
    }

    private fun text(resource: Int) = app.getString(resource)
    private fun awaitRoom(predicate: suspend () -> Boolean) {
        compose.waitUntil(15_000) {
            // Drive the paused host UI looper while Room resumes queued ViewModel writes.
            compose.runOnIdle { }
            runBlocking { predicate() }
        }
    }
    private fun waitForText(resource: Int) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(resource)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
}

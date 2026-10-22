package com.forge.workout.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.SetType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies type controls and persistence against the test application's real Room graph. */
@RunWith(AndroidJUnit4::class)
class SetTypesTest {
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
    fun everyTypePreservesLoggedValuesAndNextSetDefaultsToWorkingAfterRecreation() {
        val original = createWorkout("Set types active test")
        val entry = original.exercises.single()
        val first = entry.sets.single()
        waitForText(text(R.string.home_resume_workout))
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        waitForText(text(R.string.workout_name))
        scrollToSet(first.id)
        assertType(first.id, 1, SetType.WORKING)

        listOf(SetType.WARM_UP, SetType.DROP_SET, SetType.FAILURE, SetType.WORKING).forEach { type ->
            selectType(first.id, type)
            assertEquals(first.copy(setType = type), runBlocking { savedSet(first.id) })
            assertEquals(original.workout, runBlocking { workout(original.workout.id).workout })
            compose.onNodeWithTag("set_done_${first.id}").assertIsOn()
            assertType(first.id, 1, type)
        }

        selectType(first.id, SetType.FAILURE)
        compose.onNodeWithText(text(R.string.add_set)).performScrollTo().performClick()
        awaitRepository { workout(original.workout.id).exercises.single().sets.size == 2 }
        val next = runBlocking { workout(original.workout.id).exercises.single().sortedSets.last() }
        assertEquals(60.0, next.weight, 0.000001)
        assertEquals(8, next.reps)
        assertEquals(SetType.WORKING, next.setType)
        assertFalse(next.completed)

        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.workout_name))
        scrollToSet(first.id)
        assertType(first.id, 1, SetType.FAILURE)
        compose.onNodeWithTag("set_done_${first.id}").assertIsOn()
        scrollToSet(next.id)
        assertType(next.id, 2, SetType.WORKING)
        compose.onNodeWithTag("set_done_${next.id}").assertIsOff()
        assertEquals(first.copy(setType = SetType.FAILURE), runBlocking { savedSet(first.id) })
        assertEquals(next, runBlocking { savedSet(next.id) })
        assertNull(runBlocking { workout(original.workout.id).workout.endTime })
    }

    @Test
    fun historicalTypeCanBeEditedAndDisplayedWithoutRestartingOrChangingCompletedWorkout() {
        val original = createWorkout("Set types history test", SetType.DROP_SET, historical = true)
        val first = original.exercises.single().sets.single()
        compose.onNode(hasText(text(R.string.history), substring = false) and hasClickAction()).performClick()
        waitForText(text(R.string.history_subtitle))
        compose.onNodeWithText(original.workout.name, substring = false).performClick()
        waitForTag("workout_detail_list")
        assertHistoryNumber(1, SetType.DROP_SET)

        compose.onNodeWithContentDescription(text(R.string.detail_edit)).performClick()
        waitForText(text(R.string.edit_workout))
        selectType(first.id, SetType.WARM_UP)
        compose.onNodeWithTag("set_done_${first.id}").assertIsOn()
        compose.onNodeWithText(text(R.string.save_changes)).performScrollTo().performClick()
        waitForTag("workout_detail_list")
        assertHistoryNumber(1, SetType.WARM_UP)

        compose.activityRule.scenario.recreate()
        waitForTag("workout_detail_list")
        assertHistoryNumber(1, SetType.WARM_UP)
        assertEquals(original.workout, runBlocking { workout(original.workout.id).workout })
        assertEquals(first.copy(setType = SetType.WARM_UP), runBlocking { savedSet(first.id) })
        assertNull(runBlocking { workout(original.workout.id).workout.restEndTime })
    }

    private fun createWorkout(name: String, type: SetType = SetType.WORKING, historical: Boolean = false): WorkoutDetail = runBlocking {
        val exercise = repository.observeExercises().first().single { it.name == "Bench Press" }
        val id = repository.startWorkout(name)
        repository.addExercise(id, exercise.id)
        val set = workout(id).exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 8, completed = true, setType = type))
        if (historical) repository.finishWorkout(id)
        workout(id)
    }

    private fun selectType(id: Long, type: SetType) {
        scrollToSet(id)
        compose.onNodeWithTag("set_options_$id").performClick()
        compose.onNodeWithTag("set_type_${id}_${type.name}").performClick()
        awaitRepository { savedSet(id).setType == type }
    }

    private fun assertType(id: Long, number: Int, type: SetType) {
        val label = numberLabel(number, type)
        val description = text(R.string.set_type_description, number, typeLabel(type))
        compose.onNodeWithTag("set_options_$id")
            .assert(hasText(label, substring = false))
            .assert(hasContentDescription(description, substring = false))
    }

    private fun assertHistoryNumber(number: Int, type: SetType) {
        val label = numberLabel(number, type)
        compose.onNodeWithTag("workout_detail_list").performScrollToNode(hasText(label, substring = false))
        compose.onNodeWithText(label, substring = false).assertIsDisplayed()
    }

    private fun scrollToSet(id: Long) {
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(hasTestTag("workout_set_$id"))
        waitForTag("workout_set_$id")
        compose.onNodeWithTag("workout_set_$id").assertIsDisplayed()
    }

    private suspend fun workout(id: Long) = repository.observeWorkouts().first().single { it.workout.id == id }
    private suspend fun savedSet(id: Long): WorkoutSet = repository.observeWorkouts().first()
        .flatMap { it.exercises }.flatMap { it.sets }.single { it.id == id }

    private fun awaitRepository(predicate: suspend () -> Boolean) {
        compose.waitUntil(45_000) { runBlocking { predicate() } }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private suspend fun removeTestData() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
        repository.observeRoutines().first().forEach { repository.deleteRoutine(it.routine.id) }
        repository.observeExercises().first().filter { it.isCustom }.forEach { repository.deleteExercise(it.id) }
    }

    private fun typeLabel(type: SetType): String = text(when (type) {
        SetType.WORKING -> R.string.set_type_working
        SetType.WARM_UP -> R.string.set_type_warm_up
        SetType.DROP_SET -> R.string.set_type_drop
        SetType.FAILURE -> R.string.set_type_failure
    })

    private fun numberLabel(number: Int, type: SetType): String = when (type) {
        SetType.WORKING -> number.toString()
        SetType.WARM_UP -> text(R.string.set_warm_up_number, number)
        SetType.DROP_SET -> text(R.string.set_drop_number, number)
        SetType.FAILURE -> text(R.string.set_failure_number, number)
    }

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}

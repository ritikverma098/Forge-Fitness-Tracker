package com.forge.workout.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.viewmodel.HistoryViewModel
import java.time.LocalDate
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

@RunWith(AndroidJUnit4::class)
class HistoryFiltersTest {
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
    fun combinedFiltersIncludeArchivedExerciseAndSecondaryMuscleSurviveRecreationAndClearTogether() {
        val fixture = runBlocking {
            val curl = repository.saveExercise(Exercise(name = "History archived curl", primaryMuscleGroup = MuscleGroup.BICEPS,
                secondaryMuscleGroup = MuscleGroup.BACK))
            val press = repository.saveExercise(Exercise(name = "History press", primaryMuscleGroup = MuscleGroup.CHEST))
            val squat = repository.saveExercise(Exercise(name = "History squat", primaryMuscleGroup = MuscleGroup.LEGS))
            completedWorkout("Alpha curls", curl)
            completedWorkout("Alpha presses", press)
            completedWorkout("Beta legs", squat)
            repository.deleteExercise(curl)
            curl
        }
        openHistory()
        compose.onNodeWithTag("history_name_search").performTextReplacement("alpha")
        awaitMatchingNames("Alpha curls", "Alpha presses", ordered = false)
        openFilters()
        compose.onNodeWithTag("history_filter_exercise").performClick()
        compose.onNodeWithTag("history_exercise_$fixture").performClick()
        compose.onNodeWithTag("history_filter_exercise").assert(hasText(text(R.string.history_filter_archived, "History archived curl"), substring = false))
        compose.onNodeWithTag("history_filter_muscle").performClick()
        compose.onNodeWithTag("history_muscle_CHEST").performClick()
        compose.onNodeWithTag("history_filters_done").performScrollTo().performClick()
        waitForText(text(R.string.history_filter_no_matches))
        assertEquals(emptyList<String>(), matchingNames())

        openFilters()
        compose.onNodeWithTag("history_filter_muscle").performClick()
        compose.onNodeWithTag("history_muscle_BACK").performClick()
        compose.onNodeWithTag("history_filters_done").performScrollTo().performClick()
        awaitMatchingNames("Alpha curls")
        compose.activityRule.scenario.recreate()
        waitForTag("history_name_search")
        compose.onNodeWithTag("history_name_search").assertTextEquals("alpha")
        awaitMatchingNames("Alpha curls")

        compose.onNodeWithTag("history_workout_list").performScrollToNode(hasText("Alpha curls", substring = false))
        compose.onNodeWithText("Alpha curls", substring = false).performClick()
        waitForTag("workout_detail_list")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForTag("history_name_search")
        awaitMatchingNames("Alpha curls")
        compose.onNodeWithTag("history_clear_filters").performClick()
        compose.onNodeWithTag("history_name_search").assertTextEquals("")
        awaitMatchingNames("Beta legs", "Alpha presses", "Alpha curls", ordered = false)
        assertFalse(historyModel().selectedFilters.value.hasFilters)
        compose.onNodeWithTag("history_workout_list").performScrollToNode(hasText("Beta legs", substring = false))
        compose.onNodeWithText("Beta legs", substring = false).assertIsDisplayed()
    }

    @Test
    fun datePickerRequiresValidRangeCancellationLeavesFiltersUntouchedAndExistingDatesApplyExactly() {
        runBlocking {
            val exerciseId = repository.saveExercise(Exercise(name = "History date exercise", primaryMuscleGroup = MuscleGroup.CHEST))
            completedWorkout("Date filter session", exerciseId)
        }
        openHistory()
        openFilters()
        compose.onNodeWithTag("history_filter_dates").performScrollTo().performClick()
        waitForTag("history_date_picker")
        compose.onNodeWithTag("history_apply_dates").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.history_filter_cancel)).performClick()
        assertNull(historyModel().selectedFilters.value.fromDate)
        assertNull(historyModel().selectedFilters.value.toDate)

        val date = LocalDate.now()
        compose.runOnIdle { historyModel().setDateRange(date, date) }
        compose.onNodeWithTag("history_filter_dates").performScrollTo().performClick()
        waitForTag("history_date_picker")
        compose.onNodeWithTag("history_apply_dates").assertIsEnabled().performClick()
        compose.waitUntil(45_000) { compose.onAllNodesWithTag("history_date_picker").fetchSemanticsNodes().isEmpty() }
        assertEquals(date, historyModel().selectedFilters.value.fromDate)
        assertEquals(date, historyModel().selectedFilters.value.toDate)
        compose.onNodeWithTag("history_clear_dates").performScrollTo().performClick()
        assertNull(historyModel().selectedFilters.value.fromDate)
        assertNull(historyModel().selectedFilters.value.toDate)
        compose.onNodeWithTag("history_filters_done").performScrollTo().performClick()
        awaitMatchingNames("Date filter session")
    }

    private fun historyModel(): HistoryViewModel = ViewModelProvider(compose.activity)[HistoryViewModel::class.java]

    private fun openHistory() {
        compose.onNode(hasText(text(R.string.history), substring = false) and hasClickAction()).performClick()
        waitForTag("history_name_search")
    }

    private fun openFilters() {
        compose.onNodeWithTag("history_open_filters").performClick()
        waitForTag("history_filter_panel")
    }

    private suspend fun completedWorkout(name: String, exerciseId: Long) {
        val id = repository.startWorkout(name)
        repository.addExercise(id, exerciseId)
        val set = repository.observeWorkouts().first().single { it.workout.id == id }.exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 10, completed = true))
        repository.finishWorkout(id)
    }

    private fun matchingNames() = historyModel().state.value.grouped.values.flatten().map { it.workout.name }

    private fun awaitMatchingNames(vararg expected: String, ordered: Boolean = true) {
        compose.waitUntil(45_000) {
            val names = matchingNames()
            if (ordered) names == expected.toList() else names.toSet() == expected.toSet()
        }
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

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
}

package com.forge.workout.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.utils.ProgressMetric
import com.forge.workout.utils.ProgressRange
import com.forge.workout.viewmodel.ProgressUiState
import com.forge.workout.viewmodel.ProgressViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressMetricsTest {
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
    fun strengthMetricAndRangePersistAcrossRecreationAndTabsAndUnitChangePreservesStoredWeights() {
        val fixture = runBlocking {
            val exerciseId = createExercise("Metrics strength fixture", ExerciseType.WEIGHT_REPS)
            completedWorkout(exerciseId) { it.copy(weight = 80.0, reps = 10, completed = true) }
            exerciseId to repository.observeWorkouts().first()
        }
        navigate(R.string.progress)
        awaitState { it.selected?.id == fixture.first && it.stats.workoutCount == 1 }
        selectMetric(ProgressMetric.ESTIMATED_1RM)
        selectRange(ProgressRange.ONE_MONTH)
        awaitState { it.metric == ProgressMetric.ESTIMATED_1RM && it.range == ProgressRange.ONE_MONTH }
        scrollTo("progress_chart")
        compose.onNodeWithTag("progress_chart").assertIsDisplayed()
            .assert(hasContentDescription(text(R.string.progress_estimated_chart), substring = true))

        compose.activityRule.scenario.recreate()
        waitForTag("progress_list")
        awaitState { it.selected?.id == fixture.first && it.metric == ProgressMetric.ESTIMATED_1RM && it.range == ProgressRange.ONE_MONTH }
        assertMetricSelected(ProgressMetric.ESTIMATED_1RM)
        assertRangeSelected(ProgressRange.ONE_MONTH)
        navigate(R.string.home)
        waitForText(text(R.string.home_ready))
        navigate(R.string.progress)
        awaitState { it.selected?.id == fixture.first && it.metric == ProgressMetric.ESTIMATED_1RM && it.range == ProgressRange.ONE_MONTH }

        runBlocking { app.preferences.setUnit(WeightUnit.LB) }
        val pounds = awaitState { it.weightUnit == WeightUnit.LB && it.chartPoints.isNotEmpty() }
        assertEquals(80.0, pounds.stats.bestWeightKg, 0.000001)
        assertEquals(WeightUnit.LB.fromKg(80.0 * (1 + 10.0 / 30)), pounds.chartPoints.single().value, 0.000001)
        assertEquals(fixture.second, runBlocking { repository.observeWorkouts().first() })
        selectMetric(ProgressMetric.VOLUME)
        val volume = awaitState { it.metric == ProgressMetric.VOLUME && it.weightUnit == WeightUnit.LB }
        assertEquals(800.0, volume.stats.totalVolumeKg, 0.000001)
        assertEquals(WeightUnit.LB.fromKg(800.0), volume.chartPoints.single().value, 0.000001)
    }

    @Test
    fun archivedBodyweightDurationAndDistanceExercisesExposeOnlyApplicableMetrics() {
        val fixture = runBlocking {
            val body = createExercise("Metrics archived bodyweight", ExerciseType.BODYWEIGHT)
            val duration = createExercise("Metrics duration", ExerciseType.DURATION)
            val distance = createExercise("Metrics distance", ExerciseType.DISTANCE)
            completedWorkout(body) { it.copy(reps = 15, completed = true) }
            completedWorkout(duration) { it.copy(duration = 90, completed = true) }
            completedWorkout(distance) { it.copy(distance = 4.5, completed = true) }
            repository.deleteExercise(body)
            Triple(body, duration, distance)
        }
        navigate(R.string.progress)
        awaitState { it.selected?.id == fixture.third && it.stats.workoutCount == 1 }
        chooseExercise(fixture.first, "Metrics archived bodyweight")
        val body = awaitState { it.selected?.id == fixture.first && it.metric == ProgressMetric.BEST_REPS }
        assertEquals(setOf(ProgressMetric.BEST_REPS, ProgressMetric.FREQUENCY), body.availableMetrics.toSet())
        assertEquals(15, body.stats.maxReps)
        scrollTo("progress_metric_filters")
        compose.onNodeWithTag("progress_metric_BEST_WEIGHT").assertDoesNotExist()
        compose.onNodeWithTag("progress_metric_VOLUME").assertDoesNotExist()
        selectMetric(ProgressMetric.FREQUENCY)

        chooseExercise(fixture.second, "Metrics duration")
        val duration = awaitState { it.selected?.id == fixture.second && it.metric == ProgressMetric.FREQUENCY }
        assertEquals(setOf(ProgressMetric.DURATION, ProgressMetric.FREQUENCY), duration.availableMetrics.toSet())
        selectMetric(ProgressMetric.DURATION)
        assertEquals(90, awaitState { it.metric == ProgressMetric.DURATION }.stats.bestDurationSeconds)
        chooseExercise(fixture.third, "Metrics distance")
        val distance = awaitState { it.selected?.id == fixture.third && it.metric == ProgressMetric.DISTANCE }
        assertEquals(setOf(ProgressMetric.DISTANCE, ProgressMetric.FREQUENCY), distance.availableMetrics.toSet())
        assertEquals(4.5, distance.stats.bestDistanceKm, 0.000001)
        assertEquals(4.5, distance.chartPoints.single().value, 0.000001)
    }

    private fun selectMetric(metric: ProgressMetric) {
        scrollTo("progress_metric_filters")
        compose.onNodeWithTag("progress_metric_filters").performScrollToNode(hasTestTag("progress_metric_${metric.name}"))
        compose.onNodeWithTag("progress_metric_${metric.name}").performClick()
        awaitState { it.metric == metric }
        compose.onNodeWithTag("progress_metric_${metric.name}").assertIsSelected()
    }

    private fun selectRange(range: ProgressRange) {
        scrollTo("progress_range_filters")
        compose.onNodeWithTag("progress_range_filters").performScrollToNode(hasTestTag("progress_range_${range.name}"))
        compose.onNodeWithTag("progress_range_${range.name}").performClick()
        awaitState { it.range == range }
        compose.onNodeWithTag("progress_range_${range.name}").assertIsSelected()
    }

    private fun assertMetricSelected(metric: ProgressMetric) {
        scrollTo("progress_metric_filters")
        compose.onNodeWithTag("progress_metric_filters").performScrollToNode(hasTestTag("progress_metric_${metric.name}"))
        compose.onNodeWithTag("progress_metric_${metric.name}").assertIsSelected()
    }

    private fun assertRangeSelected(range: ProgressRange) {
        scrollTo("progress_range_filters")
        compose.onNodeWithTag("progress_range_filters").performScrollToNode(hasTestTag("progress_range_${range.name}"))
        compose.onNodeWithTag("progress_range_${range.name}").assertIsSelected()
    }

    private fun chooseExercise(id: Long, name: String) {
        scrollTo("progress_exercise_picker")
        compose.onNodeWithTag("progress_exercise_picker").performClick()
        waitForTag("progress_exercise_search")
        compose.onNodeWithTag("progress_exercise_search").performTextReplacement(name)
        compose.onNodeWithTag("progress_exercise_options").performScrollToNode(hasTestTag("progress_exercise_$id"))
        compose.onNodeWithTag("progress_exercise_$id").performClick()
    }

    private fun progressModel(): ProgressViewModel = ViewModelProvider(compose.activity)[ProgressViewModel::class.java]

    private fun awaitState(predicate: (ProgressUiState) -> Boolean): ProgressUiState {
        compose.waitUntil(45_000) { predicate(progressModel().state.value) }
        return progressModel().state.value
    }

    private suspend fun createExercise(name: String, type: ExerciseType) = repository.saveExercise(
        Exercise(name = name, primaryMuscleGroup = MuscleGroup.OTHER, exerciseType = type),
    )

    private suspend fun completedWorkout(exerciseId: Long, set: (WorkoutSet) -> WorkoutSet) {
        val id = repository.startWorkout("Progress fixture session")
        repository.addExercise(id, exerciseId)
        val original = repository.observeWorkouts().first().single { it.workout.id == id }.exercises.single().sets.single()
        repository.updateSet(set(original))
        repository.finishWorkout(id)
    }

    private fun navigate(destination: Int) {
        compose.onNode(hasText(text(destination), substring = false) and hasClickAction()).performClick()
        if (destination == R.string.progress) waitForTag("progress_list")
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("progress_list").performScrollToNode(hasTestTag(tag))
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

    private fun text(resource: Int) = compose.activity.getString(resource)
}

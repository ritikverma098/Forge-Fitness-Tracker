package com.forge.workout.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the compact controls against the isolated test application's real Room graph. */
@RunWith(AndroidJUnit4::class)
class CompactWorkoutLoggingTest {
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
    fun weightAndRepsReplaceSelectedValuesThenCompleteDuplicateAndDeleteWithoutLosingData() {
        val detail = weightedWorkout()
        val originalSet = detail.exercises.single().sets.single()
        openWorkout(originalSet.id)
        val weight = compose.onNodeWithTag(numericTag(originalSet.id, SetField.WEIGHT))
        val reps = compose.onNodeWithTag(numericTag(originalSet.id, SetField.REPS))
        val completed = compose.onNodeWithTag("set_done_${originalSet.id}")

        weight.assertContentDescriptionEquals(text(R.string.numeric_weight, "kg"))
        reps.assertContentDescriptionEquals(text(R.string.reps_label))
        weight.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        weight.assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 2)),
        )
        weight.performTextInput("82.5")
        weight.assertTextEquals("82.5")
        weight.performImeAction()
        reps.assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 1)),
        )
        reps.performTextInput("11")
        reps.performImeAction()
        reps.assertIsNotFocused()
        awaitSet(originalSet.id) { it.weight == 82.5 && it.reps == 11 }

        completed.assertIsEnabled().assertIsOff().performClick()
        awaitSet(originalSet.id) { it.completed }
        completed.assertIsOn()
        compose.onNodeWithText(text(R.string.add_set)).performScrollTo().performClick()
        awaitRepository { workout(detail.workout.id).exercises.single().sets.size == 2 }
        val duplicate = runBlocking { workout(detail.workout.id).exercises.single().sortedSets.last() }
        assertEquals(82.5, duplicate.weight, 0.00001)
        assertEquals(11, duplicate.reps)
        assertFalse(duplicate.completed)
        scrollToSet(duplicate.id)
        compose.onNodeWithTag(numericTag(duplicate.id, SetField.WEIGHT)).assertTextEquals("82.5")
        compose.onNodeWithTag(numericTag(duplicate.id, SetField.REPS)).assertTextEquals("11")
        compose.onNodeWithTag("set_done_${duplicate.id}").assertIsOff()

        scrollToSet(originalSet.id)
        compose.onNodeWithTag("set_options_${originalSet.id}").performClick()
        compose.onNodeWithText(text(R.string.delete_set, 1)).performClick()
        awaitRepository {
            val remaining = workout(detail.workout.id).exercises.single().sets
            remaining.size == 1 && remaining.single().id == duplicate.id && remaining.single().setNumber == 1
        }
        compose.onNodeWithTag("workout_set_${originalSet.id}").assertDoesNotExist()
        scrollToSet(duplicate.id)
        compose.onNodeWithTag("set_done_${duplicate.id}")
            .assertContentDescriptionEquals(text(R.string.complete_set, 1)).assertIsOff()

        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.workout_name))
        scrollToSet(duplicate.id)
        compose.onNodeWithTag(numericTag(duplicate.id, SetField.WEIGHT)).assertTextEquals("82.5")
        compose.onNodeWithTag(numericTag(duplicate.id, SetField.REPS)).assertTextEquals("11")
        assertTrue(runBlocking { workout(detail.workout.id).workout.endTime == null })
    }

    @Test
    fun invalidAndBlankQuantitiesDisableCompletionAndValidReplacementRecovers() {
        val detail = weightedWorkout()
        val set = detail.exercises.single().sets.single()
        openWorkout(set.id)
        val weight = compose.onNodeWithTag(numericTag(set.id, SetField.WEIGHT))
        val reps = compose.onNodeWithTag(numericTag(set.id, SetField.REPS))
        val completed = compose.onNodeWithTag("set_done_${set.id}")

        weight.performTextReplacement("-3")
        completed.assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.invalid_set)).assertIsDisplayed()
        assertEquals(60.0, runBlocking { savedSet(set.id).weight }, 0.00001)
        assertFalse(runBlocking { savedSet(set.id).completed })

        weight.performTextReplacement("80")
        awaitSet(set.id) { it.weight == 80.0 }
        reps.performTextReplacement("2.5")
        completed.assertIsNotEnabled()
        assertEquals(8, runBlocking { savedSet(set.id).reps })
        reps.performTextClearance()
        awaitSet(set.id) { it.reps == 0 }
        completed.assertIsNotEnabled()
        reps.performTextInput("12")
        reps.performImeAction()
        awaitSet(set.id) { it.weight == 80.0 && it.reps == 12 }
        completed.assertIsEnabled().performClick()
        awaitSet(set.id) { it.completed }
        assertTrue(runBlocking { workout(detail.workout.id).workout.endTime == null })
    }

    @Test
    fun eachNonWeightedExerciseUsesItsApplicableQuantityAndDoneClearsFocus() {
        val specifications = listOf(
            QuantityCase(ExerciseType.BODYWEIGHT, SetField.REPS, R.string.reps_label, "12"),
            QuantityCase(ExerciseType.REPS_ONLY, SetField.REPS, R.string.reps_label, "15"),
            QuantityCase(ExerciseType.DURATION, SetField.DURATION, R.string.duration_label, "45"),
            QuantityCase(ExerciseType.DISTANCE, SetField.DISTANCE, R.string.distance_label, "2.75"),
        )
        val workoutId = runBlocking {
            val id = repository.startWorkout("Compact quantity test")
            specifications.forEach { specification ->
                val exerciseId = repository.saveExercise(Exercise(
                    name = "Compact ${specification.type.name}", primaryMuscleGroup = MuscleGroup.OTHER,
                    exerciseType = specification.type,
                ))
                repository.addExercise(id, exerciseId)
            }
            id
        }
        val entries = runBlocking { workout(workoutId).sortedExercises }
        openWorkout(entries.first().sets.single().id)

        entries.zip(specifications).forEach { (entry, specification) ->
            val set = entry.sets.single()
            scrollToSet(set.id)
            compose.onNodeWithTag(numericTag(set.id, SetField.WEIGHT)).assertDoesNotExist()
            val quantity = compose.onNodeWithTag(numericTag(set.id, specification.field))
            quantity.assertContentDescriptionEquals(text(specification.label)).performTextInput(specification.value)
            quantity.performImeAction()
            quantity.assertIsNotFocused()
            awaitSet(set.id) { saved ->
                when (specification.field) {
                    SetField.REPS -> saved.reps == specification.value.toInt()
                    SetField.DURATION -> saved.duration == specification.value.toInt()
                    SetField.DISTANCE -> saved.distance == specification.value.toDouble()
                    SetField.WEIGHT -> false
                }
            }
            compose.onNodeWithTag("set_done_${set.id}").assertIsEnabled().performClick()
            awaitSet(set.id) { it.completed }
            val saved = runBlocking { savedSet(set.id) }
            assertEquals(0.0, saved.weight, 0.00001)
            if (specification.field != SetField.REPS) assertEquals(0, saved.reps)
            if (specification.field != SetField.DURATION) assertEquals(0, saved.duration)
            if (specification.field != SetField.DISTANCE) assertEquals(0.0, saved.distance, 0.00001)
        }
    }

    private fun weightedWorkout(): WorkoutDetail = runBlocking {
        val exercise = repository.observeExercises().first().single { it.name == "Bench Press" }
        val id = repository.startWorkout("Compact strength test")
        repository.addExercise(id, exercise.id)
        val set = workout(id).exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 8))
        workout(id)
    }

    private fun openWorkout(setId: Long) {
        waitForText(text(R.string.home_resume_workout))
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        waitForText(text(R.string.workout_name))
        scrollToSet(setId)
    }

    private fun scrollToSet(id: Long) {
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(hasTestTag("workout_set_$id"))
        compose.waitUntil(45_000) {
            compose.onAllNodesWithTag("workout_set_$id").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("workout_set_$id").assertIsDisplayed()
    }

    private suspend fun workout(id: Long) = repository.observeWorkouts().first().single { it.workout.id == id }
    private suspend fun savedSet(id: Long): WorkoutSet = repository.observeWorkouts().first()
        .flatMap { it.exercises }.flatMap { it.sets }.single { it.id == id }

    private fun awaitSet(id: Long, predicate: (WorkoutSet) -> Boolean) = awaitRepository { predicate(savedSet(id)) }
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

    private fun text(resource: Int, vararg arguments: Any) = compose.activity.getString(resource, *arguments)
    private fun numericTag(id: Long, field: SetField) = "numeric_${id}_${field.name}"
    private data class QuantityCase(val type: ExerciseType, val field: SetField, val label: Int, val value: String)
}

package com.forge.workout.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
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

/** Real navigation, ViewModels, Room, and DataStore; test records are removed after each case. */
@RunWith(AndroidJUnit4::class)
class ForgeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ForgeApplication
    private val repository: WorkoutRepository get() = app.repository

    @Before
    fun resetTrainingData() = runBlocking {
        repository.ensureSeeded()
        removeTestData()
        app.preferences.setTheme(ThemeMode.SYSTEM)
        app.preferences.setUnit(WeightUnit.KG)
        app.preferences.setRest(0)
        app.preferences.setConfirmation(true)
        waitForText(text(R.string.home_ready))
    }

    @After
    fun cleanup() = runBlocking { removeTestData() }

    @Test
    fun freshInstallHasEmptyHistoryAndSeededSearchableExercises() {
        compose.onNodeWithText(text(R.string.home_no_last_workout)).assertIsDisplayed()
        navigate(R.string.history)
        compose.onNodeWithText(text(R.string.history_empty_title)).assertIsDisplayed()

        openExerciseLibrary()
        waitForText(quantityText(R.plurals.library_results, 81, 81))
        compose.onNodeWithText(quantityText(R.plurals.library_results, 81, 81)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Bench Press")
        compose.onNodeWithText("Bench Press", substring = false).assertIsDisplayed()
        assertEquals(81, runBlocking { repository.observeExercises().first().size })
        assertTrue(runBlocking { repository.observeWorkouts().first().isEmpty() })
    }

    @Test
    fun customExerciseValidatesNameAndPersistsMuscleAndType() {
        openExerciseLibrary()
        waitForText(quantityText(R.plurals.library_results, 81, 81))
        compose.onNodeWithContentDescription(text(R.string.library_new_exercise)).performClick()
        clickText(R.string.library_save)
        compose.onNodeWithText(text(R.string.library_name_required)).assertIsDisplayed()

        input(R.string.library_name, "Bench Press")
        clickText(R.string.library_save)
        compose.onNodeWithText(text(R.string.library_name_duplicate)).assertIsDisplayed()

        input(R.string.library_name, "Tempo Carry UI Test", replace = true)
        compose.onNodeWithText(text(R.string.library_primary_muscle)).performScrollTo().performClick()
        compose.onAllNodesWithText(text(R.string.forearms)).onLast().performClick()
        compose.onNodeWithText(text(R.string.library_type)).performScrollTo().performClick()
        compose.onAllNodesWithText(text(R.string.duration_type)).onLast().performClick()
        input(R.string.library_notes, "Keep posture tall.")
        clickText(R.string.library_save)
        waitForRepository { repository.observeExercises().first().any { it.name == "Tempo Carry UI Test" } }

        val saved = runBlocking { repository.observeExercises().first().single { it.name == "Tempo Carry UI Test" } }
        assertEquals(MuscleGroup.FOREARMS, saved.primaryMuscleGroup)
        assertEquals(ExerciseType.DURATION, saved.exerciseType)
        assertEquals("Keep posture tall.", saved.notes)
        assertTrue(saved.isCustom)
    }

    @Test
    fun loggedWorkoutSurvivesNavigationAndFinishesIntoHistoryAndProgress() {
        clickText(R.string.home_start_workout)
        waitForText(text(R.string.workout_name))
        input(R.string.workout_name, "Strength UI Test", replace = true)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onAllNodesWithText(text(R.string.add_exercise)).onFirst().performScrollTo().performClick()
        waitForText(text(R.string.library_picker_subtitle))
        compose.onNode(hasSetTextAction()).performTextInput("Bench Press")
        compose.onNode(hasText("Bench Press", substring = false) and
            hasAnyAncestor(hasTestTag("library_exercise_results"))).performClick()
        // The picker keyboard can leave the new card below the reduced viewport.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText("Bench Press", substring = false))
        waitForText(text(R.string.reps_label))
        waitForText(text(R.string.weight_label, "kg"))
        inputLabel(text(R.string.weight_label, "kg"), "80")
        input(R.string.reps_label, "10")
        compose.onNodeWithText(text(R.string.set_done)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.complete_set, 1)).performClick()
        waitForRepository { repository.observeWorkouts().first().flatMap { it.exercises }.flatMap { it.sets }.any { it.completed } }

        compose.onNodeWithText(text(R.string.add_set)).performScrollTo().performClick()
        waitForRepository { repository.observeWorkouts().first().single().exercises.single().sets.size == 2 }
        val sets = runBlocking { repository.observeWorkouts().first().single().exercises.single().sortedSets }
        assertEquals(80.0, sets[1].weight, .00001)
        assertEquals(10, sets[1].reps)
        assertTrue(!sets[1].completed)

        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.workout_name))
        assertEquals(2, runBlocking { repository.observeWorkouts().first().single().exercises.single().sets.size })

        navigate(R.string.home)
        compose.onNodeWithText(text(R.string.home_resume_workout)).performClick()
        waitForText("Strength UI Test")
        compose.onNodeWithText(text(R.string.finish_workout)).performScrollTo().performClick()
        waitForText(text(R.string.finish_title))
        compose.onAllNodesWithText(text(R.string.finish_workout)).onLast().performClick()
        waitForText(text(R.string.detail_summary_title))
        compose.onNodeWithText(text(R.string.detail_summary_title)).assertIsDisplayed()
        compose.onAllNodesWithText("800 kg").onFirst().performScrollTo().assertIsDisplayed()
        val finished = runBlocking { repository.observeWorkouts().first().single() }
        assertTrue(finished.workout.endTime != null)
        assertEquals(2, finished.exercises.single().sets.size)

        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        navigate(R.string.history)
        waitForText("Strength UI Test")
        compose.onNodeWithText("Strength UI Test").assertIsDisplayed()
        navigate(R.string.progress)
        waitForText(text(R.string.progress_best_weight))
        compose.onNodeWithText("80 kg").assertIsDisplayed()
    }

    @Test
    fun restTimerStaysVisibleWhileScrollingAndSkipClearsTheExistingDeadline() {
        val (workoutId, deadline, lastExerciseName) = runBlocking {
            val exercises = repository.observeExercises().first()
                .filter { it.exerciseType == ExerciseType.WEIGHT_REPS }.take(8)
            val id = repository.startWorkout("Rest timer UI Test")
            exercises.forEach { repository.addExercise(id, it.id) }
            val detail = repository.observeWorkouts().first().single { it.workout.id == id }
            val firstSet = detail.sortedExercises.first().sortedSets.first()
            repository.updateSet(firstSet.copy(weight = 60.0, reps = 8, completed = true))
            val restDeadline = System.currentTimeMillis() + 180_000L
            repository.setRestEndTime(id, restDeadline)
            Triple(id, restDeadline, exercises.last().name)
        }

        waitForText(text(R.string.home_resume_workout))
        clickText(R.string.home_resume_workout)
        compose.waitUntil(45_000) {
            compose.onAllNodesWithTag("rest_timer_bar").fetchSemanticsNodes().size == 1
        }
        compose.onAllNodesWithTag("rest_timer_bar").assertCountEquals(1)
        compose.onNodeWithTag("rest_timer_bar").assertIsDisplayed()

        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText(lastExerciseName, substring = false))
        compose.onNodeWithText(lastExerciseName, substring = false).assertIsDisplayed()
        compose.onAllNodesWithTag("rest_timer_bar").assertCountEquals(1)
        compose.onNodeWithTag("rest_timer_bar").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.skip_rest)).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.skip_rest)).assertIsDisplayed()
        assertEquals(deadline, runBlocking {
            repository.observeWorkouts().first().single { it.workout.id == workoutId }.workout.restEndTime
        })

        clickText(R.string.skip_rest)
        waitForRepository {
            repository.observeWorkouts().first().single { it.workout.id == workoutId }.workout.restEndTime == null
        }
        compose.waitUntil(45_000) {
            compose.onAllNodesWithTag("rest_timer_bar").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("rest_timer_bar").assertDoesNotExist()
    }

    @Test
    fun workoutMuscleGroupsSurviveRecreationAndDefaultPickerCombinesGroupsWithSearch() {
        val fixturePrefix = "AAA Group Test"
        val bicepsName = "$fixturePrefix Biceps"
        val tricepsName = "$fixturePrefix Triceps"
        val forearmsName = "$fixturePrefix Forearms"
        val secondaryName = "$fixturePrefix Secondary"
        val chestName = "$fixturePrefix Chest"
        runBlocking {
            listOf(
                Exercise(name = bicepsName, primaryMuscleGroup = MuscleGroup.BICEPS),
                Exercise(name = tricepsName, primaryMuscleGroup = MuscleGroup.TRICEPS),
                Exercise(name = forearmsName, primaryMuscleGroup = MuscleGroup.FOREARMS),
                Exercise(name = secondaryName, primaryMuscleGroup = MuscleGroup.BACK, secondaryMuscleGroup = MuscleGroup.BICEPS),
                Exercise(name = chestName, primaryMuscleGroup = MuscleGroup.CHEST),
            ).forEach { repository.saveExercise(it) }
        }
        clickText(R.string.home_start_workout)
        waitForText(text(R.string.workout_name))

        val selectedGroups = mutableSetOf<MuscleGroup>()
        val groups = listOf(
            MuscleGroup.BICEPS to R.string.biceps,
            MuscleGroup.TRICEPS to R.string.triceps,
            MuscleGroup.FOREARMS to R.string.forearms,
        )
        groups.forEach { (group, labelResource) ->
            val label = text(labelResource)
            compose.onNodeWithTag("workout_muscle_groups").performScrollToNode(hasText(label, substring = false))
            compose.onNode(hasText(label, substring = false) and hasAnyAncestor(hasTestTag("workout_muscle_groups"))).performClick()
            selectedGroups += group
            waitForRepository { repository.observeWorkouts().first().single().workout.muscleGroups.toSet() == selectedGroups }
        }

        compose.activityRule.scenario.recreate()
        waitForText(text(R.string.workout_name))
        groups.forEach { (_, labelResource) ->
            val label = text(labelResource)
            compose.onNodeWithTag("workout_muscle_groups").performScrollToNode(hasText(label, substring = false))
            compose.onNode(hasText(label, substring = false) and hasAnyAncestor(hasTestTag("workout_muscle_groups"))).assertIsSelected()
        }
        assertEquals(selectedGroups, runBlocking { repository.observeWorkouts().first().single().workout.muscleGroups.toSet() })

        compose.onAllNodesWithText(text(R.string.add_exercise)).onFirst().performScrollTo().performClick()
        waitForText(text(R.string.library_picker_subtitle))
        compose.onNodeWithText(text(R.string.picker_workout_groups)).assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextInput(fixturePrefix)
        waitForText(quantityText(R.plurals.library_results, 4, 4))
        listOf(bicepsName, tricepsName, forearmsName, secondaryName).forEach { name ->
            compose.onNodeWithTag("library_exercise_results").performScrollToNode(hasText(name, substring = false))
            compose.onNodeWithText(name, substring = false).assertIsDisplayed()
        }
        compose.onNodeWithText(chestName, substring = false).assertDoesNotExist()

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("$fixturePrefix Che")
        waitForText(text(R.string.library_no_results_title))
        compose.onNodeWithTag("library_muscle_filters")
            .performScrollToNode(hasText(text(R.string.library_all_muscles), substring = false))
        clickText(R.string.library_all_muscles)
        waitForText(quantityText(R.plurals.library_results, 1, 1))
        compose.onNodeWithText(chestName, substring = false).assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(fixturePrefix)
        waitForText(quantityText(R.plurals.library_results, 5, 5))
        compose.onNodeWithTag("library_muscle_filters")
            .performScrollToNode(hasText(text(R.string.picker_workout_groups), substring = false))
        clickText(R.string.picker_workout_groups)
        waitForText(quantityText(R.plurals.library_results, 4, 4))
    }

    @Test
    fun historyBackRestoresScrollAndLayoutWithoutDoublePoppingAndKeepsEditorChanges() {
        val targetName = "Back navigation test 01"
        val targetId = runBlocking {
            val exerciseId = repository.observeExercises().first().single { it.name == "Bench Press" }.id
            var oldestId = 0L
            repeat(16) { index ->
                val name = "Back navigation test ${(index + 1).toString().padStart(2, '0')}"
                val id = repository.startWorkout(name)
                if (index == 0) oldestId = id
                repository.addExercise(id, exerciseId)
                val set = repository.observeWorkouts().first().single { it.workout.id == id }
                    .exercises.single().sets.single()
                repository.updateSet(set.copy(weight = 60.0, reps = 8, completed = true))
                repository.finishWorkout(id)
            }
            oldestId
        }

        navigate(R.string.history)
        waitForText(text(R.string.history_subtitle))
        compose.onNodeWithTag("history_workout_list")
            .performScrollToNode(hasText(targetName, substring = false))
        val historyHeight = compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot.height
        val historyCardTop = compose.onNodeWithText(targetName).fetchSemanticsNode().boundsInRoot.top

        fun assertHistoryRestored(name: String = targetName) {
            waitForText(text(R.string.history_subtitle))
            compose.onNodeWithText(text(R.string.history_subtitle)).assertIsDisplayed()
            compose.onNodeWithText(name).assertIsDisplayed()
            assertEquals(historyHeight, compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot.height, 1f)
            assertEquals(historyCardTop, compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top, 2f)
        }

        compose.onNodeWithText(targetName).performClick()
        waitForText(text(R.string.detail_duration))
        assertEquals(historyHeight, compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot.height, 1f)
        val outgoingBackAction = requireNotNull(
            compose.onNodeWithContentDescription(text(R.string.back)).fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action,
        )
        // Invoke the same outgoing button twice before a new screen can replace its callback.
        compose.runOnUiThread { outgoingBackAction(); outgoingBackAction() }
        assertHistoryRestored()

        compose.onNodeWithText(targetName).performClick()
        waitForText(text(R.string.detail_duration))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertHistoryRestored()

        compose.onNodeWithText(targetName).performClick()
        waitForText(text(R.string.detail_duration))
        compose.onNodeWithContentDescription(text(R.string.detail_edit)).performClick()
        waitForText(text(R.string.edit_workout))
        val editedName = "Back navigation edited 01"
        input(R.string.workout_name, editedName, replace = true)
        waitForRepository {
            repository.observeWorkouts().first().single { it.workout.id == targetId }.workout.name == editedName
        }
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.detail_duration))
        compose.onNodeWithText(editedName).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        assertHistoryRestored(editedName)
        assertEquals(editedName, runBlocking {
            repository.observeWorkouts().first().single { it.workout.id == targetId }.workout.name
        })
    }

    @Test
    fun routineStartThenHistoryAndProgressKeepsHomeAndSettingsReachable() {
        val routineName = "Home return routine UI Test"
        val exerciseNames = listOf("Bench Press", "Barbell Curl")
        val routineId = runBlocking {
            val exercises = repository.observeExercises().first()
            val id = repository.saveRoutine(name = routineName)
            exerciseNames.forEach { name ->
                repository.addRoutineExercise(id, exercises.single { it.name == name }.id)
            }
            id
        }

        // Enter directly from Home: visiting another tab first changes the saved-stack setup.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text(R.string.home_routines), substring = false))
        compose.onNodeWithText(text(R.string.home_routines)).assertIsDisplayed().performClick()
        waitForText(text(R.string.routines_title))
        compose.onNodeWithTag("routine_start_$routineId").performScrollTo().assertIsDisplayed().performClick()
        waitForText(text(R.string.workout_name))
        waitForRepository {
            repository.observeWorkouts().first().singleOrNull()?.workout?.name == routineName
        }
        val activeBefore = runBlocking { repository.observeWorkouts().first().single() }
        assertEquals(exerciseNames, activeBefore.sortedExercises.map { it.exercise.name })
        assertTrue(activeBefore.workout.endTime == null)

        navigate(R.string.history)
        waitForText(text(R.string.history_empty_title))
        compose.onNodeWithText(text(R.string.history_subtitle)).assertIsDisplayed()
        navigate(R.string.progress)
        waitForText(text(R.string.progress_subtitle))
        compose.onNodeWithText(text(R.string.progress_subtitle)).assertIsDisplayed()

        repeat(3) {
            navigate(R.string.home)
            waitForText(text(R.string.home_subtitle))
            compose.onNodeWithText(text(R.string.home_subtitle)).assertIsDisplayed()
            compose.onNode(hasText(text(R.string.home), substring = false) and hasClickAction()).assertIsSelected()
            compose.onNodeWithContentDescription(text(R.string.overview_settings)).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        waitForText(text(R.string.settings_subtitle))
        compose.onNodeWithText(text(R.string.settings_subtitle)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.home_subtitle))
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).assertIsDisplayed()

        val activeAfter = runBlocking { repository.observeWorkouts().first().single() }
        assertEquals(activeBefore, activeAfter)
        navigate(R.string.workout)
        waitForText(text(R.string.workout_name))
        compose.onNodeWithText(routineName, substring = false).assertIsDisplayed()
        assertEquals(activeBefore.workout.id, runBlocking {
            repository.observeWorkouts().first().single { it.workout.endTime == null }.workout.id
        })
    }

    @Test
    fun routinesAndSettingsPersistThroughTheApplication() {
        navigate(R.string.workout)
        clickText(R.string.routines)
        waitForText(text(R.string.routines_title))
        compose.onNodeWithContentDescription(text(R.string.routines_create)).performClick()
        input(R.string.routines_name, "Legs UI Test")
        clickText(R.string.routines_save)
        waitForText(text(R.string.routines_no_exercises_title))
        compose.onNodeWithText(text(R.string.routines_add_exercise)).performScrollTo().performClick()
        waitForText(text(R.string.library_search))
        compose.onNode(hasSetTextAction()).performTextInput("Squat")
        compose.onNodeWithText("Squat", substring = false).performClick()
        waitForRepository { repository.observeRoutines().first().any { it.routine.name == "Legs UI Test" && it.exercises.size == 1 } }
        val routine = runBlocking { repository.observeRoutines().first().single() }
        assertEquals("Squat", routine.exercises.single().exercise.name)

        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        navigate(R.string.home)
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        compose.onNodeWithText(text(R.string.theme_dark)).performClick()
        compose.onNodeWithText(text(R.string.pounds)).performScrollTo().performClick()
        waitForRepository {
            val settings = app.preferences.preferences.first()
            settings.themeMode == ThemeMode.DARK && settings.weightUnit == WeightUnit.LB
        }
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        compose.onNodeWithText(text(R.string.theme_dark)).assertIsDisplayed()
        assertEquals(WeightUnit.LB, runBlocking { app.preferences.preferences.first().weightUnit })
    }

    private fun navigate(destination: Int) {
        compose.onNode(hasText(text(destination), substring = false) and hasClickAction()).performClick()
    }

    private fun openExerciseLibrary() {
        navigate(R.string.home)
        waitForText(text(R.string.home_subtitle))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text(R.string.home_exercises), substring = false))
        compose.onNodeWithText(text(R.string.home_exercises)).assertIsDisplayed().performClick()
        waitForText(text(R.string.library_subtitle))
        compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed()
    }

    private fun clickText(resource: Int) {
        compose.onNode(hasText(text(resource), substring = false) and hasClickAction()).performClick()
    }

    private fun input(resource: Int, value: String, replace: Boolean = false) = inputLabel(text(resource), value, replace)

    private fun inputLabel(label: String, value: String, replace: Boolean = false) {
        val accessible = if(label in listOf("kg", "lb")) text(R.string.numeric_weight, label) else label
        val field = compose.onNode(hasSetTextAction() and
            (hasText(label, substring = false) or hasContentDescription(accessible, substring = false)))
        try {
            field.assertIsDisplayed()
        } catch (_: AssertionError) {
            field.performScrollTo()
        }
        if (replace) field.performTextClearance()
        field.performTextInput(value)
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForRepository(predicate: suspend () -> Boolean) {
        compose.waitUntil(45_000) { runBlocking { predicate() } }
    }

    private fun text(resource: Int, vararg args: Any): String = compose.activity.getString(resource, *args)
    private fun quantityText(resource: Int, quantity: Int, vararg args: Any): String =
        compose.activity.resources.getQuantityString(resource, quantity, *args)

    private suspend fun removeTestData() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
        repository.observeRoutines().first().forEach { repository.deleteRoutine(it.routine.id) }
        repository.observeExercises().first().filter { it.isCustom }.forEach { repository.deleteExercise(it.id) }
    }
}

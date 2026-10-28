package com.forge.workout.ui

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.forge.workout.ForgeApplication
import com.forge.workout.ForgeTestApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.backup.BackupCodec
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.viewmodel.BackupViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Restores through the live Settings dialog and uses the same Activity immediately afterward. */
@RunWith(AndroidJUnit4::class)
class RestoreNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ForgeApplication
    private val repository get() = app.repository
    private lateinit var baseline: ForgeBackup
    private val files = mutableListOf<File>()

    @Before
    fun prepare() = runBlocking {
        assertTrue(app is ForgeTestApplication)
        repository.ensureSeeded()
        val previous = app.backups.snapshot()
        val clean = previous.copy(
            exercises = previous.exercises.filter { !it.isCustom },
            workouts = emptyList(), workoutExercises = emptyList(), sets = emptyList(),
            routines = emptyList(), routineExercises = emptyList(),
            preferences = UserPreferences(ThemeMode.SYSTEM, WeightUnit.KG, 0, true),
        )
        app.backups.restore(clean)
        app.preferences.replace(clean.preferences)
        baseline = app.backups.snapshot()
        waitForText(text(R.string.home_ready))
    }

    @After
    fun cleanup() = runBlocking {
        app.backups.restore(baseline)
        app.preferences.replace(baseline.preferences)
        files.forEach { it.delete() }
    }

    @Test
    fun freshSeededBackupRestoresAndAllNavigationWorksWithoutRestart() {
        val originalActivity = compose.activity
        val original = runBlocking { app.backups.snapshot() }
        assertTrue(original.exercises.isNotEmpty())
        assertTrue(original.workouts.isEmpty())
        runBlocking { app.backups.restore(ForgeBackup(preferences = original.preferences)) }
        awaitRepository { repository.observeExercises().first().isEmpty() }
        openExerciseLibrary()
        waitForText(quantityText(R.plurals.library_results, 0, 0))
        backToHome()

        restoreThroughSettings(original)
        assertSame(originalActivity, compose.activity)
        assertSnapshot(original)
        openExerciseLibrary()
        waitForText(quantityText(R.plurals.library_results, original.exercises.size, original.exercises.size))
        compose.onNode(hasSetTextAction()).performTextReplacement("Bench Press")
        waitForText("Bench Press")
        compose.onNode(hasText("Bench Press", substring = false) and
            hasAnyAncestor(hasTestTag("library_exercise_results"))).assertIsDisplayed()
        backToHome()
        navigate(R.string.nutrition, R.string.nutrition_empty)
        navigate(R.string.history, R.string.history_subtitle)
        waitForText(text(R.string.history_empty_title))
        navigate(R.string.progress, R.string.progress_subtitle)
        waitForTag("progress_exercise_picker")
        navigate(R.string.workout, R.string.ready_to_train)
        navigate(R.string.home, R.string.home_subtitle)
        openSettings()
        backToHome()
        openRoutines()
        waitForText(text(R.string.routines_empty_title))
        backToHome()

        compose.onAllNodesWithText(text(R.string.home_start_workout)).onFirst().performScrollTo().performClick()
        waitForText(text(R.string.workout_name))
        addExercise("Bench Press")
        awaitRepository {
            repository.observeWorkouts().first().single().exercises.single().exercise.name == "Bench Press"
        }
        navigate(R.string.history, R.string.history_subtitle)
        navigate(R.string.workout, R.string.workout_name)
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(hasText("Bench Press", substring = false))
        compose.onNodeWithText("Bench Press", substring = false).assertIsDisplayed()
        assertSame(originalActivity, compose.activity)
    }

    @Test
    fun restoringOverEditedDataClearsSavedScreensAndRestoredRoutineAndPickerWorkImmediately() {
        val originalActivity = compose.activity
        val original = runBlocking {
            val exerciseId = repository.saveExercise(Exercise(name = RESTORED_EXERCISE, primaryMuscleGroup = MuscleGroup.CHEST))
            val workoutId = repository.startWorkout(RESTORED_WORKOUT)
            repository.addExercise(workoutId, exerciseId)
            val set = repository.observeWorkouts().first().single { it.workout.id == workoutId }.exercises.single().sets.single()
            repository.updateSet(set.copy(weight = 72.5, reps = 9, completed = true))
            repository.updateWorkout(workoutId, RESTORED_WORKOUT, "Keep these restored notes")
            repository.finishWorkout(workoutId)
            val routineId = repository.saveRoutine(name = RESTORED_ROUTINE)
            repository.addRoutineExercise(routineId, exerciseId)
            app.backups.snapshot()
        }
        val exercise = original.exercises.single { it.name == RESTORED_EXERCISE }
        val workout = original.workouts.single()
        val routine = original.routines.single()
        runBlocking {
            repository.saveExercise(exercise.copy(name = LOCAL_EXERCISE))
            repository.updateWorkout(workout.id, LOCAL_WORKOUT, "Local notes to replace")
            repository.updateSet(original.sets.single().copy(weight = 25.0, reps = 3))
            repository.saveRoutine(routine.id, LOCAL_ROUTINE)
        }
        openExerciseLibrary()
        compose.onNode(hasSetTextAction()).performTextReplacement("Local")
        waitForText(LOCAL_EXERCISE)
        backToHome()
        navigate(R.string.history, R.string.history_subtitle)
        compose.onNodeWithTag("history_name_search").performTextReplacement("Local")
        waitForText(LOCAL_WORKOUT)
        navigate(R.string.progress, R.string.progress_subtitle)
        waitForTag("progress_exercise_picker")
        navigate(R.string.workout, R.string.ready_to_train)
        navigate(R.string.home, R.string.home_subtitle)
        restoreThroughSettings(original)
        assertSame(originalActivity, compose.activity)
        assertSnapshot(original)

        openExerciseLibrary()
        compose.onNode(hasSetTextAction()).assert(emptyEditableText())
        compose.onNode(hasSetTextAction()).performTextReplacement(RESTORED_EXERCISE)
        waitForText(RESTORED_EXERCISE)
        compose.onNodeWithText(LOCAL_EXERCISE, substring = false).assertDoesNotExist()
        backToHome()
        navigate(R.string.nutrition, R.string.nutrition_empty)
        navigate(R.string.history, R.string.history_subtitle)
        compose.onNodeWithTag("history_name_search").assert(emptyEditableText())
        compose.onNodeWithTag("history_workout_list").performScrollToNode(hasText(RESTORED_WORKOUT, substring = false))
        compose.onNodeWithText(RESTORED_WORKOUT, substring = false).performClick()
        waitForTag("workout_detail_list")
        compose.onNodeWithText(RESTORED_WORKOUT, substring = false).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.history_subtitle))
        navigate(R.string.progress, R.string.progress_subtitle)
        waitForTag("progress_exercise_picker")
        compose.onNodeWithTag("progress_exercise_picker").assert(hasText(RESTORED_EXERCISE, substring = false))
        navigate(R.string.home, R.string.home_subtitle)
        openSettings()
        backToHome()
        openRoutines()
        waitForText(RESTORED_ROUTINE)
        // The merged card's centre overlaps its nested Start button; use Edit explicitly.
        compose.onNodeWithTag("routine_edit_${routine.id}").performScrollTo().performClick()
        waitForTag("routine_editor_list")
        compose.onNodeWithText(RESTORED_EXERCISE, substring = false).assertIsDisplayed()
        compose.onNodeWithTag("routine_editor_list")
            .performScrollToNode(hasTestTag("routine_start_${routine.id}"))
        compose.onNodeWithTag("routine_start_${routine.id}").performClick()
        waitForText(text(R.string.workout_name))
        awaitRepository {
            repository.observeWorkouts().first().any { it.workout.endTime == null && it.exercises.single().exercise.id == exercise.id }
        }
        compose.onNodeWithText(RESTORED_EXERCISE, substring = false).assertIsDisplayed()
        addExercise("Bench Press")
        awaitRepository {
            repository.observeWorkouts().first().single { it.workout.endTime == null }.exercises.map { it.exercise.name }.toSet() ==
                setOf(RESTORED_EXERCISE, "Bench Press")
        }
        navigate(R.string.history, R.string.history_subtitle)
        navigate(R.string.workout, R.string.workout_name)
        assertSame(originalActivity, compose.activity)
        assertEquals(original.workouts.single(), runBlocking {
            repository.observeWorkouts().first().single { it.workout.id == workout.id }.workout
        })
    }

    private fun restoreThroughSettings(backup: ForgeBackup) {
        openSettings()
        val document = File.createTempFile("restore-navigation-", ".json", app.cacheDir).also {
            it.writeText(BackupCodec.encode(backup), Charsets.UTF_8)
            files += it
        }
        lateinit var model: BackupViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[BackupViewModel::class.java] }
        val beforeGeneration = model.state.value.restoreGeneration
        compose.runOnIdle { model.readBackup(Uri.fromFile(document)) }
        compose.waitUntil(45_000) { !model.state.value.busy && model.state.value.pending != null }
        assertNull(model.state.value.errorMessage)
        waitForText(text(R.string.backup_restore_confirm_title))
        compose.onNodeWithText(text(R.string.backup_restore_confirm)).assertIsEnabled().performClick()
        compose.waitUntil(45_000) {
            !model.state.value.busy && model.state.value.restoreGeneration == beforeGeneration + 1
        }
        assertNull(model.state.value.pending)
        assertNull(model.state.value.errorMessage)
        waitForText(text(R.string.home_subtitle))
        compose.onNode(hasText(text(R.string.home), substring = false) and hasClickAction()).assertIsSelected()
    }

    private fun assertSnapshot(expected: ForgeBackup) {
        val actual = runBlocking { app.backups.snapshot() }
        assertEquals(expected, actual.copy(createdAt = expected.createdAt))
    }

    private fun navigate(destination: Int, marker: Int) {
        compose.onNode(hasText(text(destination), substring = false) and hasClickAction()).performClick()
        waitForText(text(marker))
        compose.onNode(hasText(text(destination), substring = false) and hasClickAction()).assertIsSelected()
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        waitForText(text(R.string.settings_subtitle))
    }

    private fun openExerciseLibrary() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text(R.string.home_exercises), substring = false))
        compose.onNodeWithText(text(R.string.home_exercises)).assertIsDisplayed().performClick()
        waitForText(text(R.string.library_subtitle))
        compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed()
    }

    private fun openRoutines() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text(R.string.home_routines), substring = false))
        compose.onNodeWithText(text(R.string.home_routines)).assertIsDisplayed().performClick()
        waitForText(text(R.string.routines_subtitle))
    }

    private fun backToHome() {
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(text(R.string.home_subtitle))
    }

    private fun addExercise(name: String) {
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText(text(R.string.add_exercise), substring = false))
        compose.onAllNodesWithText(text(R.string.add_exercise)).onFirst().performScrollTo().performClick()
        waitForText(text(R.string.library_picker_subtitle))
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        waitForText(name)
        compose.onNodeWithTag("library_exercise_results").performScrollToNode(hasText(name, substring = false))
        compose.onNode(hasText(name, substring = false) and
            hasAnyAncestor(hasTestTag("library_exercise_results"))).performClick()
        waitForTag("workout_exercise_list")
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText(text(R.string.workout_name), substring = false))
        waitForText(text(R.string.workout_name))
    }

    private fun awaitRepository(predicate: suspend () -> Boolean) {
        compose.waitUntil(45_000) { runBlocking { predicate() } }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithText(value, substring = false).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForTag(value: String) {
        compose.waitUntil(45_000) { compose.onAllNodesWithTag(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun emptyEditableText() = SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))
    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun quantityText(resource: Int, quantity: Int, vararg arguments: Any) =
        compose.activity.resources.getQuantityString(resource, quantity, *arguments)

    private companion object {
        const val RESTORED_EXERCISE = "Restore navigation movement"
        const val RESTORED_WORKOUT = "Restore navigation workout"
        const val RESTORED_ROUTINE = "Restore navigation routine"
        const val LOCAL_EXERCISE = "Local discarded movement"
        const val LOCAL_WORKOUT = "Local discarded workout"
        const val LOCAL_ROUTINE = "Local discarded routine"
    }
}

package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.Room
import com.forge.workout.R
import com.forge.workout.utils.userMessage
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.SetType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.ui.screens.routines.RoutinesScreen
import com.forge.workout.viewmodel.LibraryViewModel
import com.forge.workout.viewmodel.RoutineViewModel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider

/** Exercises the production dialogs and ViewModels against generated Room DAOs and Android SQLite. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class RoutineCustomExerciseTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var routineViewModel: RoutineViewModel
    private lateinit var libraryViewModel: LibraryViewModel
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private var visible by mutableStateOf(true)
    private var contentStarted = false
    private val startedWorkout = AtomicLong(0)

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
        runBlocking { repository.ensureSeeded() }
        routineViewModel = RoutineViewModel(repository, { userMessage(application, it) })
        libraryViewModel = LibraryViewModel(repository, { userMessage(application, it) })
    }

    @After fun tearDown() {
        if (contentStarted) {
            compose.runOnUiThread {
                visible = false
                routineViewModel.viewModelScope.cancel()
                libraryViewModel.viewModelScope.cancel()
            }
            compose.waitForIdle()
        }
        database.close()
    }

    @Test fun createsValidatedExerciseInNewRoutine() {
        showRoutines()
        compose.onNodeWithTag("routine_create").performClick()
        compose.onNodeWithTag("routine_name").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Controlled intervals")) }
        compose.onNodeWithTag("routine_save").performClick()
        awaitDatabase { repository.observeRoutines().first().any { it.routine.name == "Controlled intervals" } }
        val routineId = runBlocking { repository.observeRoutines().first().single().routine.id }
        openFilteredPicker("no matching movement")
        compose.onNodeWithTag("routine_create_exercise").performClick()

        clickText(R.string.library_save)
        compose.onNodeWithText(text(R.string.library_name_required)).assertIsDisplayed()
        input(R.string.library_name, "  bench PRESS  ")
        clickText(R.string.library_save)
        compose.onNodeWithText(text(R.string.library_name_duplicate)).assertIsDisplayed()
        assertEquals(81, runBlocking { database.workoutDao().exercises().size })
        assertTrue(routine(routineId).exercises.isEmpty())

        input(R.string.library_name, "  Timed grip hold  ")
        choose(R.string.library_primary_muscle, R.string.forearms)
        choose(R.string.library_secondary_muscle, R.string.back_muscle)
        choose(R.string.library_type, R.string.duration_type)
        input(R.string.library_notes, "  Keep shoulders relaxed.  ")
        clickText(R.string.library_save)
        val exercise = awaitExercise("Timed grip hold")

        awaitTag("routine_created_exercise_${exercise.id}")
        assertPickerFilters("no matching movement")
        compose.onNodeWithTag("routine_created_exercise_${exercise.id}").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.routine_picker_just_created)).assertIsDisplayed()
        // It is outside both the selected chest group and the search query, but remains selectable.
        assertFalse(exercise.name.contains("no matching movement", ignoreCase = true))
        assertEquals(MuscleGroup.FOREARMS, exercise.primaryMuscleGroup)
        assertEquals(MuscleGroup.BACK, exercise.secondaryMuscleGroup)
        assertEquals(ExerciseType.DURATION, exercise.exerciseType)
        assertEquals("Keep shoulders relaxed.", exercise.notes)
        assertTrue(exercise.isCustom)
        assertFalse(exercise.archived)
        compose.onNodeWithTag("routine_created_exercise_${exercise.id}").performClick()
        awaitDatabase { database.workoutDao().routineDetail(routineId)?.exercises?.size == 1 }
        compose.onNodeWithTag("routine_exercise_search").assertDoesNotExist()
        assertEquals(exercise, routine(routineId).exercises.single().exercise)

        val workout = startRoutine(routineId)
        assertEquals("Controlled intervals", workout.workout.name)
        assertEquals(listOf(exercise.id), workout.sortedExercises.map { it.exercise.id })
        assertEquals(ExerciseType.DURATION, workout.sortedExercises.single().exercise.exerciseType)
        assertBlankWorkingSet(workout, exercise.id)
        assertEquals(routineId, routine(routineId).routine.id)
    }

    @Test fun preservesExistingRoutineAndFiltersWhenCreatingExercise() {
        val original = runBlocking {
            val exercises = database.workoutDao().exercises()
            val id = repository.saveRoutine(name = "Mixed circuit")
            repository.addRoutineExercise(id, exercises.single { it.name == "Bench Press" }.id)
            repository.addRoutineExercise(id, exercises.single { it.name == "Pull Ups" }.id)
            requireNotNull(database.workoutDao().routineDetail(id))
        }
        val routineId = original.routine.id
        showRoutines()
        awaitTag("routine_card_$routineId")
        compose.onNodeWithTag("routine_edit_$routineId").performClick()
        openFilteredPicker("unmatched circuit")
        compose.onNodeWithTag("routine_create_exercise").performClick()
        input(R.string.library_name, "Canceled interval")
        clickText(R.string.library_cancel)
        assertPickerFilters("unmatched circuit")
        assertEquals(81, runBlocking { database.workoutDao().exercises().size })
        assertEquals(original, routine(routineId))

        compose.onNodeWithTag("routine_create_exercise").performClick()
        input(R.string.library_name, "Outdoor carry distance")
        choose(R.string.library_primary_muscle, R.string.forearms)
        choose(R.string.library_secondary_muscle, R.string.core)
        choose(R.string.library_type, R.string.distance_type)
        input(R.string.library_notes, "Use a measured route.")
        clickText(R.string.library_save)
        val exercise = awaitExercise("Outdoor carry distance")
        awaitTag("routine_created_exercise_${exercise.id}")
        assertPickerFilters("unmatched circuit")
        compose.onNodeWithTag("routine_created_exercise_${exercise.id}").assertIsDisplayed().performClick()
        awaitDatabase { database.workoutDao().routineDetail(routineId)?.exercises?.size == 3 }

        val updated = routine(routineId)
        assertEquals(original.routine, updated.routine)
        assertEquals(original.sortedExercises, updated.sortedExercises.take(2))
        assertEquals(original.sortedExercises.map { it.exercise.id } + exercise.id,
            updated.sortedExercises.map { it.exercise.id })
        assertEquals(listOf(0, 1, 2), updated.sortedExercises.map { it.entry.orderIndex })
        assertEquals(MuscleGroup.FOREARMS, exercise.primaryMuscleGroup)
        assertEquals(MuscleGroup.CORE, exercise.secondaryMuscleGroup)
        assertEquals(ExerciseType.DISTANCE, exercise.exerciseType)
        assertEquals("Use a measured route.", exercise.notes)

        val workout = startRoutine(routineId)
        assertEquals("Mixed circuit", workout.workout.name)
        assertEquals(updated.sortedExercises.map { it.exercise.id }, workout.sortedExercises.map { it.exercise.id })
        assertEquals(updated.sortedExercises.map { it.exercise.exerciseType },
            workout.sortedExercises.map { it.exercise.exerciseType })
        assertEquals(exercise, workout.sortedExercises.last().exercise)
        assertBlankWorkingSet(workout, exercise.id)
        assertEquals(updated, routine(routineId))
        assertNull(routineViewModel.error.value)
        assertNull(libraryViewModel.error.value)
    }

    @Test fun savingDuringRecreationResolvesOnResume() {
        val routineId = runBlocking { repository.saveRoutine(name = "Restored intervals") }
        val saving = PausedExerciseSave(repository)
        libraryViewModel.viewModelScope.cancel()
        libraryViewModel = LibraryViewModel(saving, { userMessage(application, it) })
        val restoration = StateRestorationTester(compose)
        val owner = RoutineLifecycleOwner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        showRoutines(restoration, owner)
        awaitTag("routine_card_$routineId")
        compose.onNodeWithTag("routine_edit_$routineId").performClick()
        openFilteredPicker("rotation filter")
        compose.onNodeWithTag("routine_create_exercise").performClick()
        input(R.string.library_name, "Restored grip interval")
        choose(R.string.library_primary_muscle, R.string.forearms)
        choose(R.string.library_secondary_muscle, R.string.core)
        choose(R.string.library_type, R.string.duration_type)
        input(R.string.library_notes, "Keep the restored draft.")
        clickText(R.string.library_save)
        compose.waitUntil(5_000) { saving.entered.isCompleted }
        assertTrue(libraryViewModel.busy.value)
        assertEquals(81, runBlocking { database.workoutDao().exercises().size })

        // Destroy the original composition while its database operation is suspended.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(text(R.string.library_name)).assertTextContains("Restored grip interval")
        saving.allowCommit.complete(Unit)
        val exercise = awaitExercise("Restored grip interval")
        compose.waitUntil(5_000) { !libraryViewModel.busy.value }
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
        val result = requireNotNull(libraryViewModel.exerciseSaveResult.value)
        assertEquals(exercise, result.savedExercise)
        assertNull(result.error)
        compose.onNodeWithTag("routine_created_exercise_${exercise.id}").assertDoesNotExist()
        assertTrue(routine(routineId).exercises.isEmpty())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        awaitTag("routine_created_exercise_${exercise.id}")
        assertPickerFilters("rotation filter")
        assertNull(libraryViewModel.exerciseSaveResult.value)
        assertEquals(MuscleGroup.FOREARMS, exercise.primaryMuscleGroup)
        assertEquals(MuscleGroup.CORE, exercise.secondaryMuscleGroup)
        assertEquals(ExerciseType.DURATION, exercise.exerciseType)
        assertEquals("Keep the restored draft.", exercise.notes)
        assertEquals(1, runBlocking { database.workoutDao().exercises().count { it.name == exercise.name } })
        compose.onNodeWithTag("routine_created_exercise_${exercise.id}").assertIsDisplayed().performClick()
        awaitDatabase { database.workoutDao().routineDetail(routineId)?.exercises?.size == 1 }
        assertEquals(exercise, routine(routineId).exercises.single().exercise)
        val workout = startRoutine(routineId)
        assertEquals("Restored intervals", workout.workout.name)
        assertEquals(exercise, workout.sortedExercises.single().exercise)
        assertBlankWorkingSet(workout, exercise.id)
    }

    @Test fun interruptedProcessRecreationLeavesEditorUsable() {
        val routineId = runBlocking { repository.saveRoutine(name = "Interrupted intervals") }
        val saving = PausedExerciseSave(repository)
        libraryViewModel.viewModelScope.cancel()
        libraryViewModel = LibraryViewModel(saving, { userMessage(application, it) })
        val restoration = StateRestorationTester(compose)
        val owner = RoutineLifecycleOwner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        showRoutines(restoration, owner)
        awaitTag("routine_card_$routineId")
        compose.onNodeWithTag("routine_edit_$routineId").performClick()
        openFilteredPicker("process filter")
        compose.onNodeWithTag("routine_create_exercise").performClick()
        input(R.string.library_name, "Interrupted hold")
        clickText(R.string.library_save)
        compose.waitUntil(5_000) { saving.entered.isCompleted }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            libraryViewModel.viewModelScope.cancel()
            libraryViewModel = LibraryViewModel(repository, { userMessage(application, it) })
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.library_name)).assertTextContains("Interrupted hold")
        compose.onNodeWithText(text(R.string.library_operation_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.library_save)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.library_cancel)).assertIsEnabled().performClick()
        assertPickerFilters("process filter")
        assertTrue(routine(routineId).exercises.isEmpty())
        assertEquals(81, runBlocking { database.workoutDao().exercises().size })

        compose.onNodeWithTag("routine_create_exercise").performClick()
        input(R.string.library_name, "Recovered hold")
        clickText(R.string.library_save)
        val exercise = awaitExercise("Recovered hold")
        awaitTag("routine_created_exercise_${exercise.id}")
        compose.onNodeWithTag("routine_created_exercise_${exercise.id}").performClick()
        awaitDatabase { database.workoutDao().routineDetail(routineId)?.exercises?.size == 1 }
        assertEquals(exercise, startRoutine(routineId).sortedExercises.single().exercise)
    }

    private fun showRoutines(restoration: StateRestorationTester? = null, owner: LifecycleOwner? = null) {
        val routines = repository.observeRoutines()
        val exercises = repository.observeExercises()
        contentStarted = true
        val screen: @Composable () -> Unit = {
            if (visible) {
                val liveRoutines by routines.collectAsState(initial = emptyList())
                val liveExercises by exercises.collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                MaterialTheme {
                    RoutinesScreen(liveRoutines, liveExercises, routineViewModel, libraryViewModel,
                        onStart = { id -> scope.launch {
                            startedWorkout.set(repository.startWorkout("Fallback", routineId = id))
                        } }, onBack = {})
                }
            }
        }
        val content: @Composable () -> Unit = {
            if (owner == null) screen()
            else CompositionLocalProvider(LocalLifecycleOwner provides owner) { screen() }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        compose.waitForIdle()
    }

    private fun openFilteredPicker(query: String) {
        awaitTag("routine_editor_list")
        compose.onNodeWithTag("routine_editor_list").performScrollToNode(hasTestTag("routine_add_exercise"))
        compose.onNodeWithTag("routine_add_exercise").performClick()
        compose.onNodeWithTag("routine_exercise_search").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(query)) }
        choose(R.string.library_primary_muscle, R.string.chest, scroll = false)
        compose.onNodeWithText(text(R.string.library_no_results_title)).assertIsDisplayed()
    }

    private fun assertPickerFilters(query: String) {
        compose.onNodeWithTag("routine_exercise_search").assertTextEquals(query)
        compose.onNodeWithText(text(R.string.library_primary_muscle)).assertTextContains(text(R.string.chest))
    }

    private fun choose(label: Int, option: Int, scroll: Boolean = true) {
        val field = compose.onNodeWithText(text(label))
        if (scroll) field.performScrollTo()
        field.performClick()
        compose.onAllNodesWithText(text(option)).onLast().performScrollTo().performClick()
    }

    private fun input(label: Int, value: String) {
        // Exercise the production field action; keyboard ergonomics are a separate device check.
        compose.onNode(hasSetTextAction() and hasText(text(label)))
            .performScrollTo().performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
    }

    private fun clickText(resource: Int) { compose.onNodeWithText(text(resource)).performClick() }
    private fun text(resource: Int): String = application.getString(resource)

    private fun awaitTag(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitDatabase(predicate: suspend () -> Boolean) {
        compose.waitUntil(5_000) { runBlocking { predicate() } }
        compose.waitForIdle()
    }

    private fun awaitExercise(name: String): Exercise {
        awaitDatabase { database.workoutDao().exercises().any { it.name == name } }
        return runBlocking { database.workoutDao().exercises().single { it.name == name } }
    }

    private fun routine(id: Long): RoutineDetail = runBlocking {
        requireNotNull(database.workoutDao().routineDetail(id))
    }

    private fun startRoutine(id: Long): WorkoutDetail {
        compose.onNodeWithTag("routine_editor_list").performScrollToNode(hasTestTag("routine_start_$id"))
        compose.onNodeWithTag("routine_start_$id").performClick()
        compose.waitUntil(5_000) { startedWorkout.get() != 0L }
        return runBlocking { requireNotNull(database.workoutDao().workoutDetail(startedWorkout.get())) }
    }

    private fun assertBlankWorkingSet(workout: WorkoutDetail, exerciseId: Long) {
        val entry = workout.exercises.single { it.exercise.id == exerciseId }
        val set = entry.sets.single()
        assertEquals(1, set.setNumber)
        assertEquals(entry.entry.id, set.workoutExerciseId)
        assertEquals(0.0, set.weight, 0.000001)
        assertEquals(0, set.reps)
        assertEquals(0, set.duration)
        assertEquals(0.0, set.distance, 0.000001)
        assertFalse(set.completed)
        assertEquals(SetType.WORKING, set.setType)
    }

    private class PausedExerciseSave(private val delegate: WorkoutRepository) : WorkoutRepository by delegate {
        val entered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        override suspend fun saveExercise(exercise: Exercise): Long {
            entered.complete(Unit)
            allowCommit.await()
            return delegate.saveExercise(exercise)
        }
    }

    private class RoutineLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}

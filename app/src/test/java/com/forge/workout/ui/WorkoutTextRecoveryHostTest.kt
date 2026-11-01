package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.forge.workout.R
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.ui.screens.workout.WorkoutScreen
import com.forge.workout.viewmodel.WorkoutViewModel
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Diagnostic only: exercise the actual text editors before extending F-01 recovery to them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WorkoutTextRecoveryHostTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var store: ViewModelStore
    private lateinit var writer: WorkoutViewModel
    private var savedState = SavedStateHandle()
    private lateinit var detail: WorkoutDetail
    private var visible by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private val application get() = RuntimeEnvironment.getApplication() as Application

    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
        val dao = database.workoutDao()
        dao.insertExercise(Exercise(id = 1, name = "F01 text press", primaryMuscleGroup = MuscleGroup.CHEST))
        dao.insertWorkout(Workout(id = 2, name = "Original workout", startTime = 1, endTime = 2,
            notes = "Original workout note"))
        dao.insertWorkoutExercise(WorkoutExercise(id = 3, workoutId = 2, exerciseId = 1, orderIndex = 0,
            notes = "Original exercise note"))
        dao.insertSet(WorkoutSet(id = 4, workoutExerciseId = 3, setNumber = 1, weight = 60.0, reps = 8, completed = true))
        detail = requireNotNull(dao.workoutDetail(2))
        val failing = object : WorkoutRepository by repository {
            override suspend fun updateWorkout(id: Long, name: String, notes: String) {
                throw IOException("F01 injected workout text save failure")
            }
            override suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String) {
                throw IOException("F01 injected exercise notes save failure")
            }
        }
        replaceWriter(failing)
    }

    @After fun closeData() {
        compose.runOnIdle { visible = false; store.clear() }
        compose.waitForIdle()
        database.close()
    }

    @Test fun `recovered workout name cannot be displayed as accepted while Save permits the old name`() {
        verifyText(TextField.NAME, "Recovered workout")
    }

    @Test fun `recovered workout notes cannot be displayed as accepted while Save permits old notes`() {
        verifyText(TextField.WORKOUT_NOTES, "Recovered workout note")
    }

    @Test fun `recovered exercise notes cannot be displayed as accepted while Save permits old notes`() {
        verifyText(TextField.EXERCISE_NOTES, "Recovered exercise note")
    }

    private fun verifyText(field: TextField, desired: String) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (visible) WorkoutScreen(detail, listOf(detail), UserPreferences(restSeconds = 0), writer,
                        editing = true, onStart = {}, onRoutines = {}, onAddExercise = {}, onFinish = {},
                        onDiscard = {}, onBack = {}, onRestPreset = {})
                }
            }
        }
        val selector = hasText(application.getString(field.label)) and hasSetTextAction()
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(selector)
        compose.onNode(selector).performTextReplacement(desired)
        compose.waitUntil(15_000) { compose.runOnIdle { writer.errorNotice.value != null } }
        assertFalse("Original writer must block on the injected text failure", flushWriter())
        assertEquals(field.original, readStoredText(field))
        val saved = compose.runOnIdle { registry.performSave() }
        val pendingState = compose.runOnIdle { savedState.keys().associateWith { savedState.get<Any?>(it) } }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            store.clear()
            savedState = SavedStateHandle(pendingState)
            replaceWriter(repository)
            registry = SaveableStateRegistry(saved) { true }
            visible = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(selector)
        val displayed = compose.onNode(selector).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("The production editor restored the accepted draft", desired, displayed)
        val permitted = flushWriter()
        val stored = readStoredText(field)
        println("F01 text evidence: field=$field; display=$displayed; stored=$stored; freshFlush=$permitted; error=${writer.errorNotice.value}")
        assertTrue("Restored $field must be persisted or keep Save/Finish blocked. Display=$displayed, stored=$stored, flush=$permitted",
            !permitted || stored == displayed)
    }

    private fun readStoredText(field: TextField): String = runBlocking {
        when (field) {
            TextField.NAME -> requireNotNull(repository.workout(2)).name
            TextField.WORKOUT_NOTES -> requireNotNull(repository.workout(2)).notes
            TextField.EXERCISE_NOTES -> requireNotNull(database.workoutDao().workoutExercise(3)).notes
        }
    }

    private fun replaceWriter(source: WorkoutRepository) {
        store = ViewModelStore()
        writer = WorkoutViewModel(source, { it.message.orEmpty() }, savedState = savedState)
        store.put("workout", writer)
    }

    private fun flushWriter(): Boolean {
        var result: Result<Boolean>? = null
        compose.runOnIdle { writer.viewModelScope.launch { result = runCatching { writer.flush() } } }
        compose.waitUntil(15_000) { compose.runOnIdle { result != null } }
        return compose.runOnIdle { requireNotNull(result).getOrThrow() }
    }

    private enum class TextField(val label: Int, val original: String) {
        NAME(R.string.workout_name, "Original workout"),
        WORKOUT_NOTES(R.string.workout_notes, "Original workout note"),
        EXERCISE_NOTES(R.string.exercise_notes, "Original exercise note"),
    }
}

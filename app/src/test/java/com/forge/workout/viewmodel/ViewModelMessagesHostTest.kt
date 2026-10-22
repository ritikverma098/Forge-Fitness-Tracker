package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.R
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.utils.userMessage
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ViewModelMessagesHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var preferences: PreferencesRepository
    private lateinit var store: ViewModelStore
    private val application get() = RuntimeEnvironment.getApplication() as Application

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
        preferences = PreferencesRepository(application, "message-contract-${UUID.randomUUID()}")
        store = ViewModelStore()
    }

    @After fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `default workout name is resolved for each action and mapped validation can recover`() = runTest(dispatcher) {
        var suppliedName = "Before action"
        var nameReads = 0
        val formatted = mutableListOf<Throwable>()
        val model = keep("app", AppViewModel(repository, preferences,
            errorFormatter = { formatted += it; userMessage(application, it) },
            newWorkoutName = { nameReads++; suppliedName }))
        assertEquals(0, nameReads)

        model.start()
        suppliedName = "Current name when the action runs"
        val first = model.events.first() as AppEvent.OpenWorkout
        assertEquals(1, nameReads)
        assertEquals(suppliedName, database.workoutDao().workout(first.id)!!.name)
        model.deleteWorkout(first.id)
        assertEquals(AppEvent.GoHome, model.events.first())

        suppliedName = "Second session"
        model.start()
        val second = model.events.first() as AppEvent.OpenWorkout
        assertEquals(2, nameReads)
        assertEquals("Second session", database.workoutDao().workout(second.id)!!.name)
        assertTrue(formatted.isEmpty())
        model.deleteWorkout(second.id)
        assertEquals(AppEvent.GoHome, model.events.first())

        suppliedName = " "
        model.start()
        assertEquals(AppEvent.Message(application.getString(R.string.validation_name_empty)), model.events.first())
        assertEquals(3, nameReads)
        assertEquals("Name cannot be empty.", formatted.single().message)
        assertTrue(database.workoutDao().backupWorkouts().isEmpty())

        suppliedName = "Recovered session"
        model.start()
        val recovered = model.events.first() as AppEvent.OpenWorkout
        assertEquals(4, nameReads)
        assertEquals("Recovered session", database.workoutDao().workout(recovered.id)!!.name)
        assertEquals(1, formatted.size)
    }

    @Test fun `all mutation ViewModels forward original failures to the supplied formatter and recover`() = runTest(dispatcher) {
        val workoutId = repository.startWorkout("Original session")
        val failures = FailOnceRepository(repository)
        val formatted = mutableListOf<Throwable>()
        val expectedMessage = "Injected: ${application.getString(R.string.error_generic)}"
        val formatter: (Throwable) -> String = { formatted += it; "Injected: ${userMessage(application, it)}" }

        val workout = keep("workout", WorkoutViewModel(failures, formatter))
        workout.rename(workoutId, "Recovered workout", "Updated notes")
        assertFalse(workout.flush())
        assertEquals(expectedMessage, workout.error.value)
        assertSame(failures.workoutFailure, formatted.last())
        assertEquals("Original session", database.workoutDao().workout(workoutId)!!.name)
        workout.clearError()
        workout.rename(workoutId, "Recovered workout", "Updated notes")
        assertTrue(workout.flush())
        assertNull(workout.error.value)
        assertEquals("Updated notes", database.workoutDao().workout(workoutId)!!.notes)

        val library = keep("library", LibraryViewModel(failures, formatter))
        val exercise = Exercise(name = "Injected exercise", primaryMuscleGroup = MuscleGroup.CORE)
        var exerciseId: Long? = null
        library.saveExercise(exercise, requestId = "failed-request") { exerciseId = it }
        library.busy.first { !it }
        assertNull(exerciseId)
        assertEquals(expectedMessage, library.error.value)
        assertEquals(ExerciseSaveResult("failed-request", error = expectedMessage), library.exerciseSaveResult.value)
        assertSame(failures.libraryFailure, formatted.last())
        assertTrue(database.workoutDao().exercises().isEmpty())
        library.clearError()
        library.acknowledgeExerciseSave("failed-request")
        library.saveExercise(exercise, requestId = "retry-request") { exerciseId = it }
        library.busy.first { !it }
        val savedExercise = database.workoutDao().exercise(requireNotNull(exerciseId))!!
        assertEquals(exercise.copy(id = savedExercise.id), savedExercise)
        assertEquals(savedExercise, library.exerciseSaveResult.value!!.savedExercise)
        assertNull(library.error.value)

        val routine = keep("routine", RoutineViewModel(failures, formatter))
        var routineId: Long? = null
        routine.saveRoutine(null, "Recovered routine") { routineId = it }
        routine.busy.first { !it }
        assertNull(routineId)
        assertEquals(expectedMessage, routine.error.value)
        assertSame(failures.routineFailure, formatted.last())
        assertTrue(database.workoutDao().backupRoutines().isEmpty())
        routine.clearError()
        routine.saveRoutine(null, "Recovered routine") { routineId = it }
        routine.busy.first { !it }
        assertEquals("Recovered routine", database.workoutDao().routine(requireNotNull(routineId))!!.name)
        assertNull(routine.error.value)

        val app = keep("app", AppViewModel(failures, preferences, formatter, { "Default session" }))
        app.start()
        assertEquals(AppEvent.Message(expectedMessage), app.events.first())
        assertSame(failures.appFailure, formatted.last())
        app.start()
        assertEquals(AppEvent.OpenWorkout(workoutId), app.events.first())
        assertEquals("Recovered workout", database.workoutDao().workout(workoutId)!!.name)
        assertEquals(setOf(failures.workoutFailure, failures.libraryFailure, failures.routineFailure, failures.appFailure),
            formatted.toSet())
    }

    private fun <T : ViewModel> keep(key: String, model: T): T = model.also { store.put(key, it) }

    /** Only injects failures; every successful operation uses the real Room repository. */
    private class FailOnceRepository(private val delegate: WorkoutRepository) : WorkoutRepository by delegate {
        val workoutFailure = IOException("Rename storage failure")
        val libraryFailure = IOException("Exercise storage failure")
        val routineFailure = IOException("Routine storage failure")
        val appFailure = IOException("Start storage failure")
        private var failWorkout = true
        private var failLibrary = true
        private var failRoutine = true
        private var failApp = true

        override suspend fun updateWorkout(id: Long, name: String, notes: String) {
            if (failWorkout) { failWorkout = false; throw workoutFailure }
            delegate.updateWorkout(id, name, notes)
        }

        override suspend fun saveExercise(exercise: Exercise): Long {
            if (failLibrary) { failLibrary = false; throw libraryFailure }
            return delegate.saveExercise(exercise)
        }

        override suspend fun saveRoutine(id: Long?, name: String): Long {
            if (failRoutine) { failRoutine = false; throw routineFailure }
            return delegate.saveRoutine(id, name)
        }

        override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long {
            if (failApp) { failApp = false; throw appFailure }
            return delegate.startWorkout(name, routineId, repeatWorkoutId)
        }
    }
}

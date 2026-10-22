package com.forge.workout.viewmodel

import android.app.Application
import android.net.Uri
import com.forge.workout.R
import com.forge.workout.utils.userMessage
import androidx.room.Room
import com.forge.workout.backup.BackupCodec
import com.forge.workout.backup.BackupDocuments
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.RoomWorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupRuntimeHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var preferences: PreferencesRepository
    private lateinit var backups: RoomBackupRepository
    private lateinit var application: Application

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
        preferences = PreferencesRepository(application, "backup-runtime-${UUID.randomUUID()}")
        backups = RoomBackupRepository(database) { preferences.preferences.first() }
    }

    @After fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `fresh seeded installation restores into the same subscribed app and immediately logs a new workout`() = runTest(dispatcher) {
        val app = appViewModel()
        val observed = mutableListOf<AppUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { app.state.collect { observed += it } }
        val fresh = app.state.first { it.ready && it.exercises.isNotEmpty() }
        assertTrue(fresh.workouts.isEmpty())
        assertTrue(fresh.routines.isEmpty())
        assertEquals(UserPreferences(), fresh.preferences)

        val imported = fixture("Imported")
        val backup = restoreThroughViewModel(app, imported)
        val restored = app.state.first { matches(it, imported) }
        assertEquals(1, backup.state.value.restoreGeneration)
        assertImportedState(restored, imported)
        assertTrue(observed.any { it.workouts.isEmpty() && it.exercises.size > imported.exercises.size })
        assertTrue(observed.any { matches(it, imported) })

        app.start()
        val opened = app.events.first { it is AppEvent.OpenWorkout } as AppEvent.OpenWorkout
        assertTrue(opened.id > imported.workouts.maxOf { it.id })
        repository.addExercise(opened.id, 1)
        val logged = app.state.first { state -> state.activeWorkout?.let { active ->
            active.workout.id == opened.id && active.exercises.any { it.exercise.id == 1L && it.sets.size == 1 }
        } == true }
        val set = logged.activeWorkout!!.exercises.single().sets.single()
        assertEquals(72.5, set.weight, 0.000001)
        assertEquals(8, set.reps)
        assertEquals(SetType.WORKING, set.setType)
        assertFalse(set.completed)
        repository.updateSet(set.copy(weight = 75.0, reps = 9, completed = true))
        repository.addSet(set.workoutExerciseId)
        val next = app.state.first { state -> state.activeWorkout?.exercises?.singleOrNull()?.sets?.size == 2 }
            .activeWorkout!!.exercises.single().sortedSets.last()
        assertEquals(75.0, next.weight, 0.000001)
        assertEquals(9, next.reps)
        assertFalse(next.completed)
        assertEquals(imported.preferences, preferences.preferences.first())
    }

    @Test fun `existing app receives replacement rows with reused IDs and resumes the imported active workout`() = runTest(dispatcher) {
        val original = fixture("Old", active = true)
        val old = original.copy(
            sets = original.sets.map { it.copy(weight = 60.0, reps = 4, setType = SetType.WORKING) },
            preferences = UserPreferences(ThemeMode.LIGHT, WeightUnit.KG, 30, true),
        )
        backups.restore(old) { preferences.replace(old.preferences) }
        val app = appViewModel()
        val observed = mutableListOf<AppUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { app.state.collect { observed += it } }
        app.state.first { it.ready && matches(it, old) }
        val imported = fixture("Replacement", active = true)
        val backup = restoreThroughViewModel(app, imported)
        val restored = app.state.first { matches(it, imported) }
        assertEquals(1, backup.state.value.restoreGeneration)
        assertImportedState(restored, imported)
        assertTrue(observed.any { matches(it, old) })
        assertTrue(observed.any { matches(it, imported) })
        assertEquals(21L, restored.activeWorkout!!.workout.id)
        assertEquals("Replacement active", restored.activeWorkout!!.workout.name)

        app.start()
        val opened = app.events.first { it is AppEvent.OpenWorkout } as AppEvent.OpenWorkout
        assertEquals(21L, opened.id)
        repository.addExercise(opened.id, 2)
        val entry = app.state.first { state -> state.activeWorkout?.exercises?.any { it.exercise.id == 2L } == true }
            .activeWorkout!!.exercises.single()
        assertEquals("Replacement duration", entry.exercise.name)
        repository.updateSet(entry.sets.single().copy(duration = 60, completed = true, setType = SetType.FAILURE))
        app.finish(opened.id)
        assertEquals(AppEvent.OpenSummary(21), app.events.first { it is AppEvent.OpenSummary })
        val finished = app.state.first { state -> state.activeWorkout == null &&
            state.workouts.any { it.workout.id == 21L && it.exercises.any { exercise -> exercise.sets.any { it.completed } } } }
        assertEquals(imported.preferences, finished.preferences)
        assertEquals(60, finished.workouts.single { it.workout.id == 21L }.exercises.single().sets.single().duration)
        assertTrue(repository.observeExerciseUsage().first().any { it.exerciseId == 2L && it.workoutCount == 1 })
    }

    private fun appViewModel() = AppViewModel(repository, preferences,
        { userMessage(application, it) }, { application.getString(R.string.new_workout_name) })

    private suspend fun restoreThroughViewModel(app: AppViewModel, imported: ForgeBackup): BackupViewModel {
        val model = BackupViewModel(backups, preferences,
            documents = object : BackupDocuments {
                override fun read(uri: Uri) = BackupCodec.encode(imported)
                override fun write(uri: Uri, contents: String) = error("This test only restores backups")
            }, flushBeforeOperation = { app.flush(); true }, ioDispatcher = dispatcher)
        model.readBackup(Uri.parse("content://forge-test/runtime-backup.json"))
        model.state.first { !it.busy && it.pending != null }
        model.confirmRestore()
        val result = model.state.first { !it.busy }
        assertEquals(null, result.errorMessage)
        assertEquals(null, result.pending)
        assertEquals(imported.preferences, preferences.preferences.first())
        return model
    }

    private fun matches(state: AppUiState, backup: ForgeBackup): Boolean = state.ready &&
        state.exercises.sortedBy { it.id } == backup.exercises.sortedBy { it.id } &&
        state.workouts.map { it.workout }.toSet() == backup.workouts.toSet() &&
        state.routines.map { it.routine }.toSet() == backup.routines.toSet() && state.preferences == backup.preferences

    private fun assertImportedState(state: AppUiState, backup: ForgeBackup) {
        assertEquals(backup.exercises.sortedBy { it.id }, state.exercises.sortedBy { it.id })
        assertEquals(backup.workouts.sortedBy { it.id }, state.workouts.map { it.workout }.sortedBy { it.id })
        assertEquals(backup.workoutExercises, state.workouts.flatMap { it.exercises }.map { it.entry }.sortedBy { it.id })
        assertEquals(backup.sets, state.workouts.flatMap { it.exercises }.flatMap { it.sets }.sortedBy { it.id })
        assertEquals(backup.routines, state.routines.map { it.routine })
        assertEquals(backup.routineExercises, state.routines.flatMap { it.exercises }.map { it.entry })
        assertEquals(backup.preferences, state.preferences)
    }

    private fun fixture(prefix: String, active: Boolean = false) = ForgeBackup(
        createdAt = 1_000,
        exercises = listOf(
            Exercise(id = 1, name = "$prefix press", primaryMuscleGroup = MuscleGroup.CHEST),
            Exercise(id = 2, name = "$prefix duration", primaryMuscleGroup = MuscleGroup.CORE, exerciseType = ExerciseType.DURATION),
        ),
        workouts = listOf(Workout(id = 20, name = "$prefix history", startTime = 1_000, endTime = 61_000,
            notes = "$prefix workout notes", muscleGroups = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS))) +
            if (active) listOf(Workout(id = 21, name = "$prefix active", startTime = 62_000)) else emptyList(),
        workoutExercises = listOf(WorkoutExercise(id = 30, workoutId = 20, exerciseId = 1, orderIndex = 0, notes = "$prefix exercise notes")),
        sets = listOf(WorkoutSet(id = 40, workoutExerciseId = 30, setNumber = 1, weight = 72.5, reps = 8,
            completed = true, setType = SetType.DROP_SET)),
        routines = listOf(Routine(id = 50, name = "$prefix routine", createdAt = 1_000)),
        routineExercises = listOf(RoutineExercise(id = 60, routineId = 50, exerciseId = 2, orderIndex = 0)),
        metadata = listOf(AppMetadata("default_exercises_v1", "seeded")),
        preferences = UserPreferences(ThemeMode.DARK, WeightUnit.LB, 180, false),
    )
}

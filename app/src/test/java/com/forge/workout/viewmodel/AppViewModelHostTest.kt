package com.forge.workout.viewmodel

import android.app.Application
import com.forge.workout.R
import com.forge.workout.utils.userMessage
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class AppViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var preferences: PreferencesRepository
    private lateinit var repository: HeldStartRepository
    private lateinit var model: AppViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Application = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, ForgeDatabase::class.java).build()
        preferences = PreferencesRepository(context, "app-drain-${UUID.randomUUID()}")
        repository = HeldStartRepository(RoomWorkoutRepository(database))
        model = AppViewModel(repository, preferences, { userMessage(context, it) }, { context.getString(R.string.new_workout_name) })
    }

    @After fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `flush waits for held start then every queued preference write`() = runTest(dispatcher) {
        model.start()
        runCurrent()
        repository.started.await()
        model.setTheme(ThemeMode.DARK)
        model.setUnit(WeightUnit.LB)
        model.setRest(180)
        model.setConfirmation(false)
        runCurrent()
        val barrier = async { model.flush() }
        runCurrent()

        assertFalse(barrier.isCompleted)
        assertEquals(UserPreferences(), preferences.preferences.first())
        assertTrue(database.workoutDao().backupWorkouts().isEmpty())
        repository.release.complete(Unit)
        barrier.await()

        assertEquals(UserPreferences(ThemeMode.DARK, WeightUnit.LB, 180, false), preferences.preferences.first())
        assertEquals(1, database.workoutDao().backupWorkouts().size)
        assertEquals(AppEvent.OpenWorkout(1), model.events.first())
        model.flush()
    }

    @Test fun `flush preserves queued workout action order and normal navigation events`() = runTest(dispatcher) {
        model.start()
        runCurrent()
        repository.started.await()
        model.deleteWorkout(1)
        runCurrent()
        val barrier = async { model.flush() }
        runCurrent()

        assertFalse(barrier.isCompleted)
        repository.release.complete(Unit)
        barrier.await()

        assertTrue(database.workoutDao().backupWorkouts().isEmpty())
        assertEquals(AppEvent.OpenWorkout(1), model.events.first())
        assertEquals(AppEvent.GoHome, model.events.first())
        model.flush()
    }

    private class HeldStartRepository(private val delegate: WorkoutRepository) : WorkoutRepository by delegate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long {
            started.complete(Unit)
            release.await()
            return delegate.startWorkout(name, routineId, repeatWorkoutId)
        }
    }
}

package com.forge.workout.rest

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RestTimer
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.viewmodel.WorkoutViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Controlled independent writers around the real persisted adjustment/expiry boundary. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestTimerAdjustmentRaceHostTest {
    @Test fun `expiry winning before adjustment cannot recreate a timer or deliver a second alert`() = withFixture {
        beginAdjustment()
        now = DEADLINE
        coordinator.onAlarm(id, DEADLINE)
        assertNull(repository.workout(id)!!.restEndTime)
        assertEquals(1, alerts.delivered)

        finishAdjustment()
        assertNull("A stale +15 write must not restart the consumed rest", repository.workout(id)!!.restEndTime)
        now = DEADLINE + 15_000
        coordinator.onAlarm(id, now)
        coordinator.onAlarm(id, DEADLINE)
        assertEquals(1, alerts.delivered)
    }

    @Test fun `adjustment winning postpones expiry and exactly one alert uses the new deadline`() = withFixture {
        beginAdjustment()
        finishAdjustment()
        val extended = DEADLINE + 15_000
        assertEquals(extended, repository.workout(id)!!.restEndTime)

        now = DEADLINE
        coordinator.onAlarm(id, DEADLINE)
        assertEquals(extended, repository.workout(id)!!.restEndTime)
        assertEquals(0, alerts.delivered)
        now = extended
        coordinator.onAlarm(id, extended)
        coordinator.onAlarm(id, extended)
        assertNull(repository.workout(id)!!.restEndTime)
        assertEquals(1, alerts.delivered)
    }

    @Test fun `skip winning before adjustment keeps the timer cancelled`() = withFixture {
        beginAdjustment()
        repository.setRestEndTime(id, null)
        finishAdjustment()
        assertNull("An adjustment cannot undo a concurrent Skip", repository.workout(id)!!.restEndTime)
        now = DEADLINE + 15_000
        coordinator.onAlarm(id, DEADLINE)
        coordinator.onAlarm(id, now)
        assertEquals(0, alerts.delivered)
    }

    @Test fun `finish winning before adjustment leaves a completed workout without a timer or save failure`() = withFixture {
        beginAdjustment()
        repository.finishWorkout(id)
        finishAdjustment()
        assertTrue(repository.workout(id)!!.endTime != null)
        assertNull(repository.workout(id)!!.restEndTime)
        now = DEADLINE + 15_000
        coordinator.onAlarm(id, DEADLINE)
        coordinator.onAlarm(id, now)
        assertEquals(0, alerts.delivered)
    }

    @Test fun `conditional adjustment rejects missing replaced expired skipped and finished deadlines`() = withFixture {
        val extended = DEADLINE + 15_000
        assertFalse(repository.adjustRestEndTime(id + 1, DEADLINE, extended, now))
        assertFalse(repository.adjustRestEndTime(id, DEADLINE - 1, extended, now))
        assertFalse(repository.adjustRestEndTime(id, DEADLINE, extended, DEADLINE))
        assertFalse(repository.adjustRestEndTime(id, DEADLINE, extended, DEADLINE + 1))
        assertEquals(DEADLINE, repository.workout(id)!!.restEndTime)
        assertTrue(repository.adjustRestEndTime(id, DEADLINE, extended, now))
        assertFalse(repository.adjustRestEndTime(id, DEADLINE, extended + 15_000, now))
        assertEquals(extended, repository.workout(id)!!.restEndTime)
        repository.setRestEndTime(id, null)
        assertFalse(repository.adjustRestEndTime(id, extended, extended + 15_000, now))
        val dao = database.workoutDao()
        // Imported historical rows can retain a deadline; active-workout eligibility is independent.
        dao.updateWorkout(repository.workout(id)!!.copy(endTime = now, restEndTime = DEADLINE))
        assertFalse(repository.adjustRestEndTime(id, DEADLINE, extended, now))
        assertEquals(DEADLINE, repository.workout(id)!!.restEndTime)
    }

    @Test fun `real persisted minus fifteen clamps to now and cannot be revived by a following tap`() = withFixture {
        writer.adjustRest(id, -15)
        withTimeout(10_000) { gated.entered.await() }
        finishAdjustment()
        assertEquals(now, repository.workout(id)!!.restEndTime)
        writer.adjustRest(id, 15)
        assertTrue(writer.flush())
        assertEquals(now, repository.workout(id)!!.restEndTime)
        coordinator.onAlarm(id, now)
        coordinator.onAlarm(id, now)
        assertNull(repository.workout(id)!!.restEndTime)
        assertEquals(1, alerts.delivered)
    }

    private fun withFixture(action: suspend Fixture.() -> Unit) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        val repository = RoomWorkoutRepository(database)
        val id = repository.startWorkout("Adjustment race")
        val exercise = repository.saveExercise(Exercise(name = "Race press", primaryMuscleGroup = MuscleGroup.CHEST))
        repository.addExercise(id, exercise)
        val set = database.workoutDao().workoutDetail(id)!!.exercises.single().sets.single()
        repository.updateSet(set.copy(weight = 60.0, reps = 8, completed = true))
        repository.setRestEndTime(id, DEADLINE)
        val fixture = Fixture(database, repository, id)
        try { fixture.action() }
        finally {
            fixture.gated.release.complete(Unit)
            fixture.writer.viewModelScope.cancel()
            fixture.scope.cancel()
            database.close()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(val database: ForgeDatabase, val repository: RoomWorkoutRepository, val id: Long) {
        var now = 10_000L
        val gated = GatedAdjustment(repository)
        val writer = WorkoutViewModel(gated, { it.message.orEmpty() }, clock = { now })
        val alerts = CountingAlerts()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = RestTimerCoordinator(repository, alerts, scope, clock = { now })

        suspend fun beginAdjustment() {
            writer.adjustRest(id, 15)
            withTimeout(10_000) { gated.entered.await() }
        }

        suspend fun finishAdjustment() {
            gated.release.complete(Unit)
            assertTrue("A superseded adjustment is a successful no-op, not an unresolved save", writer.flush())
        }
    }

    private class GatedAdjustment(private val delegate: WorkoutRepository) : WorkoutRepository by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun adjustRestEndTime(workoutId: Long, expectedDeadline: Long, adjustedDeadline: Long, now: Long): Boolean {
            entered.complete(Unit)
            release.await()
            return delegate.adjustRestEndTime(workoutId, expectedDeadline, adjustedDeadline, now)
        }
    }

    private class CountingAlerts : RestAlerts {
        var delivered = 0
        override fun schedule(timer: RestTimer) = Unit
        override fun cancel() = Unit
        override fun foregroundFeedback() { delivered++ }
        override fun backgroundFeedback(timer: RestTimer) { delivered++ }
    }

    private companion object { const val DEADLINE = 11_000L }
}

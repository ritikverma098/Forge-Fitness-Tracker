package com.forge.workout.rest

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RestTimer
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutSet
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestTimerCoordinatorTest {
    @Test fun `foreground expiry gives exactly one feedback and duplicate receivers do nothing`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        assertTrue(fixture.alerts.scheduled.isNotEmpty())
        assertTrue(fixture.alerts.scheduled.all { it == timer() })
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(0, fixture.alerts.foregroundCount)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
        assertEquals(1, fixture.repository.consumedCount)
        assertNull(fixture.repository.timer.value)
        fixture.coordinator.onAlarm(1, DEADLINE)
        fixture.coordinator.onAlarm(1, DEADLINE)
        fixture.coordinator.refresh()
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(1, fixture.repository.consumedCount)
    }

    @Test fun `background and locked screen alarms use one background alert instead of foreground feedback`() = runTest {
        for (locked in listOf(false, true)) {
            val fixture = fixture()
            fixture.usable = !locked
            if (locked) fixture.coordinator.setForeground(true) else fixture.coordinator.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(0, fixture.alerts.foregroundCount)
            assertEquals(0, fixture.alerts.backgroundAttempts)
            fixture.coordinator.onAlarm(1, DEADLINE)
            fixture.coordinator.onAlarm(1, DEADLINE)
            assertEquals(1, fixture.alerts.backgroundAttempts)
            assertEquals(listOf(timer()), fixture.alerts.delivered)
            assertEquals(1, fixture.repository.consumedCount)
        }
    }

    @Test fun `plus fifteen reschedules and old receiver cannot finish the extended timer`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        advanceTimeBy(4_000)
        val extended = timer(DEADLINE + 15_000)
        fixture.repository.timer.value = extended
        runCurrent()
        assertEquals(extended, fixture.alerts.scheduled.last())
        advanceTimeBy(1_000)
        runCurrent()
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(extended, fixture.repository.timer.value)
        assertEquals(0, fixture.alerts.foregroundCount)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(1, fixture.repository.consumedCount)
    }

    @Test fun `minus fifteen reschedules and old future receiver cannot consume the shorter timer`() = runTest {
        val old = timer(BASE + 30_000)
        val fixture = fixture(old)
        fixture.coordinator.setForeground(true)
        runCurrent()
        val shorter = old.copy(deadline = old.deadline - 15_000)
        fixture.repository.timer.value = shorter
        runCurrent()
        assertEquals(shorter, fixture.alerts.scheduled.last())
        fixture.coordinator.onAlarm(1, old.deadline)
        assertEquals(shorter, fixture.repository.timer.value)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
        fixture.coordinator.onAlarm(1, old.deadline)
        assertEquals(1, fixture.repository.consumedCount)
    }

    @Test fun `skip cancels pending foreground expiry and background alarm without feedback`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        val cancellations = fixture.alerts.cancelCount
        fixture.repository.timer.value = null
        runCurrent()
        assertTrue(fixture.alerts.cancelCount > cancellations)
        advanceTimeBy(10_000)
        runCurrent()
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(0, fixture.repository.consumedCount)
        assertEquals(0, fixture.alerts.foregroundCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
    }

    @Test fun `resuming after expiry clears old deadline silently and old receiver cannot alert again`() = runTest {
        val fixture = fixture()
        fixture.coordinator.start()
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(timer(), fixture.repository.timer.value)
        fixture.coordinator.setForeground(true)
        runCurrent()
        assertNull(fixture.repository.timer.value)
        assertEquals(1, fixture.repository.consumedCount)
        assertEquals(0, fixture.alerts.foregroundCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(1, fixture.repository.consumedCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
    }

    @Test fun `denied notification permission still consumes expiry and prevents duplicate delivery`() = runTest {
        val fixture = fixture()
        fixture.alerts.permissionGranted = false
        fixture.coordinator.start()
        runCurrent()
        advanceTimeBy(5_000)
        fixture.coordinator.onAlarm(1, DEADLINE)
        runCurrent()
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(1, fixture.alerts.backgroundAttempts)
        assertTrue(fixture.alerts.delivered.isEmpty())
        assertEquals(1, fixture.repository.consumedCount)
        assertNull(fixture.repository.timer.value)
    }

    @Test fun `cold receiver startup observer preserves due timer until onAlarm claims it`() = runTest {
        val due = timer(BASE - 1)
        val fixture = fixture(due)
        fixture.coordinator.start()
        runCurrent()
        assertEquals(due, fixture.repository.timer.value)
        assertEquals(0, fixture.repository.consumedCount)
        fixture.coordinator.onAlarm(due.workoutId, due.deadline)
        runCurrent()
        assertEquals(1, fixture.alerts.backgroundAttempts)
        assertEquals(listOf(due), fixture.alerts.delivered)
        assertEquals(1, fixture.repository.consumedCount)
        assertNull(fixture.repository.timer.value)
    }

    @Test fun `rotation pause and resume before expiry retain absolute deadline and one completion`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        advanceTimeBy(2_000)
        fixture.coordinator.setForeground(false)
        advanceTimeBy(1_000)
        fixture.coordinator.setForeground(true)
        runCurrent()
        assertEquals(timer(), fixture.repository.timer.value)
        assertTrue(fixture.alerts.scheduled.all { it.deadline == DEADLINE })
        assertEquals(0, fixture.alerts.foregroundCount)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(1, fixture.repository.consumedCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
    }

    @Test fun `restore pause suppresses old receivers and future restored timer is scheduled on resume`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        fixture.coordinator.pauseForRestore()
        val cancelled = fixture.alerts.cancelCount
        val schedules = fixture.alerts.scheduled.size
        advanceTimeBy(6_000)
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(0, fixture.repository.consumedCount)
        val imported = timer(BASE + 20_000).copy(workoutName = "Imported")
        fixture.repository.timer.value = imported
        runCurrent()
        fixture.coordinator.onAlarm(1, imported.deadline)
        assertEquals(schedules, fixture.alerts.scheduled.size)
        assertEquals(cancelled, fixture.alerts.cancelCount)
        fixture.coordinator.resumeAfterRestore()
        runCurrent()
        assertEquals(imported, fixture.alerts.scheduled.last())
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(imported, fixture.repository.timer.value)
        advanceTimeBy(14_000)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(1, fixture.repository.consumedCount)
    }

    @Test fun `restore of an already expired deadline is silently cleared even with reused ID and deadline`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        fixture.coordinator.pauseForRestore()
        advanceTimeBy(6_000)
        fixture.repository.timer.value = timer().copy(workoutName = "Restored same deadline")
        runCurrent()
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(0, fixture.repository.consumedCount)
        fixture.coordinator.resumeAfterRestore()
        runCurrent()
        assertEquals(1, fixture.repository.consumedCount)
        assertNull(fixture.repository.timer.value)
        assertEquals(0, fixture.alerts.foregroundCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(1, fixture.repository.consumedCount)
    }

    @Test fun `minus fifteen ending a live foreground timer now gives immediate single feedback`() = runTest {
        val fixture = fixture(timer(BASE + 15_000))
        fixture.coordinator.setForeground(true)
        runCurrent()
        fixture.repository.timer.value = timer(BASE)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(1, fixture.repository.consumedCount)
        assertNull(fixture.repository.timer.value)
        fixture.coordinator.onAlarm(1, BASE)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, fixture.alerts.foregroundCount)
    }

    @Test fun `early alarm reschedules without consuming or showing feedback`() = runTest {
        val fixture = fixture()
        fixture.coordinator.start()
        runCurrent()
        val schedules = fixture.alerts.scheduled.size
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(schedules + 1, fixture.alerts.scheduled.size)
        assertEquals(timer(), fixture.repository.timer.value)
        assertEquals(0, fixture.repository.consumedCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
    }

    @Test fun `late foreground delivery after scheduling delay is consumed without an old vibration`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        fixture.wallOffset = 4_000
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, fixture.repository.consumedCount)
        assertEquals(0, fixture.alerts.foregroundCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
        assertNull(fixture.repository.timer.value)
    }

    @Test fun `foreground expiry still works when alarm scheduling and cancellation fail`() = runTest {
        val fixture = fixture()
        fixture.tolerateErrors = true
        fixture.alerts.failSchedule = true
        fixture.alerts.failCancel = true
        fixture.coordinator.setForeground(true)
        runCurrent()
        assertTrue(fixture.errors.any { it.message == "Alarm scheduling failed" })
        assertTrue(fixture.alerts.scheduled.isEmpty())
        assertEquals(timer(), fixture.repository.timer.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(fixture.repository.timer.value)
        assertEquals(1, fixture.repository.consumedCount)
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(0, fixture.alerts.backgroundAttempts)
        assertTrue(fixture.errors.any { it.message == "Alarm cancellation failed" })
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(1, fixture.alerts.foregroundCount)
    }

    @Test fun `restore remains balanced through scheduler failures and fresh future timers continue completing`() = runTest {
        val fixture = fixture()
        fixture.coordinator.setForeground(true)
        runCurrent()
        fixture.tolerateErrors = true
        fixture.alerts.failCancel = true
        fixture.coordinator.pauseForRestore()
        advanceTimeBy(6_000)
        fixture.coordinator.onAlarm(1, DEADLINE)
        assertEquals(0, fixture.repository.consumedCount)
        val imported = timer(BASE + 20_000).copy(workoutName = "Imported")
        fixture.repository.timer.value = imported
        runCurrent()
        fixture.alerts.failSchedule = true
        fixture.coordinator.resumeAfterRestore()
        runCurrent()
        assertEquals(imported, fixture.repository.timer.value)
        assertTrue(fixture.errors.any { it.message == "Alarm cancellation failed" })
        assertTrue(fixture.errors.any { it.message == "Alarm scheduling failed" })
        advanceTimeBy(14_000)
        runCurrent()
        assertNull(fixture.repository.timer.value)
        assertEquals(1, fixture.alerts.foregroundCount)
        assertEquals(1, fixture.repository.consumedCount)

        fixture.alerts.failCancel = false
        fixture.alerts.failSchedule = false
        val fresh = timer(BASE + 25_000).copy(workoutName = "Next rest")
        fixture.repository.timer.value = fresh
        runCurrent()
        assertEquals(fresh, fixture.alerts.scheduled.last())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, fixture.alerts.foregroundCount)
        assertEquals(2, fixture.repository.consumedCount)
        assertNull(fixture.repository.timer.value)
    }

    private fun TestScope.fixture(initial: RestTimer? = timer()): Fixture {
        val fixture = Fixture(FakeRepository(initial), FakeAlerts())
        val startedAt = testScheduler.currentTime
        fixture.coordinator = RestTimerCoordinator(fixture.repository, fixture.alerts, backgroundScope,
            clock = { BASE + testScheduler.currentTime - startedAt + fixture.wallOffset }, screenUsable = { fixture.usable },
            onError = {
                fixture.errors += it
                if (!fixture.tolerateErrors) throw AssertionError("Unexpected coordinator error", it)
            })
        return fixture
    }

    private class Fixture(val repository: FakeRepository, val alerts: FakeAlerts) {
        lateinit var coordinator: RestTimerCoordinator
        var usable = true
        var wallOffset = 0L
        var tolerateErrors = false
        val errors = mutableListOf<Exception>()
    }

    private class FakeAlerts : RestAlerts {
        val scheduled = mutableListOf<RestTimer>()
        val delivered = mutableListOf<RestTimer>()
        var cancelCount = 0
        var foregroundCount = 0
        var backgroundAttempts = 0
        var permissionGranted = true
        var failSchedule = false
        var failCancel = false
        override fun schedule(timer: RestTimer) {
            if (failSchedule) throw IllegalStateException("Alarm scheduling failed")
            scheduled += timer
        }
        override fun cancel() {
            cancelCount++
            if (failCancel) throw IllegalStateException("Alarm cancellation failed")
        }
        override fun foregroundFeedback() { foregroundCount++ }
        override fun backgroundFeedback(timer: RestTimer) {
            backgroundAttempts++
            if (permissionGranted) delivered += timer
        }
    }

    private class FakeRepository(initial: RestTimer?) : WorkoutRepository {
        val timer = MutableStateFlow(initial)
        var consumedCount = 0
        override fun observeRestTimer(): Flow<RestTimer?> = timer
        override suspend fun restTimer(): RestTimer? = timer.value
        override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean {
            val current = timer.value ?: return false
            if (current.workoutId != workoutId || current.deadline != expectedDeadline || current.deadline > now) return false
            timer.value = null
            consumedCount++
            return true
        }
        override fun observeExerciseUsage(): Flow<List<ExerciseUsage>> = error("Unused")
        override fun observeExercises(): Flow<List<Exercise>> = error("Unused")
        override fun observeWorkouts(): Flow<List<WorkoutDetail>> = error("Unused")
        override fun observeRoutines(): Flow<List<RoutineDetail>> = error("Unused")
        override suspend fun workout(id: Long): com.forge.workout.model.Workout? = error("Unused")
        override suspend fun workoutSet(id: Long): WorkoutSet? = error("Unused")
        override suspend fun workoutExerciseSets(workoutExerciseId: Long): List<WorkoutSet> = error("Unused")
        override suspend fun ensureSeeded() = error("Unused")
        override suspend fun saveExercise(exercise: Exercise): Long = error("Unused")
        override suspend fun deleteExercise(id: Long) = error("Unused")
        override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long = error("Unused")
        override suspend fun updateWorkout(id: Long, name: String, notes: String) = error("Unused")
        override suspend fun updateMuscleGroups(id: Long, groups: Set<MuscleGroup>) = error("Unused")
        override suspend fun finishWorkout(id: Long) = error("Unused")
        override suspend fun deleteWorkout(id: Long) = error("Unused")
        override suspend fun addExercise(workoutId: Long, exerciseId: Long) = error("Unused")
        override suspend fun removeWorkoutExercise(id: Long) = error("Unused")
        override suspend fun moveWorkoutExercise(id: Long, delta: Int) = error("Unused")
        override suspend fun addSet(workoutExerciseId: Long) = error("Unused")
        override suspend fun updateSet(set: WorkoutSet) = error("Unused")
        override suspend fun deleteSet(id: Long) = error("Unused")
        override suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String) = error("Unused")
        override suspend fun setRestEndTime(workoutId: Long, endTime: Long?) = error("Unused")
        override suspend fun saveRoutine(id: Long?, name: String): Long = error("Unused")
        override suspend fun addRoutineExercise(routineId: Long, exerciseId: Long) = error("Unused")
        override suspend fun removeRoutineExercise(id: Long) = error("Unused")
        override suspend fun moveRoutineExercise(id: Long, delta: Int) = error("Unused")
        override suspend fun deleteRoutine(id: Long) = error("Unused")
    }

    private companion object {
        const val BASE = 1_000L
        const val DEADLINE = BASE + 5_000
        fun timer(deadline: Long = DEADLINE) = RestTimer(1, deadline, "Strength")
    }
}

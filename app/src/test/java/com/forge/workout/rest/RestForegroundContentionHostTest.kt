package com.forge.workout.rest

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.RestTimer
import com.forge.workout.model.Workout
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Isolates the real foreground-expiry coroutine and SQLite claim from initial observer emissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestForegroundContentionHostTest {
    @Test fun `backgrounding during a blocked foreground claim does not lose completion feedback`() =
        verifyForegroundClaim(resumeBeforeRelease = false)

    @Test fun `returning after expiry does not turn a blocked old claim into delayed foreground feedback`() =
        verifyForegroundClaim(resumeBeforeRelease = true)

    private fun verifyForegroundClaim(resumeBeforeRelease: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Application
        val database = Room.databaseBuilder(context, ForgeDatabase::class.java, "fg.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dao = database.workoutDao()
        val now = AtomicLong(10_000)
        val deadline = 11_000L
        val id = dao.insertWorkout(Workout(name = "Foreground contention probe", startTime = 1, restEndTime = deadline))
        val consumeEntered = CompletableDeferred<Unit>()
        val real = RoomWorkoutRepository(database)
        val repository = object : WorkoutRepository by real {
            // Only setForeground's real refresh schedules this test's real expiry coroutine.
            override fun observeRestTimer(): Flow<RestTimer?> = emptyFlow()
            override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean {
                consumeEntered.complete(Unit)
                return real.consumeRestTimer(workoutId, expectedDeadline, now)
            }
        }
        val alerts = CountingAlerts()
        val coordinator = RestTimerCoordinator(repository, alerts, scope, clock = now::get)
        val writerLocked = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        var writer: kotlinx.coroutines.Job? = null
        try {
            coordinator.setForeground(true)
            withTimeout(3_000) { alerts.scheduled.await() }
            writer = scope.launch {
                database.withTransaction {
                    dao.putMetadata(AppMetadata("r01_foreground_writer", "held"))
                    writerLocked.complete(Unit)
                    releaseWriter.await()
                }
            }
            withTimeout(3_000) { writerLocked.await() }
            now.set(deadline)
            withTimeout(3_000) { consumeEntered.await() }
            coordinator.setForeground(false)
            if (resumeBeforeRelease) {
                now.set(deadline + 5_000)
                coordinator.setForeground(true)
            }
            releaseWriter.complete(Unit)
            withTimeout(3_000) { writer.join() }
            // Await the same owned delivery (or its already-consumed no-op) without a timing guess.
            withTimeout(3_000) { coordinator.onAlarm(id, deadline) }
            val stored = real.workout(id)!!.restEndTime
            println("R01_FOREGROUND resumeBeforeRelease=$resumeBeforeRelease consumeEntered=${consumeEntered.isCompleted} deadlineAfterRelease=$stored " +
                "foregroundFeedback=${alerts.foreground.get()} backgroundFeedback=${alerts.background.get()}")
            assertNull("The expiry claim must consume the original deadline", stored)
            val expectedFeedback = if (resumeBeforeRelease) 0 else 1
            assertEquals("Feedback must match the lifecycle when the claim completes", expectedFeedback, alerts.background.get())
            assertEquals(0, alerts.foreground.get())
            coordinator.onAlarm(id, deadline)
            assertEquals("A later Android alarm must not duplicate completion", expectedFeedback, alerts.background.get())
        } finally {
            releaseWriter.complete(Unit)
            writer?.join()
            coordinator.pauseForRestore()
            scope.cancel()
            database.close()
            context.deleteDatabase("fg.db")
        }
    }

    private class CountingAlerts : RestAlerts {
        val scheduled = CompletableDeferred<Unit>()
        val foreground = AtomicInteger()
        val background = AtomicInteger()
        override fun schedule(timer: RestTimer) { scheduled.complete(Unit) }
        override fun cancel() = Unit
        override fun foregroundFeedback() { foreground.incrementAndGet() }
        override fun backgroundFeedback(timer: RestTimer) { background.incrementAndGet() }
    }
}

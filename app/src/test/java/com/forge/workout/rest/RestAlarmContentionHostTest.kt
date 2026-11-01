package com.forge.workout.rest

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.forge.workout.ForgeApplication
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Workout
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.shadows.ShadowLog

/** Uses the receiver's real eight-second budget; this is not a simulated Android alarm-delivery test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RestReceiverProbeApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RestAlarmContentionHostTest {
    @Test fun `receiver recovers completion after a database writer outlasts its broadcast budget`() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as RestReceiverProbeApplication
        val database = app.probeDatabase
        val dao = database.workoutDao()
        val notifications = app.getSystemService(NotificationManager::class.java)
        val alarms = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.WAKE_LOCK)
        shadowOf(notifications).setNotificationsEnabled(true)
        val deadline = System.currentTimeMillis() - 1
        val id = dao.insertWorkout(Workout(name = "Receiver contention probe", startTime = deadline - 1_000,
            restEndTime = deadline))
        assertEquals(deadline, dao.restTimer()!!.deadline)
        val writerLocked = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = writerScope.launch {
            database.withTransaction {
                dao.putMetadata(AppMetadata("r01_probe_writer", "held"))
                writerLocked.complete(Unit)
                releaseWriter.await()
            }
        }
        val receiver = RestAlarmReceiver()
        app.registerReceiver(receiver, IntentFilter(AndroidRestAlerts.ACTION_REST_ALARM), Context.RECEIVER_NOT_EXPORTED)
        try {
            withTimeout(3_000) { writerLocked.await() }
            val startedAt = System.nanoTime()
            app.sendBroadcast(Intent(AndroidRestAlerts.ACTION_REST_ALARM).setPackage(app.packageName)
                .putExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, id)
                .putExtra(AndroidRestAlerts.EXTRA_DEADLINE, deadline))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The actual receiver must use goAsync", shadowOf(receiver).wentAsync())
            val result = requireNotNull(shadowOf(receiver).originalPendingResult)
            val finished = Shadow.extract<ShadowBroadcastPendingResult>(result).future
            withTimeout(3_000) { app.consumeEntered.await() }
            // A native SQLite call can delay cancellation until the lock is released. Keep the
            // controlled stall beyond the receiver budget, then let both cancellation variants finish.
            val heldMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            delay((9_200L - heldMillis).coerceAtLeast(0))
            val finishedWhileLocked = finished.isDone
            assertTrue("The broadcast must stop waiting before the database writer is released", finishedWhileLocked)
            assertNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
            releaseWriter.complete(Unit)
            withTimeout(3_000) { writer.join() }
            finished.get(5, TimeUnit.SECONDS)
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val timedOut = ShadowLog.getLogsForTag("ForgeRestAlarm").any { it.throwable is TimeoutCancellationException }
            assertTrue("The receiver should exhaust its actual eight-second budget", timedOut)
            assertTrue("The write must remain held through the receiver deadline", elapsedMillis >= 7_500)
            val storedAfterRelease = app.repository.workout(id)!!.restEndTime
            val scheduledAfterTimeout = shadowOf(alarms).scheduledAlarms.size
            println("R01_HOST timeoutMs=$elapsedMillis receiverFinishedWhileLocked=$finishedWhileLocked consumeEntered=${app.consumeEntered.isCompleted} " +
                "deadlineAfterRelease=$storedAfterRelease scheduledRetryCount=$scheduledAfterTimeout " +
                "notificationsAfterRelease=${shadowOf(notifications).size()}")

            // Robolectric does not deliver OS alarms automatically in this test. If recovery has
            // scheduled one, deliver it only once it is due, exactly as the native probe does.
            val delivered = mutableSetOf<android.app.PendingIntent>()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (scheduledAfterTimeout == 0) 2 else 15)
            while (System.nanoTime() < until && shadowOf(notifications).size() == 0) {
                for (alarm in shadowOf(alarms).scheduledAlarms.toList()) {
                    // Robolectric 4.17 exposes the pending intent only through this compatibility field.
                    @Suppress("DEPRECATION")
                    val operation = alarm.operation ?: continue
                    if (alarm.triggerAtMs <= System.currentTimeMillis() && delivered.add(operation)) operation.send()
                }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertNotNull("A transient writer must not abandon the only background completion alert",
                shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
            assertNull("Successful recovery must consume the original absolute deadline", app.repository.workout(id)!!.restEndTime)
            assertEquals(1, shadowOf(notifications).size())
            app.restTimers.setForeground(true)
            app.restTimers.refresh()
            assertEquals("Resuming must not produce another notification", 1, shadowOf(notifications).size())
        } finally {
            releaseWriter.complete(Unit)
            writerScope.cancel()
            withTimeout(3_000) { writer.join() }
            app.unregisterReceiver(receiver)
            app.restTimers.pauseForRestore()
            database.close()
            app.deleteDatabase(RestReceiverProbeApplication.DATABASE_NAME)
        }
    }
}

/** The native probe retains full app startup; this host probe deliberately isolates receiver delivery. */
class RestReceiverProbeApplication : ForgeApplication() {
    override fun onCreate() = Unit

    val consumeEntered = CompletableDeferred<Unit>()
    val probeDatabase: ForgeDatabase by lazy {
        Room.databaseBuilder(this, ForgeDatabase::class.java, DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    }
    override val database: ForgeDatabase get() = probeDatabase
    override val repository: WorkoutRepository by lazy {
        val real = RoomWorkoutRepository(probeDatabase)
        object : WorkoutRepository by real {
            override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean {
                consumeEntered.complete(Unit)
                return real.consumeRestTimer(workoutId, expectedDeadline, now)
            }
        }
    }

    companion object { const val DATABASE_NAME = "receiver_contention_probe.db" }
}

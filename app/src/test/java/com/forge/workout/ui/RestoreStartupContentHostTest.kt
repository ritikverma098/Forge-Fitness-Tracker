package com.forge.workout.ui

import android.app.AlarmManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import com.forge.workout.ForgeApplication
import com.forge.workout.R
import com.forge.workout.backup.BackupRepository
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.backup.RestoreStartupState
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.rest.AndroidRestAlerts
import com.forge.workout.ui.navigation.ForgeApp
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RestoreGateTestApplication::class, qualifiers = "en-rUS-w600dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RestoreStartupContentHostTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as RestoreGateTestApplication

    @After fun closeFixture() = runBlocking {
        if (app.startupState.value == RestoreStartupState.READY) {
            app.workoutEnergy.stop()
            app.restTimers.pauseForRestore()
            app.closeDatabase()
        }
    }

    @Test fun `loading and failed recovery never construct the app graph and retry opens working Home`() {
        compose.setContent { ForgeApp(app) }
        compose.onNodeWithText(app.getString(R.string.loading)).assertIsDisplayed()
        assertEquals(0, app.repositoryAccesses.get())
        assertEquals(0, app.preferenceAccesses.get())
        app.release.complete(Unit)
        compose.waitUntil(10_000) { app.startupState.value == RestoreStartupState.FAILED }
        compose.onNodeWithText(app.getString(R.string.restore_recovery_failed)).assertIsDisplayed()
        assertEquals(0, app.repositoryAccesses.get())
        assertEquals(0, app.preferenceAccesses.get())
        app.fail.set(false)
        compose.onNodeWithText(app.getString(R.string.retry_failed_changes)).performClick()
        compose.waitUntil(10_000) { app.startupState.value == RestoreStartupState.READY }
        // Recovery readiness precedes the app ViewModel's asynchronous Room/DataStore load.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(app.getString(R.string.home_start_workout)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.getString(R.string.home_start_workout)).assertExists()
        assertEquals(2, app.recoveryAttempts.get())
        assertTrue(app.repositoryAccesses.get() > 0)
        assertTrue(app.preferenceAccesses.get() > 0)
    }

    @Suppress("DEPRECATION")
    @Test fun `retry after a failed restore recovery unpauses the actual rest coordinator`() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        fun restAlarms() = alarms.scheduledAlarms.filter {
            shadowOf(it.operation).savedIntent.action == AndroidRestAlerts.ACTION_REST_ALARM
        }
        app.fail.set(false)
        app.release.complete(Unit)
        app.awaitStartup()
        app.repository.ensureSeeded()
        val id = app.repository.startWorkout("Recovery retry timer")
        val deadline = System.currentTimeMillis() + 60_000
        app.repository.setRestEndTime(id, deadline)
        app.restTimers.refresh()
        assertEquals(1, restAlarms().size)

        app.pauseForRestore()
        assertTrue(restAlarms().isEmpty())
        app.fail.set(true)
        assertTrue(runCatching { app.resumeAfterRestoreAttempt() }.exceptionOrNull() is IOException)
        assertEquals(RestoreStartupState.FAILED, app.startupState.value)
        assertTrue(restAlarms().isEmpty())

        app.fail.set(false)
        app.retryStartup()
        app.awaitStartup()
        assertEquals(RestoreStartupState.READY, app.startupState.value)
        assertEquals(1, restAlarms().size)
        assertEquals(deadline, restAlarms().single().triggerAtMs)
        assertEquals(3, app.recoveryAttempts.get())
        app.repository.setRestEndTime(id, null)
        app.restTimers.refresh()
        assertTrue(restAlarms().isEmpty())
    }
}

class RestoreGateTestApplication : ForgeApplication() {
    override fun onCreate() = Unit
    val release = CompletableDeferred<Unit>()
    val fail = AtomicBoolean(true)
    val recoveryAttempts = AtomicInteger()
    val repositoryAccesses = AtomicInteger()
    val preferenceAccesses = AtomicInteger()
    private val fixtureDatabase by lazy { Room.inMemoryDatabaseBuilder(this, ForgeDatabase::class.java).build() }
    override val database get() = fixtureDatabase
    private val fixtureRepository by lazy { RoomWorkoutRepository(fixtureDatabase) }
    override val repository: WorkoutRepository get() {
        repositoryAccesses.incrementAndGet()
        return fixtureRepository
    }
    private val fixturePreferences by lazy { PreferencesRepository(this, "startup-gate-${UUID.randomUUID()}") }
    override val preferences: PreferencesRepository get() {
        preferenceAccesses.incrementAndGet()
        return fixturePreferences
    }
    override val backups: BackupRepository = object : BackupRepository {
        override suspend fun snapshot(): ForgeBackup = error("This fixture only checks startup")
        override suspend fun restore(backup: ForgeBackup, beforeCommit: suspend () -> Unit) = error("No restore requested")
        override suspend fun recoverInterruptedRestore() {
            recoveryAttempts.incrementAndGet()
            release.await()
            if (fail.get()) throw IOException("Injected recovery failure")
        }
    }

    fun closeDatabase() { fixtureDatabase.close() }
}

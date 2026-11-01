package com.forge.workout.backup

import android.app.Application
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RestoreCrashRecoveryHostTest {
    private lateinit var application: Application
    private lateinit var database: ForgeDatabase
    private lateinit var preferences: PreferencesRepository
    private lateinit var backups: RoomBackupRepository
    private var replacePreferences: suspend (UserPreferences) -> Unit = { preferences.replace(it) }
    private val originalPreferences = UserPreferences(ThemeMode.LIGHT, WeightUnit.KG, 30, true)

    @Before fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.deleteDatabase("rc.db")
        preferences = PreferencesRepository(application, "restore-crash-${UUID.randomUUID()}")
        openDatabase()
    }

    @After fun tearDown() {
        database.close()
        application.deleteDatabase("rc.db")
    }

    @Test fun `abrupt exit after preferences replacement reopens one coherent old generation`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original) { preferences.replace(original.preferences) }
        val before = backups.snapshot().copy(createdAt = 123)
        val imported = original.copy(
            workouts = original.workouts.map { it.copy(name = "Imported ${it.id}") },
            preferences = UserPreferences(ThemeMode.DARK, WeightUnit.LB, 180, false),
        )
        var barrierReached = false
        try {
            backups.restore(imported) {
                preferences.replace(imported.preferences)
                assertEquals(imported.preferences, preferences.preferences.first())
                assertEquals(imported.workouts, database.workoutDao().backupWorkouts())
                barrierReached = true
                // Error bypasses application Exception compensation. This is a host fault
                // model, not an Android process-kill test; native coverage performs SIGKILL.
                throw AbruptExit()
            }
            fail("The injected abrupt exit must reach the transaction boundary")
        } catch (_: AbruptExit) { }
        assertEquals(true, barrierReached)
        assertNotEquals(original.preferences, preferences.preferences.first())
        database.close()
        openDatabase()
        assertEquals(before, backups.snapshot().copy(createdAt = 123))
        assertEquals(original.preferences, preferences.preferences.first())
    }

    @Test fun `committed replacement reopens the complete imported generation`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original) { preferences.replace(original.preferences) }
        val imported = original.copy(
            workouts = original.workouts.map { it.copy(name = "Committed ${it.id}") },
            preferences = UserPreferences(ThemeMode.DARK, WeightUnit.LB, 180, false),
        )
        backups.restore(imported) { preferences.replace(imported.preferences) }
        database.close()
        openDatabase()
        assertEquals(imported.copy(createdAt = 123), backups.snapshot().copy(createdAt = 123))
        assertEquals(imported.preferences, preferences.preferences.first())
        assertNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        backups.recoverInterruptedRestore()
        backups.recoverInterruptedRestore()
        assertEquals(imported.preferences, preferences.preferences.first())
    }

    @Test fun `transaction exception and cancellation roll both stores back before returning`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original)
        val before = backups.snapshot().copy(createdAt = 123)
        val imported = completeBackup()
        assertTrue(runCatching { backups.restore(imported) { throw IOException("Commit failed") } }.exceptionOrNull() is IOException)
        assertEquals(before, backups.snapshot().copy(createdAt = 123))
        assertNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))

        val entered = CompletableDeferred<Unit>()
        val operation = launch {
            backups.restore(imported) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        operation.cancelAndJoin()
        assertEquals(before, backups.snapshot().copy(createdAt = 123))
        assertNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
    }

    @Test fun `failed preference compensation retains journal until a later recovery succeeds`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original)
        var rejectRollback = false
        replacePreferences = { value ->
            if (rejectRollback && value == originalPreferences) throw IOException("Preference storage unavailable")
            preferences.replace(value)
        }
        val failure = runCatching {
            backups.restore(completeBackup()) { rejectRollback = true; throw IOException("Commit failed") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, failure!!.suppressed.size)
        assertNotNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        assertTrue(runCatching { backups.snapshot() }.exceptionOrNull() is IOException)
        assertEquals(original.workouts, database.workoutDao().backupWorkouts())
        rejectRollback = false
        backups.recoverInterruptedRestore()
        assertNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        assertEquals(original.copy(createdAt = 123), backups.snapshot().copy(createdAt = 123))
    }

    @Test fun `abrupt exit before replacement leaves the original preferences recoverable`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original)
        replacePreferences = { throw AbruptExit() }
        assertTrue(runCatching { backups.restore(completeBackup()) }.exceptionOrNull() is AbruptExit)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertNotNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        replacePreferences = { preferences.replace(it) }
        database.close()
        openDatabase()
        assertEquals(original.copy(createdAt = 123), backups.snapshot().copy(createdAt = 123))
    }

    @Test fun `journal deletion failure is recoverable after the preferences have already rolled back`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original)
        assertTrue(runCatching { backups.restore(completeBackup()) { throw AbruptExit() } }.exceptionOrNull() is AbruptExit)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER block_recovery_delete BEFORE DELETE ON app_metadata
            WHEN OLD.key = '${RestorePreferencesJournal.KEY}' BEGIN SELECT RAISE(ABORT, 'Storage failure'); END
        """.trimIndent())
        assertTrue(runCatching { backups.recoverInterruptedRestore() }.isFailure)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertNotNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_recovery_delete")
        backups.recoverInterruptedRestore()
        assertNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        assertEquals(original.copy(createdAt = 123), backups.snapshot().copy(createdAt = 123))
    }

    @Test fun `malformed local journal fails closed and is retained for recovery`() = runBlocking {
        preferences.replace(originalPreferences)
        database.workoutDao().putMetadata(AppMetadata(RestorePreferencesJournal.KEY, "{}"))
        assertTrue(runCatching { backups.recoverInterruptedRestore() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { backups.snapshot() }.exceptionOrNull() is IllegalStateException)
        assertEquals("{}", database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        assertEquals(originalPreferences, preferences.preferences.first())
    }

    @Test fun `portable backups cannot import or export internal recovery instructions`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original)
        val injected = original.copy(metadata = original.metadata + AppMetadata(RestorePreferencesJournal.KEY,
            RestorePreferencesJournal.encode(completeBackup().preferences)))
        assertTrue(runCatching { BackupCodec.encode(injected) }.exceptionOrNull() is BackupValidationException)
        assertTrue(runCatching { backups.restore(injected) }.exceptionOrNull() is BackupValidationException)
        assertNull(database.workoutDao().metadata(RestorePreferencesJournal.KEY))
        assertEquals(original.copy(createdAt = 123), backups.snapshot().copy(createdAt = 123))
        database.workoutDao().putMetadata(AppMetadata("forge_internal_restore_future", "local-only"))
        val exported = backups.snapshot()
        assertFalse(exported.metadata.any { RestorePreferencesJournal.isInternal(it.key) })
        assertEquals(original.copy(createdAt = 123), BackupCodec.decode(BackupCodec.encode(exported)).copy(createdAt = 123))
    }

    @Test fun `snapshot waits for restore commit instead of exporting mixed database and preferences`() = runBlocking {
        backups.restore(completeBackup().copy(preferences = originalPreferences))
        val imported = completeBackup()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val restore = async {
            backups.restore(imported) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val snapshot = async(start = CoroutineStart.UNDISPATCHED) { backups.snapshot() }
        assertFalse(snapshot.isCompleted)
        release.complete(Unit)
        restore.await()
        assertEquals(imported.copy(createdAt = 123), snapshot.await().copy(createdAt = 123))
    }

    @Test fun `queued restore freezes caller collections before waiting for another operation`() = runBlocking {
        val original = completeBackup().copy(preferences = originalPreferences)
        backups.restore(original)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { backups.restore(original) { entered.complete(Unit); release.await() } }
        entered.await()
        val callerWorkouts = original.workouts.map { it.copy(name = "Queued ${it.id}") }.toMutableList()
        val imported = original.copy(workouts = callerWorkouts)
        val expected = imported.copy(workouts = callerWorkouts.toList(), createdAt = 123)
        val queued = async(start = CoroutineStart.UNDISPATCHED) { backups.restore(imported) }
        assertFalse(queued.isCompleted)
        callerWorkouts.clear()
        release.complete(Unit)
        first.await()
        queued.await()
        assertEquals(expected, backups.snapshot().copy(createdAt = 123))
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(application, ForgeDatabase::class.java, "rc.db").build()
        backups = RoomBackupRepository(database, preferencesRestorer = { replacePreferences(it) }) { preferences.preferences.first() }
    }

    private class AbruptExit : Error("Simulated abrupt process exit")
}

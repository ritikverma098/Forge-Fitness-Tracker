package com.forge.workout.backup

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.preferences.UserPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkoutRestoreGenerationHostTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: ForgeDatabase
    private lateinit var backups: RoomBackupRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        backups = RoomBackupRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun `each successful restore advances the generation including identical and workout only restores`() = runBlocking<Unit> {
        assertNull(backups.workoutDraftGeneration())
        val original = fixture()
        backups.restore(original)
        val first = generation()
        assertEquals(original, snapshot())

        backups.restore(original)
        val second = generation()
        assertNotEquals(first, second)
        assertEquals(original, snapshot())

        val foods = RoomNutritionRepository(database)
        foods.upsertFood(FoodDetail(FoodEntity(id = "custom:retained", name = "Independent personal food")))
        val nutrition = snapshot().nutrition
        val workoutOnly = original.copy(nutrition = null, workouts = original.workouts.map { it.copy(name = "Restored ${it.id}") })
        backups.restore(workoutOnly)
        val third = generation()
        assertNotEquals(first, third)
        assertNotEquals(second, third)
        assertEquals(nutrition, snapshot().nutrition)
        assertEquals(workoutOnly.workouts, snapshot().workouts)
        assertForeignKeys()
    }

    @Test fun `generation and restored data survive closing and reopening the database`() = runBlocking<Unit> {
        database.close()
        val context = RuntimeEnvironment.getApplication()
        val path = File(temporary.root, "restore-generation.db").absolutePath
        database = Room.databaseBuilder(context, ForgeDatabase::class.java, path).build()
        backups = RoomBackupRepository(database)
        val original = fixture()
        backups.restore(original)
        val saved = generation()
        database.close()

        database = Room.databaseBuilder(context, ForgeDatabase::class.java, path).build()
        backups = RoomBackupRepository(database)
        assertEquals(saved, generation())
        assertEquals(original, snapshot())
        backups.restore(original)
        assertNotEquals(saved, generation())
        assertEquals(original, snapshot())
    }

    @Test fun `callback failure rolls generation and restored rows back together`() = runBlocking<Unit> {
        val original = fixture()
        backups.restore(original)
        val oldGeneration = generation()
        val before = snapshot()
        var callbackRan = false
        try {
            backups.restore(changed(original)) {
                callbackRan = true
                throw IOException("Injected restore callback failure")
            }
            throw AssertionError("Expected callback failure")
        } catch (error: IOException) {
            assertEquals("Injected restore callback failure", error.message)
        }
        assertTrue(callbackRan)
        assertEquals(oldGeneration, generation())
        assertEquals(before, snapshot())
        assertForeignKeys()
    }

    @Test fun `SQL failure leaves the committed generation and all prior data intact`() = runBlocking<Unit> {
        val original = fixture()
        backups.restore(original)
        val oldGeneration = generation()
        val before = snapshot()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_generation_restore BEFORE INSERT ON workout_sets WHEN NEW.id = 401 BEGIN SELECT RAISE(ABORT, 'Injected restore failure'); END",
        )
        var callbackRan = false
        try {
            backups.restore(changed(original)) { callbackRan = true }
            throw AssertionError("Expected SQL failure")
        } catch (_: SQLiteConstraintException) {
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_generation_restore")
        }
        assertFalse(callbackRan)
        assertEquals(oldGeneration, generation())
        assertEquals(before, snapshot())
        assertForeignKeys()
    }

    @Test fun `malformed restore cannot advance generation or run its callback`() = runBlocking<Unit> {
        val original = fixture()
        backups.restore(original)
        val oldGeneration = generation()
        val before = snapshot()
        val invalid = changed(original).copy(sets = original.sets.mapIndexed { index, set ->
            if (index == 0) set.copy(weight = -1.0) else set
        })
        var callbackRan = false
        expectInvalid { backups.restore(invalid) { callbackRan = true } }
        assertFalse(callbackRan)
        assertEquals(oldGeneration, generation())
        assertEquals(before, snapshot())
    }

    @Test fun `generation is excluded from portable exports and cannot be injected by an import`() = runBlocking<Unit> {
        val original = fixture()
        backups.restore(original)
        val token = generation()
        val internal = database.workoutDao().backupMetadata().single { it.value == token }
        assertTrue(RestorePreferencesJournal.isInternal(internal.key))
        val exported = snapshot()
        assertFalse(exported.metadata.any { RestorePreferencesJournal.isInternal(it.key) })
        val json = BackupCodec.encode(exported)
        assertFalse(json.contains(token))
        assertEquals(exported, BackupCodec.decode(json))

        val injected = exported.copy(metadata = exported.metadata + internal)
        expectInvalid { BackupCodec.encode(injected) }
        val manipulated = JSONObject(json)
        manipulated.getJSONArray("metadata").put(JSONObject().put("key", internal.key).put("value", token))
        expectInvalid { BackupCodec.decode(manipulated.toString()) }
        var callbackRan = false
        expectInvalid { backups.restore(injected) { callbackRan = true } }
        assertFalse(callbackRan)
        assertEquals(token, generation())
        assertEquals(exported, snapshot())
    }

    private fun fixture() = completeBackup().copy(preferences = UserPreferences())
    private fun changed(source: ForgeBackup) = source.copy(workouts = source.workouts.map { it.copy(name = "Replacement ${it.id}") },
        sets = source.sets.map { it.copy(reps = it.reps + 1) })
    private suspend fun generation() = requireNotNull(backups.workoutDraftGeneration()).also { assertTrue(it.isNotBlank()) }
    private suspend fun snapshot() = backups.snapshot().copy(createdAt = 10000)
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid backup rejection")
        } catch (error: BackupValidationException) {
            assertEquals(BackupIssue.INVALID_DATA, error.issue)
        }
    }
    private fun assertForeignKeys() = database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
        assertFalse(it.moveToFirst())
    }
}

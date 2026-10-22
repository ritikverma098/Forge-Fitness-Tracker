package com.forge.workout.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import com.forge.workout.R
import com.forge.workout.backup.BackupCodec
import com.forge.workout.backup.BackupDocuments
import com.forge.workout.backup.BackupRepository
import com.forge.workout.backup.ForgeBackup
import com.forge.workout.backup.NutritionBackup
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.nutrition.model.*
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class BackupViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var preferences: PreferencesRepository
    private lateinit var repository: FakeBackupRepository
    private lateinit var documents: FakeDocuments
    private val originalPreferences = UserPreferences(ThemeMode.LIGHT, WeightUnit.KG, 30, true)
    private val importedPreferences = UserPreferences(ThemeMode.DARK, WeightUnit.LB, 180, false)
    private lateinit var original: ForgeBackup
    private lateinit var imported: ForgeBackup
    private var flushCount = 0
    private var allowFlush = true
    private var restoredCallbacks = 0

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        preferences = PreferencesRepository(context, "backup-host-${UUID.randomUUID()}")
        preferences.setTheme(originalPreferences.themeMode)
        preferences.setUnit(originalPreferences.weightUnit)
        preferences.setRest(originalPreferences.restSeconds)
        preferences.setConfirmation(originalPreferences.confirmDeletion)
        original = fixture("Original", originalPreferences)
        imported = fixture("Imported", importedPreferences)
        repository = FakeBackupRepository(original)
        documents = FakeDocuments(BackupCodec.encode(imported))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `reading shows accurate preview and cancel leaves all current data unchanged`() = runTest(dispatcher) {
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)

        val preview = requireNotNull(model.state.value.pending)
        assertEquals(imported.createdAt, preview.createdAt)
        assertEquals(1, preview.workoutCount)
        assertEquals(1, preview.routineCount)
        assertEquals(1, preview.customExerciseCount)
        assertEquals(1, documents.readCount)
        assertEquals(0, flushCount)
        assertNull(model.state.value.errorMessage)
        assertUnchanged(model)

        model.cancelRestore()
        model.confirmRestore()
        runCurrent()
        assertNull(model.state.value.pending)
        assertUnchanged(model)
    }

    @Test
    fun `nutrition preview counts personal data and reading a workout-only file clears that scope without mutation`() = runTest(dispatcher) {
        val full = imported.copy(nutrition = nutritionFixture())
        documents.content = BackupCodec.encode(full)
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)
        val preview = requireNotNull(model.state.value.pending)
        assertTrue(preview.hasNutrition)
        assertEquals(1, preview.customFoodCount)
        assertEquals(1, preview.recipeCount)
        assertEquals(1, preview.diaryCount)
        assertEquals(1, preview.bodyWeightCount)
        assertEquals(1, preview.workoutCount)
        assertUnchanged(model)
        assertEquals(0, flushCount)

        documents.content = BackupCodec.encode(imported)
        model.readBackup(INPUT)
        awaitIdle(model)
        val legacy = requireNotNull(model.state.value.pending)
        assertFalse(legacy.hasNutrition)
        assertEquals(0, legacy.customFoodCount)
        assertEquals(0, legacy.recipeCount)
        assertEquals(0, legacy.diaryCount)
        assertEquals(0, legacy.bodyWeightCount)
        model.cancelRestore()
        model.confirmRestore()
        runCurrent()
        assertUnchanged(model)
    }

    @Test
    fun `malformed JSON never offers confirmation or mutates local data`() = runTest(dispatcher) {
        documents.content = "{\"format\": \"forge-workout-backup\", broken}"
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)

        assertNull(model.state.value.pending)
        assertEquals(R.string.backup_invalid, model.state.value.errorMessage)
        assertEquals(R.string.backup_invalid, model.events.first())
        model.confirmRestore()
        runCurrent()
        assertUnchanged(model)
    }

    @Test
    fun `unsupported format version never offers confirmation or mutates local data`() = runTest(dispatcher) {
        documents.content = JSONObject(documents.content).put("formatVersion", BackupCodec.FORMAT_VERSION + 1).toString()
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)

        assertNull(model.state.value.pending)
        assertEquals(R.string.backup_version_unsupported, model.state.value.errorMessage)
        assertEquals(R.string.backup_version_unsupported, model.events.first())
        model.confirmRestore()
        runCurrent()
        assertUnchanged(model)
    }

    @Test
    fun `confirmation replaces once imports preferences and notifies after commit`() = runTest(dispatcher) {
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)
        repository.restoreGate = CompletableDeferred()

        model.confirmRestore()
        model.confirmRestore()
        runCurrent()
        repository.restoreStarted.await()
        assertTrue(model.state.value.busy)
        assertEquals(1, repository.restoreCount)
        model.cancelRestore()
        model.confirmRestore()
        assertNotNull(model.state.value.pending)
        assertEquals(original, repository.stored)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertEquals(0, restoredCallbacks)

        repository.restoreGate!!.complete(Unit)
        awaitIdle(model)
        assertEquals(imported, repository.stored)
        assertEquals(importedPreferences, preferences.preferences.first())
        assertEquals(1, repository.restoreCount)
        assertEquals(1, flushCount)
        assertEquals(1, model.state.value.restoreGeneration)
        assertEquals(1, restoredCallbacks)
        assertNull(model.state.value.pending)
        assertNull(model.state.value.errorMessage)
        assertEquals(R.string.backup_restored, model.events.first())
        model.confirmRestore()
        runCurrent()
        assertEquals(1, repository.restoreCount)
    }

    @Test
    fun `failed restore preserves preview generation current rows and preferences`() = runTest(dispatcher) {
        repository.failRestoreBeforeImport = true
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)
        val preview = model.state.value.pending
        model.confirmRestore()
        awaitIdle(model)

        assertEquals(preview, model.state.value.pending)
        assertEquals(R.string.backup_restore_failed, model.state.value.errorMessage)
        assertEquals(R.string.backup_restore_failed, model.events.first())
        assertEquals(original, repository.stored)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertEquals(0, model.state.value.restoreGeneration)
        assertEquals(0, restoredCallbacks)
    }

    @Test
    fun `commit failure after preference import rolls preferences back with database`() = runTest(dispatcher) {
        repository.failRestoreAfterImport = true
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)
        model.confirmRestore()
        awaitIdle(model)

        assertNotNull(model.state.value.pending)
        assertEquals(R.string.backup_restore_failed, model.state.value.errorMessage)
        assertEquals(original, repository.stored)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertEquals(0, model.state.value.restoreGeneration)
        assertEquals(0, restoredCallbacks)
    }

    @Test
    fun `cancellation before the preference callback completes its write then rolls back the transaction`() = runTest(dispatcher) {
        assertCancelledRestore(cancelBeforeImport = true)
    }

    @Test
    fun `cancellation after preference import rolls preferences back without announcing restoration`() = runTest(dispatcher) {
        assertCancelledRestore(cancelBeforeImport = false)
    }

    @Test
    fun `failed draft flush prevents both export and confirmed restore`() = runTest(dispatcher) {
        allowFlush = false
        val model = model()
        model.writeBackup(OUTPUT)
        awaitIdle(model)
        assertEquals(0, repository.snapshotCount)
        assertEquals(0, documents.writeCount)
        assertEquals(R.string.failed_save_retry, model.state.value.errorMessage)
        assertEquals(R.string.failed_save_retry, model.events.first())

        model.readBackup(INPUT)
        awaitIdle(model)
        assertNotNull(model.state.value.pending)
        model.confirmRestore()
        awaitIdle(model)
        assertNotNull(model.state.value.pending)
        assertEquals(R.string.failed_save_retry, model.state.value.errorMessage)
        assertEquals(R.string.failed_save_retry, model.events.first())
        assertUnchanged(model)
        assertEquals(2, flushCount)
    }

    @Test
    fun `export waits for snapshot and ignores repeated or competing operations while busy`() = runTest(dispatcher) {
        repository.snapshotGate = CompletableDeferred()
        val model = model()
        model.writeBackup(OUTPUT)
        model.writeBackup(OUTPUT)
        runCurrent()
        repository.snapshotStarted.await()
        assertTrue(model.state.value.busy)
        assertEquals(1, repository.snapshotCount)
        assertEquals(0, documents.writeCount)
        model.readBackup(INPUT)
        model.writeBackup(OUTPUT)
        runCurrent()
        assertEquals(0, documents.readCount)
        assertEquals(1, repository.snapshotCount)

        repository.snapshotGate!!.complete(Unit)
        awaitIdle(model)
        assertEquals(1, documents.writeCount)
        assertEquals(OUTPUT, documents.writtenUri)
        assertEquals(original, BackupCodec.decode(requireNotNull(documents.writtenContent)))
        assertNull(model.state.value.pending)
        assertNull(model.state.value.errorMessage)
        assertEquals(R.string.backup_created, model.events.first())
        assertEquals(1, flushCount)
        assertUnchanged(model)
    }

    @Test
    fun `document read and write failures are reported without changing stored data`() = runTest(dispatcher) {
        val model = model()
        documents.failRead = true
        model.readBackup(INPUT)
        awaitIdle(model)
        assertNull(model.state.value.pending)
        assertEquals(R.string.backup_read_failed, model.state.value.errorMessage)
        assertEquals(R.string.backup_read_failed, model.events.first())
        assertUnchanged(model)

        documents.failWrite = true
        model.writeBackup(OUTPUT)
        awaitIdle(model)
        assertEquals(R.string.backup_write_failed, model.state.value.errorMessage)
        assertEquals(R.string.backup_write_failed, model.events.first())
        assertEquals(0, documents.writeCount)
        assertUnchanged(model)
    }

    @Test
    fun `timer hooks pause only after confirmation and resume after committed rows and preferences`() = runTest(dispatcher) {
        val hooks = mutableListOf<String>()
        var timersPaused = false
        val model = model(
            beforeRestore = {
                assertEquals(original, repository.stored)
                assertEquals(originalPreferences, preferences.preferences.first())
                assertEquals(0, repository.restoreCount)
                timersPaused = true
                hooks += "pause"
            },
            afterRestoreAttempt = {
                assertEquals(imported, repository.stored)
                assertEquals(importedPreferences, preferences.preferences.first())
                assertEquals(0, restoredCallbacks)
                currentCoroutineContext().ensureActive()
                delay(1)
                timersPaused = false
                hooks += "resume"
            },
        )
        model.readBackup(INPUT)
        awaitIdle(model)
        assertTrue(hooks.isEmpty())
        assertFalse(timersPaused)
        repository.restoreGate = CompletableDeferred()

        model.confirmRestore()
        model.confirmRestore()
        runCurrent()
        repository.restoreStarted.await()
        assertTrue(timersPaused)
        assertEquals(listOf("pause"), hooks)
        repository.restoreGate!!.complete(Unit)
        awaitIdle(model)

        assertEquals(listOf("pause", "resume"), hooks)
        assertFalse(timersPaused)
        assertEquals(1, model.state.value.restoreGeneration)
        assertEquals(1, restoredCallbacks)
        assertNull(model.state.value.pending)
    }

    @Test
    fun `failed transaction resumes timers after preserving or rolling back current rows and preferences`() = runTest(dispatcher) {
        for (failBeforeImport in listOf(true, false)) {
            repository = FakeBackupRepository(original).apply {
                failRestoreBeforeImport = failBeforeImport
                failRestoreAfterImport = !failBeforeImport
            }
            val hooks = mutableListOf<String>()
            var timersPaused = false
            val model = model(
                beforeRestore = { timersPaused = true; hooks += "pause" },
                afterRestoreAttempt = {
                    assertEquals(original, repository.stored)
                    assertEquals(originalPreferences, preferences.preferences.first())
                    currentCoroutineContext().ensureActive()
                    delay(1)
                    timersPaused = false
                    hooks += "resume"
                },
            )
            model.readBackup(INPUT)
            awaitIdle(model)
            val preview = model.state.value.pending
            model.confirmRestore()
            awaitIdle(model)

            assertEquals(listOf("pause", "resume"), hooks)
            assertFalse(timersPaused)
            assertEquals(preview, model.state.value.pending)
            assertEquals(R.string.backup_restore_failed, model.state.value.errorMessage)
            assertEquals(0, model.state.value.restoreGeneration)
            assertEquals(0, restoredCallbacks)
        }
    }

    @Test
    fun `cancelled pause or transaction still completes suspending timer resume without restored callback`() = runTest(dispatcher) {
        for (cancelDuringPause in listOf(true, false)) {
            repository = FakeBackupRepository(original).apply { cancelRestoreAfterImport = !cancelDuringPause }
            val hooks = mutableListOf<String>()
            var timersPaused = false
            val model = model(
                beforeRestore = {
                    timersPaused = true
                    hooks += "pause"
                    if (cancelDuringPause) {
                        currentCoroutineContext().cancel()
                        currentCoroutineContext().ensureActive()
                    }
                },
                afterRestoreAttempt = {
                    // A cancellable finally block cannot complete this suspending cleanup.
                    currentCoroutineContext().ensureActive()
                    delay(1)
                    assertEquals(original, repository.stored)
                    assertEquals(originalPreferences, preferences.preferences.first())
                    timersPaused = false
                    hooks += "resume"
                },
            )
            model.readBackup(INPUT)
            awaitIdle(model)
            val preview = requireNotNull(model.state.value.pending)
            model.confirmRestore()
            awaitIdle(model)

            assertEquals(listOf("pause", "resume"), hooks)
            assertFalse(timersPaused)
            assertEquals(if (cancelDuringPause) 0 else 1, repository.restoreCount)
            assertEquals(preview, model.state.value.pending)
            assertNull(model.state.value.errorMessage)
            assertEquals(0, model.state.value.restoreGeneration)
            assertEquals(0, restoredCallbacks)
        }
    }

    @Test
    fun `preview cancellation invalid reading export and failed flush never pause or resume timers`() = runTest(dispatcher) {
        var pauses = 0
        var resumes = 0
        var timersPaused = false
        val model = model(
            beforeRestore = { pauses++; timersPaused = true },
            afterRestoreAttempt = { resumes++; timersPaused = false },
        )
        model.readBackup(INPUT)
        awaitIdle(model)
        model.cancelRestore()
        model.confirmRestore()
        runCurrent()
        documents.content = "{invalid backup}"
        model.readBackup(INPUT)
        awaitIdle(model)
        model.confirmRestore()
        runCurrent()
        model.writeBackup(OUTPUT)
        awaitIdle(model)
        documents.content = BackupCodec.encode(imported)
        model.readBackup(INPUT)
        awaitIdle(model)
        allowFlush = false
        model.confirmRestore()
        awaitIdle(model)

        assertEquals(0, pauses)
        assertEquals(0, resumes)
        assertFalse(timersPaused)
        assertEquals(0, repository.restoreCount)
        assertEquals(original, repository.stored)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertNotNull(model.state.value.pending)
        assertEquals(R.string.failed_save_retry, model.state.value.errorMessage)
        assertEquals(0, model.state.value.restoreGeneration)
        assertEquals(0, restoredCallbacks)
    }

    private fun model(beforeRestore: suspend () -> Unit = {}, afterRestoreAttempt: suspend () -> Unit = {}) = BackupViewModel(
        backupRepository = repository,
        preferences = preferences,
        documents = documents,
        flushBeforeOperation = { flushCount++; allowFlush },
        onRestored = { restoredCallbacks++ },
        ioDispatcher = dispatcher,
        beforeRestore = beforeRestore,
        afterRestoreAttempt = afterRestoreAttempt,
    )

    private suspend fun awaitIdle(model: BackupViewModel) {
        // Await the observable end of the operation, including real DataStore I/O.
        model.state.first { !it.busy }
        assertFalse(model.state.value.busy)
    }

    private suspend fun assertUnchanged(model: BackupViewModel) {
        assertEquals(original, repository.stored)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertEquals(0, repository.restoreCount)
        assertEquals(0, model.state.value.restoreGeneration)
        assertEquals(0, restoredCallbacks)
    }

    private suspend fun assertCancelledRestore(cancelBeforeImport: Boolean) {
        repository.cancelRestoreBeforeImport = cancelBeforeImport
        repository.cancelRestoreAfterImport = !cancelBeforeImport
        var preferencesObservedBeforeRollback: UserPreferences? = null
        repository.onCancelledRestore = {
            // This runs inside the cancelled fake Room transaction, before the ViewModel rolls DataStore back.
            preferencesObservedBeforeRollback = preferences.preferences.first()
        }
        val model = model()
        model.readBackup(INPUT)
        awaitIdle(model)
        val preview = requireNotNull(model.state.value.pending)

        model.confirmRestore()
        awaitIdle(model)

        assertEquals(importedPreferences, preferencesObservedBeforeRollback)
        assertEquals(original, repository.stored)
        assertEquals(originalPreferences, preferences.preferences.first())
        assertEquals(preview, model.state.value.pending)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.errorMessage)
        assertEquals(1, repository.restoreCount)
        assertEquals(1, flushCount)
        assertEquals(0, model.state.value.restoreGeneration)
        assertEquals(0, restoredCallbacks)
    }

    private fun fixture(prefix: String, savedPreferences: UserPreferences) = ForgeBackup(
        createdAt = 1_000,
        exercises = listOf(Exercise(id = 10, name = "$prefix press", primaryMuscleGroup = MuscleGroup.CHEST)),
        workouts = listOf(Workout(id = 20, name = "$prefix session", startTime = 1_000, endTime = 61_000,
            muscleGroups = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS))),
        workoutExercises = listOf(WorkoutExercise(id = 30, workoutId = 20, exerciseId = 10, orderIndex = 0)),
        sets = listOf(WorkoutSet(id = 40, workoutExerciseId = 30, setNumber = 1, weight = 60.0, reps = 8, completed = true)),
        routines = listOf(Routine(id = 50, name = "$prefix routine", createdAt = 1_000)),
        routineExercises = listOf(RoutineExercise(id = 60, routineId = 50, exerciseId = 10, orderIndex = 0)),
        metadata = listOf(AppMetadata("default_exercises_v1", "seeded")),
        preferences = savedPreferences,
    )

    private fun nutritionFixture(): NutritionBackup {
        val custom = FoodDetail(FoodEntity(rowId = 1, id = "custom:bread", name = "Bread", gramsPerBase = 100.0),
            listOf(FoodNutrientEntity("custom:bread", NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
                FoodNutrientEntity("custom:bread", NutrientCodes.PROTEIN, "Protein", "g", 10.0),
                FoodNutrientEntity("custom:bread", NutrientCodes.CARBS, "Carbohydrates", "g", 30.0),
                FoodNutrientEntity("custom:bread", NutrientCodes.FAT, "Fat", "g", 4.0)))
        val result = RecipeFactory.validate(RecipeDraft(id = "recipe:bowl", name = "Bowl", servings = "2", cookedWeightGrams = "200",
            ingredients = listOf(RecipeIngredientDraft(snapshot = custom.snapshot(),
                choice = FoodServingChoice("GRAM", unit = FoodUnit.GRAM, baseQuantity = 1.0, suggestedQuantity = 100.0), quantity = "100"))))
        val recipeFood = requireNotNull(result.food).let { it.copy(food = it.food.copy(rowId = 2)) }
        return NutritionBackup(foods = listOf(custom, recipeFood),
            recipes = listOf(RecipeDetail(requireNotNull(result.recipe), result.ingredients.map { it.copy(id = 7) })),
            diary = listOf(DiaryEntity(id = 9, day = "2026-10-12", meal = MealType.BREAKFAST, foodId = custom.food.id,
                snapshot = custom.snapshot(), quantity = 50.0, unit = FoodUnit.GRAM, factor = .5,
                kcal = 100.0, protein = 5.0, carbs = 15.0, fat = 2.0)),
            goals = listOf(NutritionGoalEntity("2026-10-12", kcal = 2_000.0)),
            bodyWeights = listOf(BodyWeightEntity(id = 8, day = "2026-10-12", kg = 80.0)),
            favorites = listOf(FavoriteEntity(custom.food.id, custom.food.name)))
    }

    private class FakeBackupRepository(var stored: ForgeBackup) : BackupRepository {
        var snapshotCount = 0
        var restoreCount = 0
        var snapshotGate: CompletableDeferred<Unit>? = null
        var restoreGate: CompletableDeferred<Unit>? = null
        val snapshotStarted = CompletableDeferred<Unit>()
        val restoreStarted = CompletableDeferred<Unit>()
        var failRestoreBeforeImport = false
        var failRestoreAfterImport = false
        var cancelRestoreBeforeImport = false
        var cancelRestoreAfterImport = false
        var onCancelledRestore: (suspend () -> Unit)? = null

        override suspend fun snapshot(): ForgeBackup {
            snapshotCount++
            snapshotStarted.complete(Unit)
            snapshotGate?.await()
            return stored
        }

        override suspend fun restore(backup: ForgeBackup, beforeCommit: suspend () -> Unit) {
            restoreCount++
            restoreStarted.complete(Unit)
            restoreGate?.await()
            if (failRestoreBeforeImport) throw IOException("Storage unavailable")
            try {
                // Cancel only the ViewModel's transaction coroutine; the test's parent remains active.
                if (cancelRestoreBeforeImport) currentCoroutineContext().cancel()
                beforeCommit()
                if (cancelRestoreAfterImport) currentCoroutineContext().cancel()
                currentCoroutineContext().ensureActive()
                if (failRestoreAfterImport) throw IOException("Commit failed")
                stored = backup
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { onCancelledRestore?.invoke() }
                throw cancelled
            }
        }
    }

    private class FakeDocuments(var content: String) : BackupDocuments {
        var readCount = 0
        var writeCount = 0
        var writtenUri: Uri? = null
        var writtenContent: String? = null
        var failRead = false
        var failWrite = false

        override fun read(uri: Uri): String {
            readCount++
            if (failRead) throw IOException("Read permission denied")
            return content
        }

        override fun write(uri: Uri, contents: String) {
            if (failWrite) throw IOException("Write permission denied")
            writeCount++
            writtenUri = uri
            writtenContent = contents
        }
    }

    companion object {
        private val INPUT = Uri.parse("content://forge-test/backup-input.json")
        private val OUTPUT = Uri.parse("content://forge-test/backup-output.json")
    }
}

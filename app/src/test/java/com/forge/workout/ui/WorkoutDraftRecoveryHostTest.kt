package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.ui.screens.workout.SetRow
import com.forge.workout.viewmodel.WorkoutViewModel
import com.forge.workout.viewmodel.SetField
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Diagnostic only: real Room + production set editor, saved UI and a genuinely new writer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class WorkoutDraftRecoveryHostTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomWorkoutRepository
    private lateinit var store: ViewModelStore
    private lateinit var writer: WorkoutViewModel
    private var savedState = SavedStateHandle()
    private var visible by mutableStateOf(true)
    private var unit by mutableStateOf(WeightUnit.KG)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })
    private lateinit var set: WorkoutSet
    private var resetNotices = 0

    @Before fun prepare() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomWorkoutRepository(database)
        val dao = database.workoutDao()
        dao.insertExercise(Exercise(id = 1, name = "F01 press", primaryMuscleGroup = MuscleGroup.CHEST))
        dao.insertWorkout(Workout(id = 2, name = "F01 recovery", startTime = 1))
        dao.insertWorkoutExercise(WorkoutExercise(id = 3, workoutId = 2, exerciseId = 1, orderIndex = 0))
        set = WorkoutSet(id = 4, workoutExerciseId = 3, setNumber = 1, weight = 60.0, reps = 8, completed = true)
        dao.insertSet(set)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_f01_weight BEFORE UPDATE OF weight ON workout_sets
            WHEN NEW.weight != OLD.weight
            BEGIN SELECT RAISE(ABORT, 'F01 injected weight write failure'); END
        """.trimIndent())
        replaceWriter()
    }

    @After fun closeData() {
        compose.runOnIdle { visible = false; store.clear() }
        compose.waitForIdle()
        database.close()
    }

    @Test fun `fresh writer must not finish or export an old weight behind a restored kilogram draft`() {
        verifyRecovery(WeightUnit.KG, "80", WeightUnit.KG, "80", 80.0)
    }

    @Test fun `fresh writer must recover exact kilograms behind a restored pound display`() {
        verifyRecovery(WeightUnit.KG, "80", WeightUnit.LB, "176.37", 80.0)
    }

    @Test fun `fresh writer must not replace a tiny pound anchor with rounded kilogram text`() {
        verifyRecovery(WeightUnit.LB, "0.01", WeightUnit.KG, "0", WeightUnit.LB.toKg(0.01))
    }

    @Test fun `retaining the writer across UI restoration keeps failure blocking and Retry succeeds`() {
        showRow()
        enterRejectedEdit("80")
        restoreUi(WeightUnit.KG, newWriter = false)
        compose.onNodeWithTag("numeric_4_WEIGHT").assertTextEquals("80")
        assertFalse(flushWriter())
        assertNotNull(writer.errorNotice.value)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_f01_weight")
        compose.runOnIdle { writer.retryFailedChanges() }
        awaitRoom { repository.workoutSet(4)!!.weight == 80.0 }
        assertTrue(flushWriter())
        println("F01 control: retained writer blocks Finish; Retry persists 80 kg.")
    }

    @Test fun `legacy UI without a writer journal reverts to stored values and requests review`() {
        showRow()
        enterRejectedEdit("80")
        restoreUi(WeightUnit.LB, newWriter = true, recoverJournal = false)
        compose.onNodeWithTag("numeric_4_WEIGHT").assertTextEquals("132.28")
        assertTrue(resetNotices > 0)
        assertTrue(flushWriter())
        assertEquals(60.0, runBlocking { repository.workoutSet(4)!! }.weight, 0.0)
    }

    @Test fun `recovered assignments gate Finish before the edited row is composed`() {
        showRow()
        enterRejectedEdit("80")
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_f01_weight")
        restoreUi(WeightUnit.KG, newWriter = true, showEditor = false)
        assertTrue(flushWriter())
        assertEquals(80.0, runBlocking { repository.workoutSet(4)!! }.weight, 0.0)
        assertEquals(80.0, runBlocking { RoomBackupRepository(database).snapshot() }.sets.single().weight, 0.0)
    }

    @Test fun `a persistent failure after writer recovery stays blocked until Retry succeeds`() {
        showRow()
        enterRejectedEdit("80")
        restoreUi(WeightUnit.KG, newWriter = true)
        compose.onNodeWithTag("numeric_4_WEIGHT").assertTextEquals("80")
        assertFalse(flushWriter())
        assertNotNull(writer.errorNotice.value)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_f01_weight")
        compose.runOnIdle { writer.retryFailedChanges() }
        assertTrue(flushWriter())
        assertEquals(80.0, runBlocking { repository.workoutSet(4)!! }.weight, 0.0)
    }

    private fun verifyRecovery(sourceUnit: WeightUnit, typed: String, restoredUnit: WeightUnit, display: String, expectedKg: Double) {
        unit = sourceUnit
        showRow()
        enterRejectedEdit(typed)
        // Remove the injected fault before recovery: a new writer is free to persist
        // the exact saved assignment, if it can reconstruct that assignment at all.
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_f01_weight")
        restoreUi(restoredUnit, newWriter = true)
        compose.onNodeWithTag("numeric_4_WEIGHT").assertTextEquals(display)
        val permitted = flushWriter()
        val stored = runBlocking { repository.workoutSet(4)!! }.weight
        val exported = if (permitted) runBlocking { RoomBackupRepository(database).snapshot() }.sets.single().weight else null
        if (permitted) runBlocking { repository.finishWorkout(2) }
        val finished = runBlocking { repository.workout(2)!! }.endTime != null
        val finalWeight = runBlocking { repository.workoutSet(4)!! }.weight
        println("F01 evidence: display=$display ${restoredUnit.symbol}; expectedKg=$expectedKg; storedKg=$stored; freshFlush=$permitted; error=${writer.errorNotice.value}; exportedKg=$exported; finished=$finished; finishedKg=$finalWeight")
        assertTrue("Fresh writer must persist the exact restored value or keep Finish/export blocked. Stored=$stored, expected=$expectedKg, flush=$permitted", !permitted || stored == expectedKg)
        if (permitted) {
            assertEquals(expectedKg, finalWeight, 0.0)
            assertEquals(expectedKg, requireNotNull(exported), 0.0)
        }
    }

    private fun showRow() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    if (visible) {
                    val accepted by remember(writer) { writer.numericDrafts(set.id) }.collectAsState()
                    val editorSet = set.copy(weight = accepted[SetField.WEIGHT] ?: set.weight)
                    SetRow(editorSet, ExerciseType.WEIGHT_REPS, null, unit, false,
                        onValueChange = { field, value -> writer.setValue(set.id, field, value) },
                        onValidityChange = { field, valid -> writer.inputValidity(2, "set_4_${field.name}", valid) },
                        onTypeChange = {}, onCompleteChange = {}, onDelete = {},
                        editorSessionId = writer.editorSessionId, onEditorStateReset = { resetNotices++ })
                    }
                }
            }
        }
    }

    private fun enterRejectedEdit(text: String) {
        compose.onNodeWithTag("numeric_4_WEIGHT").performTextReplacement(text)
        compose.waitUntil(15_000) { compose.runOnIdle { writer.errorNotice.value != null } }
        assertFalse("Original writer must detect the injected failure", flushWriter())
        assertEquals(60.0, runBlocking { repository.workoutSet(4)!! }.weight, 0.0)
    }

    private fun restoreUi(nextUnit: WeightUnit, newWriter: Boolean, recoverJournal: Boolean = true, showEditor: Boolean = true) {
        val saved = compose.runOnIdle { registry.performSave() }
        val writerState = compose.runOnIdle { savedState.keys().associateWith { savedState.get<Any?>(it) } }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            if (newWriter) {
                store.clear()
                savedState = SavedStateHandle(if (recoverJournal) writerState else emptyMap())
                replaceWriter()
            }
            registry = SaveableStateRegistry(saved) { true }
            unit = nextUnit
            visible = showEditor
        }
        compose.waitForIdle()
    }

    private fun replaceWriter() {
        store = ViewModelStore()
        writer = WorkoutViewModel(repository, { it.message.orEmpty() }, savedState = savedState)
        store.put("workout", writer)
    }

    private fun awaitRoom(predicate: suspend () -> Boolean) {
        compose.waitUntil(15_000) { compose.runOnIdle {}; runBlocking { predicate() } }
    }

    private fun flushWriter(): Boolean {
        var result: Result<Boolean>? = null
        compose.runOnIdle { writer.viewModelScope.launch { result = runCatching { writer.flush() } } }
        compose.waitUntil(15_000) { compose.runOnIdle { result != null } }
        return compose.runOnIdle { requireNotNull(result).getOrThrow() }
    }
}

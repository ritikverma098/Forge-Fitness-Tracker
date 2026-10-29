package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.input.TextFieldValue
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.screens.workout.SetRow
import com.forge.workout.viewmodel.SetField
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SetRowRestorationHostTest {
    @get:Rule val compose = createComposeRule()
    private var visible by mutableStateOf(true)
    private var unit by mutableStateOf(WeightUnit.KG)
    private var set by mutableStateOf(WorkoutSet(id = 20, workoutExerciseId = 10, setNumber = 1, weight = 60.0, reps = 8))
    private val changes = mutableListOf<Pair<SetField, Double>>()

    @Test fun `restoring a tab after a unit change converts display without writing rounded values`() {
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("60")
        restoreTab(WeightUnit.LB)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("132.28")
        restoreTab(WeightUnit.KG)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("60")
        assertTrue(changes.isEmpty())
    }

    @Test fun `invalid weight draft survives restoration when its unit has not changed`() {
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").performTextReplacement("-2")
        restoreTab(WeightUnit.KG)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("-2")
        compose.onNodeWithTag("set_done_20").assertIsNotEnabled()
        assertTrue(changes.isEmpty())
    }

    @Test fun `quantity draft is independent of the weight unit`() {
        showRow()
        compose.onNodeWithTag("numeric_20_REPS").performTextReplacement("2.5")
        restoreTab(WeightUnit.LB)
        compose.onNodeWithTag("numeric_20_REPS").assertTextEquals("2.5")
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("132.28")
        compose.onNodeWithTag("set_done_20").assertIsNotEnabled()
        assertTrue(changes.isEmpty())
    }

    @Test fun `accepted pending weight survives restored unit change and later save acknowledgement`() {
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").performTextReplacement("80")
        assertEquals(60.0, set.weight, 0.0)
        assertEquals(listOf(SetField.WEIGHT to 80.0), changes)
        restoreTab(WeightUnit.LB)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("176.37")
        compose.runOnIdle { set = set.copy(weight = 80.0) }
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("176.37")
        restoreTab(WeightUnit.KG)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("80")
        assertEquals(listOf(SetField.WEIGHT to 80.0), changes)
    }

    @Test fun `cleared pending weight stays blank across restored and live unit changes`() {
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").performTextClearance()
        assertEquals(60.0, set.weight, 0.0)
        restoreTab(WeightUnit.LB)
        assertEquals("", compose.onNodeWithTag("numeric_20_WEIGHT").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { unit = WeightUnit.KG }
        assertEquals("", compose.onNodeWithTag("numeric_20_WEIGHT").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { set = set.copy(weight = 0.0) }
        restoreTab(WeightUnit.LB)
        assertEquals("", compose.onNodeWithTag("numeric_20_WEIGHT").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(listOf(SetField.WEIGHT to 0.0), changes)
    }

    @Test fun `tiny pound draft keeps exact anchor through rounded kilogram focus and restoration`() {
        unit = WeightUnit.LB
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").performTextReplacement("0.01")
        val submitted = WeightUnit.LB.toKg(0.01)
        assertEquals(listOf(SetField.WEIGHT to submitted), changes)
        assertEquals(60.0, set.weight, 0.0)
        restoreTab(WeightUnit.KG)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("0")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        restoreTab(WeightUnit.LB)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("0.01")
        restoreTab(WeightUnit.KG)
        restoreTab(WeightUnit.LB)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("0.01")
        assertEquals(listOf(SetField.WEIGHT to submitted), changes)
    }

    @Test fun `live unit changes preserve accepted draft before model acknowledgement`() {
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").performTextReplacement("80")
        compose.runOnIdle { unit = WeightUnit.LB }
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("176.37")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { unit = WeightUnit.KG }
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("80")
        assertEquals(60.0, set.weight, 0.0)
        assertEquals(listOf(SetField.WEIGHT to 80.0), changes)
    }

    @Test fun `invalid weight falls back to canonical model in a different unit without submitting it`() {
        showRow()
        compose.onNodeWithTag("numeric_20_WEIGHT").performTextReplacement("-2")
        restoreTab(WeightUnit.LB)
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("132.28")
        assertEquals(60.0, set.weight, 0.0)
        assertTrue(changes.isEmpty())
    }

    @Test fun `legacy unit and text payload restores accepted weight without requiring an anchor`() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val text = with(TextFieldValue.Saver) { scope.save(TextFieldValue("80")) }!!
        unit = WeightUnit.LB
        showRow(arrayListOf("KG", text))
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("176.37")
        assertEquals(60.0, set.weight, 0.0)
        assertTrue(changes.isEmpty())
    }

    @Test fun `malformed legacy text payload safely initializes a nonnull draft`() {
        showRow(arrayListOf("KG", "invalid saved text state"))
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("60")
        compose.onNodeWithTag("numeric_20_REPS").assertTextEquals("8")
        assertTrue(changes.isEmpty())
    }

    @Test fun `legacy mutable state with null value falls back without restoring a null draft`() {
        showRow(mutableStateOf<Any?>(null))
        compose.onNodeWithTag("numeric_20_WEIGHT").assertTextEquals("60")
        compose.onNodeWithTag("numeric_20_REPS").assertTextEquals("8")
        assertTrue(changes.isEmpty())
    }

    private fun showRow(restoredWeight: Any? = null) {
        var restoredRegistry by mutableStateOf(restoredWeight?.let {
            SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        })
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (visible) holder.SaveableStateProvider("workout") {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides (restoredRegistry ?: LocalSaveableStateRegistry.current)) {
                        SetRow(set, ExerciseType.WEIGHT_REPS, null, unit, false,
                            onValueChange = { field, value -> changes += field to value },
                            onValidityChange = { _, _ -> }, onTypeChange = {}, onCompleteChange = {}, onDelete = {})
                    }
                }
            }
        }
        if (restoredWeight != null) {
            // Discover the numeric field's real key instead of consuming the first
            // entry, which belongs to the exercise menu's remembered scroll state.
            val weightKey = compose.runOnIdle {
                restoredRegistry!!.performSave().entries.single { (_, values) ->
                    values.any { payload -> (payload as? List<*>)?.firstOrNull() == unit.name }
                }.key
            }
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            compose.runOnIdle {
                restoredRegistry = SaveableStateRegistry(
                    restoredValues = mapOf(weightKey to listOf(restoredWeight)),
                    canBeSaved = { true },
                )
                visible = true
            }
            compose.waitForIdle()
        }
    }

    private fun restoreTab(nextUnit: WeightUnit) {
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { unit = nextUnit; visible = true }
        compose.waitForIdle()
    }
}

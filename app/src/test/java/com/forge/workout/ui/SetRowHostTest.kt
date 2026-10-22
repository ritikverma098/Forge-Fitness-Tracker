package com.forge.workout.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.SetType
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.screens.workout.SetRow
import com.forge.workout.viewmodel.SetField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Direct component interaction tests: immutable caller state and callback recording only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h900dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SetRowHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `kg edits emit individual values and Next Done move and clear focus`() {
        val fixture = showSet()
        val weight = compose.onNodeWithTag(numericTag(SET_ID, SetField.WEIGHT))
        val reps = compose.onNodeWithTag(numericTag(SET_ID, SetField.REPS))
        weight.assertTextEquals("60").assertContentDescriptionEquals(text(R.string.numeric_weight, "kg"))
        reps.assertTextEquals("8").assertContentDescriptionEquals(text(R.string.reps_label))

        weight.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        weight.assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 2)),
        )
        weight.performTextInput("82.5")
        weight.assertTextEquals("82.5")
        assertEquals(82.5, fixture.set.value.weight, 0.000001)
        assertEquals(8, fixture.set.value.reps)
        weight.performImeAction()
        reps.assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, 1)),
        )
        reps.performTextInput("11")
        reps.performImeAction()
        reps.assertIsNotFocused()
        assertEquals(listOf(SetField.WEIGHT to 82.5, SetField.REPS to 11.0), fixture.values)
        assertEquals(82.5, fixture.set.value.weight, 0.000001)
        assertEquals(11, fixture.set.value.reps)

        val completed = compose.onNodeWithTag("set_done_$SET_ID")
        completed.assertIsEnabled().assertIsOff().performClick()
        completed.assertIsOn().performClick()
        completed.assertIsOff()
        assertEquals(listOf(true, false), fixture.completions)
        assertTrue(fixture.lastValidity(SetField.WEIGHT))
        assertTrue(fixture.lastValidity(SetField.REPS))
    }

    @Test
    fun `pound display emits kilograms and switching units does not emit rounded input again`() {
        val fixture = showSet(unit = WeightUnit.LB)
        val weight = compose.onNodeWithTag(numericTag(SET_ID, SetField.WEIGHT))
        weight.assertTextEquals("132.28").assertContentDescriptionEquals(text(R.string.numeric_weight, "lb"))
        assertTrue("Initial display must not write a rounded weight", fixture.values.isEmpty())

        weight.performTextReplacement("220.46226218")
        assertEquals(1, fixture.values.size)
        assertEquals(SetField.WEIGHT, fixture.values.single().first)
        assertEquals(100.0, fixture.values.single().second, 0.000001)
        assertEquals(100.0, fixture.set.value.weight, 0.000001)
        assertEquals(8, fixture.set.value.reps)
        compose.runOnIdle { fixture.unit.value = WeightUnit.KG }
        weight.assertTextEquals("100").assertContentDescriptionEquals(text(R.string.numeric_weight, "kg"))
        assertEquals("A unit change only changes presentation", 1, fixture.values.size)
        assertEquals(100.0, fixture.set.value.weight, 0.000001)
    }

    @Test
    fun `invalid quantity and weight never emit values and zero cannot complete before valid recovery`() {
        val fixture = showSet()
        val reps = compose.onNodeWithTag(numericTag(SET_ID, SetField.REPS))
        val weight = compose.onNodeWithTag(numericTag(SET_ID, SetField.WEIGHT))
        val completed = compose.onNodeWithTag("set_done_$SET_ID")

        reps.performTextReplacement("2.5")
        completed.assertIsNotEnabled()
        assertFalse(fixture.lastValidity(SetField.REPS))
        assertEquals(8, fixture.set.value.reps)
        assertTrue(fixture.values.isEmpty())
        reps.performTextClearance()
        assertTrue(fixture.lastValidity(SetField.REPS))
        assertEquals(0, fixture.set.value.reps)
        completed.assertIsNotEnabled()
        weight.performTextReplacement("-10")
        assertFalse(fixture.lastValidity(SetField.WEIGHT))
        assertEquals(60.0, fixture.set.value.weight, 0.000001)
        completed.assertIsNotEnabled()

        weight.performTextReplacement("80")
        assertTrue(fixture.lastValidity(SetField.WEIGHT))
        completed.assertIsNotEnabled()
        reps.performTextReplacement("10001")
        assertFalse(fixture.lastValidity(SetField.REPS))
        assertEquals(0, fixture.set.value.reps)
        completed.assertIsNotEnabled()
        assertTrue(fixture.completions.isEmpty())
        reps.performTextReplacement("12")
        completed.assertIsEnabled().performClick()
        completed.assertIsOn()
        assertEquals(listOf(SetField.REPS to 0.0, SetField.WEIGHT to 80.0, SetField.REPS to 12.0), fixture.values)
        assertEquals(listOf(true), fixture.completions)
        assertEquals(80.0, fixture.set.value.weight, 0.000001)
        assertEquals(12, fixture.set.value.reps)
    }

    @Test
    fun `nonweighted types expose only their applicable quantity and emit unchanged duration or distance units`() {
        val fixture = showSet(initial = initialSet().copy(weight = 0.0, reps = 0),
            type = ExerciseType.BODYWEIGHT, unit = WeightUnit.LB)
        val cases = listOf(
            QuantityCase(ExerciseType.BODYWEIGHT, SetField.REPS, R.string.reps_label, "12"),
            QuantityCase(ExerciseType.REPS_ONLY, SetField.REPS, R.string.reps_label, "15"),
            QuantityCase(ExerciseType.DURATION, SetField.DURATION, R.string.duration_label, "75"),
            QuantityCase(ExerciseType.DISTANCE, SetField.DISTANCE, R.string.distance_label, "3.25"),
        )
        cases.forEachIndexed { index, case ->
            val id = SET_ID + index
            compose.runOnIdle {
                fixture.type.value = case.type
                fixture.set.value = initialSet().copy(id = id, weight = 0.0, reps = 0)
            }
            compose.onNodeWithTag(numericTag(id, SetField.WEIGHT)).assertDoesNotExist()
            compose.onNodeWithTag("set_done_$id").assertIsNotEnabled()
            val quantity = compose.onNodeWithTag(numericTag(id, case.field))
            quantity.assertContentDescriptionEquals(text(case.label)).performTextReplacement(case.value)
            assertEquals(case.field to case.value.toDouble(), fixture.values.last())
            compose.onNodeWithTag("set_done_$id").assertIsEnabled().performClick()
            compose.onNodeWithTag("set_done_$id").assertIsOn()
            assertEquals(0.0, fixture.set.value.weight, 0.000001)
            if (case.field != SetField.REPS) assertEquals(0, fixture.set.value.reps)
            if (case.field != SetField.DURATION) assertEquals(0, fixture.set.value.duration)
            if (case.field != SetField.DISTANCE) assertEquals(0.0, fixture.set.value.distance, 0.000001)
        }
        assertEquals(cases.map { it.field to it.value.toDouble() }, fixture.values)
        assertEquals(List(4) { true }, fixture.completions)
    }

    @Test
    fun `set menu emits requested type and delete without changing numerical values or completion`() {
        val fixture = showSet()
        compose.onNodeWithTag("set_options_$SET_ID").performClick()
        compose.onNodeWithTag("set_type_${SET_ID}_${SetType.WARM_UP.name}").performClick()
        assertEquals(listOf(SetType.WARM_UP), fixture.types)
        assertEquals(initialSet().copy(setType = SetType.WARM_UP), fixture.set.value)
        compose.onNodeWithTag("set_options_$SET_ID").assert(hasText(text(R.string.set_warm_up_number, 1)))

        compose.onNodeWithTag("set_options_$SET_ID").performClick()
        compose.onNodeWithText(text(R.string.delete_set, 1)).performClick()
        assertEquals(1, fixture.deletions)
        assertEquals(initialSet().copy(setType = SetType.WARM_UP), fixture.set.value)
        assertTrue(fixture.values.isEmpty())
        assertTrue(fixture.completions.isEmpty())
    }

    private fun showSet(
        initial: WorkoutSet = initialSet(),
        type: ExerciseType = ExerciseType.WEIGHT_REPS,
        unit: WeightUnit = WeightUnit.KG,
    ): RowFixture {
        val fixture = RowFixture(initial, type, unit)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(411.dp)) {
                    SetRow(
                        set = fixture.set.value,
                        type = fixture.type.value,
                        previous = null,
                        unit = fixture.unit.value,
                        showPrevious = true,
                        onValueChange = fixture::valueChanged,
                        onValidityChange = { field, valid -> fixture.validities += field to valid },
                        onTypeChange = { selected ->
                            fixture.types += selected
                            fixture.set.value = fixture.set.value.copy(setType = selected)
                        },
                        onCompleteChange = { completed ->
                            fixture.completions += completed
                            fixture.set.value = fixture.set.value.copy(completed = completed)
                        },
                        onDelete = { fixture.deletions++ },
                    )
                }
            }
        }
        compose.waitForIdle()
        return fixture
    }

    private class RowFixture(initial: WorkoutSet, exerciseType: ExerciseType, weightUnit: WeightUnit) {
        val set = mutableStateOf(initial)
        val type = mutableStateOf(exerciseType)
        val unit = mutableStateOf(weightUnit)
        val values = mutableListOf<Pair<SetField, Double>>()
        val validities = mutableListOf<Pair<SetField, Boolean>>()
        val types = mutableListOf<SetType>()
        val completions = mutableListOf<Boolean>()
        var deletions = 0

        fun valueChanged(field: SetField, value: Double) {
            values += field to value
            set.value = when (field) {
                SetField.WEIGHT -> set.value.copy(weight = value)
                SetField.REPS -> set.value.copy(reps = value.toInt())
                SetField.DURATION -> set.value.copy(duration = value.toInt())
                SetField.DISTANCE -> set.value.copy(distance = value)
            }
        }

        fun lastValidity(field: SetField) = validities.last { it.first == field }.second
    }

    private data class QuantityCase(val type: ExerciseType, val field: SetField, val label: Int, val value: String)
    private fun numericTag(id: Long, field: SetField) = "numeric_${id}_${field.name}"
    private fun text(resource: Int, vararg arguments: Any) =
        (RuntimeEnvironment.getApplication() as Application).getString(resource, *arguments)

    private companion object {
        const val SET_ID = 41L
        fun initialSet() = WorkoutSet(id = SET_ID, workoutExerciseId = 7, setNumber = 1, weight = 60.0, reps = 8)
    }
}

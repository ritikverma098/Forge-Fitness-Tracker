package com.forge.workout.ui.screens.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.*
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.viewmodel.SetField
import com.forge.workout.ui.components.setTypeLabel
import com.forge.workout.ui.components.setNumberLabel
import java.math.BigDecimal
import java.math.RoundingMode

private fun quantityField(type: ExerciseType) = when(type) {
    ExerciseType.DURATION -> SetField.DURATION
    ExerciseType.DISTANCE -> SetField.DISTANCE
    else -> SetField.REPS
}
@Composable private fun quantityLabel(type: ExerciseType) = stringResource(when(type) {
    ExerciseType.DURATION -> R.string.duration_label
    ExerciseType.DISTANCE -> R.string.distance_label
    else -> R.string.reps_label
})

@Composable internal fun SetTableHeader(type: ExerciseType, unit: WeightUnit, showPrevious: Boolean) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderCell(stringResource(R.string.set_column), Modifier.width(48.dp))
            if(showPrevious) HeaderCell(stringResource(R.string.previous_column), Modifier.weight(.8f))
            if(type == ExerciseType.WEIGHT_REPS) HeaderCell(unit.symbol, Modifier.weight(1f))
            HeaderCell(quantityLabel(type), Modifier.weight(1f))
            HeaderCell(stringResource(R.string.set_done), Modifier.width(48.dp))
        }
}
@Composable private fun HeaderCell(label: String, modifier: Modifier) {
    Text(label, modifier, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun SetRow(set: WorkoutSet, type: ExerciseType, previous: WorkoutSet?, unit: WeightUnit,
    showPrevious: Boolean, onValueChange: (SetField, Double) -> Unit,
    onValidityChange: (SetField, Boolean) -> Unit, onTypeChange: (SetType) -> Unit,
    onCompleteChange: (Boolean) -> Unit, onDelete: () -> Unit) {
    var validWeight by remember(set.id) { mutableStateOf(true) }
    var validQuantity by remember(set.id) { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    val quantityFocus = remember(set.id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val completionDescription = stringResource(R.string.complete_set, set.setNumber)
    val optionsDescription = stringResource(R.string.set_options, set.setNumber)
    val typeDescription = stringResource(R.string.set_type_description, set.setNumber, setTypeLabel(set.setType))
    val quantityReady = when(type) { ExerciseType.DURATION -> set.duration > 0; ExerciseType.DISTANCE -> set.distance > 0; else -> set.reps > 0 }
        Column(Modifier.fillMaxWidth().testTag("workout_set_${set.id}")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(48.dp)) {
                    TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("set_options_${set.id}").semantics { contentDescription = optionsDescription },
                        contentPadding = PaddingValues(0.dp)) {
                        Text(setNumberLabel(set.setNumber, set.setType), Modifier.semantics { contentDescription = typeDescription },
                            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                    DropdownMenu(menu, { menu = false }) {
                        SetType.entries.forEach { type ->
                            DropdownMenuItem(text = { Text(setTypeLabel(type)) },
                                modifier = Modifier.testTag("set_type_${set.id}_${type.name}"),
                                leadingIcon = { RadioButton(selected = set.setType == type, onClick = null) },
                                onClick = { menu = false; onTypeChange(type) })
                        }
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_set, set.setNumber)) },
                            onClick = { menu = false; onDelete() })
                    }
                }
                if(showPrevious) PreviousCell(previous, type, unit, Modifier.weight(.8f))
                if(type == ExerciseType.WEIGHT_REPS) NumericField(set.id, SetField.WEIGHT, unit.fromKg(set.weight),
                    stringResource(R.string.numeric_weight, unit.symbol), false, unit.name, unit.fromKg(10_000.0), false, Modifier.weight(1f), ImeAction.Next,
                    { quantityFocus.requestFocus() }, {
                        validWeight = it; onValidityChange(SetField.WEIGHT, it)
                    }) { onValueChange(SetField.WEIGHT, unit.toKg(it)) }
                val field = quantityField(type)
                val value = when(field) { SetField.DURATION -> set.duration.toDouble(); SetField.DISTANCE -> set.distance; else -> set.reps.toDouble() }
                val maximum = when(field) { SetField.DURATION -> 604_800.0; SetField.DISTANCE -> 100_000.0; else -> 10_000.0 }
                NumericField(set.id, field, value, quantityLabel(type), field != SetField.DISTANCE, field.name,
                    maximum, set.completed, Modifier.weight(1f).focusRequester(quantityFocus), ImeAction.Done,
                    { focusManager.clearFocus() }, {
                        validQuantity = it; onValidityChange(field, it)
                    }) { onValueChange(field, it) }
                Checkbox(checked = set.completed, onCheckedChange = {
                    if(!it || validWeight && validQuantity) onCompleteChange(it)
                }, enabled = set.completed || validWeight && validQuantity && quantityReady,
                    modifier = Modifier.size(48.dp).testTag("set_done_${set.id}").semantics { contentDescription = completionDescription })
            }
            if(!showPrevious && previous != null) PreviousCell(previous, type, unit, Modifier.padding(start = 52.dp))
            if(!validWeight || !validQuantity) Text(stringResource(R.string.invalid_set), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
}
@Composable private fun PreviousCell(previous: WorkoutSet?, type: ExerciseType, unit: WeightUnit, modifier: Modifier) {
    val description = previous?.let { setDescription(it, type, unit) } ?: "—"
    val accessible = if(previous != null) stringResource(R.string.previous, description) else stringResource(R.string.no_previous)
    Text(description, modifier.semantics { contentDescription = accessible }, textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun NumericField(id: Long, field: SetField, initial: Double, label: String, integer: Boolean,
    unitKey: String, maximum: Double, required: Boolean, modifier: Modifier, imeAction: ImeAction,
    onImeAction: () -> Unit, onValid: (Boolean) -> Unit, onValue: (Double) -> Unit) {
    var draft by rememberSaveable(id, field, saver = numericInputSaver) {
        mutableStateOf(initialNumericDraft(initial, integer, unitKey))
    }
    // Keep the accepted value while its save is pending or failed. Unit changes
    // format that exact value without feeding rounded presentation back into storage.
    val presented = remember(draft, unitKey, initial, integer) { draft.presented(unitKey, initial, integer) }
    val input = presented.input
    fun valid(text: String): Boolean {
        val parsed = text.replace(',', '.').toDoubleOrNull()
        return if(text.isEmpty()) !required else parsed != null && parsed.isFinite() && parsed >= 0 && parsed <= maximum &&
            (!required || parsed > 0) && (!integer || parsed % 1.0 == 0.0)
    }
    val isValid = valid(input.text)
    LaunchedEffect(id, field, required) {
        // Unchecking Done can make a previously rejected zero/blank valid without
        // another text edit. Persist it before the old quantity can be reused.
        if (field != SetField.WEIGHT && !required && isValid && initial != 0.0 &&
            (input.text.replace(',', '.').toDoubleOrNull() ?: 0.0) == 0.0) onValue(0.0)
    }
    LaunchedEffect(isValid) { onValid(isValid) }
    BasicTextField(value = input, onValueChange = {
        val current = draft.presented(unitKey, initial, integer)
        val changed = current.input.text != it.text
        draft = current.copy(input = it, canonicalWeightKg =
            if (changed) canonicalWeight(it.text, unitKey) else current.canonicalWeightKg)
        if(changed) {
            val accepted = valid(it.text)
            onValid(accepted)
            if(accepted) onValue(it.text.replace(',', '.').toDoubleOrNull() ?: 0.0)
        }
    }, modifier = modifier.heightIn(min = 48.dp).testTag("numeric_${id}_${field.name}")
        .semantics { contentDescription = label }
        .onFocusChanged { if(it.isFocused) {
            val current = draft.presented(unitKey, initial, integer)
            draft = current.copy(input = current.input.copy(selection = TextRange(0, current.input.text.length)))
        } }
        .background(if(isValid) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
        .padding(horizontal = 6.dp, vertical = 10.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if(integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        decorationBox = { inner -> Box(contentAlignment = Alignment.Center) {
            if(input.text.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            inner()
        } })
}

private data class NumericInputDraft(val input: TextFieldValue, val unitKey: String, val canonicalWeightKg: Double?) {
    fun presented(nextUnitKey: String, initial: Double, integer: Boolean): NumericInputDraft {
        if (unitKey == nextUnitKey) return this
        val nextUnit = WeightUnit.entries.firstOrNull { it.name == nextUnitKey }
        if (nextUnit == null || canonicalWeightKg == null) return initialNumericDraft(initial, integer, nextUnitKey)
        val text = if (input.text.isEmpty()) "" else numericText(nextUnit.fromKg(canonicalWeightKg), integer = false)
        return NumericInputDraft(TextFieldValue(text), nextUnitKey, canonicalWeightKg)
    }
}

private fun numericText(value: Double, integer: Boolean): String = if (integer) value.toInt().toString()
    else BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private fun initialNumericDraft(initial: Double, integer: Boolean, unitKey: String): NumericInputDraft = NumericInputDraft(
    TextFieldValue(if (initial == 0.0) "" else numericText(initial, integer)), unitKey,
    WeightUnit.entries.firstOrNull { it.name == unitKey }?.toKg(initial),
)

private fun canonicalWeight(text: String, unitKey: String): Double? {
    val unit = WeightUnit.entries.firstOrNull { it.name == unitKey } ?: return null
    if (text.isEmpty()) return 0.0
    val parsed = text.replace(',', '.').toDoubleOrNull() ?: return null
    return if (parsed.isFinite() && parsed in 0.0..unit.fromKg(10_000.0)) unit.toKg(parsed) else null
}

// Keep the unrounded anchor through focus changes, saved tabs and repeated unit
// conversions. Legacy unit/text payloads derive their anchor from valid source text.
private val numericInputSaver = Saver<MutableState<NumericInputDraft>, Any>(
    save = { state ->
        val draft = state.value
        with(TextFieldValue.Saver) { save(draft.input) }
            ?.let { arrayListOf(draft.unitKey, it, draft.canonicalWeightKg) }
    },
    restore = { saved ->
        val payload = if (saved is State<*>) saved.value else saved
        (payload as? List<*>)?.let { values ->
            val unit = values.getOrNull(0) as? String
            val input = values.getOrNull(1)?.let { runCatching { TextFieldValue.Saver.restore(it) }.getOrNull() }
            if (unit == null || input == null) null else {
                val parsed = canonicalWeight(input.text, unit)
                val anchor = (values.getOrNull(2) as? Double)?.takeIf { parsed != null && it.isFinite() && it in 0.0..10_000.0 }
                mutableStateOf(NumericInputDraft(input, unit, anchor ?: parsed))
            }
        }
    },
)

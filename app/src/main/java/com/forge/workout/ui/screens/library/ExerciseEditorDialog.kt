package com.forge.workout.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.ui.components.exerciseTypeLabel
import com.forge.workout.ui.components.muscleLabel

@Composable
internal fun ExerciseEditorDialog(
    exercise: Exercise?,
    exercises: List<Exercise>,
    busy: Boolean,
    serverError: String? = null,
    onDismiss: () -> Unit,
    onSave: (Exercise) -> Unit,
) {
    var name by rememberSaveable(exercise?.id) { mutableStateOf(exercise?.name.orEmpty()) }
    var primaryName by rememberSaveable(exercise?.id) { mutableStateOf((exercise?.primaryMuscleGroup ?: MuscleGroup.OTHER).name) }
    var secondaryName by rememberSaveable(exercise?.id) { mutableStateOf(exercise?.secondaryMuscleGroup?.name) }
    var typeName by rememberSaveable(exercise?.id) { mutableStateOf((exercise?.exerciseType ?: ExerciseType.WEIGHT_REPS).name) }
    var notes by rememberSaveable(exercise?.id) { mutableStateOf(exercise?.notes.orEmpty()) }
    var submitted by rememberSaveable(exercise?.id) { mutableStateOf(false) }
    val primary = MuscleGroup.valueOf(primaryName)
    val secondary = secondaryName?.let(MuscleGroup::valueOf)
    val type = ExerciseType.valueOf(typeName)
    val nameError = when {
        name.isBlank() -> R.string.library_name_required
        name.trim().length > 100 -> R.string.library_name_too_long
        exercises.any { !it.archived && it.id != exercise?.id && it.name.trim().equals(name.trim(), ignoreCase = true) } -> R.string.library_name_duplicate
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (exercise == null) R.string.library_new_exercise else R.string.library_edit_exercise)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (serverError != null) Text(serverError, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_name)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = submitted && nameError != null,
                    supportingText = {
                        if (submitted && nameError != null) Text(stringResource(nameError))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                EnumSelectionField(
                    label = stringResource(R.string.library_primary_muscle),
                    valueLabel = muscleLabel(primary),
                    options = MuscleGroup.entries,
                    optionLabel = { muscleLabel(it) },
                    onSelected = {
                        primaryName = it.name
                        if (secondaryName == it.name) secondaryName = null
                    },
                    enabled = !busy,
                )
                EnumSelectionField(
                    label = stringResource(R.string.library_secondary_muscle),
                    valueLabel = secondary?.let { muscleLabel(it) } ?: stringResource(R.string.library_no_secondary),
                    options = listOf<MuscleGroup?>(null) + MuscleGroup.entries.filter { it != primary },
                    optionLabel = { it?.let { muscleLabel(it) } ?: stringResource(R.string.library_no_secondary) },
                    onSelected = { secondaryName = it?.name },
                    enabled = !busy,
                )
                EnumSelectionField(
                    label = stringResource(R.string.library_type),
                    valueLabel = exerciseTypeLabel(type),
                    options = ExerciseType.entries,
                    optionLabel = { exerciseTypeLabel(it) },
                    onSelected = { typeName = it.name },
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.library_notes)) },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                    isError = submitted && notes.length > 5000,
                    supportingText = {
                        if (submitted && notes.length > 5000) Text(stringResource(R.string.library_notes_too_long))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    submitted = true
                    if (nameError == null && notes.length <= 5000) onSave(
                        Exercise(
                            id = exercise?.id ?: 0L,
                            name = name.trim(),
                            primaryMuscleGroup = primary,
                            secondaryMuscleGroup = secondary,
                            exerciseType = type,
                            notes = notes.trim(),
                            isCustom = true,
                            archived = false,
                        ),
                    )
                },
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.library_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.library_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> EnumSelectionField(
    label: String,
    valueLabel: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = valueLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = { expanded = false; onSelected(option) },
                )
            }
        }
    }
}

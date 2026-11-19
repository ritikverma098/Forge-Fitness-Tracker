package com.forge.workout.ui.screens.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RoutineDetail
import com.forge.workout.ui.components.ForgeIconTile
import com.forge.workout.ui.components.muscleLabel
import com.forge.workout.ui.screens.library.EnumSelectionField
import com.forge.workout.ui.screens.library.ExerciseEditorDialog
import com.forge.workout.ui.screens.library.ExerciseThumbnail
import com.forge.workout.viewmodel.LibraryViewModel
import kotlinx.coroutines.flow.combine
import java.util.UUID

@Composable
internal fun RoutineNameDialog(
    routine: RoutineDetail?,
    routines: List<RoutineDetail>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(routine?.routine?.id) { mutableStateOf(routine?.routine?.name.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val nameError = when {
        name.isBlank() -> R.string.routines_name_required
        name.trim().length > 100 -> R.string.routines_name_too_long
        routines.any { it.routine.id != routine?.routine?.id && it.routine.name.trim().equals(name.trim(), ignoreCase = true) } -> R.string.routines_name_duplicate
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { ForgeIconTile(Icons.Outlined.FitnessCenter, highlighted = true) },
        title = { Text(stringResource(if (routine == null) R.string.routines_create else R.string.routines_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.routines_name)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = submitted && nameError != null,
                    supportingText = if (submitted && nameError != null) ({ Text(stringResource(nameError)) }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("routine_name"),
                    shape = MaterialTheme.shapes.medium,
                )
            }
        },
        confirmButton = {
            Button(enabled = !busy, modifier = Modifier.testTag("routine_save"),
                onClick = { submitted = true; if (nameError == null) onSave(name.trim()) }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.routines_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.library_cancel)) } },
    )
}

@Composable
internal fun RoutineExercisePicker(
    exercises: List<Exercise>,
    addedIds: Set<Long>,
    busy: Boolean,
    addError: String?,
    libraryViewModel: LibraryViewModel,
    onDismiss: () -> Unit,
    onPick: (Exercise) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var muscleName by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var createdId by rememberSaveable { mutableStateOf<Long?>(null) }
    var createdFallback by remember { mutableStateOf<Exercise?>(null) }
    val libraryBusy by libraryViewModel.busy.collectAsStateWithLifecycle()
    val operationBusy = busy || libraryBusy || pendingRequest != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val genericError = stringResource(R.string.library_operation_failed)
    val group = muscleName?.let(MuscleGroup::valueOf)
    val persistedCreated = exercises.find { it.id == createdId }
    val created = if (persistedCreated != null) persistedCreated.takeUnless { it.archived }
        else createdFallback?.takeIf { it.id == createdId && !it.archived }
    val filtered = remember(exercises, query, muscleName, createdId) {
        exercises.filter {
            !it.archived && it.id != createdId && it.name.contains(query.trim(), ignoreCase = true) &&
                (muscleName == null || it.primaryMuscleGroup.name == muscleName || it.secondaryMuscleGroup?.name == muscleName)
        }.sortedBy { it.name.lowercase() }
    }
    LaunchedEffect(libraryViewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(libraryViewModel.exerciseSaveResult, libraryViewModel.busy) { result, _ -> result }.collect { result ->
                if (result != null && result.requestId == pendingRequest) {
                    val saved = result.savedExercise
                    if (saved != null) {
                        createdId = saved.id
                        // The retained result may precede Room's exercise Flow reaching the screen.
                        createdFallback = saved
                        creating = false
                        editorError = null
                    } else {
                        editorError = result.error?.ifBlank { genericError } ?: genericError
                    }
                    pendingRequest = null
                    libraryViewModel.clearError()
                    libraryViewModel.acknowledgeExerciseSave(result.requestId)
                } else if (pendingRequest != null && !libraryViewModel.busy.value &&
                    libraryViewModel.exerciseSaveResult.value?.requestId != pendingRequest) {
                    // Process recreation can restore the form after the old ViewModel/job was lost.
                    pendingRequest = null
                    editorError = genericError
                }
            }
        }
    }
    if (creating) {
        ExerciseEditorDialog(
            exercise = null,
            exercises = exercises,
            busy = operationBusy,
            serverError = editorError,
            onDismiss = { creating = false; editorError = null; libraryViewModel.clearError() },
            onSave = { exercise ->
                if (!operationBusy && !libraryViewModel.busy.value && pendingRequest == null) {
                    val requestId = UUID.randomUUID().toString()
                    pendingRequest = requestId
                    editorError = null
                    libraryViewModel.saveExercise(exercise, requestId = requestId)
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!operationBusy) { libraryViewModel.clearError(); onDismiss() } },
        title = { Text(stringResource(R.string.routines_add_exercise)) },
        text = {
            val focusManager = LocalFocusManager.current
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    enabled = !operationBusy,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("routine_exercise_search"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                )
                Box(Modifier.testTag("routine_exercise_muscle")) {
                    EnumSelectionField(
                        label = stringResource(R.string.library_primary_muscle),
                        valueLabel = group?.let { muscleLabel(it) } ?: stringResource(R.string.library_all_muscles),
                        options = listOf<MuscleGroup?>(null) + MuscleGroup.entries,
                        optionLabel = { it?.let { muscleLabel(it) } ?: stringResource(R.string.library_all_muscles) },
                        onSelected = { muscleName = it?.name },
                        enabled = !operationBusy,
                    )
                }
                FilledTonalButton(
                    onClick = { editorError = null; libraryViewModel.clearError(); creating = true },
                    enabled = !operationBusy,
                    modifier = Modifier.fillMaxWidth().testTag("routine_create_exercise"),
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.library_new_exercise))
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                (editorError ?: addError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).weight(1f, fill = false)
                    .testTag("routine_exercise_results"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    created?.let { exercise ->
                        item(key = "created_${exercise.id}") {
                            RoutineExerciseOption(exercise, available = exercise.id !in addedIds,
                                enabled = !operationBusy, newlyCreated = true, onPick = onPick)
                        }
                    }
                    if (filtered.isEmpty() && created == null) item(key = "empty") {
                        Text(stringResource(R.string.library_no_results_title), modifier = Modifier.padding(16.dp))
                    }
                    items(filtered, key = { it.id }) { exercise ->
                        RoutineExerciseOption(exercise, available = exercise.id !in addedIds,
                            enabled = !operationBusy, newlyCreated = false, onPick = onPick)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { libraryViewModel.clearError(); onDismiss() }, enabled = !operationBusy) {
            Text(stringResource(R.string.library_done))
        } },
    )
}

@Composable
private fun RoutineExerciseOption(
    exercise: Exercise,
    available: Boolean,
    enabled: Boolean,
    newlyCreated: Boolean,
    onPick: (Exercise) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    val background = if (newlyCreated) colors.primaryContainer else colors.surfaceContainerHigh
    Row(
        Modifier.fillMaxWidth().clip(shape).background(background)
            .testTag(if (newlyCreated) "routine_created_exercise_${exercise.id}" else "routine_exercise_${exercise.id}")
            .clickable(enabled = enabled && available) { onPick(exercise) }
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExerciseThumbnail(exercise.primaryMuscleGroup)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (newlyCreated) Text(stringResource(R.string.routine_picker_just_created),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(exercise.name, style = MaterialTheme.typography.titleSmall,
                color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (available) muscleLabel(exercise.primaryMuscleGroup) else stringResource(R.string.routines_already_added),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

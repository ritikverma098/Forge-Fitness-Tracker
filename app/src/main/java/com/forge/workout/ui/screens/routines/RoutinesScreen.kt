package com.forge.workout.ui.screens.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.RoutineExerciseDetail
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ForgeIconTile
import com.forge.workout.ui.components.ForgePanel
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.muscleLabel
import com.forge.workout.ui.screens.library.ExerciseThumbnail
import com.forge.workout.viewmodel.RoutineViewModel
import com.forge.workout.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@Composable
fun RoutinesScreen(
    routines: List<RoutineDetail>,
    exercises: List<Exercise>,
    viewModel: RoutineViewModel,
    libraryViewModel: LibraryViewModel,
    onStart: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = routines.find { it.routine.id == selectedId }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val genericError = stringResource(R.string.library_operation_failed)
    val savedMessage = stringResource(R.string.routines_save_success)
    val deletedMessage = stringResource(R.string.routines_delete_success)
    val addedMessage = stringResource(R.string.routines_add_success)
    BackHandler(enabled = selected != null && !editingName && !showPicker && deletingId == null) {
        selectedId = null
    }
    LaunchedEffect(error) {
        error?.let {
            val message = it.ifBlank { genericError }
            if (editingName || showPicker) {
                formError = message
                viewModel.clearError()
            } else {
                snackbar.showSnackbar(message)
                viewModel.clearError()
            }
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = selected?.routine?.name ?: stringResource(R.string.routines_title),
                subtitle = if (selected == null) stringResource(R.string.routines_subtitle)
                    else pluralStringResource(
                        R.plurals.routines_available_exercise_count,
                        selected.exercises.count { !it.exercise.archived },
                        selected.exercises.count { !it.exercise.archived },
                    ),
                onBack = { if (selected != null) selectedId = null else onBack() },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = { formError = null; renamingId = selected.routine.id; editingName = true }, enabled = !busy,
                            modifier = Modifier.testTag("routine_rename")) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.routines_rename))
                        }
                        IconButton(onClick = { deletingId = selected.routine.id }, enabled = !busy) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.library_delete))
                        }
                    } else {
                        FilledTonalIconButton(onClick = { formError = null; renamingId = null; editingName = true }, enabled = !busy,
                            modifier = Modifier.testTag("routine_create")) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.routines_create))
                        }
                    }
                },
            )
            if (selected == null) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (routines.isEmpty()) item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.routines_empty_title),
                            description = stringResource(R.string.routines_empty_body),
                            icon = Icons.Outlined.FitnessCenter,
                            onAction = if (busy) null else ({ formError = null; renamingId = null; editingName = true }),
                            actionLabel = stringResource(R.string.routines_create),
                        )
                    }
                    items(routines, key = { it.routine.id }) { routine ->
                        RoutineCard(
                            detail = routine,
                            onEdit = { selectedId = routine.routine.id },
                            onDelete = { deletingId = routine.routine.id },
                            onStart = { onStart(routine.routine.id) },
                            enabled = !busy,
                        )
                    }
                    if (routines.isNotEmpty()) item {
                        OutlinedButton(
                            onClick = { formError = null; renamingId = null; editingName = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            Icon(Icons.Outlined.Add, null, Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.routines_create))
                        }
                    }
                }
            } else {
                RoutineEditorContent(
                    detail = selected,
                    enabled = !busy,
                    onAdd = { formError = null; showPicker = true },
                    onStart = { onStart(selected.routine.id) },
                    onMove = viewModel::moveExercise,
                    onRemove = viewModel::removeExercise,
                )
            }
        }
    }
    if (editingName) {
        RoutineNameDialog(
            routine = routines.find { it.routine.id == renamingId },
            routines = routines,
            busy = busy,
            error = formError,
            onDismiss = { editingName = false },
            onSave = { name ->
                formError = null
                viewModel.saveRoutine(renamingId, name) { id ->
                    selectedId = id
                    editingName = false
                    scope.launch { snackbar.showSnackbar(savedMessage) }
                }
            },
        )
    }
    if (showPicker && selected != null) {
        RoutineExercisePicker(
            exercises = exercises,
            addedIds = selected.exercises.map { it.exercise.id }.toSet(),
            busy = busy,
            addError = formError,
            libraryViewModel = libraryViewModel,
            onDismiss = { showPicker = false },
            onPick = { exercise ->
                formError = null
                viewModel.addExercise(selected.routine.id, exercise.id) {
                    showPicker = false
                    scope.launch { snackbar.showSnackbar(addedMessage) }
                }
            },
        )
    }
    routines.find { it.routine.id == deletingId }?.let { routine ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingId = null },
            title = { Text(stringResource(R.string.routines_delete_title, routine.routine.name)) },
            text = { Text(stringResource(R.string.routines_delete_body)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        viewModel.deleteRoutine(routine.routine.id) {
                            if (selectedId == routine.routine.id) selectedId = null
                            deletingId = null
                            scope.launch { snackbar.showSnackbar(deletedMessage) }
                        }
                    },
                ) { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }, enabled = !busy) { Text(stringResource(R.string.library_cancel)) }
            },
        )
    }
}

@Composable
private fun RoutineCard(detail: RoutineDetail, onEdit: () -> Unit, onDelete: () -> Unit, onStart: () -> Unit, enabled: Boolean) {
    val separator = stringResource(R.string.routines_preview_separator)
    val available = remember(detail.exercises) { detail.exercises.count { !it.exercise.archived } }
    val preview = remember(detail.exercises, separator) {
        detail.exercises.sortedBy { it.entry.orderIndex }.joinToString(separator) { it.exercise.name }
    }
    ForgePanel(
        modifier = Modifier.fillMaxWidth().testTag("routine_card_${detail.routine.id}"),
        onClick = onEdit.takeIf { enabled },
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ForgeIconTile(Icons.Outlined.FitnessCenter, highlighted = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(detail.routine.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        pluralStringResource(
                            R.plurals.routines_available_exercise_count,
                            available,
                            available,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.library_delete))
                }
            }
            Text(
                text = if (detail.exercises.isEmpty()) stringResource(R.string.routines_untitled_exercises)
                    else preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStart, enabled = enabled && available > 0,
                    modifier = Modifier.fillMaxWidth().testTag("routine_start_${detail.routine.id}")) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp).padding(end = 4.dp))
                    Text(stringResource(R.string.routines_start))
                }
                TextButton(onClick = onEdit, enabled = enabled, modifier = Modifier.align(Alignment.End)
                    .testTag("routine_edit_${detail.routine.id}")) { Text(stringResource(R.string.routines_edit)) }
            }
        }
    }
}

@Composable
private fun RoutineEditorContent(
    detail: RoutineDetail,
    enabled: Boolean,
    onAdd: () -> Unit,
    onStart: () -> Unit,
    onMove: (Long, Int) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val entries = remember(detail.exercises) { detail.exercises.sortedBy { it.entry.orderIndex } }
    LazyColumn(
        modifier = Modifier.testTag("routine_editor_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entries.isEmpty()) item {
            EmptyState(
                title = stringResource(R.string.routines_no_exercises_title),
                description = stringResource(R.string.routines_no_exercises_body),
                icon = Icons.Outlined.FitnessCenter,
            )
        }
        itemsIndexed(entries, key = { _, item -> item.entry.id }) { index, item ->
            RoutineExerciseRow(
                item = item,
                position = index + 1,
                modifier = Modifier.animateItem(),
                canMoveUp = enabled && index > 0,
                canMoveDown = enabled && index < entries.lastIndex,
                canRemove = enabled,
                onMove = { onMove(item.entry.id, it) },
                onRemove = { onRemove(item.entry.id) },
            )
        }
        item {
            OutlinedButton(onClick = onAdd, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("routine_add_exercise"), contentPadding = PaddingValues(16.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.routines_add_exercise))
            }
        }
        if (entries.isNotEmpty()) item {
            Button(onClick = onStart, enabled = enabled && entries.any { !it.exercise.archived },
                modifier = Modifier.fillMaxWidth().testTag("routine_start_${detail.routine.id}"), contentPadding = PaddingValues(16.dp)) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.routines_start))
            }
        }
    }
}

@Composable
private fun RoutineExerciseRow(
    item: RoutineExerciseDetail,
    position: Int,
    modifier: Modifier,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    ForgePanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        stringResource(R.string.routines_order, position),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ExerciseThumbnail(item.exercise.primaryMuscleGroup)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.exercise.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(muscleLabel(item.exercise.primaryMuscleGroup), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.exercise.archived) Text(
                        stringResource(R.string.routines_unavailable_exercise),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.routines_move_up, item.exercise.name))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.routines_move_down, item.exercise.name))
                }
                IconButton(onClick = onRemove, enabled = canRemove) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.routines_remove_exercise, item.exercise.name))
                }
            }
        }
    }
}

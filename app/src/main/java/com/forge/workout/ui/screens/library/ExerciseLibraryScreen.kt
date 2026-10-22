package com.forge.workout.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.forge.workout.model.MuscleGroup
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.exerciseTypeLabel
import com.forge.workout.ui.components.muscleLabel
import com.forge.workout.viewmodel.LibraryViewModel
import com.forge.workout.utils.ExerciseFiltering
import kotlinx.coroutines.launch

@Composable
fun ExerciseLibraryScreen(
    exercises: List<Exercise>,
    viewModel: LibraryViewModel,
    onSelect: ((Exercise) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    workoutMuscleGroups: Set<MuscleGroup> = emptySet(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var muscleName by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorError by rememberSaveable { mutableStateOf<String?>(null) }
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val rankedExercises by viewModel.rankedExercises.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val genericError = stringResource(R.string.library_operation_failed)
    val savedMessage = stringResource(R.string.library_save_success)
    val deletedMessage = stringResource(R.string.library_delete_success)
    val picker = onSelect != null
    val source = if(picker && rankedExercises.isNotEmpty()) rankedExercises else exercises
    val visible = remember(source, query, muscleName, showAll, workoutMuscleGroups, picker) {
        ExerciseFiltering.filter(
            exercises = source,
            query = query,
            workoutMuscleGroups = workoutMuscleGroups,
            selectedMuscleGroup = muscleName?.let(MuscleGroup::valueOf),
            showAll = showAll,
            sortAlphabetically = !picker,
        )
    }
    LaunchedEffect(error) {
        error?.let {
            if (showEditor) editorError = it.ifBlank { genericError }
            else snackbar.showSnackbar(it.ifBlank { genericError })
            viewModel.clearError()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = stringResource(if (onSelect == null) R.string.library_title else R.string.library_picker_title),
                subtitle = stringResource(if (onSelect == null) R.string.library_subtitle else R.string.library_picker_subtitle),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { editingId = null; editorError = null; showEditor = true }, enabled = !busy) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.library_new_exercise))
                    }
                },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.library_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.library_clear_search))
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            LazyRow(
                modifier = Modifier.testTag("library_muscle_filters"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (workoutMuscleGroups.isNotEmpty()) item {
                    FilterChip(
                        selected = muscleName == null && !showAll,
                        onClick = { muscleName = null; showAll = false },
                        label = { Text(stringResource(R.string.picker_workout_groups)) },
                    )
                }
                item {
                    FilterChip(
                        selected = muscleName == null && (showAll || workoutMuscleGroups.isEmpty()),
                        onClick = { muscleName = null; showAll = true },
                        label = { Text(stringResource(R.string.library_all_muscles)) },
                    )
                }
                items(MuscleGroup.entries, key = { it.name }) { muscle ->
                    FilterChip(
                        selected = muscleName == muscle.name,
                        onClick = {
                            if (muscleName == muscle.name) {
                                muscleName = null
                                showAll = true
                            } else {
                                muscleName = muscle.name
                                showAll = false
                            }
                        },
                        label = { Text(muscleLabel(muscle)) },
                    )
                }
            }
            Text(
                pluralStringResource(R.plurals.library_results, visible.size, visible.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if(picker) Text(stringResource(R.string.picker_rank_hint), Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (visible.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.library_no_results_title),
                    description = stringResource(R.string.library_no_results_body),
                    icon = Icons.Outlined.Search,
                    onAction = { editingId = null; showEditor = true },
                    actionLabel = stringResource(R.string.library_new_exercise),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("library_exercise_results"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.id }) { exercise ->
                        ExerciseLibraryRow(
                            exercise = exercise,
                            onClick = { if (onSelect != null) onSelect(exercise) else detailId = exercise.id },
                            onEdit = { editingId = exercise.id; showEditor = true },
                            onDelete = { deletingId = exercise.id },
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
    if (showEditor) {
        ExerciseEditorDialog(
            exercise = exercises.find { it.id == editingId },
            exercises = exercises,
            busy = busy,
            serverError = editorError,
            onDismiss = { showEditor = false; editorError = null },
            onSave = { exercise ->
                editorError = null
                viewModel.saveExercise(exercise) {
                    showEditor = false
                    scope.launch { snackbar.showSnackbar(savedMessage) }
                }
            },
        )
    }
    exercises.find { it.id == deletingId }?.let { exercise ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingId = null },
            title = { Text(stringResource(R.string.library_delete_exercise, exercise.name)) },
            text = { Text(stringResource(R.string.library_delete_exercise_body)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        viewModel.deleteExercise(exercise.id) {
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
    exercises.find { it.id == detailId }?.let { exercise ->
        AlertDialog(
            onDismissRequest = { detailId = null },
            title = { Text(exercise.name) },
            icon = { Icon(Icons.Outlined.FitnessCenter, null) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(muscleLabel(exercise.primaryMuscleGroup), style = MaterialTheme.typography.titleMedium)
                    exercise.secondaryMuscleGroup?.let { Text(muscleLabel(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(exerciseTypeLabel(exercise.exerciseType))
                    if (exercise.notes.isNotBlank()) Text(exercise.notes)
                    Text(
                        stringResource(if (exercise.isCustom) R.string.library_custom else R.string.library_default_exercise),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailId = null }) { Text(stringResource(R.string.library_done)) }
            },
            dismissButton = if (exercise.isCustom) {
                { TextButton(onClick = { detailId = null; editingId = exercise.id; showEditor = true }) {
                    Text(stringResource(R.string.library_edit_exercise))
                } }
            } else null,
        )
    }
}

@Composable
private fun ExerciseLibraryRow(
    exercise: Exercise,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Outlined.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exercise.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    muscleLabel(exercise.primaryMuscleGroup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (exercise.isCustom) Text(
                    stringResource(R.string.library_custom),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (exercise.isCustom) {
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.library_edit, exercise.name), Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.library_delete_description, exercise.name), Modifier.size(20.dp))
                }
            }
        }
    }
}

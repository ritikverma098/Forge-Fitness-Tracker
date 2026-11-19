package com.forge.workout.ui.screens.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.*
import com.forge.workout.preferences.*
import com.forge.workout.ui.components.*
import com.forge.workout.ui.screens.library.ExerciseThumbnail
import com.forge.workout.viewmodel.*
import com.forge.workout.utils.WorkoutPreviousPerformance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun WorkoutScreen(
    detail: WorkoutDetail?, history: List<WorkoutDetail>, preferences: UserPreferences,
    viewModel: WorkoutViewModel, editing: Boolean, onStart: () -> Unit, onRoutines: () -> Unit,
    onAddExercise: () -> Unit, onFinish: () -> Unit, onDiscard: () -> Unit, onBack: () -> Unit,
    onRestPreset: (Int) -> Unit, onLibrary: () -> Unit = {}, onEditorStateReset: () -> Unit = {},
    routines: List<RoutineDetail> = emptyList(), onRoutineStart: ((Long) -> Unit)? = null,
    onRecentWorkout: ((Long) -> Unit)? = null,
) {
    if(detail == null) {
        if (editing) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(stringResource(R.string.edit_workout), onBack = onBack)
                EmptyState(stringResource(R.string.detail_missing_title), stringResource(R.string.detail_missing_description), Icons.Rounded.FitnessCenter)
            }
        } else WorkoutOverview(history, routines, preferences.weightUnit, onStart, onRoutines, onLibrary, onRoutineStart, onRecentWorkout)
        return
    }
    val workout = detail.workout
    val acceptedIdentity by remember(viewModel, workout.id) { viewModel.workoutDrafts(workout.id) }.collectAsStateWithLifecycle()
    var name by rememberWorkoutEditorText(workout.id, acceptedIdentity?.name ?: workout.name, viewModel.editorSessionId, onEditorStateReset)
    var notes by rememberWorkoutEditorText(workout.id, acceptedIdentity?.notes ?: workout.notes, viewModel.editorSessionId, onEditorStateReset)
    var showFinish by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showNotes by rememberSaveable(workout.id) { mutableStateOf(notes.isNotBlank()) }
    var showRest by remember { mutableStateOf(false) }
    var showSessionOptions by remember { mutableStateOf(false) }
    val invalidInputs by viewModel.invalidInputs.collectAsStateWithLifecycle()
    val nameValid = name.trim().length in 1..100
    val notesValid = notes.length <= 5000
    val canSave = nameValid && notesValid && invalidInputs[workout.id].isNullOrEmpty()
    val exercises = remember(detail.exercises) { detail.sortedExercises }
    val previousPerformance by key(workout.id, workout.startTime) {
        produceState<Map<Long, WorkoutExerciseDetail>>(emptyMap(), history) {
            value = withContext(Dispatchers.Default) { WorkoutPreviousPerformance.build(workout, history) }
        }
    }
    val exerciseGroups = remember(exercises, previousPerformance) {
        exercises.map { item ->
            val previous = previousPerformance[item.exercise.id]
            ExerciseLogGroup(item, item.sortedSets, previous, previous?.sortedSets?.filter { it.completed }.orEmpty())
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if(!editing) WorkoutRestSection(workout.id, workout.restEndTime, viewModel)
    BoxWithConstraints(Modifier.weight(1f)) {
        // All tables have the same width. Resolve it once, outside lazy item measurement.
        val showPrevious = with(LocalDensity.current) {
            val tableWidthPx = (constraints.maxWidth - 2 * 20.dp.roundToPx() - 2 * 16.dp.roundToPx()).coerceAtLeast(0)
            tableWidthPx.toDp() / fontScale >= 260.dp
        }
    LazyColumn(Modifier.fillMaxSize().testTag("workout_exercise_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "workout_header", contentType = "workout_title") {
            ScreenHeader(stringResource(if(editing) R.string.edit_workout else R.string.workout), onBack = if(editing) onBack else null, actions = {
                if (!editing) {
                    FilledTonalIconButton(onClick = onLibrary) {
                        Icon(Icons.Rounded.GridView, stringResource(R.string.exercise_library))
                    }
                    FilledTonalIconButton(onClick = onRoutines) {
                        Icon(Icons.AutoMirrored.Rounded.ViewList, stringResource(R.string.routines))
                    }
                }
            })
        }
        item(key = "workout_identity", contentType = "workout_identity") {
            ForgePanel(Modifier.fillMaxWidth().testTag("workout_identity").padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ForgeIconTile(Icons.Rounded.Timer, highlighted = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(if(editing) R.string.session_edit else R.string.session_live),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            WorkoutElapsedTime(workout.startTime, workout.endTime)
                        }
                        IconButton(onClick = { showRest = true }) {
                            Icon(Icons.Rounded.Timer, stringResource(R.string.rest_preset))
                        }
                        if (!editing) Box {
                            IconButton(onClick = { showSessionOptions = true }) {
                                Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.workout_premium_session_options))
                            }
                            DropdownMenu(expanded = showSessionOptions, onDismissRequest = { showSessionOptions = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.add_exercise)) },
                                    leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                    onClick = { showSessionOptions = false; onAddExercise() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.discard_workout), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { showSessionOptions = false; showDiscard = true })
                            }
                        }
                    }
                    OutlinedTextField(name, onValueChange = {
                        name = it
                        if(it.trim().length in 1..100 && notes.length <= 5000) viewModel.rename(workout.id,it,notes)
                    }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.workout_name)) }, singleLine = true,
                        isError = !nameValid, supportingText = if (nameValid) null else ({ Text(stringResource(R.string.workout_name_limit)) }),
                        shape = MaterialTheme.shapes.medium)
                }
            }
        }
        item(key = "workout_muscles", contentType = "workout_muscles") {
            Column(Modifier.fillMaxWidth().testTag("workout_muscle_selector").padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.workout_muscle_groups), style = MaterialTheme.typography.labelLarge)
                LazyRow(Modifier.testTag("workout_muscle_groups"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MuscleGroup.entries, key = { it.name }) { group ->
                        FilterChip(selected = group in workout.muscleGroups,
                            onClick = { viewModel.toggleMuscleGroup(workout.id, group) },
                            label = { Text(muscleLabel(group)) },
                            leadingIcon = if (group in workout.muscleGroups) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
            }
        }
        item(key = "workout_notes", contentType = "workout_notes") {
            Column(Modifier.fillMaxWidth().testTag("workout_notes_section").padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { showNotes = !showNotes }) {
                    Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.workout_notes))
                    Icon(if (showNotes) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp))
                }
                AnimatedVisibility(showNotes) {
                    OutlinedTextField(notes, { notes = it; if(name.trim().length in 1..100 && it.length <= 5000) viewModel.rename(workout.id,name,it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.workout_notes)) }, isError = !notesValid,
                        supportingText = if (notesValid) null else ({ Text(stringResource(R.string.notes_limit)) }), shape = MaterialTheme.shapes.medium)
                }
            }
        }
        if(exercises.isEmpty()) item(key = "workout_empty", contentType = "empty") {
            EmptyState(stringResource(R.string.no_exercises_yet),stringResource(R.string.no_exercises_description),Icons.Rounded.FitnessCenter,onAddExercise,stringResource(R.string.add_exercise))
            Spacer(Modifier.height(16.dp))
        }
        exerciseGroups.forEachIndexed { index, group ->
            val exercise = group.item
            item(key = exercise.entry.id, contentType = "exercise_header") {
                // Room rebuilds graph objects on a set edit. Retain equal entries so unchanged headers can skip.
                val retainedItem = remember(exercise) { exercise }
                val retainedPrevious = remember(group.previous) { group.previous }
                ExerciseLogCard(retainedItem, retainedPrevious, preferences, viewModel, workout.id, index, exercises.size, showPrevious, onEditorStateReset)
            }
            items(group.sets, key = { "set_${it.id}" }, contentType = { "exercise_set" }) { set ->
                val retainedSet = remember(set) { set }
                val previous = group.previousSets.getOrNull(set.setNumber - 1) ?: group.previousSets.lastOrNull()
                val retainedPrevious = remember(previous) { previous }
                ExerciseSetRow(retainedSet, exercise.exercise.exerciseType, retainedPrevious, preferences, viewModel,
                    workout.id, editing, showPrevious, onEditorStateReset)
            }
            item(key = "exercise_${exercise.entry.id}_add_set", contentType = "exercise_footer") {
                ExerciseLogSurface(bottom = true, modifier = Modifier.padding(bottom = 16.dp)) {
                    FilledTonalButton(onClick = { viewModel.addSet(exercise.entry.id) },
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Icon(Icons.Rounded.Add,null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_set))
                    }
                }
            }
        }
        item(key = "workout_actions", contentType = "actions") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAddExercise, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Rounded.Add,null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_exercise)) }
                Button(onClick = { if(canSave) { if(editing) onFinish() else showFinish = true } }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Rounded.Check,null); Spacer(Modifier.width(8.dp)); Text(stringResource(if(editing) R.string.save_changes else R.string.finish_workout))
                }
                if(!canSave) Text(stringResource(R.string.invalid_saved_inputs),color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.saved_automatically),Modifier.align(Alignment.CenterHorizontally),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
    }
    if(showFinish) AlertDialog(onDismissRequest = { showFinish = false },
        icon = { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.finish_title)) },text = { Text(stringResource(R.string.finish_message)) },
        confirmButton = { Button(onClick = { showFinish = false; onFinish() }) { Text(stringResource(R.string.finish_workout)) } },
        dismissButton = { TextButton(onClick = { showFinish = false }) { Text(stringResource(R.string.cancel)) } })
    if(showDiscard) AlertDialog(onDismissRequest = { showDiscard = false },
        icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.discard_title)) },text = { Text(stringResource(R.string.discard_message)) },
        confirmButton = { TextButton(onClick = { showDiscard = false; onDiscard() }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(stringResource(R.string.cancel)) } })
    if(showRest) AlertDialog(onDismissRequest = { showRest = false },title = { Text(stringResource(R.string.rest_preset)) },text = {
        Column { PreferencesRepository.REST_PRESETS.forEach { seconds ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = preferences.restSeconds == seconds,onClick = { onRestPreset(seconds); showRest = false })
                Text(restLabel(seconds))
            }
        } }
    },confirmButton = { TextButton(onClick = { showRest = false }) { Text(stringResource(R.string.close)) } })
}

private data class ExerciseLogGroup(
    val item: WorkoutExerciseDetail,
    val sets: List<WorkoutSet>,
    val previous: WorkoutExerciseDetail?,
    val previousSets: List<WorkoutSet>,
)

/** Adjacent lazy items share a surface; only group boundaries have rounded corners and outer spacing. */
@Composable private fun ExerciseLogSurface(
    modifier: Modifier = Modifier, top: Boolean = false, bottom: Boolean = false, content: @Composable () -> Unit,
) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(topStart = if(top) 20.dp else 0.dp, topEnd = if(top) 20.dp else 0.dp,
            bottomStart = if(bottom) 20.dp else 0.dp, bottomEnd = if(bottom) 20.dp else 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
}

// Retain the header's name so composition traces can distinguish it from individually lazy set rows.
@Composable private fun ExerciseLogCard(item: WorkoutExerciseDetail, previous: WorkoutExerciseDetail?, preferences: UserPreferences, viewModel: WorkoutViewModel, workoutId: Long, index: Int, count: Int, showPrevious: Boolean, onEditorStateReset: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var showNotes by rememberSaveable(item.entry.id) { mutableStateOf(item.entry.notes.isNotBlank()) }
    val acceptedNotes by remember(viewModel, item.entry.id) { viewModel.exerciseNoteDrafts(item.entry.id) }.collectAsStateWithLifecycle()
    var notes by rememberWorkoutEditorText(item.entry.id, acceptedNotes ?: item.entry.notes, viewModel.editorSessionId, onEditorStateReset)
    var remove by remember { mutableStateOf(false) }
    LaunchedEffect(workoutId,item.entry.id,notes.length <= 5000) {
        viewModel.inputValidity(workoutId,"notes_${item.entry.id}",notes.length <= 5000)
    }
    ExerciseLogSurface(top = true) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExerciseThumbnail(item.exercise.primaryMuscleGroup)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.exercise.name,style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(muscleLabel(item.exercise.primaryMuscleGroup), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(exerciseTypeLabel(item.exercise.exerciseType), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert,stringResource(R.string.exercise_options)) }
                    DropdownMenu(menu,{ menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) },enabled = index > 0,onClick = { menu = false; viewModel.moveExercise(item.entry.id,-1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) },enabled = index < count - 1,onClick = { menu = false; viewModel.moveExercise(item.entry.id,1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.exercise_notes)) },onClick = { menu = false; showNotes = !showNotes })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_exercise)) },onClick = { menu = false; remove = true })
                    }
                }
            }
            if(item.exercise.notes.isNotBlank()) Text(item.exercise.notes,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(previous == null) Text(stringResource(R.string.no_previous),style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(showNotes) {
                OutlinedTextField(notes,{
                    notes = it
                    viewModel.inputValidity(workoutId,"notes_${item.entry.id}",it.length <= 5000)
                    if(it.length <= 5000) viewModel.exerciseNotes(item.entry.id,it)
                },Modifier.fillMaxWidth(),label = { Text(stringResource(R.string.exercise_notes)) },isError = notes.length > 5000,
                    supportingText = if (notes.length <= 5000) null else ({ Text(stringResource(R.string.notes_limit)) }), shape = MaterialTheme.shapes.medium)
            }
            if(item.sets.isNotEmpty()) SetTableHeader(item.exercise.exerciseType, preferences.weightUnit, showPrevious)
        }
    }
    if(remove) AlertDialog(onDismissRequest = { remove = false },title = { Text(stringResource(R.string.remove_exercise_title)) },text = { Text(stringResource(R.string.remove_exercise_message)) },
        confirmButton = { TextButton(onClick = { remove = false; viewModel.removeExercise(item.entry.id) }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun ExerciseSetRow(
    set: WorkoutSet, type: ExerciseType, previous: WorkoutSet?, preferences: UserPreferences,
    viewModel: WorkoutViewModel, workoutId: Long, editing: Boolean, showPrevious: Boolean,
    onEditorStateReset: () -> Unit,
) {
    val accepted by remember(viewModel, set.id) { viewModel.numericDrafts(set.id) }.collectAsStateWithLifecycle()
    val editorSet = remember(set, accepted) { set.copy(
        weight = accepted[SetField.WEIGHT] ?: set.weight,
        reps = accepted[SetField.REPS]?.toInt() ?: set.reps,
        duration = accepted[SetField.DURATION]?.toInt() ?: set.duration,
        distance = accepted[SetField.DISTANCE] ?: set.distance,
    ) }
    ExerciseLogSurface {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            SetRow(editorSet,type,previous,preferences.weightUnit,showPrevious,
                onValueChange = { field, value -> viewModel.setValue(set.id,field,value) },
                onValidityChange = { field, valid ->
                    val suffix = if(field == SetField.WEIGHT) "weight" else field.name
                    viewModel.inputValidity(workoutId,"set_${set.id}_$suffix",valid)
                },
                onTypeChange = { viewModel.setType(set.id,it) },
                onCompleteChange = { viewModel.completeSet(set.id,it,workoutId,preferences.restSeconds,editing) },
                onDelete = { viewModel.deleteSet(set.id) },
                editorSessionId = viewModel.editorSessionId, onEditorStateReset = onEditorStateReset)
        }
    }
}

fun setDescription(set: WorkoutSet,type: ExerciseType,unit: WeightUnit): String = when(type) {
    ExerciseType.WEIGHT_REPS -> "${weightFormat(set.weight,unit)} × ${set.reps}"
    ExerciseType.BODYWEIGHT,ExerciseType.REPS_ONLY -> set.reps.toString()
    ExerciseType.DURATION -> durationFormat(set.duration.toLong())
    ExerciseType.DISTANCE -> "${numberFormat(set.distance)} km"
}
@Composable fun restLabel(seconds: Int): String = when { seconds == 0 -> stringResource(R.string.timer_off); seconds >= 120 -> stringResource(R.string.minutes_value,seconds / 60); else -> pluralStringResource(R.plurals.seconds_value,seconds,seconds) }

package com.forge.workout.ui.screens.history

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.MetricCard
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.dateFormat
import com.forge.workout.ui.components.durationFormat
import com.forge.workout.ui.components.muscleLabel
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.ui.components.weightFormat
import com.forge.workout.ui.components.setNumberLabel
import com.forge.workout.utils.PersonalRecords
import com.forge.workout.utils.RecordType
import com.forge.workout.utils.WorkoutCalculations

@Composable
fun WorkoutDetailScreen(
    detail: WorkoutDetail?,
    history: List<WorkoutDetail>,
    weightUnit: WeightUnit,
    isSummary: Boolean = false,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onRepeat: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    confirmDelete: Boolean = true,
    extraContent: (@Composable () -> Unit)? = null,
) {
    var showDelete by rememberSaveable(detail?.workout?.id) { mutableStateOf(false) }
    if (detail == null) {
        Column {
            ScreenHeader(stringResource(R.string.detail_missing_title), onBack = onBack)
            EmptyState(stringResource(R.string.detail_missing_title), stringResource(R.string.detail_missing_description), Icons.Rounded.FitnessCenter)
        }
        return
    }
    val summary = remember(detail) { WorkoutCalculations.summary(detail) }
    val records = remember(detail, history, isSummary) {
        if (isSummary && detail.workout.endTime != null) PersonalRecords.detect(detail, history.filter { it.workout.endTime != null && it.workout.id != detail.workout.id })
        else emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (isSummary) stringResource(R.string.detail_summary_title) else detail.workout.name,
            subtitle = if (isSummary) stringResource(R.string.detail_summary_subtitle) else dateFormat(detail.workout.startTime),
            onBack = onBack,
            actions = {
                IconButton(onClick = { onEdit(detail.workout.id) }) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.detail_edit)) }
                IconButton(onClick = { if (confirmDelete) showDelete = true else onDelete(detail.workout.id) }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.detail_delete), tint = MaterialTheme.colorScheme.error)
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("workout_detail_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isSummary) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(detail.workout.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(dateFormat(detail.workout.startTime), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(durationFormat(summary.durationSeconds), stringResource(R.string.detail_duration), Modifier.weight(1f))
                    MetricCard(summary.exerciseCount.toString(), stringResource(R.string.detail_exercises), Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(summary.setCount.toString(), stringResource(R.string.detail_sets), Modifier.weight(1f))
                    MetricCard(summary.totalReps.toString(), stringResource(R.string.detail_reps), Modifier.weight(1f))
                }
            }
            item { MetricCard(weightFormat(summary.totalVolumeKg, weightUnit), stringResource(R.string.detail_volume), Modifier.fillMaxWidth()) }
            if (extraContent != null) item(key = "workout_extra", contentType = "extra") { extraContent() }
            if (isSummary && records.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.detail_prs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            records.forEach { record ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    val repetitionOnly = detail.exercises.firstOrNull { it.exercise.id == record.exerciseId }?.exercise?.exerciseType
                                        .let { it == ExerciseType.BODYWEIGHT || it == ExerciseType.REPS_ONLY }
                                    val title = when (record.type) {
                                        RecordType.HIGHEST_WEIGHT -> stringResource(R.string.detail_record_weight)
                                        RecordType.REPS_AT_WEIGHT -> stringResource(if (repetitionOnly) R.string.progress_max_reps else R.string.detail_record_reps)
                                        RecordType.WORKOUT_VOLUME -> stringResource(R.string.detail_record_volume)
                                    }
                                    Text(if (record.exerciseId != null) record.exerciseName else title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when (record.type) {
                                            RecordType.HIGHEST_WEIGHT -> {
                                                val best = detail.exercises.firstOrNull { it.exercise.id == record.exerciseId }?.sets
                                                    ?.filter { it.completed && kotlin.math.abs(it.weight - record.value) <= .000001 }
                                                    ?.maxByOrNull { it.reps }
                                                "$title · ${best?.let { setPerformance(it, ExerciseType.WEIGHT_REPS, weightUnit) } ?: weightFormat(record.value, weightUnit)}"
                                            }
                                            RecordType.REPS_AT_WEIGHT -> if (repetitionOnly) pluralStringResource(R.plurals.detail_reps_only, record.value.toInt(), record.value.toInt()) else pluralStringResource(R.plurals.detail_record_reps_value, record.value.toInt(), record.value.toInt(), weightFormat(record.weightKg ?: 0.0, weightUnit))
                                            RecordType.WORKOUT_VOLUME -> weightFormat(record.value, weightUnit)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (detail.workout.notes.isNotBlank()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.detail_notes), style = MaterialTheme.typography.titleMedium)
                            Text(detail.workout.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            items(detail.sortedExercises, key = { it.entry.id }) { entry -> ExerciseResultCard(entry, weightUnit) }
            if (detail.exercises.isEmpty()) {
                item { EmptyState(stringResource(R.string.detail_no_exercises), stringResource(R.string.detail_no_exercises_description), Icons.Rounded.FitnessCenter) }
            }
            item {
                Button(onClick = { onRepeat(detail.workout.id) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.detail_repeat)) }
                OutlinedButton(onClick = { onEdit(detail.workout.id) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.detail_edit)) }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_description)) },
            confirmButton = {
                TextButton(onClick = { showDelete = false; onDelete(detail.workout.id) }) { Text(stringResource(R.string.overview_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.overview_cancel)) } },
        )
    }
}

@Composable
private fun ExerciseResultCard(detail: WorkoutExerciseDetail, weightUnit: WeightUnit) {
    Card(modifier = Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(detail.exercise.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(muscleLabel(detail.exercise.primaryMuscleGroup), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (detail.entry.notes.isNotBlank()) { Text(detail.entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (detail.sets.isEmpty()) {
                Text(stringResource(R.string.detail_no_sets), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.detail_set_header), modifier = Modifier.weight(.55f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.detail_result_header), modifier = Modifier.weight(2f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.detail_complete_header), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                detail.sortedSets.forEach { set ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(setNumberLabel(set.setNumber, set.setType), modifier = Modifier.weight(.55f), style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.weight(2f)) {
                            Text(setPerformance(set, detail.exercise.exerciseType, weightUnit), style = MaterialTheme.typography.bodyMedium, color = if (set.completed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!set.completed) Text(stringResource(R.string.detail_incomplete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            if (set.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = stringResource(if (set.completed) R.string.detail_set_status_complete else R.string.detail_set_status_incomplete),
                            tint = if (set.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun setPerformance(set: WorkoutSet, type: ExerciseType, unit: WeightUnit): String = when (type) {
    ExerciseType.WEIGHT_REPS -> pluralStringResource(R.plurals.detail_weight_reps, set.reps, weightFormat(set.weight, unit), set.reps)
    ExerciseType.BODYWEIGHT -> if (set.weight > 0) pluralStringResource(R.plurals.detail_weight_reps, set.reps, weightFormat(set.weight, unit), set.reps) else pluralStringResource(R.plurals.detail_bodyweight_reps, set.reps, set.reps)
    ExerciseType.REPS_ONLY -> pluralStringResource(R.plurals.detail_reps_only, set.reps, set.reps)
    ExerciseType.DURATION -> durationFormat(set.duration.toLong())
    ExerciseType.DISTANCE -> if (set.duration > 0) stringResource(R.string.detail_distance_duration, numberFormat(set.distance), durationFormat(set.duration.toLong())) else stringResource(R.string.detail_distance, numberFormat(set.distance))
}

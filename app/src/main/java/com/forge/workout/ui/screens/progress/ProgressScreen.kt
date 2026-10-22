package com.forge.workout.ui.screens.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.MetricCard
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.dateFormat
import com.forge.workout.ui.components.durationFormat
import com.forge.workout.ui.components.muscleLabel
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.ui.components.weightFormat
import com.forge.workout.ui.screens.history.setPerformance
import com.forge.workout.utils.ProgressMetric
import com.forge.workout.utils.ProgressRange
import com.forge.workout.utils.ProgressStats
import com.forge.workout.viewmodel.ProgressUiState
import com.forge.workout.viewmodel.ProgressViewModel

@Composable
fun ProgressScreen(
    exercises: List<Exercise>,
    workouts: List<WorkoutDetail>,
    weightUnit: WeightUnit,
    viewModel: ProgressViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showPicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(exercises, workouts, weightUnit) { viewModel.setData(exercises, workouts, weightUnit) }
    val selected = state.selected
    val recent = remember(state.stats.sessions) { state.stats.sessions.takeLast(6).asReversed() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.progress_title), stringResource(R.string.progress_subtitle))
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("progress_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (selected == null) {
                item {
                    EmptyState(stringResource(R.string.progress_library_empty_title),
                        stringResource(R.string.progress_library_empty_description), Icons.AutoMirrored.Rounded.TrendingUp)
                }
            } else {
                item {
                    Card(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth().testTag("progress_exercise_picker"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(stringResource(R.string.progress_choose_exercise), style = MaterialTheme.typography.labelLarge)
                                Text(exerciseLabel(selected), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(muscleLabel(selected.primaryMuscleGroup), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.ExpandMore, contentDescription = null)
                        }
                    }
                }
                item { ProgressSelectors(state, viewModel::selectMetric, viewModel::selectRange) }
                if (state.stats.sessions.isEmpty()) {
                    item {
                        val allTime = state.range == ProgressRange.ALL
                        EmptyState(
                            stringResource(if (allTime) R.string.progress_empty_title else R.string.progress_range_empty_title),
                            stringResource(if (allTime) R.string.progress_empty_description else R.string.progress_range_empty_description),
                            Icons.AutoMirrored.Rounded.TrendingUp,
                        )
                    }
                } else {
                    item { ProgressStatistics(selected, state.stats, state.weightUnit) }
                    item {
                        ProgressChart(
                            chartTitle(state.metric), state.chartPoints, chartUnit(state.metric, state.weightUnit),
                            weekly = state.metric == ProgressMetric.FREQUENCY,
                        )
                    }
                    item {
                        Text(stringResource(R.string.progress_recent_sessions),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    items(recent, key = { it.workoutId }) { session ->
                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(dateFormat(session.date), style = MaterialTheme.typography.titleSmall)
                                Text(pluralStringResource(R.plurals.progress_logged_sets, session.sets.size, session.sets.size),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(setPerformance(session.bestSet, selected.exerciseType, state.weightUnit),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showPicker) {
        ExercisePicker(state.exercises, onDismiss = { showPicker = false }, onSelect = {
            viewModel.selectExercise(it)
            showPicker = false
        })
    }
}

@Composable
private fun ProgressSelectors(
    state: ProgressUiState,
    onMetric: (ProgressMetric) -> Unit,
    onRange: (ProgressRange) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.progress_metric_label), style = MaterialTheme.typography.labelLarge)
        LazyRow(Modifier.fillMaxWidth().testTag("progress_metric_filters"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.availableMetrics, key = { it.name }) { metric ->
                FilterChip(
                    selected = state.metric == metric,
                    onClick = { onMetric(metric) },
                    label = { Text(metricLabel(metric)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("progress_metric_${metric.name}"),
                )
            }
        }
        Text(stringResource(R.string.progress_range_label), style = MaterialTheme.typography.labelLarge)
        LazyRow(Modifier.fillMaxWidth().testTag("progress_range_filters"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ProgressRange.entries, key = { it.name }) { range ->
                val description = rangeDescription(range)
                FilterChip(
                    selected = state.range == range,
                    onClick = { onRange(range) },
                    label = { Text(rangeLabel(range)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("progress_range_${range.name}")
                        .semantics { contentDescription = description },
                )
            }
        }
    }
}

@Composable
private fun ProgressStatistics(exercise: Exercise, stats: ProgressStats, unit: WeightUnit) {
    val weighted = exercise.exerciseType == ExerciseType.WEIGHT_REPS
    val primaryValue = when (exercise.exerciseType) {
        ExerciseType.DURATION -> durationFormat(stats.bestDurationSeconds.toLong())
        ExerciseType.DISTANCE -> stringResource(R.string.detail_distance, numberFormat(stats.bestDistanceKm))
        ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY -> stats.maxReps.toString()
        ExerciseType.WEIGHT_REPS -> weightFormat(stats.bestWeightKg, unit)
    }
    val primaryLabel = stringResource(when (exercise.exerciseType) {
        ExerciseType.DURATION -> R.string.progress_longest_duration
        ExerciseType.DISTANCE -> R.string.progress_longest_distance
        ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY -> R.string.progress_max_reps
        ExerciseType.WEIGHT_REPS -> R.string.progress_best_weight
    })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(primaryValue, primaryLabel, Modifier.weight(1f))
            MetricCard(stats.workoutCount.toString(), stringResource(R.string.progress_frequency), Modifier.weight(1f))
        }
        if (weighted) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard(stats.maxReps.toString(), stringResource(R.string.progress_max_reps), Modifier.weight(1f))
                MetricCard(weightFormat(stats.estimatedOneRepMaxKg, unit), stringResource(R.string.progress_estimated_1rm), Modifier.weight(1f))
            }
            Text(stringResource(R.string.progress_epley_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MetricCard(
            stats.bestSet?.let { setPerformance(it, exercise.exerciseType, unit) } ?: stringResource(R.string.progress_no_value),
            stringResource(R.string.progress_best_set),
            Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun metricLabel(metric: ProgressMetric): String = stringResource(when (metric) {
    ProgressMetric.BEST_WEIGHT -> R.string.progress_metric_best_weight
    ProgressMetric.ESTIMATED_1RM -> R.string.progress_metric_estimated_1rm
    ProgressMetric.BEST_REPS -> R.string.progress_metric_best_reps
    ProgressMetric.VOLUME -> R.string.progress_metric_volume
    ProgressMetric.FREQUENCY -> R.string.progress_metric_frequency
    ProgressMetric.DURATION -> R.string.progress_metric_duration
    ProgressMetric.DISTANCE -> R.string.progress_metric_distance
})

@Composable
private fun rangeLabel(range: ProgressRange): String = stringResource(when (range) {
    ProgressRange.ONE_MONTH -> R.string.progress_range_one_month
    ProgressRange.THREE_MONTHS -> R.string.progress_range_three_months
    ProgressRange.SIX_MONTHS -> R.string.progress_range_six_months
    ProgressRange.ONE_YEAR -> R.string.progress_range_one_year
    ProgressRange.ALL -> R.string.progress_range_all
})

@Composable
private fun rangeDescription(range: ProgressRange): String = stringResource(when (range) {
    ProgressRange.ONE_MONTH -> R.string.progress_range_one_month_description
    ProgressRange.THREE_MONTHS -> R.string.progress_range_three_months_description
    ProgressRange.SIX_MONTHS -> R.string.progress_range_six_months_description
    ProgressRange.ONE_YEAR -> R.string.progress_range_one_year_description
    ProgressRange.ALL -> R.string.progress_range_all_description
})

@Composable
private fun chartTitle(metric: ProgressMetric): String = stringResource(when (metric) {
    ProgressMetric.BEST_WEIGHT -> R.string.progress_weight_chart
    ProgressMetric.ESTIMATED_1RM -> R.string.progress_estimated_chart
    ProgressMetric.BEST_REPS -> R.string.progress_reps_chart
    ProgressMetric.VOLUME -> R.string.progress_volume_chart
    ProgressMetric.FREQUENCY -> R.string.progress_frequency_chart
    ProgressMetric.DURATION -> R.string.progress_duration_chart
    ProgressMetric.DISTANCE -> R.string.progress_distance_chart
})

@Composable
private fun chartUnit(metric: ProgressMetric, unit: WeightUnit): String = when (metric) {
    ProgressMetric.BEST_WEIGHT, ProgressMetric.ESTIMATED_1RM, ProgressMetric.VOLUME -> unit.symbol
    ProgressMetric.BEST_REPS -> stringResource(R.string.progress_reps_unit)
    ProgressMetric.FREQUENCY -> stringResource(R.string.progress_sessions_per_week)
    ProgressMetric.DURATION -> stringResource(R.string.progress_seconds_unit)
    ProgressMetric.DISTANCE -> stringResource(R.string.progress_km_unit)
}

@Composable
private fun exerciseLabel(exercise: Exercise): String = if (exercise.archived) {
    stringResource(R.string.progress_archived_exercise, exercise.name)
} else exercise.name

@Composable
private fun ExercisePicker(exercises: List<Exercise>, onDismiss: () -> Unit, onSelect: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(exercises, query) { exercises.filter { it.name.contains(query.trim(), ignoreCase = true) } }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.progress_choose_exercise), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("progress_exercise_search"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.progress_search_exercise)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 400.dp).testTag("progress_exercise_options"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(stringResource(R.string.progress_no_matching_exercises), modifier = Modifier.padding(vertical = 16.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(filtered, key = { it.id }) { exercise ->
                        Card(onClick = { onSelect(exercise.id) }, modifier = Modifier.fillMaxWidth().testTag("progress_exercise_${exercise.id}"),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(exerciseLabel(exercise), style = MaterialTheme.typography.titleSmall)
                                Text(muscleLabel(exercise.primaryMuscleGroup), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.progress_close)) }
            }
        }
    }
}

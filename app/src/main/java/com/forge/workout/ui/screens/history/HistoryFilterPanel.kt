package com.forge.workout.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.ui.components.muscleLabel
import com.forge.workout.utils.HistoryFilters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryFilterPanel(
    filters: HistoryFilters,
    exercises: List<Exercise>,
    onExercise: (Long?) -> Unit,
    onMuscle: (MuscleGroup?) -> Unit,
    onDateRange: (LocalDate?, LocalDate?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showDates by rememberSaveable { mutableStateOf(false) }
    val selectedExercise = exercises.firstOrNull { it.id == filters.exerciseId }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val dates = when {
        filters.fromDate != null && filters.toDate != null -> stringResource(R.string.history_filter_date_range,
            filters.fromDate.format(dateFormatter), filters.toDate.format(dateFormatter))
        filters.fromDate != null -> stringResource(R.string.history_filter_from_date, filters.fromDate.format(dateFormatter))
        filters.toDate != null -> stringResource(R.string.history_filter_to_date, filters.toDate.format(dateFormatter))
        else -> stringResource(R.string.history_filter_all_dates)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().testTag("history_filter_panel").verticalScroll(rememberScrollState())
                .imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.history_filter_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.history_filter_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HistorySelectionField(
                label = stringResource(R.string.history_filter_exercise),
                valueLabel = selectedExercise?.let { historyExerciseLabel(it) } ?: stringResource(
                    if(filters.exerciseId != null) R.string.history_filter_unavailable_exercise else R.string.history_filter_any_exercise),
                options = listOf<Exercise?>(null) + exercises,
                optionLabel = { it?.let { exercise -> historyExerciseLabel(exercise) } ?: stringResource(R.string.history_filter_any_exercise) },
                optionTag = { "history_exercise_${it?.id ?: "all"}" },
                onSelected = { onExercise(it?.id) },
                modifier = Modifier.testTag("history_filter_exercise"),
            )
            HistorySelectionField(
                label = stringResource(R.string.history_filter_muscle),
                valueLabel = filters.muscleGroup?.let { muscleLabel(it) } ?: stringResource(R.string.history_filter_any_muscle),
                options = listOf<MuscleGroup?>(null) + MuscleGroup.entries,
                optionLabel = { it?.let { group -> muscleLabel(group) } ?: stringResource(R.string.history_filter_any_muscle) },
                optionTag = { "history_muscle_${it?.name ?: "all"}" },
                onSelected = onMuscle,
                modifier = Modifier.testTag("history_filter_muscle"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.history_filter_dates), style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { showDates = true }, modifier = Modifier.fillMaxWidth().testTag("history_filter_dates")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.DateRange, null)
                        Text(dates)
                    }
                }
                if (filters.fromDate != null || filters.toDate != null) {
                    TextButton(onClick = { onDateRange(null, null) }, modifier = Modifier.testTag("history_clear_dates")) {
                        Text(stringResource(R.string.history_filter_clear_dates))
                    }
                }
            }
            if (filters.hasFilters) {
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth().testTag("history_panel_clear_filters")) {
                    Text(stringResource(R.string.history_filter_clear_all))
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("history_filters_done")) {
                Text(stringResource(R.string.history_filter_done))
            }
        }
    }
    if (showDates) HistoryDateRangeDialog(filters, onDateRange, onDismiss = { showDates = false })
}

@Composable
private fun historyExerciseLabel(exercise: Exercise): String = if (exercise.archived) {
    stringResource(R.string.history_filter_archived, exercise.name)
} else exercise.name

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> HistorySelectionField(
    label: String,
    valueLabel: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    optionTag: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = valueLabel, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelected(option) }, modifier = Modifier.testTag(optionTag(option)),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDateRangeDialog(
    filters: HistoryFilters,
    onSelected: (LocalDate?, LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Material date picker timestamps represent calendar dates at midnight UTC.
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (filters.fromDate ?: filters.toDate)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = filters.toDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    val from = picker.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val to = picker.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val valid = from != null && to != null && from <= to
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (valid) { onSelected(from, to); onDismiss() } }, enabled = valid,
                modifier = Modifier.testTag("history_apply_dates")) { Text(stringResource(R.string.history_filter_apply_dates)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_filter_cancel)) }
        },
    ) {
        DateRangePicker(
            state = picker,
            title = { Text(stringResource(R.string.history_filter_choose_dates), modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).testTag("history_date_picker"),
        )
    }
}

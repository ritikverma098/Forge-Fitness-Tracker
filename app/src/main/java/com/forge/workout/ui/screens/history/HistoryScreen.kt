package com.forge.workout.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.WorkoutCard
import com.forge.workout.viewmodel.HistoryViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(workouts: List<WorkoutDetail>, weightUnit: WeightUnit, viewModel: HistoryViewModel, onOpen: (Long) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.selectedFilters.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(workouts) { viewModel.setWorkouts(workouts) }
    val today = LocalDate.now()
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.history_title), stringResource(R.string.history_subtitle), actions = {
            BadgedBox(badge = { if (filters.hasFilters) Badge() }) {
                IconButton(onClick = { focusManager.clearFocus(); showFilters = true }, modifier = Modifier.testTag("history_open_filters")) {
                    Icon(Icons.Rounded.FilterList, stringResource(R.string.history_filter_open))
                }
            }
        })
        OutlinedTextField(
            value = filters.name,
            onValueChange = viewModel::setName,
            placeholder = { Text(stringResource(R.string.history_filter_search)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (filters.name.isNotEmpty()) IconButton(onClick = { viewModel.setName("") }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.history_filter_clear_search))
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("history_name_search"),
        )
        if (filters.hasFilters) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_filter_active), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { focusManager.clearFocus(); viewModel.clearFilters() }, modifier = Modifier.testTag("history_clear_filters")) {
                    Text(stringResource(R.string.history_filter_clear_all))
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("history_workout_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.hasHistory) {
                item {
                    EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_description), Icons.Rounded.History)
                }
            } else if (state.grouped.isEmpty()) {
                item {
                    EmptyState(stringResource(R.string.history_filter_no_matches), stringResource(R.string.history_filter_no_matches_body),
                        Icons.Rounded.Search, onAction = viewModel::clearFilters, actionLabel = stringResource(R.string.history_filter_clear_all))
                }
            }
            state.grouped.forEach { (date, dayWorkouts) ->
                item(key = "day-$date") {
                    Text(
                        when (date) {
                            today -> stringResource(R.string.history_today)
                            today.minusDays(1) -> stringResource(R.string.history_yesterday)
                            else -> date.format(dateFormatter)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(dayWorkouts, key = { it.workout.id }) { detail ->
                    WorkoutCard(detail, weightUnit) { onOpen(detail.workout.id) }
                }
            }
        }
    }
    if (showFilters) {
        HistoryFilterPanel(
            filters = filters, exercises = state.exercises,
            onExercise = viewModel::selectExercise, onMuscle = viewModel::selectMuscle,
            onDateRange = viewModel::setDateRange, onClear = viewModel::clearFilters,
            onDismiss = { showFilters = false },
        )
    }
}

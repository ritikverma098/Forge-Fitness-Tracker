package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.DiaryMath
import com.forge.workout.nutrition.model.NutrientTotal
import com.forge.workout.nutrition.model.NutritionHistoryDay
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.viewmodel.NutritionHistoryViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun NutritionHistoryScreen(viewModel: NutritionHistoryViewModel, onBack: () -> Unit, onDay: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRange by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(29).toString()) }
    var to by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.nutrition_history_title), rangeLabel(state.fromDay, state.toDay), onBack,
            actions = {
                IconButton(onClick = {
                    from = state.fromDay.takeUnless { it.isBlank() || it == "0001-01-01" } ?: LocalDate.now().minusDays(29).toString()
                    to = state.toDay.takeUnless { it.isBlank() || it == "9999-12-31" } ?: LocalDate.now().toString()
                    invalid = false; showRange = true
                }, modifier = Modifier.testTag("nutrition_history_range")) {
                    Icon(Icons.Rounded.DateRange, stringResource(R.string.nutrition_history_range))
                }
            })
        LazyColumn(Modifier.weight(1f).testTag("nutrition_history_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.error) item(key = "error", contentType = "status") {
                EmptyState(stringResource(R.string.nutrition_history_title), stringResource(R.string.nutrition_history_error),
                    Icons.Rounded.Restaurant, viewModel::reload, stringResource(R.string.nutrition_retry))
            }
            if (!state.loading && !state.error && state.days.isEmpty()) item(key = "empty", contentType = "status") {
                EmptyState(stringResource(R.string.nutrition_history_empty), stringResource(R.string.nutrition_history_empty_description), Icons.Rounded.Restaurant)
            }
            items(state.days, key = { "day_${it.day}" }, contentType = { "day" }) { day -> HistoryDayCard(day) { onDay(day.day) } }
            if (state.loading) item(key = "loading", contentType = "status") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (state.hasMore && !state.loading && !state.error) item(key = "more", contentType = "action") {
                OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nutrition_history_more")) {
                    Text(stringResource(R.string.nutrition_history_more))
                }
            }
            if (state.limitReached) item(key = "limit", contentType = "status") {
                Text(stringResource(R.string.nutrition_history_limit), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showRange) AlertDialog(onDismissRequest = { showRange = false },
        title = { Text(stringResource(R.string.nutrition_history_range)) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.nutrition_history_range_description))
                Text(stringResource(R.string.nutrition_history_from), style = MaterialTheme.typography.titleSmall)
                NutritionDaySelector(from, { from = it; invalid = false }, Modifier.testTag("nutrition_history_from"))
                Text(stringResource(R.string.nutrition_history_to), style = MaterialTheme.typography.titleSmall)
                NutritionDaySelector(to, { to = it; invalid = false }, Modifier.testTag("nutrition_history_to"))
                if (invalid) Text(stringResource(R.string.nutrition_history_range_error), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(onClick = { if (viewModel.setRange(from, to)) showRange = false else invalid = true },
                modifier = Modifier.testTag("nutrition_history_apply")) { Text(stringResource(R.string.nutrition_history_apply)) }
        }, dismissButton = {
            Row {
                TextButton(onClick = { viewModel.setRange("", ""); showRange = false }, modifier = Modifier.testTag("nutrition_history_clear")) {
                    Text(stringResource(R.string.nutrition_history_clear))
                }
                TextButton(onClick = { showRange = false }) { Text(stringResource(R.string.cancel)) }
            }
        })
}

@Composable
private fun HistoryDayCard(day: NutritionHistoryDay, onOpen: () -> Unit) {
    val goal = day.goal?.kcal
    val remaining = DiaryMath.remaining(goal, day.totals.kcal)
    val missing = !day.totals.kcal.complete || !day.totals.protein.complete || !day.totals.carbs.complete || !day.totals.fat.complete
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("nutrition_history_day_${day.day}"),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(historyDate(day.day), style = MaterialTheme.typography.titleLarge)
            Text(pluralStringResource(R.plurals.nutrition_history_entries, day.entryCount, day.entryCount),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(historyTotal(day.totals.kcal, day.entryCount, true), Modifier.testTag("nutrition_history_kcal_${day.day}"),
                style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(if (goal == null) stringResource(R.string.nutrition_goal_unset)
                else stringResource(R.string.nutrition_history_goal, stringResource(R.string.nutrition_kcal, numberFormat(goal))),
                style = MaterialTheme.typography.bodySmall)
            if (goal != null) Text(when {
                remaining == null -> stringResource(R.string.nutrition_remaining_unknown)
                remaining < 0 -> stringResource(R.string.nutrition_history_over, stringResource(R.string.nutrition_kcal, numberFormat(-remaining)))
                remaining > 0 -> stringResource(R.string.nutrition_history_remaining, stringResource(R.string.nutrition_kcal, numberFormat(remaining)))
                else -> stringResource(R.string.nutrition_history_on_goal)
            }, Modifier.testTag("nutrition_history_goal_status_${day.day}"), style = MaterialTheme.typography.bodySmall,
                color = if (remaining != null && remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            HistoryMacro(stringResource(R.string.food_protein), day.totals.protein, day.entryCount, "nutrition_history_protein_${day.day}")
            HistoryMacro(stringResource(R.string.food_carbs), day.totals.carbs, day.entryCount, "nutrition_history_carbs_${day.day}")
            HistoryMacro(stringResource(R.string.food_fat), day.totals.fat, day.entryCount, "nutrition_history_fat_${day.day}")
            if (missing) Text(stringResource(R.string.nutrition_unknown_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryMacro(label: String, total: NutrientTotal, count: Int, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(historyTotal(total, count, false), Modifier.weight(1f).testTag(tag), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun historyTotal(total: NutrientTotal, count: Int, energy: Boolean): String {
    if (count > 0 && total.missingCount == count) return stringResource(R.string.food_nutrient_unavailable)
    val value = stringResource(if (energy) R.string.nutrition_kcal else R.string.nutrition_grams, numberFormat(total.knownValue))
    return if (total.complete) value else stringResource(R.string.nutrition_lower_bound, value)
}

@Composable
private fun rangeLabel(from: String, to: String): String = when {
    (from.isBlank() || from == "0001-01-01") && (to.isBlank() || to == "9999-12-31") -> stringResource(R.string.nutrition_history_all_dates)
    from.isBlank() || from == "0001-01-01" -> stringResource(R.string.nutrition_history_to_date, historyDate(to))
    to.isBlank() || to == "9999-12-31" -> stringResource(R.string.nutrition_history_from_date, historyDate(from))
    else -> stringResource(R.string.nutrition_history_between, historyDate(from), historyDate(to))
}

private fun historyDate(day: String): String = LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

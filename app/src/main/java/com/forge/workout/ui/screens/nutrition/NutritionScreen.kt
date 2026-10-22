package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.*
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.viewmodel.NutritionDashboardViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun NutritionScreen(
    viewModel: NutritionDashboardViewModel,
    onDay: (String) -> Unit,
    onCatalog: () -> Unit,
    onAddFood: (MealType) -> Unit,
    onEntry: (Long) -> Unit,
    onGoals: () -> Unit,
    onRepeat: (List<Long>, String, MealType) -> Unit,
    onDelete: (Long) -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    onBodyWeights: (() -> Unit)? = null,
    onEnergySettings: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback, feedbackToken) { feedback?.let { snackbar.showSnackbar(it); onFeedbackHandled() } }
    val meals = remember(state.entries) { state.entries.groupBy { it.meal } }
    val workoutCalories = remember(state.workoutEnergy) { state.workoutEnergy.sumOf { it.effectiveKcal } }
    var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var repeatIds by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var repeatDay by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var repeatMeal by rememberSaveable { mutableStateOf(MealType.BREAKFAST.name) }
    fun repeat(ids: List<Long>, meal: MealType) {
        repeatIds = ids; repeatDay = LocalDate.now().toString(); repeatMeal = meal.name
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            ScreenHeader(stringResource(R.string.nutrition), stringResource(R.string.nutrition_daily_subtitle), actions = {
                IconButton(onClick = { onAddFood(MealType.BREAKFAST) }, enabled = !busy) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.add_food_title))
                }
                IconButton(onClick = onGoals, modifier = Modifier.testTag("nutrition_goals")) {
                    Icon(Icons.Rounded.Flag, stringResource(R.string.nutrition_goals))
                }
                IconButton(onClick = onCatalog) { Icon(Icons.AutoMirrored.Rounded.MenuBook, stringResource(R.string.catalog_title)) }
            })
            LazyColumn(Modifier.weight(1f).testTag("nutrition_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "day", contentType = "controls") {
                    Column {
                        NutritionDaySelector(state.day, onDay, Modifier.testTag("nutrition_day"))
                        if (onHistory != null) TextButton(onClick = onHistory,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nutrition_history")) {
                            Text(stringResource(R.string.nutrition_history_title))
                        }
                    }
                }
                when {
                    state.loading -> item(key = "loading", contentType = "status") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    state.error -> item(key = "error", contentType = "status") {
                        EmptyState(stringResource(R.string.nutrition_error_title), stringResource(R.string.nutrition_error_description),
                            Icons.Rounded.Restaurant, viewModel::reload, stringResource(R.string.nutrition_retry))
                    }
                    else -> {
                        item(key = "energy", contentType = "summary") {
                            EnergySummary(state.totals.kcal, state.goal?.kcal, if (state.includeWorkoutCalories) workoutCalories else 0.0)
                        }
                        item(key = "workout_energy", contentType = "summary") {
                            NutritionCard(Modifier.testTag("nutrition_workout_energy")) {
                                Text(stringResource(R.string.energy_title), style = MaterialTheme.typography.titleMedium)
                                if (state.workoutEnergy.isEmpty()) Text(stringResource(R.string.energy_empty_day), style = MaterialTheme.typography.bodyMedium)
                                else Text(stringResource(if (state.workoutEnergy.any { it.manualKcal == null }) R.string.energy_kcal_estimate else R.string.energy_kcal_manual,
                                    numberFormat(workoutCalories)), Modifier.testTag("nutrition_workout_kcal"),
                                    style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(if (state.includeWorkoutCalories) R.string.energy_in_budget else R.string.energy_not_in_budget),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (onBodyWeights != null) TextButton(onClick = onBodyWeights, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("nutrition_body_weights")) {
                                        Text(stringResource(R.string.energy_body_weights))
                                    }
                                    if (onEnergySettings != null) TextButton(onClick = onEnergySettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("nutrition_energy_settings")) {
                                        Text(stringResource(R.string.energy_settings))
                                    }
                                }
                            }
                        }
                        item(key = "macros", contentType = "summary") {
                            NutritionCard {
                                NutrientProgress(stringResource(R.string.food_protein), state.totals.protein, state.goal?.protein, "nutrition_protein")
                                NutrientProgress(stringResource(R.string.food_carbs), state.totals.carbs, state.goal?.carbs, "nutrition_carbs")
                                NutrientProgress(stringResource(R.string.food_fat), state.totals.fat, state.goal?.fat, "nutrition_fat")
                            }
                        }
                        if (state.entries.isEmpty()) item(key = "empty", contentType = "status") {
                            EmptyState(stringResource(R.string.nutrition_empty), stringResource(R.string.nutrition_empty_description), Icons.Rounded.Restaurant)
                        }
                        MealType.entries.forEach { meal ->
                            val rows = meals[meal].orEmpty()
                            val expanded = meal.name !in collapsed
                            item(key = "meal_${meal.name}", contentType = "heading") {
                                val kcal = remember(rows) { DiaryMath.totals(rows).kcal }
                                MealHeading(meal, kcal, expanded, rows.isNotEmpty(), busy,
                                    onExpand = { collapsed = if (expanded) collapsed + meal.name else collapsed - meal.name },
                                    onAdd = { onAddFood(meal) }, onRepeat = { repeat(rows.map { it.id }, meal) })
                            }
                            if (expanded) {
                                if (rows.isEmpty()) item(key = "empty_${meal.name}", contentType = "status") {
                                    Text(stringResource(R.string.nutrition_meal_empty), Modifier.padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                items(rows, key = { "entry_${it.id}" }, contentType = { "entry" }) { entry ->
                                    DiaryRow(entry, busy, { onEntry(entry.id) }, { repeat(listOf(entry.id), entry.meal) }, { deleteId = entry.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { if (!busy) deleteId = null },
        title = { Text(stringResource(R.string.nutrition_delete_title)) }, text = { Text(stringResource(R.string.nutrition_delete_description)) },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }, enabled = !busy,
            modifier = Modifier.testTag("nutrition_delete_confirm")) { Text(stringResource(R.string.nutrition_delete_entry)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
    if (repeatIds.isNotEmpty()) AlertDialog(onDismissRequest = { if (!busy) repeatIds = emptyList() },
        title = { Text(stringResource(R.string.nutrition_repeat_title)) }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.nutrition_repeat_description))
                DayMealSelector(repeatDay, MealType.valueOf(repeatMeal), { repeatDay = it }, { repeatMeal = it.name })
            }
        }, confirmButton = { TextButton(onClick = { onRepeat(repeatIds, repeatDay, MealType.valueOf(repeatMeal)); repeatIds = emptyList() }, enabled = !busy,
            modifier = Modifier.testTag("nutrition_repeat_confirm")) { Text(stringResource(R.string.nutrition_repeat_confirm)) } },
        dismissButton = { TextButton(onClick = { repeatIds = emptyList() }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun NutritionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun EnergySummary(total: NutrientTotal, goal: Double?, workoutCalories: Double = 0.0) {
    val remaining = DiaryMath.remaining(goal, total, workoutCalories)
    NutritionCard(Modifier.testTag("nutrition_energy_card")) {
        Text(stringResource(R.string.nutrition_eaten), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(totalLabel(total, true), Modifier.testTag("nutrition_eaten"), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nutrition_goal), style = MaterialTheme.typography.labelMedium)
                Text(goal?.let { stringResource(R.string.nutrition_kcal, numberFormat(it)) } ?: stringResource(R.string.nutrition_goal_unset),
                    Modifier.testTag("nutrition_goal"), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (remaining != null && remaining < 0) R.string.nutrition_over_goal else R.string.nutrition_remaining), style = MaterialTheme.typography.labelMedium)
                Text(when {
                    goal == null -> stringResource(R.string.nutrition_goal_unset)
                    remaining == null -> stringResource(R.string.nutrition_remaining_unknown)
                    else -> stringResource(R.string.nutrition_kcal, numberFormat(kotlin.math.abs(remaining)))
                }, Modifier.testTag("nutrition_remaining"), style = MaterialTheme.typography.titleMedium)
            }
        }
        GoalBar(total, goal?.plus(workoutCalories))
        if (!total.complete) MissingValues(total)
    }
}

@Composable
private fun NutrientProgress(label: String, total: NutrientTotal, goal: Double?, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(if (goal == null) totalLabel(total, false) else stringResource(R.string.nutrition_macro_target,
            totalLabel(total, false), stringResource(R.string.nutrition_grams, numberFormat(goal))), Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
        GoalBar(total, goal)
        if (!total.complete) MissingValues(total)
    }
}

@Composable
private fun GoalBar(total: NutrientTotal, goal: Double?) {
    if (goal != null && goal > 0) LinearProgressIndicator(progress = { (total.knownValue / goal).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
}

@Composable
private fun totalLabel(total: NutrientTotal, energy: Boolean): String {
    val measured = stringResource(if (energy) R.string.nutrition_kcal else R.string.nutrition_grams, numberFormat(total.knownValue))
    return if (total.complete) measured else stringResource(R.string.nutrition_lower_bound, measured)
}

@Composable
private fun MissingValues(total: NutrientTotal) {
    Text(pluralStringResource(R.plurals.nutrition_missing_foods, total.missingCount, total.missingCount),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.nutrition_unknown_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MealHeading(meal: MealType, kcal: NutrientTotal, expanded: Boolean, hasRows: Boolean, busy: Boolean,
    onExpand: () -> Unit, onAdd: () -> Unit, onRepeat: () -> Unit) {
    val label = mealLabel(meal)
    val action = stringResource(if (expanded) R.string.nutrition_collapse_meal else R.string.nutrition_expand_meal, label)
    Row(Modifier.fillMaxWidth().testTag("meal_${meal.name}"), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onExpand, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = action }) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Text(totalLabel(kcal, true), Modifier.testTag("nutrition_meal_kcal_${meal.name}"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (hasRows) IconButton(onClick = onRepeat, enabled = !busy, modifier = Modifier.testTag("nutrition_repeat_meal_${meal.name}")) {
            Icon(Icons.Rounded.Repeat, stringResource(R.string.nutrition_repeat_meal))
        }
        IconButton(onClick = onAdd, enabled = !busy, modifier = Modifier.testTag("nutrition_add_${meal.name}")) {
            Icon(Icons.Rounded.Add, stringResource(R.string.nutrition_add_to_meal, label))
        }
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntity, busy: Boolean, onOpen: () -> Unit, onRepeat: () -> Unit, onDelete: () -> Unit) {
    var menu by rememberSaveable(entry.id) { mutableStateOf(false) }
    Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("nutrition_entry_${entry.id}"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.snapshot.name, style = MaterialTheme.typography.titleMedium)
                if (entry.snapshot.brand.isNotBlank()) Text(entry.snapshot.brand, style = MaterialTheme.typography.bodySmall)
                Text(if (entry.portionLabel != null) stringResource(R.string.food_selected_serving, numberFormat(entry.quantity), entry.portionLabel)
                    else when (entry.unit) {
                        FoodUnit.GRAM -> stringResource(R.string.food_gram_amount, numberFormat(entry.quantity))
                        FoodUnit.MILLILITER -> stringResource(R.string.food_milliliter_amount, numberFormat(entry.quantity))
                        FoodUnit.SERVING -> pluralStringResource(R.plurals.food_serving_amount, if (entry.quantity == 1.0) 1 else 2, numberFormat(entry.quantity))
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.kcal?.let { stringResource(R.string.nutrition_kcal, numberFormat(it)) } ?: stringResource(R.string.food_nutrient_unavailable),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("nutrition_entry_actions_${entry.id}")) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.nutrition_entry_actions, entry.snapshot.name))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.nutrition_repeat)) }, onClick = { menu = false; onRepeat() }, enabled = !busy,
                        modifier = Modifier.testTag("nutrition_repeat_${entry.id}"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.nutrition_delete_entry)) }, onClick = { menu = false; onDelete() }, enabled = !busy,
                        modifier = Modifier.testTag("nutrition_delete_${entry.id}"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NutritionDaySelector(day: String, onDay: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    var dates by rememberSaveable { mutableStateOf(false) }
    val date = remember(day) { LocalDate.parse(day) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDay(date.minusDays(1).toString()) }, enabled = enabled && date > LocalDate.of(1, 1, 1),
                modifier = Modifier.testTag("nutrition_previous_day")) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.nutrition_previous_day))
            }
            OutlinedButton(onClick = { dates = true }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("nutrition_date_picker")) {
                Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp))
                Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), Modifier.padding(start = 8.dp))
            }
            IconButton(onClick = { onDay(date.plusDays(1).toString()) }, enabled = enabled && date < LocalDate.of(9999, 12, 31),
                modifier = Modifier.testTag("nutrition_next_day")) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.nutrition_next_day))
            }
        }
        TextButton(onClick = { onDay(LocalDate.now().toString()) }, enabled = enabled,
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("nutrition_today")) {
            Text(stringResource(R.string.add_food_today))
        }
    }
    if (dates) {
        val windowSize = LocalWindowInfo.current.containerSize
        val compactHeight = with(LocalDensity.current) { windowSize.height.toDp() < 480.dp }
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999,
            initialDisplayMode = if (compactHeight) DisplayMode.Input else DisplayMode.Picker)
        DatePickerDialog(onDismissRequest = { dates = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let {
                onDay(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()); dates = false
            } }, enabled = enabled && picker.selectedDateMillis != null, modifier = Modifier.testTag("nutrition_date_confirm")) {
                Text(stringResource(R.string.add_food_date_confirm))
            }
        }, dismissButton = { TextButton(onClick = { dates = false }) { Text(stringResource(R.string.cancel)) } }) {
            DatePicker(picker, title = { Text(stringResource(R.string.nutrition_choose_day), Modifier.padding(24.dp)) }, modifier = Modifier.heightIn(max = 480.dp))
        }
    }
}

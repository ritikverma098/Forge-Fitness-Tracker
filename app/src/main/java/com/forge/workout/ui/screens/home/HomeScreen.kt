package com.forge.workout.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.nutrition.model.NutrientTotal
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.*
import com.forge.workout.viewmodel.DailyNutritionState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

@Composable
fun HomeScreen(
    workouts: List<WorkoutDetail>,
    activeWorkout: WorkoutDetail?,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onRoutines: () -> Unit,
    onSettings: () -> Unit,
    today: LocalDate,
    onNutrition: () -> Unit,
    onProgress: () -> Unit,
    onWorkoutDetail: (Long) -> Unit,
    nutritionOverview: @Composable () -> Unit,
) {
    val completed = remember(workouts) {
        workouts.filter { it.workout.endTime != null }.sortedByDescending { it.workout.startTime }
    }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEnd = weekStart.plusDays(7)
    val zone = ZoneId.systemDefault()
    val dailyCounts = remember(completed, zone, weekStart) {
        val counts = IntArray(7)
        for (entry in completed) {
            val day = Instant.ofEpochMilli(entry.workout.startTime).atZone(zone).toLocalDate()
            if (day >= weekStart && day < weekEnd) counts[day.dayOfWeek.value - 1]++
        }
        counts.toList()
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.home_title), stringResource(R.string.home_subtitle), actions = {
            FilledTonalIconButton(onClick = onSettings, shape = MaterialTheme.shapes.small) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.overview_settings))
            }
        })
        LazyColumn(
            modifier = Modifier.weight(1f).testTag("home_dashboard"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "training") {
                ForgeHero(Icons.Rounded.FitnessCenter, Modifier.fillMaxWidth()) {
                    ForgeEyebrow(stringResource(if (activeWorkout != null) R.string.home_in_progress else R.string.premium_training))
                    Text(activeWorkout?.workout?.name ?: stringResource(R.string.home_ready), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        activeWorkout?.let {
                            pluralStringResource(R.plurals.workout_exercise_count, it.exercises.size, it.exercises.size)
                        } ?: stringResource(if (completed.isEmpty()) R.string.home_no_last_workout else R.string.home_ready_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = if (activeWorkout != null) onResume else onStart,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (activeWorkout != null) R.string.home_resume_workout else R.string.home_start_workout))
                    }
                }
            }
            item(key = "weekly") { WeeklyActivity(dailyCounts, completed.size, onHistory) }
            item(key = "overview") { nutritionOverview() }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeSectionHeading(stringResource(R.string.premium_quick_actions))
                    QuickLink(stringResource(R.string.home_exercises), Icons.Rounded.FitnessCenter, onExercises)
                    QuickLink(stringResource(R.string.home_routines), Icons.Rounded.ViewWeek, onRoutines)
                    QuickLink(stringResource(R.string.premium_log_food), Icons.Rounded.Restaurant, onNutrition)
                    QuickLink(stringResource(R.string.premium_view_progress), Icons.AutoMirrored.Rounded.TrendingUp, onProgress)
                }
            }
            item(key = "recent") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ForgeSectionHeading(stringResource(R.string.home_recent_workouts), Modifier.weight(1f))
                    TextButton(onClick = onHistory) { Text(stringResource(R.string.home_see_all)) }
                }
            }
            if (completed.isEmpty()) item(key = "no_workouts") {
                EmptyState(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_description), Icons.Rounded.History)
            }
            items(completed.take(3), key = { it.workout.id }, contentType = { "workout" }) { detail ->
                WorkoutCard(detail, weightUnit) { onWorkoutDetail(detail.workout.id) }
            }
        }
    }
}

@Composable
private fun WeeklyActivity(counts: List<Int>, total: Int, onHistory: () -> Unit) {
    val peak = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
    val scaled = LocalDensity.current.fontScale >= 1.5f
    val locale = LocalLocale.current.platformLocale
    ForgePanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ForgeIconTile(Icons.Rounded.BarChart, highlighted = true)
                ForgeSectionHeading(
                    stringResource(R.string.premium_weekly_activity),
                    Modifier.weight(1f),
                    pluralStringResource(R.plurals.premium_sessions_this_week, counts.sum(), counts.sum()),
                )
                IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, stringResource(R.string.history)) }
            }
            if (!scaled) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counts.forEachIndexed { index, count ->
                        val day = DayOfWeek.of(index + 1)
                        val label = day.getDisplayName(TextStyle.FULL, locale)
                        val description = pluralStringResource(R.plurals.premium_day_activity, count, label, count)
                        Column(Modifier.weight(1f).semantics { contentDescription = description },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.height(34.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(.66f).height(if (count == 0) 4.dp else (10 + 24f * count / peak).dp),
                                    shape = MaterialTheme.shapes.extraSmall,
                                    color = if (count == 0) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
                                ) {}
                            }
                            Text(day.getDisplayName(TextStyle.NARROW, locale), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text(pluralStringResource(R.plurals.premium_lifetime_sessions, total, total), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickLink(title: String, icon: ImageVector, onClick: () -> Unit) {
    ForgePanel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIconTile(icon, highlighted = true)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun HomeNutritionOverview(state: DailyNutritionState, onNutrition: () -> Unit, onRetry: () -> Unit) {
    if (state.error) {
        EmptyState(stringResource(R.string.nutrition_error_title), stringResource(R.string.nutrition_error_description),
            Icons.Rounded.Restaurant, onRetry, stringResource(R.string.retry_failed_changes))
        return
    }
    if (state.loading || state.entries.isEmpty()) return

    val workoutKcal = remember(state.workoutEnergy, state.includeWorkoutCalories) {
        if (state.includeWorkoutCalories) state.workoutEnergy.sumOf { it.effectiveKcal } else 0.0
    }
    val budget = state.goal?.kcal?.plus(workoutKcal)
    val kcal = state.totals.kcal
    val ring = budget?.takeIf { it > 0 && kcal.complete }
    val layoutsFit = LocalDensity.current.fontScale < 1.5f
    ForgePanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ForgeIconTile(Icons.Rounded.Restaurant, highlighted = true)
                ForgeSectionHeading(stringResource(R.string.premium_nutrition_today), Modifier.weight(1f))
                IconButton(onClick = onNutrition) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.premium_log_food))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (ring != null && layoutsFit) CircularProgressIndicator(
                    progress = { (kcal.knownValue / ring).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.size(54.dp),
                    strokeWidth = 6.dp,
                    strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Column(Modifier.weight(1f)) {
                    Text(if (kcal.missingCount == state.entries.size) stringResource(R.string.food_nutrient_unavailable)
                        else stringResource(R.string.nutrition_kcal, numberFormat(kcal.knownValue)),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            !kcal.complete -> stringResource(R.string.premium_partial_total)
                            budget == null -> stringResource(R.string.premium_calorie_goal_unset)
                            else -> stringResource(R.string.premium_daily_budget, numberFormat(budget))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HomeMacro(stringResource(R.string.food_protein), state.totals.protein, state.goal?.protein, state.entries.size)
            HomeMacro(stringResource(R.string.food_carbs), state.totals.carbs, state.goal?.carbs, state.entries.size)
            HomeMacro(stringResource(R.string.food_fat), state.totals.fat, state.goal?.fat, state.entries.size)
        }
    }
}

@Composable
private fun HomeMacro(label: String, total: NutrientTotal, goal: Double?, entries: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val value = stringResource(R.string.nutrition_grams, numberFormat(total.knownValue))
        val detail = when {
            total.missingCount == entries -> stringResource(R.string.food_nutrient_unavailable)
            !total.complete -> stringResource(R.string.premium_partial_value, value)
            goal != null -> stringResource(R.string.premium_macro_target, value, numberFormat(goal))
            else -> value
        }
        Text(stringResource(R.string.premium_macro_line, label, detail), style = MaterialTheme.typography.bodyMedium)
        if (total.complete && goal != null && goal > 0) ForgeProgressBar((total.knownValue / goal).toFloat())
    }
}

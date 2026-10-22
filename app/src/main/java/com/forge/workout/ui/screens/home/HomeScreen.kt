package com.forge.workout.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.MetricCard
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.WorkoutCard
import com.forge.workout.ui.components.dateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
) {
    val completed = remember(workouts) {
        workouts.filter { it.workout.endTime != null }.sortedByDescending { it.workout.startTime }
    }
    val weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val weeklyCount = completed.count { it.workout.startTime >= weekStart }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.home_title),
            subtitle = stringResource(R.string.home_subtitle),
            actions = {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.overview_settings))
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = if (activeWorkout != null) stringResource(R.string.home_in_progress)
                            else stringResource(R.string.home_ready),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = activeWorkout?.workout?.name ?: stringResource(R.string.home_ready_description),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Button(
                            onClick = if (activeWorkout != null) onResume else onStart,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (activeWorkout != null) stringResource(R.string.home_resume_workout) else stringResource(R.string.home_start_workout))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard(completed.size.toString(), stringResource(R.string.home_total_workouts), Modifier.weight(1f))
                    MetricCard(weeklyCount.toString(), stringResource(R.string.home_this_week), Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.home_last_workout), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(completed.firstOrNull()?.workout?.name ?: stringResource(R.string.home_no_last_workout), style = MaterialTheme.typography.titleMedium)
                        completed.firstOrNull()?.let {
                            Text(dateFormat(it.workout.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.home_explore), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            item { QuickLink(stringResource(R.string.home_exercises), stringResource(R.string.home_exercises_description), Icons.Rounded.FitnessCenter, onExercises) }
            item { QuickLink(stringResource(R.string.home_routines), stringResource(R.string.home_routines_description), Icons.Rounded.ViewWeek, onRoutines) }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.home_recent_workouts), modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onHistory) { Text(stringResource(R.string.home_see_all)) }
                }
            }
            if (completed.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.history_empty_title),
                        stringResource(R.string.history_empty_description),
                        Icons.Rounded.FitnessCenter,
                        onStart,
                        stringResource(R.string.home_start_workout),
                    )
                }
            } else {
                items(completed.take(3), key = { it.workout.id }) { detail ->
                    WorkoutCard(detail, weightUnit, onHistory)
                }
            }
        }
    }
}

@Composable
private fun QuickLink(title: String, description: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.forge.workout.ui.screens.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.ForgeHero
import com.forge.workout.ui.components.ForgeIconTile
import com.forge.workout.ui.components.ForgePanel
import com.forge.workout.ui.components.ForgeSectionHeading
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.WorkoutCard

@Composable
internal fun WorkoutOverview(
    history: List<WorkoutDetail>,
    routines: List<RoutineDetail>,
    unit: WeightUnit,
    onStart: () -> Unit,
    onRoutines: () -> Unit,
    onLibrary: () -> Unit,
    onRoutineStart: ((Long) -> Unit)?,
    onRecentWorkout: ((Long) -> Unit)?,
) {
    val recent = remember(history) {
        // The workout feed arrives newest first. Stop once the preview is filled.
        history.asSequence().filter { it.workout.endTime != null }.take(3).toList()
    }
    val quickRoutines = remember(routines) {
        routines.asSequence().filter { routine -> routine.exercises.any { !it.exercise.archived } }.take(2).toList()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.workout), subtitle = stringResource(R.string.workout_premium_subtitle)) }
        item {
            ForgeHero(Icons.Rounded.FitnessCenter, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                ForgeIconTile(Icons.Rounded.FitnessCenter, highlighted = true)
                Text(stringResource(R.string.ready_to_train), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.workout_empty_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.start_workout))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkoutShortcut(stringResource(R.string.routines), stringResource(R.string.routines_subtitle), Icons.AutoMirrored.Rounded.ViewList, onRoutines)
                WorkoutShortcut(stringResource(R.string.exercise_library), stringResource(R.string.library_subtitle), Icons.Rounded.GridView, onLibrary)
            }
        }
        if (onRoutineStart != null && quickRoutines.isNotEmpty()) {
            item { ForgeSectionHeading(stringResource(R.string.workout_premium_quick_routines), Modifier.padding(horizontal = 20.dp)) }
            items(quickRoutines, key = { "routine_${it.routine.id}" }) { routine ->
                val count = remember(routine.exercises) { routine.exercises.count { !it.exercise.archived } }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    WorkoutShortcut(routine.routine.name, pluralStringResource(R.plurals.routines_available_exercise_count, count, count),
                        Icons.Rounded.PlayArrow, onClick = { onRoutineStart(routine.routine.id) })
                }
            }
        }
        if (onRecentWorkout != null && recent.isNotEmpty()) {
            item { ForgeSectionHeading(stringResource(R.string.workout_premium_recent_sessions), Modifier.padding(horizontal = 20.dp)) }
            items(recent, key = { "recent_${it.workout.id}" }) { detail ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    WorkoutCard(detail, unit, onClick = { onRecentWorkout(detail.workout.id) })
                }
            }
        }
    }
}

@Composable
private fun WorkoutShortcut(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ForgePanel(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIconTile(icon, highlighted = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

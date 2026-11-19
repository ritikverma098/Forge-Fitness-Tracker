package com.forge.workout.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.forge.workout.R
import com.forge.workout.model.*
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.utils.WorkoutCalculations
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) FilledTonalIconButton(
            onClick = onBack, shape = MaterialTheme.shapes.small,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
@Composable fun EmptyState(title: String, description: String, icon: ImageVector, onAction: (() -> Unit)? = null, actionLabel: String? = null) {
    ForgePanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ForgeIconTile(icon, highlighted = true)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (onAction != null && actionLabel != null) Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
@Composable fun MetricCard(value: String, label: String, modifier: Modifier = Modifier) {
    ForgePanel(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun WorkoutCard(detail: WorkoutDetail, weightUnit: WeightUnit, onClick: () -> Unit) {
    val stats = remember(detail) { WorkoutCalculations.summary(detail) }
    ForgePanel(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIconTile(Icons.Rounded.FitnessCenter, highlighted = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(detail.workout.name, style = MaterialTheme.typography.titleMedium)
                Text(dateFormat(detail.workout.startTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.workout_counts,pluralStringResource(R.plurals.workout_exercise_count,stats.exerciseCount,stats.exerciseCount),pluralStringResource(R.plurals.workout_set_count,stats.setCount,stats.setCount)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${durationFormat(stats.durationSeconds)} · ${weightFormat(stats.totalVolumeKg, weightUnit)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
}
@Composable fun muscleLabel(group: MuscleGroup): String = stringResource(when(group) {
    MuscleGroup.CHEST -> R.string.chest; MuscleGroup.BACK -> R.string.back_muscle; MuscleGroup.SHOULDERS -> R.string.shoulders
    MuscleGroup.BICEPS -> R.string.biceps; MuscleGroup.TRICEPS -> R.string.triceps; MuscleGroup.FOREARMS -> R.string.forearms
    MuscleGroup.LEGS -> R.string.legs; MuscleGroup.GLUTES -> R.string.glutes; MuscleGroup.CORE -> R.string.core
    MuscleGroup.CALVES -> R.string.calves; MuscleGroup.CARDIO -> R.string.cardio; MuscleGroup.FULL_BODY -> R.string.full_body; MuscleGroup.OTHER -> R.string.other
})
@Composable fun exerciseTypeLabel(type: ExerciseType): String = stringResource(when(type) {
    ExerciseType.WEIGHT_REPS -> R.string.weight_reps; ExerciseType.BODYWEIGHT -> R.string.bodyweight
    ExerciseType.REPS_ONLY -> R.string.reps_only; ExerciseType.DURATION -> R.string.duration_type; ExerciseType.DISTANCE -> R.string.distance_type
})
fun numberFormat(value: Double): String = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)
fun weightFormat(kg: Double, unit: WeightUnit): String = "${numberFormat(unit.fromKg(kg))} ${unit.symbol}"
fun durationFormat(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return if(safe >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", safe / 3600, safe / 60 % 60, safe % 60)
        else String.format(Locale.getDefault(), "%d:%02d", safe / 60, safe % 60)
}
fun dateFormat(timestamp: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

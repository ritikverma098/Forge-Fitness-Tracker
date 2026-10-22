package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.WorkoutActivityPreset
import com.forge.workout.nutrition.model.WorkoutEnergyEntity
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.ui.components.weightFormat
import com.forge.workout.viewmodel.WorkoutEnergyViewModel

@Composable
fun WorkoutCaloriesCard(
    viewModel: WorkoutEnergyViewModel,
    onEdit: () -> Unit,
    onWeights: () -> Unit,
    weightUnit: WeightUnit = WeightUnit.KG,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NutritionCard(Modifier.testTag("workout_calories_card")) {
        Text(stringResource(R.string.energy_title), style = MaterialTheme.typography.titleMedium)
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            state.error -> {
                Text(stringResource(R.string.energy_error), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.nutrition_retry)) }
            }
            state.workoutMissing -> Text(stringResource(R.string.energy_missing_workout), style = MaterialTheme.typography.bodyMedium)
            state.missingWeight -> {
                Text(stringResource(R.string.energy_missing_weight), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onWeights, modifier = Modifier.heightIn(min = 48.dp).testTag("workout_energy_add_weight")) {
                    Text(stringResource(R.string.energy_body_weights))
                }
            }
            state.energy == null -> {
                Text(stringResource(R.string.energy_not_recorded), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("workout_energy_edit")) {
                    Text(stringResource(R.string.energy_edit))
                }
            }
            else -> {
                EnergyRecord(requireNotNull(state.energy), weightUnit)
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).testTag("workout_energy_edit")) {
                    Text(stringResource(R.string.energy_edit))
                }
            }
        }
    }
}

@Composable
internal fun EnergyRecord(energy: WorkoutEnergyEntity, weightUnit: WeightUnit) {
    if (energy.manualKcal == null) Text(stringResource(R.string.energy_estimate), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(if (energy.manualKcal == null) R.string.energy_kcal_estimate else R.string.energy_kcal_manual,
        numberFormat(energy.effectiveKcal)), Modifier.testTag("workout_energy_kcal"), style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary)
    val preset = WorkoutActivityPreset.entries.firstOrNull { it.id == energy.activity }
    Text(stringResource(R.string.energy_activity_met, preset?.let { activityLabel(it) } ?: energy.activity, numberFormat(energy.met)),
        style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.energy_captured_weight, weightFormat(energy.bodyWeightKg, weightUnit)),
        Modifier.testTag("workout_energy_weight"), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.energy_active_fraction, numberFormat(energy.activeFraction * 100.0)),
        style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.energy_assumption), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ActivityChoices(selected: WorkoutActivityPreset, enabled: Boolean, onSelected: (WorkoutActivityPreset) -> Unit) {
    Text(stringResource(R.string.energy_activity), style = MaterialTheme.typography.titleMedium)
    LazyRow(Modifier.fillMaxWidth().testTag("energy_activity_choices"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(WorkoutActivityPreset.entries, key = { it.id }) { preset ->
            FilterChip(selected == preset, onClick = { onSelected(preset) }, enabled = enabled,
                label = { Text(activityLabel(preset)) }, modifier = Modifier.testTag("energy_activity_${preset.id}"))
        }
    }
    Text(stringResource(R.string.energy_activity_met, activityLabel(selected), numberFormat(selected.met)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.energy_source), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun activityLabel(preset: WorkoutActivityPreset): String = stringResource(when (preset) {
    WorkoutActivityPreset.LIGHT_CALISTHENICS -> R.string.energy_light_calisthenics
    WorkoutActivityPreset.MODERATE_CALISTHENICS -> R.string.energy_moderate_calisthenics
    WorkoutActivityPreset.VIGOROUS_CALISTHENICS -> R.string.energy_vigorous_calisthenics
    WorkoutActivityPreset.RESISTANCE -> R.string.energy_resistance
    WorkoutActivityPreset.VIGOROUS_RESISTANCE -> R.string.energy_vigorous_resistance
    WorkoutActivityPreset.BODYWEIGHT -> R.string.energy_bodyweight
})

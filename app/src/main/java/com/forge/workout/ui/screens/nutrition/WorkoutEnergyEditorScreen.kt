package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.viewmodel.WorkoutEnergyCommand
import com.forge.workout.viewmodel.WorkoutEnergyDraft
import com.forge.workout.viewmodel.WorkoutEnergyEditorViewModel

@Composable
fun WorkoutEnergyEditorScreen(
    viewModel: WorkoutEnergyEditorViewModel,
    onBack: () -> Unit,
    onSave: (WorkoutEnergyCommand) -> Unit,
    weightUnit: WeightUnit = WeightUnit.KG,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    onWeights: (() -> Unit)? = null,
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember(viewModel) { mutableStateOf(state.draft) }
    var pending by remember(viewModel) { mutableStateOf<WorkoutEnergyDraft?>(null) }
    LaunchedEffect(state.draft, pending) {
        if (pending == null || pending == state.draft) { draft = state.draft; pending = null }
    }
    val update: (WorkoutEnergyDraft.() -> WorkoutEnergyDraft) -> Unit = { transform ->
        val next = draft.transform()
        if (viewModel.updateDraft(next)) { draft = next; pending = next }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback, feedbackToken) { feedback?.let { snackbar.showSnackbar(it); onFeedbackHandled() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScreenHeader(stringResource(R.string.energy_edit), onBack = onBack)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error -> EmptyState(stringResource(R.string.energy_title), stringResource(R.string.energy_error), Icons.Rounded.FitnessCenter,
                    viewModel::reload, stringResource(R.string.nutrition_retry))
                state.workoutMissing -> EmptyState(stringResource(R.string.energy_title), stringResource(R.string.energy_missing_workout), Icons.Rounded.FitnessCenter)
                state.missingWeight -> EmptyState(stringResource(R.string.energy_title), stringResource(R.string.energy_missing_weight), Icons.Rounded.FitnessCenter,
                    onWeights, if (onWeights != null) stringResource(R.string.energy_body_weights) else null)
                else -> LazyColumn(Modifier.weight(1f).testTag("workout_energy_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.energy != null) item(key = "record", contentType = "card") {
                        NutritionCard {
                            Text(stringResource(R.string.energy_recorded), style = MaterialTheme.typography.titleMedium)
                            EnergyRecord(requireNotNull(state.energy), weightUnit)
                        }
                    }
                    item(key = "activity", contentType = "form") {
                        NutritionCard {
                            ActivityChoices(draft.preset, !busy) { preset -> update { copy(preset = preset) } }
                            EnergyNumericField(draft.activePercent, { value -> update { copy(activePercent = value) } },
                                R.string.energy_active_percent, "workout_energy_active_percent", "activePercent" in state.invalidFields,
                                R.string.energy_active_error, busy)
                            Text(stringResource(R.string.energy_assumption), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.energy_net_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item(key = "manual", contentType = "form") {
                        NutritionCard {
                            EnergyNumericField(draft.manualKcal, { value -> update { copy(manualKcal = value) } },
                                R.string.energy_manual, "workout_energy_manual", "manualKcal" in state.invalidFields, R.string.energy_manual_error, busy)
                            Text(stringResource(R.string.energy_manual_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(key = "save", contentType = "action") {
                        Button(onClick = { viewModel.validatedEstimate()?.let(onSave) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("workout_energy_save")) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(stringResource(R.string.energy_save))
                        }
                    }
                }
            }
        }
    }
}

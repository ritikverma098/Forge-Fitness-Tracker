package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.NutritionSettingsEntity
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.viewmodel.EnergySettingsDraft
import com.forge.workout.viewmodel.EnergySettingsViewModel

@Composable
fun EnergySettingsScreen(
    viewModel: EnergySettingsViewModel,
    onBack: () -> Unit,
    onSave: (NutritionSettingsEntity) -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember(viewModel) { mutableStateOf(state.draft) }
    var pending by remember(viewModel) { mutableStateOf<EnergySettingsDraft?>(null) }
    LaunchedEffect(state.draft, pending) {
        if (pending == null || pending == state.draft) { draft = state.draft; pending = null }
    }
    val update: (EnergySettingsDraft.() -> EnergySettingsDraft) -> Unit = { transform ->
        val next = draft.transform()
        if (viewModel.updateDraft(next)) { draft = next; pending = next }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback, feedbackToken) { feedback?.let { snackbar.showSnackbar(it); onFeedbackHandled() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScreenHeader(stringResource(R.string.energy_settings), onBack = onBack)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error -> EmptyState(stringResource(R.string.energy_title), stringResource(R.string.energy_error), Icons.Rounded.FitnessCenter,
                    viewModel::reload, stringResource(R.string.nutrition_retry))
                else -> LazyColumn(Modifier.weight(1f).testTag("energy_settings_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "budget", contentType = "card") {
                        NutritionCard {
                            val includeLabel = stringResource(R.string.energy_include)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(includeLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(draft.includeWorkoutCalories, onCheckedChange = { value -> update { copy(includeWorkoutCalories = value) } },
                                    enabled = !busy, modifier = Modifier.testTag("energy_settings_include").semantics { contentDescription = includeLabel })
                            }
                            Text(stringResource(R.string.energy_include_warning), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(key = "defaults", contentType = "form") {
                        NutritionCard {
                            Text(stringResource(R.string.energy_defaults), style = MaterialTheme.typography.titleLarge)
                            ActivityChoices(draft.preset, !busy) { preset -> update { copy(preset = preset) } }
                            EnergyNumericField(draft.activePercent, { value -> update { copy(activePercent = value) } },
                                R.string.energy_active_percent, "energy_settings_active_percent", "activePercent" in state.invalidFields,
                                R.string.energy_active_error, busy)
                            Text(stringResource(R.string.energy_assumption), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.energy_defaults_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item(key = "save", contentType = "action") {
                        Button(onClick = { viewModel.validatedSettings()?.let(onSave) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("energy_settings_save")) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(stringResource(R.string.energy_save_settings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EnergyNumericField(value: String, onValue: (String) -> Unit, label: Int, tag: String, invalid: Boolean, error: Int, busy: Boolean) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag(tag), label = { Text(stringResource(label)) },
        enabled = !busy, singleLine = true, isError = invalid, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        supportingText = if (invalid) ({ Text(stringResource(error)) }) else null)
}

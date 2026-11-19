package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.BodyWeightEntity
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ForgeIconTile
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.weightFormat
import com.forge.workout.viewmodel.BodyWeightDraft
import com.forge.workout.viewmodel.BodyWeightViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

@Composable
fun BodyWeightScreen(
    viewModel: BodyWeightViewModel,
    onBack: () -> Unit,
    onSave: (BodyWeightEntity) -> Unit,
    onDelete: (Long) -> Unit,
    weightUnit: WeightUnit = WeightUnit.KG,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, weightUnit) { viewModel.setUnit(weightUnit) }
    var draft by remember(viewModel) { mutableStateOf(state.draft) }
    var draftUnit by remember(viewModel) { mutableStateOf(state.unit) }
    var pending by remember(viewModel) { mutableStateOf<BodyWeightDraft?>(null) }
    LaunchedEffect(state.draft, state.unit, pending) {
        if (draftUnit != state.unit || pending == null || pending == state.draft) {
            draft = state.draft; draftUnit = state.unit; pending = null
        }
    }
    val setWeight: (String) -> Unit = { value ->
        val next = draft.copy(weight = value)
        if (viewModel.updateDraft(next, draftUnit)) { draft = next; pending = next }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback, feedbackToken) { feedback?.let { snackbar.showSnackbar(it); onFeedbackHandled() } }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScreenHeader(stringResource(R.string.energy_weight_title), onBack = onBack)
            LazyColumn(Modifier.weight(1f).testTag("body_weight_list"), state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "form", contentType = "form") {
                    NutritionCard(emphasized = true) {
                        NutritionSection(stringResource(R.string.nutrition_premium_new_weight), Icons.Rounded.MonitorWeight)
                        NutritionDaySelector(draft.day, { day -> pending = null; viewModel.setDay(day) },
                            Modifier.testTag("body_weight_day"), enabled = !busy)
                        Text(stringResource(R.string.energy_weight_help), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.loading) NutritionLoading(stringResource(R.string.nutrition_premium_loading))
                        else if (state.error) {
                            Text(stringResource(R.string.energy_weight_load_error), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.nutrition_retry)) }
                        } else {
                            OutlinedTextField(draft.weight, setWeight, Modifier.fillMaxWidth().testTag("body_weight_quantity"),
                                label = { Text(stringResource(R.string.energy_weight_amount, draftUnit.symbol)) }, singleLine = true, enabled = !busy,
                                isError = "weight" in state.invalidFields, shape = MaterialTheme.shapes.medium,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                supportingText = if ("weight" in state.invalidFields) ({ Text(stringResource(R.string.energy_weight_error)) }) else null)
                            Button(onClick = { viewModel.validatedWeight()?.let(onSave) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("body_weight_save")) {
                                NutritionSaveLabel(stringResource(R.string.energy_weight_save), busy)
                            }
                        }
                    }
                }
                if (!state.loading && !state.error && state.history.isEmpty()) item(key = "empty", contentType = "status") {
                    EmptyState(stringResource(R.string.energy_weight_empty), stringResource(R.string.energy_weight_empty_description), Icons.Rounded.MonitorWeight)
                }
                items(state.history, key = { "weight_${it.id}" }, contentType = { "weight" }) { record ->
                    val date = remember(record.day) { LocalDate.parse(record.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
                    NutritionCard(Modifier.testTag("body_weight_record_${record.id}")) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (LocalDensity.current.fontScale < 1.3f) {
                                    ForgeIconTile(Icons.Rounded.MonitorWeight)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(weightFormat(record.kg, draftUnit), style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = {
                                pending = null; viewModel.setDay(record.day)
                                scope.launch { listState.animateScrollToItem(0) }
                            }, enabled = !busy, modifier = Modifier.testTag("body_weight_edit_${record.id}")) {
                                Icon(Icons.Rounded.Edit, stringResource(R.string.energy_weight_edit, date))
                            }
                            IconButton(onClick = { deleteId = record.id }, enabled = !busy,
                                modifier = Modifier.testTag("body_weight_delete_${record.id}")) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.energy_weight_delete, date))
                            }
                        }
                    }
                }
                if (state.hasMore) item(key = "more", contentType = "action") {
                    OutlinedButton(onClick = viewModel::loadMore, enabled = !busy && !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("body_weight_more")) {
                        Text(stringResource(R.string.energy_weight_more))
                    }
                }
                if (state.limitReached) item(key = "limit", contentType = "status") {
                    Text(stringResource(R.string.energy_weight_limit), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { if (!busy) deleteId = null },
        title = { Text(stringResource(R.string.energy_weight_delete_title)) }, text = { Text(stringResource(R.string.energy_weight_delete_description)) },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }, enabled = !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("body_weight_delete_confirm")) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleteId = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) } })
}

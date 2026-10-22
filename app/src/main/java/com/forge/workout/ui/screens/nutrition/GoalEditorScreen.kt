package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.NutritionGoalEntity
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.viewmodel.GoalDraft
import com.forge.workout.viewmodel.GoalEditorViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun GoalEditorScreen(
    viewModel: GoalEditorViewModel,
    onBack: () -> Unit,
    onSave: (NutritionGoalEntity) -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember(viewModel) { mutableStateOf(state.draft) }
    var pending by remember(viewModel) { mutableStateOf<GoalDraft?>(null) }
    LaunchedEffect(state.draft, pending) {
        if (pending == null || pending == state.draft) { draft = state.draft; pending = null }
    }
    val update: (GoalDraft.() -> GoalDraft) -> Unit = { transform ->
        val next = draft.transform()
        if (viewModel.updateDraft(next)) { draft = next; pending = next }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback, feedbackToken) { feedback?.let { snackbar.showSnackbar(it); onFeedbackHandled() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScreenHeader(stringResource(R.string.nutrition_goals), onBack = onBack)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error -> EmptyState(stringResource(R.string.nutrition_error_title), stringResource(R.string.nutrition_error_description),
                    Icons.Rounded.Flag, viewModel::reload, stringResource(R.string.nutrition_retry))
                else -> LazyColumn(Modifier.weight(1f).testTag("goal_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "effective", contentType = "card") {
                        NutritionCard {
                            Text(stringResource(R.string.nutrition_goals_effective), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(LocalDate.parse(draft.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                                Modifier.testTag("goal_effective_day"), style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.nutrition_goals_help), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item(key = "values", contentType = "form") {
                        NutritionCard {
                            GoalField(draft.kcal, { value -> update { copy(kcal = value) } }, R.string.nutrition_goal_kcal,
                                "goal_kcal", "kcal" in state.invalidFields, busy)
                            GoalField(draft.protein, { value -> update { copy(protein = value) } }, R.string.nutrition_goal_protein,
                                "goal_protein", "protein" in state.invalidFields, busy)
                            GoalField(draft.carbs, { value -> update { copy(carbs = value) } }, R.string.nutrition_goal_carbs,
                                "goal_carbs", "carbs" in state.invalidFields, busy)
                            GoalField(draft.fat, { value -> update { copy(fat = value) } }, R.string.nutrition_goal_fat,
                                "goal_fat", "fat" in state.invalidFields, busy)
                        }
                    }
                    item(key = "save", contentType = "action") {
                        Button(onClick = { viewModel.validatedGoal()?.let(onSave) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("goal_save")) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text(stringResource(R.string.nutrition_goal_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalField(value: String, onValue: (String) -> Unit, label: Int, tag: String, invalid: Boolean, busy: Boolean) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag(tag), label = { Text(stringResource(label)) },
        enabled = !busy, singleLine = true, isError = invalid, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        supportingText = if (invalid) ({ Text(stringResource(R.string.nutrition_goal_error)) }) else null)
}

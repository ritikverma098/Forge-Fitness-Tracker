package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.*
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.viewmodel.DiaryEntryViewModel
import com.forge.workout.viewmodel.FoodDetailsUiState

@Composable
fun DiaryEntryScreen(
    viewModel: DiaryEntryViewModel,
    onBack: () -> Unit,
    onSave: (DiaryEntity, FoodServingChoice, Double, String, MealType) -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val entry = state.entry
    if (entry == null) {
        if (!state.loading && !state.error) Column {
            ScreenHeader(stringResource(R.string.nutrition_diary_edit), onBack = onBack)
            EmptyState(stringResource(R.string.nutrition_entry_missing), stringResource(R.string.nutrition_entry_missing_description), Icons.Rounded.Restaurant)
        }
        else FoodDetailsScreen(FoodDetailsUiState(loading = state.loading, error = state.error), viewModel::reload, onBack,
            title = stringResource(R.string.nutrition_diary_edit), feedback = feedback, onFeedbackHandled = onFeedbackHandled,
            feedbackToken = feedbackToken)
        return
    }
    key(entry.id) { LoadedDiaryEntry(entry, viewModel::reload, onBack, onSave, busy, feedback, onFeedbackHandled, feedbackToken) }
}

@Composable
private fun LoadedDiaryEntry(
    entry: DiaryEntity,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onSave: (DiaryEntity, FoodServingChoice, Double, String, MealType) -> Unit,
    busy: Boolean,
    feedback: String?,
    onFeedbackHandled: () -> Unit,
    feedbackToken: Any?,
) {
    val detail = remember(entry) { runCatching { DiaryMath.snapshotDetails(entry) }.getOrNull() }
    val choice = remember(entry, detail) {
        if (detail == null) null else runCatching { NutritionMath.choices(detail) }.getOrNull()?.firstOrNull {
            it.id == (entry.portionId?.let { id -> "portion:$id" } ?: entry.unit.name)
        }
    }
    var day by rememberSaveable(entry.id) { mutableStateOf(entry.day) }
    var meal by rememberSaveable(entry.id) { mutableStateOf(entry.meal.name) }
    FoodDetailsScreen(FoodDetailsUiState(loading = false, detail = detail, error = detail == null || choice == null), onReload, onBack,
        onConfirm = { _, selected, quantity, _ -> onSave(entry, selected, quantity, day, MealType.valueOf(meal)) },
        confirmLabel = stringResource(R.string.nutrition_diary_save), confirmBusy = busy,
        feedback = feedback, onFeedbackHandled = onFeedbackHandled, feedbackToken = feedbackToken,
        initialChoice = choice, initialQuantity = entry.quantity,
        title = stringResource(R.string.nutrition_diary_edit), extraContent = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayMealSelector(day, MealType.valueOf(meal), { if (!busy) day = it }, { if (!busy) meal = it.name })
                Text(stringResource(R.string.nutrition_frozen_info), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}

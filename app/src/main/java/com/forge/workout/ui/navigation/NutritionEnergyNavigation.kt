package com.forge.workout.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.forge.workout.ForgeApplication
import com.forge.workout.R
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.screens.nutrition.*
import com.forge.workout.viewmodel.*
import kotlinx.coroutines.flow.first

internal fun NavGraphBuilder.nutritionEnergyChildren(
    nav: NavHostController,
    application: ForgeApplication,
    writer: NutritionEnergyMutationViewModel,
    unit: WeightUnit,
) {
    composable("nutrition/body-weight") { entry ->
        val editor: BodyWeightViewModel = viewModel(factory = viewModelFactory { initializer {
            BodyWeightViewModel(application.nutritionRepository, createSavedStateHandle())
        } })
        val busy by writer.busy.collectAsStateWithLifecycle()
        val result by writer.result.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == entry.id }
        BodyWeightScreen(editor, { nav.popFrom(entry) },
            onSave = { weight -> if (nav.canActFrom(entry)) writer.saveWeight(entry.id, weight.day, weight.kg) },
            onDelete = { id -> if (nav.canActFrom(entry)) writer.deleteWeight(entry.id, id) }, weightUnit = unit,
            busy = busy, feedback = feedbackResult?.let { it.error ?: application.getString(R.string.energy_changes_saved) },
            feedbackToken = feedbackResult,
            onFeedbackHandled = { feedbackResult?.let(writer::acknowledge) })
    }
    composable("nutrition/energy-settings") { entry ->
        val editor: EnergySettingsViewModel = viewModel(factory = viewModelFactory { initializer {
            EnergySettingsViewModel(application.nutritionRepository, createSavedStateHandle())
        } })
        val busy by writer.busy.collectAsStateWithLifecycle()
        val result by writer.result.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == entry.id }
        LaunchedEffect(feedbackResult, entry) {
            feedbackResult?.takeIf { it.error == null }?.let { saved ->
                entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.popFrom(entry)) writer.acknowledge(saved)
            }
        }
        EnergySettingsScreen(editor, { nav.popFrom(entry) },
            onSave = { settings -> if (nav.canActFrom(entry)) writer.saveSettings(entry.id, settings) }, busy = busy,
            feedback = feedbackResult?.error, feedbackToken = feedbackResult,
            onFeedbackHandled = { feedbackResult?.let(writer::acknowledge) })
    }
    composable("nutrition/energy/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
        val id = entry.arguments?.getLong("id") ?: return@composable
        val editor: WorkoutEnergyEditorViewModel = viewModel(factory = viewModelFactory { initializer {
            WorkoutEnergyEditorViewModel(application.nutritionRepository, id, createSavedStateHandle())
        } })
        val busy by writer.busy.collectAsStateWithLifecycle()
        val result by writer.result.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == entry.id }
        LaunchedEffect(feedbackResult, entry) {
            feedbackResult?.takeIf { it.error == null }?.let { saved ->
                entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.popFrom(entry)) writer.acknowledge(saved)
            }
        }
        WorkoutEnergyEditorScreen(editor, { nav.popFrom(entry) },
            onSave = { command -> if (nav.canActFrom(entry)) writer.saveEstimate(entry.id, command.workoutId,
                command.preset, command.activeFraction, command.manualKcal) }, weightUnit = unit,
            busy = busy, feedback = feedbackResult?.error, feedbackToken = feedbackResult,
            onFeedbackHandled = { feedbackResult?.let(writer::acknowledge) },
            onWeights = { if (nav.canActFrom(entry)) nav.navigate("nutrition/body-weight") { launchSingleTop = true } })
    }
}

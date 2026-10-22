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
import com.forge.workout.ui.screens.nutrition.DiaryEntryScreen
import com.forge.workout.ui.screens.nutrition.GoalEditorScreen
import com.forge.workout.ui.screens.nutrition.NutritionHistoryScreen
import com.forge.workout.viewmodel.*
import kotlinx.coroutines.flow.first

internal fun NavGraphBuilder.nutritionDiaryChildren(
    nav: NavHostController,
    application: ForgeApplication,
    writer: DiaryMutationViewModel,
    logging: FoodLogViewModel,
) {
    composable("nutrition/history") { entry ->
        val history: NutritionHistoryViewModel = viewModel(factory = viewModelFactory { initializer {
            NutritionHistoryViewModel(application.nutritionRepository, createSavedStateHandle())
        } })
        NutritionHistoryScreen(history, { nav.popFrom(entry) }, onDay = { day ->
            if (nav.canActFrom(entry)) {
                logging.selectDay(day)
                nav.popFrom(entry)
            }
        })
    }
    composable("nutrition/entry/{entryId}", arguments = listOf(navArgument("entryId") { type = NavType.LongType })) { entry ->
        val id = entry.arguments?.getLong("entryId") ?: return@composable
        val editor: DiaryEntryViewModel = viewModel(factory = viewModelFactory { initializer {
            DiaryEntryViewModel(application.nutritionRepository, id)
        } })
        val busy by writer.busy.collectAsStateWithLifecycle()
        val result by writer.result.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == entry.id && it.error != null }
        LaunchedEffect(result, entry) {
            result?.takeIf { it.requestId == entry.id && it.error == null }?.let { saved ->
                entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.popFrom(entry)) writer.acknowledge(saved)
            }
        }
        DiaryEntryScreen(editor, { nav.popFrom(entry) },
            onSave = { original, choice, quantity, day, meal ->
                if (nav.canActFrom(entry)) writer.saveEntry(entry.id, original, choice, quantity, day, meal)
            }, busy = busy, feedback = result?.takeIf { it.requestId == entry.id }?.error, feedbackToken = feedbackResult,
            onFeedbackHandled = { feedbackResult?.let(writer::acknowledge) })
    }
    composable("nutrition/goals/{day}", arguments = listOf(navArgument("day") { type = NavType.StringType })) { entry ->
        val day = entry.arguments?.getString("day") ?: return@composable
        val editor: GoalEditorViewModel = viewModel(factory = viewModelFactory { initializer {
            GoalEditorViewModel(application.nutritionRepository, day, createSavedStateHandle())
        } })
        val busy by writer.busy.collectAsStateWithLifecycle()
        val result by writer.result.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == entry.id && it.error != null }
        LaunchedEffect(result, entry) {
            result?.takeIf { it.requestId == entry.id && it.error == null }?.let { saved ->
                entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.popFrom(entry)) writer.acknowledge(saved)
            }
        }
        GoalEditorScreen(editor, { nav.popFrom(entry) },
            onSave = { goal -> if (nav.canActFrom(entry)) writer.saveGoal(entry.id, goal) }, busy = busy,
            feedback = result?.takeIf { it.requestId == entry.id }?.error, feedbackToken = feedbackResult,
            onFeedbackHandled = { feedbackResult?.let(writer::acknowledge) })
    }
}

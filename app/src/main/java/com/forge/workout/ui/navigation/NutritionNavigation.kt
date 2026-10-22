package com.forge.workout.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.forge.workout.ForgeApplication
import com.forge.workout.R
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.model.MealType
import com.forge.workout.ui.screens.nutrition.*
import com.forge.workout.viewmodel.*
import kotlinx.coroutines.flow.first

internal fun NavGraphBuilder.nutritionChildren(
    nav: NavHostController,
    application: ForgeApplication,
    catalogViewModel: CatalogViewModel,
    foodLogViewModel: FoodLogViewModel,
    foodMutationViewModel: FoodMutationViewModel,
) {
    fun openChild(source: NavBackStackEntry, destination: String) {
        if (nav.canActFrom(source)) nav.navigate(destination) { launchSingleTop = true }
    }
    nutritionRecipeChildren(nav, application, foodMutationViewModel)
    composable("nutrition/add") { backStack ->
        val picker: FoodPickerViewModel = viewModel(factory = viewModelFactory { initializer {
                FoodPickerViewModel(application.nutritionRepository, createSavedStateHandle())
        } })
        val day by foodLogViewModel.day.collectAsStateWithLifecycle()
        val meal by foodLogViewModel.meal.collectAsStateWithLifecycle()
        val feedback by backStack.savedStateHandle.getStateFlow<String?>("food_feedback", null).collectAsStateWithLifecycle()
        AddFoodScreen(picker, { nav.popFrom(backStack) },
        onFood = { id -> openChild(backStack, "nutrition/log/${android.net.Uri.encode(id)}") },
        day = day, meal = MealType.valueOf(meal), onDay = foodLogViewModel::selectDay,
        onMeal = foodLogViewModel::selectMeal, feedback = feedback,
        onFeedbackHandled = { backStack.savedStateHandle["food_feedback"] = null },
        onCreateFood = { openChild(backStack, "nutrition/custom?logAfter=true") },
        onCreateRecipe = { openChild(backStack, "nutrition/recipe?logAfter=true") })
    }
    composable("nutrition/custom?foodId={foodId}&logAfter={logAfter}&ingredientOwner={ingredientOwner}", arguments = listOf(
    navArgument("foodId") { type = NavType.StringType; nullable = true; defaultValue = null },
    navArgument("logAfter") { type = NavType.BoolType; defaultValue = true },
    navArgument("ingredientOwner") { type = NavType.StringType; nullable = true; defaultValue = null },
    )) { backStack ->
        val foodId = backStack.arguments?.getString("foodId")
        val editor: CustomFoodEditorViewModel = viewModel(factory = viewModelFactory { initializer {
                CustomFoodEditorViewModel(application.nutritionRepository, createSavedStateHandle(), foodId)
        } })
        val busy by foodMutationViewModel.busy.collectAsStateWithLifecycle()
        val result by foodMutationViewModel.saveResult.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == backStack.id && it.error != null }
        LaunchedEffect(result, backStack) {
            result?.takeIf { it.requestId == backStack.id && it.error == null && it.foodId != null }?.let { saved ->
                backStack.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.canActFrom(backStack)) {
                    val ingredientOwner = backStack.arguments?.getString("ingredientOwner")
                    if (ingredientOwner != null) {
                        nav.navigate("nutrition/ingredient/${android.net.Uri.encode(ingredientOwner)}/${android.net.Uri.encode(saved.foodId)}") {
                            popUpTo(backStack.destination.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else if (backStack.arguments?.getBoolean("logAfter") == true) {
                        nav.navigate("nutrition/log/${android.net.Uri.encode(saved.foodId)}") {
                            popUpTo(backStack.destination.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else nav.popFrom(backStack)
                    foodMutationViewModel.acknowledge(saved.requestId)
                }
            }
        }
        CustomFoodScreen(editor, { nav.popFrom(backStack) }, onSave = { food ->
            if (nav.canActFrom(backStack)) foodMutationViewModel.saveFood(backStack.id, food)
        }, busy = busy, feedback = feedbackResult?.error, feedbackToken = feedbackResult,
        onFeedbackHandled = { feedbackResult?.let { foodMutationViewModel.acknowledge(it) } })
    }
    composable("nutrition/log/{foodId}", arguments = listOf(navArgument("foodId") { type = NavType.StringType })) { backStack ->
        val foodId = backStack.arguments?.getString("foodId") ?: return@composable
        val details: FoodDetailsViewModel = viewModel(factory = viewModelFactory { initializer {
                FoodDetailsViewModel(application.nutritionRepository, foodId)
        } })
        val busy by foodLogViewModel.busy.collectAsStateWithLifecycle()
        val result by foodLogViewModel.result.collectAsStateWithLifecycle()
        val mutationBusy by foodMutationViewModel.busy.collectAsStateWithLifecycle()
        val mutationResult by foodMutationViewModel.saveResult.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == backStack.id && it.error != null }
        val mutationFeedbackResult = mutationResult?.takeIf {
            (it.requestId == backStack.id || it.requestId == "favorite:$foodId") && it.error != null
        }
        val detailsState by details.state.collectAsStateWithLifecycle()
        LaunchedEffect(mutationResult, backStack) {
            mutationResult?.takeIf { it.requestId == backStack.id && it.deleted && it.error == null }?.let { saved ->
                backStack.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.popFrom(backStack)) foodMutationViewModel.acknowledge(saved.requestId)
            }
        }
        LaunchedEffect(result, backStack) {
            result?.takeIf { it.requestId == backStack.id }?.let { saved ->
                if (saved.entryId != null) {
                    backStack.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                    val pickerEntry = nav.previousBackStackEntry
                    if (nav.popFrom(backStack)) {
                        foodLogViewModel.acknowledge(saved.requestId)
                        pickerEntry?.savedStateHandle?.set("food_feedback", application.getString(R.string.food_logged))
                    }
                }
            }
        }
        FoodDetailsScreen(details, { nav.popFrom(backStack) }, onConfirm = { snapshot, choice, quantity, _ ->
            if (nav.canActFrom(backStack)) foodLogViewModel.log(backStack.id, snapshot, choice, quantity)
        }, confirmLabel = stringResource(R.string.food_log_action), confirmBusy = busy || mutationBusy,
        feedback = feedbackResult?.error ?: mutationFeedbackResult?.error,
        feedbackToken = feedbackResult ?: mutationFeedbackResult,
        onFeedbackHandled = {
            if (feedbackResult != null) foodLogViewModel.acknowledge(feedbackResult)
            else mutationFeedbackResult?.let { foodMutationViewModel.acknowledge(it) }
        }, onToggleFavorite = {
            if (nav.canActFrom(backStack)) detailsState.detail?.let { foodMutationViewModel.favorite(foodId, it.food.name, !detailsState.favorite) }
        }, onEdit = when (detailsState.detail?.food?.sourceType) {
            FoodSourceType.CUSTOM -> ({ openChild(backStack, "nutrition/custom?foodId=${android.net.Uri.encode(foodId)}&logAfter=false") })
            FoodSourceType.RECIPE -> ({ openChild(backStack, "nutrition/recipe?recipeId=${android.net.Uri.encode(foodId)}&logAfter=false") })
            else -> null
        }, onDelete = when (detailsState.detail?.food?.sourceType) {
            FoodSourceType.CUSTOM -> ({ if (nav.canActFrom(backStack)) foodMutationViewModel.deleteFood(backStack.id, foodId) })
            FoodSourceType.RECIPE -> ({ if (nav.canActFrom(backStack)) foodMutationViewModel.deleteRecipe(backStack.id, foodId) })
            else -> null
        })
    }
    composable("nutrition/catalog") { backStack ->
        FoodCatalogScreen(catalogViewModel, { nav.popFrom(backStack) },
        onFood = { id -> openChild(backStack, "nutrition/food/${android.net.Uri.encode(id)}") })
    }
    composable("nutrition/food/{foodId}", arguments = listOf(navArgument("foodId") { type = NavType.StringType })) { backStack ->
        val foodId = backStack.arguments?.getString("foodId") ?: return@composable
        val details: FoodDetailsViewModel = viewModel(factory = viewModelFactory { initializer {
                FoodDetailsViewModel(application.nutritionRepository, foodId)
        } })
        val detailsState by details.state.collectAsStateWithLifecycle()
        val busy by foodMutationViewModel.busy.collectAsStateWithLifecycle()
        val result by foodMutationViewModel.saveResult.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf {
            (it.requestId == backStack.id || it.requestId == "favorite:$foodId") && it.error != null
        }
        LaunchedEffect(result, backStack) {
            result?.takeIf { it.requestId == backStack.id && it.deleted && it.error == null }?.let { saved ->
                backStack.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.popFrom(backStack)) foodMutationViewModel.acknowledge(saved.requestId)
            }
        }
        FoodDetailsScreen(details, { nav.popFrom(backStack) }, confirmBusy = busy,
        feedback = feedbackResult?.error, feedbackToken = feedbackResult,
        onFeedbackHandled = { feedbackResult?.let { foodMutationViewModel.acknowledge(it) } },
        onToggleFavorite = {
            if (nav.canActFrom(backStack)) detailsState.detail?.let { foodMutationViewModel.favorite(foodId, it.food.name, !detailsState.favorite) }
        }, onEdit = when (detailsState.detail?.food?.sourceType) {
            FoodSourceType.CUSTOM -> ({ openChild(backStack, "nutrition/custom?foodId=${android.net.Uri.encode(foodId)}&logAfter=false") })
            FoodSourceType.RECIPE -> ({ openChild(backStack, "nutrition/recipe?recipeId=${android.net.Uri.encode(foodId)}&logAfter=false") })
            else -> null
        }, onDelete = when (detailsState.detail?.food?.sourceType) {
            FoodSourceType.CUSTOM -> ({ if (nav.canActFrom(backStack)) foodMutationViewModel.deleteFood(backStack.id, foodId) })
            FoodSourceType.RECIPE -> ({ if (nav.canActFrom(backStack)) foodMutationViewModel.deleteRecipe(backStack.id, foodId) })
            else -> null
        })
    }

}

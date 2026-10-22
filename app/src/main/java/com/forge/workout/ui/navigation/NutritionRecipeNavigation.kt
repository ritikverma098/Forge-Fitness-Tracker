package com.forge.workout.ui.navigation

import android.net.Uri
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.forge.workout.nutrition.model.MealType
import com.forge.workout.ui.screens.nutrition.AddFoodScreen
import com.forge.workout.ui.screens.nutrition.FoodDetailsScreen
import com.forge.workout.ui.screens.nutrition.RecipeBuilderScreen
import com.forge.workout.viewmodel.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate

private const val RECIPE_ROUTE = "nutrition/recipe?recipeId={recipeId}&logAfter={logAfter}"

/** Ingredient children share their editor owner's draft rather than creating another editor instance. */
internal fun NavGraphBuilder.nutritionRecipeChildren(
    nav: NavHostController,
    application: ForgeApplication,
    mutations: FoodMutationViewModel,
) {
    fun openChild(source: NavBackStackEntry, route: String) {
        if (nav.canActFrom(source)) nav.navigate(route) { launchSingleTop = true }
    }
    fun ownerEntry(ownerId: String): NavBackStackEntry? =
        runCatching { nav.getBackStackEntry(RECIPE_ROUTE) }.getOrNull()?.takeIf { it.id == ownerId }

    composable(RECIPE_ROUTE, arguments = listOf(
        navArgument("recipeId") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("logAfter") { type = NavType.BoolType; defaultValue = true },
    )) { entry ->
        val editor: RecipeEditorViewModel = viewModel(factory = viewModelFactory { initializer {
            RecipeEditorViewModel(application.nutritionRepository, createSavedStateHandle(), entry.arguments?.getString("recipeId"))
        } })
        val busy by mutations.busy.collectAsStateWithLifecycle()
        val result by mutations.saveResult.collectAsStateWithLifecycle()
        val feedbackResult = result?.takeIf { it.requestId == entry.id && it.error != null }
        LaunchedEffect(result, entry) {
            result?.takeIf { it.requestId == entry.id && it.error == null && it.foodId != null }?.let { saved ->
                entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }
                if (nav.canActFrom(entry)) {
                    if (entry.arguments?.getBoolean("logAfter") == true) {
                        nav.navigate("nutrition/log/${Uri.encode(saved.foodId)}") {
                            popUpTo(entry.destination.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else nav.popFrom(entry)
                    mutations.acknowledge(saved.requestId)
                }
            }
        }
        RecipeBuilderScreen(editor, { nav.popFrom(entry) },
            onAddIngredient = { openChild(entry, "nutrition/ingredients/${Uri.encode(entry.id)}") },
            onSave = { validated ->
                if (nav.canActFrom(entry)) mutations.saveRecipe(entry.id, requireNotNull(validated.recipe),
                    requireNotNull(validated.food), validated.ingredients)
            }, busy = busy, feedback = result?.takeIf { it.requestId == entry.id }?.error, feedbackToken = feedbackResult,
            onFeedbackHandled = { feedbackResult?.let { mutations.acknowledge(it) } })
    }
    composable("nutrition/ingredients/{ownerId}", arguments = listOf(navArgument("ownerId") { type = NavType.StringType })) { entry ->
        val ownerId = entry.arguments?.getString("ownerId") ?: return@composable
        if (ownerEntry(ownerId) == null) {
            LaunchedEffect(entry) { entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }; nav.popFrom(entry) }
            return@composable
        }
        val picker: FoodPickerViewModel = viewModel(factory = viewModelFactory { initializer {
            FoodPickerViewModel(application.nutritionRepository, createSavedStateHandle())
        } })
        AddFoodScreen(picker, { nav.popFrom(entry) },
            onFood = { id -> openChild(entry, "nutrition/ingredient/${Uri.encode(ownerId)}/${Uri.encode(id)}") },
            day = LocalDate.now().toString(), meal = MealType.BREAKFAST, onDay = {}, onMeal = {}, ingredientMode = true,
            onCreateFood = { openChild(entry, "nutrition/custom?logAfter=false&ingredientOwner=${Uri.encode(ownerId)}") })
    }
    composable("nutrition/ingredient/{ownerId}/{foodId}", arguments = listOf(
        navArgument("ownerId") { type = NavType.StringType }, navArgument("foodId") { type = NavType.StringType },
    )) { entry ->
        val foodId = entry.arguments?.getString("foodId") ?: return@composable
        val owner = entry.arguments?.getString("ownerId")?.let(::ownerEntry)
        if (owner == null) {
            LaunchedEffect(entry) { entry.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED }; nav.popFrom(entry) }
            return@composable
        }
        val editor: RecipeEditorViewModel = viewModel(viewModelStoreOwner = owner,
            factory = viewModelFactory { initializer {
                RecipeEditorViewModel(application.nutritionRepository, createSavedStateHandle(), owner.arguments?.getString("recipeId"))
            } })
        val details: FoodDetailsViewModel = viewModel(factory = viewModelFactory { initializer {
            FoodDetailsViewModel(application.nutritionRepository, foodId)
        } })
        var feedback by rememberSaveable(foodId) { mutableStateOf<String?>(null) }
        val unableMessage = stringResource(R.string.recipe_ingredient_not_added)
        val selfMessage = stringResource(R.string.recipe_self_ingredient)
        FoodDetailsScreen(details, { nav.popFrom(entry) }, confirmLabel = stringResource(R.string.recipe_use_ingredient),
            feedback = feedback, onFeedbackHandled = { feedback = null },
            onConfirm = { snapshot, choice, quantity, _ ->
                if (nav.canActFrom(entry)) {
                    val ownRecipeId = owner.arguments?.getString("recipeId")
                    if (ownRecipeId != null && snapshot.foodId == ownRecipeId) feedback = selfMessage
                    else if (editor.addIngredient(snapshot, choice, quantity)) nav.popBackStack(owner.destination.id, inclusive = false)
                    else feedback = unableMessage
                }
            })
    }
}

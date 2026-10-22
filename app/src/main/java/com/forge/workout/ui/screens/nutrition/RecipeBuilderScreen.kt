package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.NutrientCodes
import com.forge.workout.nutrition.model.NutrientValue
import com.forge.workout.nutrition.model.RecipeDraft
import com.forge.workout.nutrition.model.RecipeIngredientDraft
import com.forge.workout.nutrition.model.RecipeValidation
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.viewmodel.RecipeEditorViewModel

@Composable
fun RecipeBuilderScreen(
    viewModel: RecipeEditorViewModel,
    onBack: () -> Unit,
    onAddIngredient: () -> Unit,
    onSave: (RecipeValidation) -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var localDraft by remember(viewModel) { mutableStateOf(state.draft) }
    var pendingDraft by remember(viewModel) { mutableStateOf<RecipeDraft?>(null) }
    LaunchedEffect(state.draft, pendingDraft) {
        if (pendingDraft == null || pendingDraft == state.draft) {
            localDraft = state.draft
            pendingDraft = null
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback, feedbackToken) {
        feedback?.let { message -> snackbar.showSnackbar(message); onFeedbackHandled() }
    }
    // Transform the latest draft, including text entered before the derived state's next frame.
    val update: (RecipeDraft.() -> RecipeDraft) -> Unit = { transform ->
        val next = localDraft.transform()
        if (viewModel.updateDraft(next)) {
            localDraft = next
            pendingDraft = next
        }
    }
    val draft = localDraft
    val perServing = state.perServing.takeIf { state.draft == draft }
    val per100g = remember(perServing, draft.servings, draft.cookedWeightGrams) {
        val servings = draft.servings.trim().replace(',', '.').toDoubleOrNull()
        val grams = draft.cookedWeightGrams.trim().replace(',', '.').toDoubleOrNull()
        if (perServing == null || servings == null || !servings.isFinite() || servings <= 0 ||
            grams == null || !grams.isFinite() || grams <= 0) null
        else perServing.map { nutrient ->
            nutrient.copy(value = nutrient.value?.let { it * servings * 100.0 / grams })
        }.takeIf { nutrients -> nutrients.all { it.value == null || (it.value.isFinite() && it.value <= 1_000_000_000_000.0) } }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScreenHeader(stringResource(R.string.recipe_title), onBack = onBack)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error -> EmptyState(stringResource(R.string.food_details_error_title),
                    stringResource(R.string.recipe_editor_error), Icons.Rounded.Restaurant,
                    viewModel::reload, stringResource(R.string.food_details_retry))
                else -> LazyColumn(Modifier.weight(1f).testTag("recipe_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "identity", contentType = "form") {
                        RecipeCard {
                            RecipeField(draft.name, { value -> update { copy(name = value) } },
                                stringResource(R.string.recipe_name), "recipe_name", "name" in state.invalidFields,
                                R.string.recipe_name_error, busy)
                            RecipeField(draft.servings, { value -> update { copy(servings = value) } },
                                stringResource(R.string.recipe_servings), "recipe_servings", "servings" in state.invalidFields,
                                R.string.recipe_servings_error, busy, numeric = true)
                            Text(stringResource(R.string.recipe_servings_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(key = "ingredients_heading", contentType = "action") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.recipe_ingredients), style = MaterialTheme.typography.titleLarge)
                            OutlinedButton(onClick = onAddIngredient, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recipe_add_ingredient")) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                                Text(stringResource(R.string.recipe_add_ingredient), Modifier.padding(start = 8.dp))
                            }
                            if ("ingredients" in state.invalidFields) RecipeError(R.string.recipe_ingredients_error)
                        }
                    }
                    if (draft.ingredients.isEmpty()) item(key = "empty", contentType = "status") {
                        EmptyState(stringResource(R.string.recipe_empty_title), stringResource(R.string.recipe_empty_description), Icons.Rounded.Restaurant)
                    }
                    itemsIndexed(draft.ingredients, key = { _, ingredient -> "ingredient_${ingredient.key}" }, contentType = { _, _ -> "ingredient" }) { index, ingredient ->
                        IngredientCard(ingredient, index, draft.ingredients.size, busy,
                            invalid = "ingredient:${ingredient.key}" in state.invalidFields,
                            invalidUnits = "ingredient:${ingredient.key}:units" in state.invalidFields,
                            onQuantity = { value -> update { copy(ingredients = ingredients.map { if (it.key == ingredient.key) it.copy(quantity = value) else it }) } },
                            onRemove = { update { copy(ingredients = ingredients.filterNot { it.key == ingredient.key }) } },
                            onMove = { offset -> update { moved(ingredient.key, offset) } })
                    }
                    item(key = "finished_weight", contentType = "form") {
                        RecipeCard {
                            Text(stringResource(R.string.recipe_cooked_weight_title), style = MaterialTheme.typography.titleMedium)
                            RecipeField(draft.cookedWeightGrams, { value -> update { copy(cookedWeightGrams = value) } },
                                stringResource(R.string.recipe_cooked_weight), "recipe_cooked_weight", "cookedWeightGrams" in state.invalidFields,
                                R.string.recipe_cooked_weight_error, busy, numeric = true)
                            Text(stringResource(R.string.recipe_cooked_weight_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item(key = "per_serving", contentType = "preview") {
                        NutritionPreview(R.string.recipe_per_serving, perServing, "recipe_per_serving", "recipe_serving_macro")
                    }
                    item(key = "per_100g", contentType = "preview") {
                        if (draft.cookedWeightGrams.isNotBlank()) NutritionPreview(R.string.recipe_per_100g, per100g,
                            "recipe_per_100g", "recipe_100g_macro")
                        else Text(stringResource(R.string.recipe_missing_weight_help), Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item(key = "save", contentType = "action") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if ("food" in state.invalidFields) RecipeError(R.string.recipe_invalid_food)
                            if ("draft" in state.invalidFields) RecipeError(R.string.recipe_size_error)
                            Button(onClick = { viewModel.validatedRecipe()?.let(onSave) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("recipe_save")) {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary)
                                else Text(stringResource(R.string.recipe_save))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun RecipeDraft.moved(key: String, offset: Int): RecipeDraft {
    val index = ingredients.indexOfFirst { it.key == key }
    val destination = index + offset
    if (index < 0 || destination !in ingredients.indices) return this
    return copy(ingredients = ingredients.toMutableList().apply { add(destination, removeAt(index)) })
}

@Composable
private fun IngredientCard(
    ingredient: RecipeIngredientDraft,
    index: Int,
    total: Int,
    busy: Boolean,
    invalid: Boolean,
    invalidUnits: Boolean,
    onQuantity: (String) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    RecipeCard(Modifier.testTag("recipe_ingredient_${ingredient.key}")) {
        Text(ingredient.snapshot.name, style = MaterialTheme.typography.titleMedium)
        if (ingredient.snapshot.brand.isNotBlank()) Text(ingredient.snapshot.brand, style = MaterialTheme.typography.bodySmall)
        if (ingredient.snapshot.source.isNotBlank()) Text(ingredient.snapshot.source, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ingredient.choice.label ?: stringResource(when (ingredient.choice.unit) {
            FoodUnit.GRAM -> R.string.food_unit_grams
            FoodUnit.MILLILITER -> R.string.food_unit_milliliters
            FoodUnit.SERVING -> R.string.food_unit_servings
        }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        RecipeField(ingredient.quantity, onQuantity, stringResource(R.string.recipe_amount),
            "recipe_ingredient_quantity_${ingredient.key}", invalid, R.string.recipe_ingredient_error, busy, numeric = true)
        if (invalidUnits) RecipeError(R.string.recipe_ingredient_units_error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { onMove(-1) }, enabled = !busy && index > 0,
                modifier = Modifier.size(48.dp).testTag("recipe_up_${ingredient.key}")) {
                Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.recipe_move_up, ingredient.snapshot.name))
            }
            IconButton(onClick = { onMove(1) }, enabled = !busy && index < total - 1,
                modifier = Modifier.size(48.dp).testTag("recipe_down_${ingredient.key}")) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.recipe_move_down, ingredient.snapshot.name))
            }
            IconButton(onClick = onRemove, enabled = !busy, modifier = Modifier.size(48.dp).testTag("recipe_remove_${ingredient.key}")) {
                Icon(Icons.Rounded.Close, stringResource(R.string.recipe_remove_ingredient, ingredient.snapshot.name))
            }
        }
    }
}

@Composable
private fun RecipeCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun RecipeField(value: String, onValue: (String) -> Unit, label: String, tag: String,
    invalid: Boolean, error: Int, busy: Boolean, numeric: Boolean = false) {
    OutlinedTextField(value, onValue, Modifier.fillMaxWidth().testTag(tag), label = { Text(label) }, singleLine = true,
        enabled = !busy, isError = invalid, shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Next), supportingText = if (invalid) ({ Text(stringResource(error)) }) else null)
}

@Composable
private fun RecipeError(resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun NutritionPreview(title: Int, nutrients: List<NutrientValue>?, tag: String, macroTag: String) {
    val codes = remember { listOf(NutrientCodes.ENERGY, NutrientCodes.PROTEIN, NutrientCodes.CARBS, NutrientCodes.FAT) }
    val values = remember(nutrients) { nutrients?.associateBy { it.code } }
    RecipeCard(Modifier.testTag(tag)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        codes.forEach { code ->
            val nutrient = values?.get(code)
            val value = when {
                nutrients == null -> stringResource(R.string.food_pending_value)
                nutrient?.value == null -> stringResource(R.string.food_nutrient_unavailable)
                else -> stringResource(R.string.food_nutrient_value, numberFormat(nutrient.value), nutrient.unit)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(when (code) {
                    NutrientCodes.ENERGY -> R.string.food_energy
                    NutrientCodes.PROTEIN -> R.string.food_protein
                    NutrientCodes.CARBS -> R.string.food_carbs
                    else -> R.string.food_fat
                }), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(value, Modifier.weight(1f).testTag("${macroTag}_$code"), style = MaterialTheme.typography.titleSmall)
            }
        }
        Text(stringResource(if (nutrients == null) R.string.recipe_preview_pending else R.string.recipe_unknown_help),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

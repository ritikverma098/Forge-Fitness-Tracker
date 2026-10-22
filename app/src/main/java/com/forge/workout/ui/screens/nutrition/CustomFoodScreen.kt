package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.nutrition.model.CustomFoodDraft
import com.forge.workout.nutrition.model.CustomFoodFactory
import com.forge.workout.nutrition.model.CustomNutrientDraft
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.NutrientCodes
import com.forge.workout.viewmodel.CustomFoodEditorViewModel

@Composable
fun CustomFoodScreen(
    viewModel: CustomFoodEditorViewModel,
    onBack: () -> Unit,
    onSave: (FoodDetail) -> Unit,
    busy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var localDraft by remember(viewModel) { mutableStateOf(state.draft) }
    var pendingDraft by remember(viewModel) { mutableStateOf<CustomFoodDraft?>(null) }
    LaunchedEffect(state.draft, pendingDraft) {
        if (pendingDraft == null || pendingDraft == state.draft) {
            localDraft = state.draft
            pendingDraft = null
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var showAddNutrient by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(feedback, feedbackToken) {
        feedback?.let { message -> snackbar.showSnackbar(message); onFeedbackHandled() }
    }
    val draft = localDraft
    val core = remember { CustomFoodDraft.coreNutrients() }
    val coreCodes = remember(core) { core.map { it.code }.toSet() }
    val optional = remember(draft.nutrients) { draft.nutrients.filter { it.code !in coreCodes } }
    val available = remember(draft.nutrients) { optionalNutrients.filter { choice -> draft.nutrients.none { it.code == choice.code } } }
    // Apply edits to the latest draft even before the derived ViewModel state reaches the next frame.
    val update: (CustomFoodDraft.() -> CustomFoodDraft) -> Unit = { change ->
        val next = CustomFoodFactory.normalizeUpdate(localDraft, localDraft.change())
        localDraft = next
        pendingDraft = next
        viewModel.updateDraft(next)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            ScreenHeader(stringResource(R.string.custom_food_title), onBack = onBack)
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error -> EmptyState(stringResource(R.string.food_details_error_title),
                    stringResource(R.string.custom_food_editor_error), Icons.Rounded.Restaurant,
                    viewModel::reload, stringResource(R.string.food_details_retry))
                else -> LazyColumn(Modifier.weight(1f).testTag("custom_food_list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "identity", contentType = "card") {
                        CustomFoodCard {
                            EditorField(draft.name, { value -> update { copy(name = value) } }, stringResource(R.string.custom_food_name),
                                "custom_food_name", "name" in state.invalidFields, R.string.custom_food_name_error, busy)
                            EditorField(draft.brand, { value -> update { copy(brand = value) } }, stringResource(R.string.custom_food_brand),
                                "custom_food_brand", "brand" in state.invalidFields, R.string.custom_food_brand_error, busy)
                        }
                    }
                    item(key = "basis", contentType = "card") {
                        CustomFoodCard {
                            Text(stringResource(R.string.custom_food_reference_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.custom_food_reference_description), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            EditorField(draft.baseAmount, { value -> update { copy(baseAmount = value) } }, stringResource(R.string.custom_food_base_amount),
                                "custom_food_base_amount", "baseAmount" in state.invalidFields, R.string.custom_food_positive_error, busy, numeric = true)
                            LazyRow(Modifier.fillMaxWidth().testTag("custom_food_units"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(FoodUnit.entries, key = { it.name }) { unit ->
                                    FilterChip(draft.baseUnit == unit, onClick = { update { copy(baseUnit = unit) } }, enabled = !busy,
                                        label = { Text(editorUnitLabel(unit)) }, modifier = Modifier.testTag("custom_food_unit_${unit.name}"))
                                }
                            }
                        }
                    }
                    item(key = "macros", contentType = "card") {
                        CustomFoodCard {
                            Text(stringResource(R.string.custom_food_macros), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.custom_food_unknown_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            core.forEach { definition ->
                                val nutrient = draft.nutrients.firstOrNull { it.code == definition.code } ?: definition
                                NutrientField(nutrient, { value -> update { withNutrient(nutrient.copy(value = value)) } },
                                    "nutrient:${nutrient.code}" in state.invalidFields, busy)
                            }
                            if ("nutrients" in state.invalidFields) Text(stringResource(R.string.custom_food_nutrients_error),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item(key = "optional_heading", contentType = "heading") {
                        Text(stringResource(R.string.custom_food_optional_nutrients), style = MaterialTheme.typography.titleLarge)
                    }
                    items(optional, key = { "nutrient_${it.code}" }, contentType = { "nutrient" }) { nutrient ->
                        CustomFoodCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NutrientField(nutrient, { value -> update { withNutrient(nutrient.copy(value = value)) } },
                                    "nutrient:${nutrient.code}" in state.invalidFields, busy, Modifier.weight(1f))
                                IconButton(onClick = { update { copy(
                                    nutrients = nutrients.filter { it.code != nutrient.code },
                                    removedNutrientCodes = (removedNutrientCodes + nutrient.code).distinct()) } }, enabled = !busy,
                                    modifier = Modifier.testTag("custom_food_remove_${nutrient.code}")) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.custom_food_remove_nutrient, nutrientLabel(nutrient)))
                                }
                            }
                        }
                    }
                    if (available.isNotEmpty()) item(key = "add_nutrient", contentType = "action") {
                        OutlinedButton(onClick = { showAddNutrient = true }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("custom_food_add_nutrient")) {
                            Text(stringResource(R.string.custom_food_add_nutrient))
                        }
                    }
                    item(key = "measurements", contentType = "card") {
                        CustomFoodCard {
                            Text(stringResource(R.string.custom_food_conversions), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.custom_food_conversion_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (draft.baseUnit != FoodUnit.GRAM) EditorField(draft.gramsPerBase,
                                { value -> update { copy(gramsPerBase = value) } }, stringResource(R.string.custom_food_grams),
                                "custom_food_grams_per_base", "gramsPerBase" in state.invalidFields, R.string.custom_food_measurement_error, busy, numeric = true)
                            if (draft.baseUnit != FoodUnit.MILLILITER) EditorField(draft.millilitersPerBase,
                                { value -> update { copy(millilitersPerBase = value) } }, stringResource(R.string.custom_food_milliliters),
                                "custom_food_milliliters_per_base", "millilitersPerBase" in state.invalidFields, R.string.custom_food_measurement_error, busy, numeric = true)
                        }
                    }
                    item(key = "notes", contentType = "card") {
                        CustomFoodCard {
                            EditorField(draft.notes, { value -> update { copy(notes = value) } }, stringResource(R.string.custom_food_notes),
                                "custom_food_notes", "notes" in state.invalidFields, R.string.custom_food_notes_error, busy, multiline = true)
                        }
                    }
                    item(key = "save", contentType = "action") {
                        if ("food" in state.invalidFields) Text(stringResource(R.string.custom_food_editor_error),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { if (!busy) viewModel.validatedFood()?.let(onSave) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("custom_food_save")) {
                            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)) }
                            Text(stringResource(R.string.custom_food_save))
                        }
                    }
                }
            }
        }
    }
    if (showAddNutrient) AlertDialog(onDismissRequest = { showAddNutrient = false },
        title = { Text(stringResource(R.string.custom_food_add_nutrient)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp).testTag("custom_food_nutrient_choices")) {
                items(available, key = { it.code }) { option ->
                    TextButton(onClick = {
                        if (!busy) {
                            update { withNutrient(CustomNutrientDraft(option.code, option.sourceName, option.unit)) }
                            showAddNutrient = false
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("custom_food_add_${option.code}")) {
                        Text(stringResource(R.string.custom_food_nutrient_label, stringResource(option.label), option.unit))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showAddNutrient = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun CustomFoodCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun EditorField(value: String, onValue: (String) -> Unit, label: String, tag: String, invalid: Boolean,
    error: Int, busy: Boolean, modifier: Modifier = Modifier, numeric: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, onValue, modifier.fillMaxWidth().testTag(tag), enabled = !busy,
        label = { Text(label) }, singleLine = !multiline, minLines = if (multiline) 3 else 1, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Next), shape = RoundedCornerShape(18.dp),
        supportingText = if (invalid) { { Text(stringResource(error)) } } else null)
}

@Composable
private fun NutrientField(nutrient: CustomNutrientDraft, onValue: (String) -> Unit, invalid: Boolean,
    busy: Boolean, modifier: Modifier = Modifier) {
    EditorField(nutrient.value, onValue, stringResource(R.string.custom_food_nutrient_label, nutrientLabel(nutrient), nutrient.unit),
        "custom_food_nutrient_${nutrient.code}", invalid, R.string.custom_food_number_error, busy, modifier, numeric = true)
}

private fun CustomFoodDraft.withNutrient(nutrient: CustomNutrientDraft): CustomFoodDraft = copy(
    nutrients = if (nutrients.any { it.code == nutrient.code }) nutrients.map { if (it.code == nutrient.code) nutrient else it } else nutrients + nutrient,
    removedNutrientCodes = removedNutrientCodes - nutrient.code,
)

@Composable
private fun editorUnitLabel(unit: FoodUnit) = stringResource(when (unit) {
    FoodUnit.GRAM -> R.string.food_unit_grams
    FoodUnit.MILLILITER -> R.string.food_unit_milliliters
    FoodUnit.SERVING -> R.string.food_unit_servings
})

@Composable
private fun nutrientLabel(nutrient: CustomNutrientDraft): String = when (nutrient.code) {
    NutrientCodes.ENERGY -> stringResource(R.string.food_energy)
    NutrientCodes.PROTEIN -> stringResource(R.string.food_protein)
    NutrientCodes.CARBS -> stringResource(R.string.food_carbs)
    NutrientCodes.FAT -> stringResource(R.string.food_fat)
    else -> optionalNutrients.firstOrNull { it.code == nutrient.code }?.let { stringResource(it.label) } ?: nutrient.name
}

private data class OptionalNutrient(val code: String, val sourceName: String, val unit: String, val label: Int)

// Stable USDA nutrient metadata is persisted independently of the UI's translated labels.
private val optionalNutrients = listOf(
    OptionalNutrient("usda:291", "Fiber, total dietary", "g", R.string.custom_nutrient_fiber),
    OptionalNutrient("usda:269", "Sugars, total", "g", R.string.custom_nutrient_sugars),
    OptionalNutrient("usda:606", "Fatty acids, total saturated", "g", R.string.custom_nutrient_saturated_fat),
    OptionalNutrient("usda:307", "Sodium, Na", "mg", R.string.custom_nutrient_sodium),
    OptionalNutrient("usda:301", "Calcium, Ca", "mg", R.string.custom_nutrient_calcium),
    OptionalNutrient("usda:303", "Iron, Fe", "mg", R.string.custom_nutrient_iron),
    OptionalNutrient("usda:306", "Potassium, K", "mg", R.string.custom_nutrient_potassium),
    OptionalNutrient("usda:304", "Magnesium, Mg", "mg", R.string.custom_nutrient_magnesium),
    OptionalNutrient("usda:309", "Zinc, Zn", "mg", R.string.custom_nutrient_zinc),
    OptionalNutrient("usda:401", "Vitamin C, total ascorbic acid", "mg", R.string.custom_nutrient_vitamin_c),
    OptionalNutrient("usda:320", "Vitamin A, RAE", "µg", R.string.custom_nutrient_vitamin_a),
    OptionalNutrient("usda:328", "Vitamin D (D2 + D3)", "µg", R.string.custom_nutrient_vitamin_d),
    OptionalNutrient("usda:418", "Vitamin B-12", "µg", R.string.custom_nutrient_vitamin_b12),
)

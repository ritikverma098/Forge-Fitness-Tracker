package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodServingChoice
import com.forge.workout.nutrition.model.FoodSnapshot
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.NutrientCodes
import com.forge.workout.nutrition.model.NutrientValue
import com.forge.workout.nutrition.model.NutritionMath
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.viewmodel.CatalogViewModel
import com.forge.workout.viewmodel.FoodDetailsViewModel
import com.forge.workout.viewmodel.FoodDetailsUiState
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun FoodDetailsScreen(
    viewModel: FoodDetailsViewModel,
    onBack: () -> Unit,
    onConfirm: ((FoodSnapshot, FoodServingChoice, Double, Double) -> Unit)? = null,
    confirmLabel: String? = null,
    confirmBusy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    onToggleFavorite: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    feedbackToken: Any? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FoodDetailsScreen(state, viewModel::reload, onBack, onConfirm, confirmLabel, confirmBusy, feedback,
        onFeedbackHandled, onToggleFavorite, onEdit, onDelete, feedbackToken = feedbackToken)
}

@Composable
fun FoodDetailsScreen(
    state: FoodDetailsUiState,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onConfirm: ((FoodSnapshot, FoodServingChoice, Double, Double) -> Unit)? = null,
    confirmLabel: String? = null,
    confirmBusy: Boolean = false,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    onToggleFavorite: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    initialChoice: FoodServingChoice? = null,
    initialQuantity: Double? = null,
    extraContent: (@Composable () -> Unit)? = null,
    title: String? = null,
    feedbackToken: Any? = null,
) {
    val snackbar = remember { SnackbarHostState() }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(feedback, feedbackToken) {
        feedback?.let { message -> snackbar.showSnackbar(message); onFeedbackHandled() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(title ?: stringResource(R.string.food_details_title), onBack = onBack, actions = {
                if (state.detail != null && !state.loading && !state.error) {
                    if (onToggleFavorite != null) IconToggleButton(checked = state.favorite,
                        onCheckedChange = { onToggleFavorite() }, enabled = !confirmBusy, modifier = Modifier.testTag("food_favorite")) {
                        Icon(if (state.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            stringResource(if (state.favorite) R.string.food_favorite_remove else R.string.food_favorite_add),
                            tint = if (state.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onEdit != null || onDelete != null) Box {
                        IconButton(onClick = { showActions = true }, enabled = !confirmBusy, modifier = Modifier.testTag("food_actions")) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.food_more_actions))
                        }
                        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                            if (onEdit != null) DropdownMenuItem(text = { Text(stringResource(R.string.food_edit_action)) },
                                onClick = { showActions = false; onEdit() }, enabled = !confirmBusy, modifier = Modifier.testTag("food_edit"))
                            if (onDelete != null) DropdownMenuItem(text = { Text(stringResource(R.string.food_delete_action)) },
                                onClick = { showActions = false; showDelete = true }, enabled = !confirmBusy, modifier = Modifier.testTag("food_delete"))
                        }
                    }
                }
            })
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.food_details_loading))
                    }
                }
                state.error -> EmptyState(stringResource(R.string.food_details_error_title),
                    stringResource(R.string.food_details_error_description), Icons.Rounded.Restaurant,
                    onReload, stringResource(R.string.food_details_retry))
                state.detail == null -> EmptyState(stringResource(R.string.food_details_missing_title),
                    stringResource(R.string.food_details_missing_description), Icons.Rounded.Restaurant)
                else -> FoodDetailsContent(requireNotNull(state.detail), snackbar, onReload, onConfirm, confirmLabel, confirmBusy,
                    initialChoice, initialQuantity, extraContent)
            }
        }
    }
    if (showDelete && onDelete != null && state.detail != null) AlertDialog(
        onDismissRequest = { showDelete = false }, title = { Text(stringResource(R.string.food_delete_title)) },
        text = { Text(stringResource(R.string.food_delete_description)) },
        confirmButton = {
            TextButton(onClick = { if (!confirmBusy) { showDelete = false; onDelete() } }, enabled = !confirmBusy,
                modifier = Modifier.testTag("food_delete_confirm")) { Text(stringResource(R.string.food_delete_action)) }
        }, dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun FoodDetailsContent(
    detail: FoodDetail,
    snackbar: SnackbarHostState,
    onReload: () -> Unit,
    onConfirm: ((FoodSnapshot, FoodServingChoice, Double, Double) -> Unit)?,
    confirmLabel: String?,
    confirmBusy: Boolean,
    initialChoice: FoodServingChoice? = null,
    initialQuantity: Double? = null,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val food = detail.food
    val snapshot = remember(detail) { detail.snapshot() }
    val choices = remember(detail) { runCatching { NutritionMath.choices(detail) }.getOrDefault(emptyList()) }
    if (choices.isEmpty()) {
        EmptyState(stringResource(R.string.food_details_error_title), stringResource(R.string.food_details_error_description),
            Icons.Rounded.Restaurant, onReload, stringResource(R.string.food_details_retry))
        return
    }
    val defaultChoice = remember(choices, food.brand, initialChoice) {
        choices.firstOrNull { it.id == initialChoice?.id }
            ?: if (food.brand.isNotBlank()) choices.firstOrNull { it.portionId != null } ?: choices.first() else choices.first()
    }
    var choiceId by rememberSaveable(food.id) { mutableStateOf(defaultChoice.id) }
    var amount by rememberSaveable(food.id) { mutableStateOf(inputAmount(initialQuantity ?: defaultChoice.suggestedQuantity)) }
    var expandedGroups by rememberSaveable(food.id) { mutableStateOf(listOf<String>()) }
    val choice = choices.firstOrNull { it.id == choiceId } ?: defaultChoice
    val selectionExists = choices.any { it.id == choiceId }
    LaunchedEffect(choices, choiceId) {
        if (!selectionExists) { choiceId = defaultChoice.id; amount = inputAmount(defaultChoice.suggestedQuantity) }
    }
    val quantity = remember(amount) { amount.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } }
    val factor = remember(snapshot, choice, quantity) {
        quantity?.let { runCatching { NutritionMath.factor(snapshot, choice, it) }.getOrNull() }
    }
    val values = remember(snapshot, factor) { factor?.let { runCatching { NutritionMath.scaled(snapshot, it) }.getOrNull() } }
    val valid = selectionExists && quantity != null && factor != null && values != null
    val nutrients = remember(values) { values?.associateBy { it.code }.orEmpty() }
    val groups = remember(snapshot.nutrients) { nutrientGroups(snapshot.nutrients) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val sourceLinkError = stringResource(R.string.food_source_link_failed)

    LazyColumn(Modifier.fillMaxSize().testTag("food_details_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "identity", contentType = "card") {
            FoodDetailsCard {
                Text(food.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(when (food.sourceType) {
                    FoodSourceType.REFERENCE -> if (food.brand.isBlank()) R.string.food_reference else R.string.food_branded_reference
                    FoodSourceType.CUSTOM -> R.string.food_custom
                    FoodSourceType.RECIPE -> R.string.food_recipe
                }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (food.brand.isNotBlank()) Text(stringResource(R.string.food_brand, food.brand), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.food_reference_basis, basisLabel(food.baseAmount, food.baseUnit)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (extraContent != null) item(key = "destination", contentType = "controls") {
            FoodDetailsCard { extraContent() }
        }
        item(key = "serving", contentType = "card") {
            FoodDetailsCard {
                Text(stringResource(R.string.food_serving_title), style = MaterialTheme.typography.titleMedium)
                LazyRow(Modifier.fillMaxWidth().testTag("food_serving_choices"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(choices, key = { it.id }) { option ->
                        FilterChip(selected = option.id == choice.id,
                            onClick = { if (option.id != choiceId) { choiceId = option.id; amount = inputAmount(option.suggestedQuantity) } },
                            label = { Text(servingLabel(option)) }, modifier = Modifier.testTag("food_serving_${option.id}"))
                    }
                }
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth().testTag("food_quantity"),
                    label = { Text(stringResource(R.string.food_quantity)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    isError = !valid, supportingText = {
                        if (!valid) Text(stringResource(if (factor != null && values == null) R.string.food_amount_too_large else R.string.food_quantity_error))
                    }, shape = RoundedCornerShape(18.dp))
                if (valid) Text(
                    if (choice.portionId == null) basisLabel(requireNotNull(quantity), choice.unit)
                    else stringResource(R.string.food_selected_serving, numberFormat(requireNotNull(quantity)), servingLabel(choice)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "macros", contentType = "card") {
            FoodDetailsCard {
                Text(stringResource(R.string.food_nutrition_for_serving), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.food_energy), style = MaterialTheme.typography.titleMedium)
                Text(nutrientValue(nutrients[NutrientCodes.ENERGY], valid), Modifier.testTag("food_macro_energy"),
                    style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                MacroRow(stringResource(R.string.food_protein), nutrients[NutrientCodes.PROTEIN], valid, "food_macro_protein")
                MacroRow(stringResource(R.string.food_carbs), nutrients[NutrientCodes.CARBS], valid, "food_macro_carbs")
                MacroRow(stringResource(R.string.food_fat), nutrients[NutrientCodes.FAT], valid, "food_macro_fat")
                Text(stringResource(R.string.food_unavailable_explanation), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onConfirm != null) item(key = "confirm", contentType = "action") {
            Button(onClick = { if (valid && !confirmBusy) onConfirm(snapshot, choice, requireNotNull(quantity), requireNotNull(factor)) },
                enabled = valid && !confirmBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("food_confirm")) {
                if (confirmBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(confirmLabel ?: stringResource(R.string.food_confirm_serving))
            }
        }
        if (groups.isNotEmpty()) item(key = "more_heading", contentType = "heading") {
            Text(stringResource(R.string.food_more_nutrients), style = MaterialTheme.typography.titleLarge)
        }
        groups.forEach { (group, rows) ->
            val expanded = group.name in expandedGroups
            item(key = "group_${group.name}", contentType = "heading") {
                val title = stringResource(group.title)
                val action = stringResource(if (expanded) R.string.food_collapse_nutrients else R.string.food_expand_nutrients, title)
                TextButton(onClick = {
                    expandedGroups = if (expanded) expandedGroups - group.name else expandedGroups + group.name
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("food_group_${group.name}")
                    .semantics { contentDescription = action }) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
            }
            if (expanded) items(rows, key = { "nutrient_${it.code}" }, contentType = { "nutrient" }) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("food_nutrient_${row.code}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(nutrientValue(nutrients[row.code], valid), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (food.notes.isNotBlank()) item(key = "notes", contentType = "card") {
            FoodDetailsCard {
                Text(stringResource(R.string.food_notes), style = MaterialTheme.typography.titleMedium)
                Text(food.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (food.source.isNotBlank() || food.sourceId != null || food.sourceUrl != null) item(key = "source", contentType = "card") {
            FoodDetailsCard {
                Text(stringResource(R.string.food_source_title), style = MaterialTheme.typography.titleMedium)
                if (food.source.isNotBlank()) Text(food.source, style = MaterialTheme.typography.bodyMedium)
                food.sourceId?.let { Text(stringResource(R.string.food_source_id, it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                food.sourceUrl?.takeIf { CatalogViewModel.validUrl(it) }?.let { address ->
                    TextButton(onClick = {
                        try { uriHandler.openUri(address) } catch (_: Exception) { scope.launch { snackbar.showSnackbar(sourceLinkError) } }
                    }) {
                        Text(stringResource(R.string.food_view_source))
                        Spacer(Modifier.size(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodDetailsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun MacroRow(label: String, nutrient: NutrientValue?, valid: Boolean, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(nutrientValue(nutrient, valid), Modifier.testTag(tag), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun nutrientValue(nutrient: NutrientValue?, valid: Boolean): String = when {
    !valid -> stringResource(R.string.food_pending_value)
    nutrient?.value == null -> stringResource(R.string.food_nutrient_unavailable)
    else -> stringResource(R.string.food_nutrient_value, numberFormat(nutrient.value), nutrient.unit)
}

@Composable
private fun servingLabel(choice: FoodServingChoice): String = choice.label ?: stringResource(when (choice.unit) {
    FoodUnit.GRAM -> R.string.food_unit_grams
    FoodUnit.MILLILITER -> R.string.food_unit_milliliters
    FoodUnit.SERVING -> R.string.food_unit_servings
})

@Composable
private fun basisLabel(amount: Double, unit: FoodUnit): String = when (unit) {
    FoodUnit.GRAM -> stringResource(R.string.food_gram_amount, numberFormat(amount))
    FoodUnit.MILLILITER -> stringResource(R.string.food_milliliter_amount, numberFormat(amount))
    FoodUnit.SERVING -> pluralStringResource(R.plurals.food_serving_amount, if (amount == 1.0) 1 else 2, numberFormat(amount))
}

private fun inputAmount(value: Double) = value.toBigDecimal().stripTrailingZeros().toPlainString()

private enum class FoodNutrientGroup(val title: Int) {
    VITAMINS(R.string.food_vitamins), MINERALS(R.string.food_minerals), OTHER(R.string.food_other_nutrients),
}

private val vitaminNames = listOf("vitamin", "thiamin", "riboflavin", "niacin", "folate", "folic", "choline", "biotin",
    "pantothenic", "retinol", "carotene", "cryptoxanthin", "tocopherol", "tocotrienol")
private val mineralNames = listOf("sodium", "calcium", "iron", "potassium", "phosphorus", "magnesium", "zinc", "selenium",
    "copper", "manganese", "fluoride", "iodine", "iodide", "chloride", "chromium", "molybdenum")

private fun nutrientGroups(nutrients: List<NutrientValue>): List<Pair<FoodNutrientGroup, List<NutrientValue>>> {
    val core = setOf(NutrientCodes.ENERGY, NutrientCodes.PROTEIN, NutrientCodes.CARBS, NutrientCodes.FAT)
    val grouped = nutrients.filter { it.code !in core }.groupBy { nutrient ->
        val name = nutrient.name.lowercase(Locale.ROOT)
        when {
            vitaminNames.any { it in name } -> FoodNutrientGroup.VITAMINS
            mineralNames.any { it in name } -> FoodNutrientGroup.MINERALS
            else -> FoodNutrientGroup.OTHER
        }
    }
    return FoodNutrientGroup.entries.mapNotNull { group -> grouped[group]?.let { rows ->
        group to rows.sortedBy { it.name.lowercase(Locale.ROOT) }
    } }
}

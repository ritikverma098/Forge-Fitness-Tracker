package com.forge.workout.ui.screens.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.nutrition.model.FoodBrowseFilter
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.MealType
import com.forge.workout.ui.components.EmptyState
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.numberFormat
import com.forge.workout.viewmodel.FoodPickerViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun AddFoodScreen(
    viewModel: FoodPickerViewModel,
    onBack: () -> Unit,
    onFood: (String) -> Unit,
    day: String,
    meal: MealType,
    onDay: (String) -> Unit,
    onMeal: (MealType) -> Unit,
    onCreateFood: (() -> Unit)? = null,
    feedback: String? = null,
    onFeedbackHandled: () -> Unit = {},
    onCreateRecipe: (() -> Unit)? = null,
    ingredientMode: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(feedback) {
        feedback?.let { message -> snackbar.showSnackbar(message); onFeedbackHandled() }
    }
    var query by rememberSaveable { mutableStateOf(state.query) }
    var pendingQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.query, pendingQuery) {
        if (pendingQuery == null || pendingQuery == state.query) {
            query = state.query
            pendingQuery = null
        }
    }
    val setQuery: (String) -> Unit = { value ->
        query = value.take(250)
        pendingQuery = query
        viewModel.setQuery(query)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        ScreenHeader(stringResource(if (ingredientMode) R.string.recipe_add_ingredient else R.string.add_food_title),
            if (ingredientMode) stringResource(R.string.recipe_choose_food)
            else stringResource(R.string.add_food_destination, mealLabel(meal), dayLabel(day)), onBack)
        LazyColumn(Modifier.weight(1f).testTag("food_picker_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ingredientMode) item(key = "destination", contentType = "controls") {
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    DayMealSelector(day, meal, onDay, onMeal, Modifier.fillMaxWidth().padding(16.dp))
                }
            }
            item(key = "query", contentType = "controls") {
                OutlinedTextField(query, setQuery, Modifier.fillMaxWidth().testTag("food_picker_query"),
                    label = { Text(stringResource(R.string.add_food_search)) }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { setQuery("") }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.add_food_clear_search))
                        }
                    }, shape = RoundedCornerShape(18.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
            }
            item(key = "filters", contentType = "controls") {
                LazyRow(Modifier.fillMaxWidth().testTag("food_picker_filters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FoodBrowseFilter.entries, key = { it.name }) { filter ->
                        FilterChip(state.filter == filter, onClick = { viewModel.setFilter(filter) },
                            label = { Text(filterLabel(filter)) }, modifier = Modifier.testTag("picker_filter_${filter.name}"))
                    }
                }
            }
            if (onCreateFood != null) item(key = "create", contentType = "action") {
                OutlinedButton(onClick = onCreateFood, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("picker_create_food")) {
                    Text(stringResource(R.string.add_food_create))
                }
            }
            if (onCreateRecipe != null && !ingredientMode) item(key = "create_recipe", contentType = "action") {
                OutlinedButton(onClick = onCreateRecipe, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("picker_create_recipe")) {
                    Text(stringResource(R.string.recipe_create))
                }
            }
            if (state.error) item(key = "error", contentType = "status") {
                EmptyState(stringResource(R.string.add_food_error_title), stringResource(R.string.add_food_error_description),
                    Icons.Rounded.Restaurant, viewModel::reload, stringResource(R.string.add_food_retry))
            }
            if (!state.loading && !state.error && state.foods.isEmpty()) item(key = "empty", contentType = "status") {
                EmptyState(stringResource(R.string.add_food_empty_title), stringResource(R.string.add_food_empty_description), Icons.Rounded.Restaurant)
            }
            items(state.foods, key = { "food_${it.id}" }, contentType = { "food" }) { food -> PickerFoodRow(food) { onFood(food.id) } }
            if (state.loading) item(key = "loading", contentType = "status") {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.add_food_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.hasMore && !state.loading && !state.error) item(key = "more", contentType = "action") {
                OutlinedButton(onClick = viewModel::loadMore,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("picker_load_more")) {
                    Text(stringResource(R.string.add_food_load_more))
                }
            }
            if (state.limitReached) item(key = "refine", contentType = "status") {
                Text(stringResource(R.string.add_food_refine_search), Modifier.padding(12.dp).testTag("picker_refine_search"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayMealSelector(
    day: String,
    meal: MealType,
    onDay: (String) -> Unit,
    onMeal: (MealType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDates by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDates = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("picker_day")) {
                Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(dayLabel(day))
            }
            TextButton(onClick = { onDay(LocalDate.now().toString()) }, modifier = Modifier.heightIn(min = 48.dp).testTag("picker_today")) {
                Text(stringResource(R.string.add_food_today))
            }
        }
        LazyRow(Modifier.fillMaxWidth().testTag("picker_meals"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MealType.entries, key = { it.name }) { option ->
                FilterChip(meal == option, onClick = { onMeal(option) }, label = { Text(mealLabel(option)) },
                    modifier = Modifier.testTag("picker_meal_${option.name}"))
            }
        }
    }
    if (showDates) {
        val windowSize = LocalWindowInfo.current.containerSize
        val compactHeight = with(LocalDensity.current) { windowSize.height.toDp() < 480.dp }
        val date = remember(day) { LocalDate.parse(day) }
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1..9999,
            initialDisplayMode = if (compactHeight) DisplayMode.Input else DisplayMode.Picker)
        DatePickerDialog(onDismissRequest = { showDates = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { selected ->
                        onDay(Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        showDates = false
                    }
                }, enabled = picker.selectedDateMillis != null, modifier = Modifier.testTag("picker_date_confirm")) {
                    Text(stringResource(R.string.add_food_date_confirm))
                }
            }, dismissButton = { TextButton(onClick = { showDates = false }) { Text(stringResource(R.string.cancel)) } }) {
            DatePicker(picker, title = { Text(stringResource(R.string.add_food_choose_day), Modifier.padding(24.dp)) },
                modifier = Modifier.heightIn(max = 480.dp).testTag("picker_date_dialog"))
        }
    }
}

@Composable
private fun PickerFoodRow(food: FoodEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("picker_food_${food.id}"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(food.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (food.brand.isNotBlank()) Text(food.brand, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pickerFoodBasis(food), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (food.sourceType != FoodSourceType.REFERENCE) Text(stringResource(
                    if (food.sourceType == FoodSourceType.CUSTOM) R.string.food_custom else R.string.food_recipe),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun pickerFoodBasis(food: FoodEntity): String = when (food.baseUnit) {
    FoodUnit.GRAM -> stringResource(R.string.food_gram_amount, numberFormat(food.baseAmount))
    FoodUnit.MILLILITER -> stringResource(R.string.food_milliliter_amount, numberFormat(food.baseAmount))
    FoodUnit.SERVING -> pluralStringResource(R.plurals.food_serving_amount, if (food.baseAmount == 1.0) 1 else 2, numberFormat(food.baseAmount))
}

@Composable
private fun filterLabel(filter: FoodBrowseFilter): String = stringResource(when (filter) {
    FoodBrowseFilter.ALL -> R.string.add_food_all
    FoodBrowseFilter.RECENT -> R.string.add_food_recent
    FoodBrowseFilter.FREQUENT -> R.string.add_food_frequent
    FoodBrowseFilter.FAVORITES -> R.string.add_food_favorites
    FoodBrowseFilter.INDIAN -> R.string.add_food_indian
    FoodBrowseFilter.BRANDED -> R.string.add_food_branded
})

@Composable
fun mealLabel(meal: MealType): String = stringResource(when (meal) {
    MealType.BREAKFAST -> R.string.add_food_breakfast
    MealType.LUNCH -> R.string.add_food_lunch
    MealType.DINNER -> R.string.add_food_dinner
    MealType.SNACK -> R.string.add_food_snack
})

private fun dayLabel(day: String): String = LocalDate.parse(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

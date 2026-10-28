package com.forge.workout.navigation

import android.net.Uri
import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import com.forge.workout.ForgeApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.catalog.CatalogInstallResult
import com.forge.workout.nutrition.catalog.CatalogLoadState
import com.forge.workout.nutrition.catalog.FoodCatalogService
import com.forge.workout.nutrition.model.CatalogPackEntity
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodNutrientEntity
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.model.NutrientCodes
import com.forge.workout.nutrition.model.MealType
import com.forge.workout.nutrition.model.FoodSnapshot
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.NutrientValue
import com.forge.workout.nutrition.model.DiaryEntity
import com.forge.workout.nutrition.model.NutritionGoalEntity
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.backup.BackupCodec
import com.forge.workout.nutrition.model.*
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.ui.navigation.destinations
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import org.robolectric.Shadows.shadowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Runs Forge's actual graph and screens with an isolated Room/DataStore application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NutritionNavigationApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NutritionEntryNavigationHostTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as NutritionNavigationApplication
    private val repository get() = app.repository
    private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Before fun prepare() = runBlocking {
        repository.ensureSeeded()
        app.preferences.setTheme(ThemeMode.SYSTEM)
        app.preferences.setRest(0)
        waitForText(R.string.home_subtitle)
    }

    @After fun closeApplicationData() {
        val application = app
        compose.activityRule.scenario.close()
        application.database.close()
    }

    @Test fun `five main tabs retain host bounds and Exercises is a child of Home and empty Workout`() {
        assertEquals(listOf("home", "workout", "nutrition", "history", "progress"), destinations.map { it.route })
        assertTabs(R.string.home)
        val height = hostHeight()
        listOf(
            R.string.workout to R.string.ready_to_train,
            R.string.nutrition to R.string.nutrition_daily_subtitle,
            R.string.history to R.string.history_subtitle,
            R.string.progress to R.string.progress_subtitle,
            R.string.home to R.string.home_subtitle,
        ).forEach { (tab, marker) ->
            openTab(tab, marker)
            assertEquals(height, hostHeight(), 1f)
        }

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(hasText(text(R.string.home_exercises), substring = false))
        compose.onNodeWithText(text(R.string.home_exercises)).assertIsDisplayed().performClick()
        assertLibraryChild(height)
        compose.onNode(hasSetTextAction()).performTextInput("Bench Press")
        compose.activityRule.scenario.recreate()
        waitForText(R.string.library_subtitle)
        compose.onNode(hasSetTextAction()).assertTextContains("Bench Press")
        backTo(R.string.home_subtitle)
        assertTabs(R.string.home)
        assertEquals(height, hostHeight(), 1f)

        openTab(R.string.workout, R.string.ready_to_train)
        compose.onNodeWithText(text(R.string.exercise_library)).performScrollTo().assertIsDisplayed().performClick()
        assertLibraryChild(height)
        backTo(R.string.ready_to_train)
        assertTabs(R.string.workout)
        compose.onNodeWithText(text(R.string.routines)).performScrollTo().assertIsDisplayed().performClick()
        waitForText(R.string.routines_subtitle)
        compose.onAllNodes(tabRole).assertCountEquals(0)
        backTo(R.string.ready_to_train)
        assertTabs(R.string.workout)
        assertTrue(runBlocking { repository.observeWorkouts().first().isEmpty() })
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test fun `returning from Home children retains bottom bar composition tab positions and host bounds`() {
        val bodyExecutions = AtomicInteger()
        val trace = object : CompositionTracer {
            override fun isTraceInProgress() = true
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                if (info.substringBefore(" (") == "androidx.compose.material3.NavigationBar") {
                    bodyExecutions.incrementAndGet()
                }
            }
            override fun traceEventEnd() = Unit
        }
        compose.runOnUiThread { Composer.setTracer(trace) }
        try {
            // Observe a fresh composition first so a renamed/disabled trace hook cannot pass with zero counts.
            compose.activityRule.scenario.recreate()
            waitForText(R.string.home_subtitle)
            waitForTag("forge_bottom_navigation")
            assertTabs(R.string.home)
            assertTrue("The tracer must observe the initial Material NavigationBar body", bodyExecutions.get() > 0)
            val originalBar = compose.onNodeWithTag("forge_bottom_navigation").fetchSemanticsNode()
            val originalBarBounds = originalBar.boundsInRoot
            val originalHost = compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot
            val originalTabs = destinations.associate { destination ->
                destination.label to compose.onNode(tabRole and hasText(text(destination.label), substring = false))
                    .fetchSemanticsNode().boundsInRoot
            }

            fun assertBounds(label: String, expected: Rect, actual: Rect) {
                assertEquals("$label left", expected.left, actual.left, .5f)
                assertEquals("$label top", expected.top, actual.top, .5f)
                assertEquals("$label right", expected.right, actual.right, .5f)
                assertEquals("$label bottom", expected.bottom, actual.bottom, .5f)
            }
            fun assertRetainedHome() {
                assertTabs(R.string.home)
                val bar = compose.onNodeWithTag("forge_bottom_navigation").fetchSemanticsNode()
                assertEquals("Returning Home must retain the bar's semantics identity", originalBar.id, bar.id)
                assertBounds("Bottom bar", originalBarBounds, bar.boundsInRoot)
                assertBounds("Navigation host", originalHost,
                    compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot)
                originalTabs.forEach { (label, bounds) ->
                    assertBounds(text(label), bounds,
                        compose.onNode(tabRole and hasText(text(label), substring = false)).fetchSemanticsNode().boundsInRoot)
                }
            }

            listOf(false, true, false).forEach { systemBack ->
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
                    .performScrollToNode(hasText(text(R.string.home_exercises), substring = false))
                compose.onNodeWithText(text(R.string.home_exercises)).assertIsDisplayed().performClick()
                waitForText(R.string.library_subtitle)
                compose.onAllNodes(tabRole).assertCountEquals(0)
                assertBounds("Child navigation host", originalHost,
                    compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot)
                compose.runOnIdle { bodyExecutions.set(0) }
                if (systemBack) {
                    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    waitForText(R.string.home_subtitle)
                } else backTo(R.string.home_subtitle)
                assertEquals("Back to unchanged Home must not execute Material NavigationBar again", 0, bodyExecutions.get())
                assertRetainedHome()
            }

            compose.onNodeWithContentDescription(text(R.string.overview_settings)).assertIsDisplayed().performClick()
            waitForText(R.string.settings_subtitle)
            compose.onAllNodes(tabRole).assertCountEquals(0)
            compose.runOnIdle { bodyExecutions.set(0) }
            backTo(R.string.home_subtitle)
            assertEquals("Settings Back must retain the same Material bar body", 0, bodyExecutions.get())
            assertRetainedHome()
            assertTrue(runBlocking { repository.observeWorkouts().first().isEmpty() })
        } finally {
            compose.runOnUiThread { Composer.setTracer(null) }
        }
    }

    @Test fun `active Workout retains sets when opening library routines Nutrition and recreating`() {
        val original = runBlocking {
            val bench = repository.observeExercises().first().single { it.name == "Bench Press" }
            val id = repository.startWorkout("Navigation retained workout")
            repository.addExercise(id, bench.id)
            val set = repository.observeWorkouts().first().single().exercises.single().sets.single()
            repository.updateSet(set.copy(weight = 85.0, reps = 7, completed = false))
            repository.observeWorkouts().first().single()
        }
        openTab(R.string.workout, R.string.workout_name)
        compose.onNodeWithText("Navigation retained workout").assertIsDisplayed()
        val height = hostHeight()
        compose.onNodeWithContentDescription(text(R.string.exercise_library)).performClick()
        assertLibraryChild(height)
        backTo(R.string.workout_name)
        assertTabs(R.string.workout)
        compose.onNodeWithContentDescription(text(R.string.routines)).performClick()
        waitForText(R.string.routines_subtitle)
        compose.onAllNodes(tabRole).assertCountEquals(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText(R.string.workout_name)
        assertTabs(R.string.workout)

        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        assertEquals(height, hostHeight(), 1f)
        compose.activityRule.scenario.recreate()
        waitForText(R.string.nutrition_daily_subtitle)
        assertTabs(R.string.nutrition)
        openTab(R.string.workout, R.string.workout_name)
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText("Bench Press", substring = false))
        compose.onNodeWithText("Bench Press", substring = false).assertIsDisplayed()
        assertEquals(height, hostHeight(), 1f)
        assertEquals(original, runBlocking { repository.observeWorkouts().first().single() })
    }

    @Test fun `encoded food ID opens details and Back preserves catalog scroll and Nutrition tab`() {
        val foodId = "navigation:food / one"
        val foodName = "Navigation reference food"
        val original = runBlocking {
            app.nutritionRepository.upsertCatalogPack(CatalogPackEntity("navigation-fixture", "Navigation fixture", "1",
                "https://example.com/", "CC0", "Navigation fixture source", foodCount = 1))
            app.nutritionRepository.upsertFood(FoodDetail(
                FoodEntity(id = foodId, name = foodName, source = "Navigation fixture source",
                    sourceType = FoodSourceType.REFERENCE, packId = "navigation-fixture", gramsPerBase = 100.0),
                nutrients = listOf(FoodNutrientEntity(foodId, NutrientCodes.ENERGY, "Energy", "kcal", 150.0)),
            ))
            app.nutritionRepository.food(foodId)
        }
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        val height = hostHeight()
        compose.onNodeWithContentDescription(text(R.string.catalog_title)).performClick()
        waitForText(R.string.catalog_title)
        val count = app.resources.getQuantityString(R.plurals.catalog_total_foods, 1, 1)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(count, substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag("catalog_food_$foodId"))
        val catalogTop = compose.onNodeWithTag("catalog_food_$foodId").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("catalog_food_$foodId").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasText(foodName, substring = false))
        compose.onNodeWithText(foodName, substring = false).assertIsDisplayed()
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_macro_energy"))
        compose.onNodeWithTag("food_macro_energy").assertTextContains("150 kcal")
        compose.onNodeWithTag("food_confirm").assertDoesNotExist()
        compose.onAllNodes(tabRole).assertCountEquals(0)
        assertEquals(height, hostHeight(), 1f)

        backTo(R.string.catalog_title)
        compose.onNodeWithTag("catalog_food_$foodId").assertIsDisplayed()
        assertEquals(catalogTop, compose.onNodeWithTag("catalog_food_$foodId").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.onAllNodes(tabRole).assertCountEquals(0)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText(R.string.nutrition_daily_subtitle)
        assertTabs(R.string.nutrition)
        assertEquals(height, hostHeight(), 1f)
        assertEquals(original, runBlocking { app.nutritionRepository.food(foodId) })
    }

    @Test fun `food search logs a serving to selected meal and returns to retained picker without duplicates`() {
        val foodId = "custom:navigation-log"
        runBlocking {
            app.nutritionRepository.upsertFood(FoodDetail(FoodEntity(id = foodId, name = "Forge test food"),
                listOf(FoodNutrientEntity(foodId, NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
                    FoodNutrientEntity(foodId, NutrientCodes.CARBS, "Carbohydrates", "g", null))))
        }
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithContentDescription(text(R.string.add_food_title)).performClick()
        waitForText(R.string.add_food_title)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_meal_LUNCH"))
        compose.onNodeWithTag("picker_meal_LUNCH").performClick()
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("food_picker_query"))
        compose.onNodeWithTag("food_picker_query").performTextInput("forge test")
        waitForPickerFood(foodId)
        compose.onNodeWithTag("picker_food_$foodId").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_quantity"))
        compose.onNodeWithTag("food_quantity").performTextReplacement("150")
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.add_food_title)
        waitForText(R.string.food_logged)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("food_picker_query"))
        compose.onNodeWithTag("food_picker_query").assertTextContains("forge test")
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_meal_LUNCH"))
        compose.onNodeWithTag("picker_meal_LUNCH").assertIsSelected()
        val day = java.time.LocalDate.now().toString()
        val entries = runBlocking { app.nutritionRepository.observeDiary(day).first() }
        assertEquals(1, entries.size)
        assertEquals(MealType.LUNCH, entries.single().meal)
        assertEquals(300.0, entries.single().kcal!!, 0.0)
        assertEquals(200.0, entries.single().snapshot.nutrients.first { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertEquals(null, entries.single().carbs)
        backTo(R.string.nutrition_daily_subtitle)
        assertTabs(R.string.nutrition)
        compose.onAllNodesWithText(text(R.string.food_logged), substring = false).assertCountEquals(0)
        openTab(R.string.workout, R.string.ready_to_train)
    }

    @Test fun `create custom food from picker then favorite edit and delete preserves logged nutrition`() {
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithContentDescription(text(R.string.add_food_title)).performClick()
        waitForText(R.string.add_food_title)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_create_food"))
        compose.onNodeWithTag("picker_create_food").performClick()
        waitForText(R.string.custom_food_title)
        editCustomField("custom_food_name", "Forge homemade test")
        editCustomField("custom_food_brand", "My kitchen")
        editCustomField("custom_food_nutrient_energy", "200")
        editCustomField("custom_food_notes", "Original note")
        compose.onNodeWithTag("custom_food_list").performScrollToNode(hasTestTag("custom_food_save"))
        compose.onNodeWithTag("custom_food_save").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        val food = runBlocking { app.nutritionRepository.searchFoods("Forge homemade").single() }
        assertEquals(FoodSourceType.CUSTOM, food.sourceType)
        assertTrue(food.id.startsWith("custom:"))
        compose.onNodeWithTag("food_favorite").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(text(R.string.food_favorite_remove)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_quantity"))
        compose.onNodeWithTag("food_quantity").performTextReplacement("150")
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.add_food_title)
        waitForText(R.string.food_logged)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("food_picker_query"))
        compose.onNodeWithTag("food_picker_query").performTextReplacement("Forge homemade")
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_food_${food.id}"))
        compose.onNodeWithTag("picker_food_${food.id}").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("food_actions").performClick()
        compose.onNodeWithTag("food_edit").performClick()
        waitForText(R.string.custom_food_title)
        editCustomField("custom_food_nutrient_energy", "400")
        editCustomField("custom_food_notes", "Updated note")
        compose.onNodeWithTag("custom_food_list").performScrollToNode(hasTestTag("custom_food_save"))
        compose.onNodeWithTag("custom_food_save").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Updated note", substring = false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                || runBlocking { app.nutritionRepository.food(food.id)?.food?.notes == "Updated note" }
        }
        val day = java.time.LocalDate.now().toString()
        assertEquals(400.0, runBlocking { app.nutritionRepository.food(food.id) }!!.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        val original = runBlocking { app.nutritionRepository.observeDiary(day).first() }.single()
        assertEquals(300.0, original.kcal!!, 0.0)
        assertEquals(200.0, original.snapshot.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        compose.onNodeWithTag("food_actions").performClick()
        compose.onNodeWithTag("food_delete").performClick()
        compose.onNodeWithTag("food_delete_confirm").performClick()
        waitForText(R.string.add_food_title)
        assertEquals(null, runBlocking { app.nutritionRepository.food(food.id) })
        assertEquals(original, runBlocking { app.nutritionRepository.observeDiary(day).first() }.single())
        backTo(R.string.nutrition_daily_subtitle)
        assertTabs(R.string.nutrition)
    }

    @Test fun `recipe ingredients retain owner across child screens then calculated servings log and edits preserve diary snapshot`() {
        runBlocking {
            listOf("custom:recipe-a" to ("Recipe component A" to 200.0)).forEach { (id, definition) ->
                app.nutritionRepository.upsertFood(FoodDetail(FoodEntity(id = id, name = definition.first),
                    listOf(FoodNutrientEntity(id, NutrientCodes.ENERGY, "Energy", "kcal", definition.second)) +
                        listOf(NutrientCodes.PROTEIN, NutrientCodes.CARBS, NutrientCodes.FAT).map {
                            FoodNutrientEntity(id, it, it, "g", 0.0)
                        }))
            }
        }
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithContentDescription(text(R.string.add_food_title)).performClick()
        waitForText(R.string.add_food_title)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_create_recipe"))
        compose.onNodeWithTag("picker_create_recipe").performClick()
        waitForText(R.string.recipe_title)
        editRecipeField("recipe_name", "Forge test bowl")
        editRecipeField("recipe_servings", "2")
        addRecipeIngredient("custom:recipe-a", "Recipe component A", "100")
        compose.onNodeWithTag("recipe_list").performScrollToNode(hasTestTag("recipe_add_ingredient"))
        compose.onNodeWithTag("recipe_add_ingredient").performClick()
        waitForText(R.string.recipe_add_ingredient)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_create_food"))
        compose.onNodeWithTag("picker_create_food").performClick()
        waitForText(R.string.custom_food_title)
        editCustomField("custom_food_name", "Recipe component B")
        editCustomField("custom_food_nutrient_energy", "100")
        listOf(NutrientCodes.PROTEIN, NutrientCodes.CARBS, NutrientCodes.FAT).forEach {
            editCustomField("custom_food_nutrient_$it", "0")
        }
        compose.onNodeWithTag("custom_food_list").performScrollToNode(hasTestTag("custom_food_save"))
        compose.onNodeWithTag("custom_food_save").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        val createdIngredient = runBlocking { app.nutritionRepository.searchFoods("Recipe component B").single() }
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_quantity"))
        compose.onNodeWithTag("food_quantity").performTextReplacement("200")
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.recipe_title)
        assertTrue(runBlocking { app.nutritionRepository.observeDiary(java.time.LocalDate.now().toString()).first() }.isEmpty())
        editRecipeField("recipe_cooked_weight", "500")
        compose.activityRule.scenario.recreate()
        waitForText(R.string.recipe_title)
        compose.onNodeWithTag("recipe_list").performScrollToNode(hasTestTag("recipe_save"))
        compose.onNodeWithTag("recipe_save").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        val recipeFood = runBlocking { app.nutritionRepository.searchFoods("Forge test bowl").single() }
        assertEquals(FoodSourceType.RECIPE, recipeFood.sourceType)
        val recipe = runBlocking { app.nutritionRepository.recipe(recipeFood.id) }!!
        assertEquals(2, recipe.ingredients.size)
        assertEquals(listOf("custom:recipe-a", createdIngredient.id), recipe.sortedIngredients.map { it.foodId })
        assertEquals(2.0, recipe.recipe.servings, 0.0)
        assertEquals(500.0, recipe.recipe.cookedWeightGrams!!, 0.0)
        assertEquals(250.0, recipeFood.gramsPerBase!!, 0.0)
        assertEquals(200.0, runBlocking { app.nutritionRepository.food(recipeFood.id) }!!.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.add_food_title)
        waitForText(R.string.food_logged)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("food_picker_query"))
        compose.onNodeWithTag("food_picker_query").performTextReplacement("Forge test bowl")
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_food_${recipeFood.id}"))
        compose.onNodeWithTag("picker_food_${recipeFood.id}").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("food_actions").performClick()
        compose.onNodeWithTag("food_edit").performClick()
        waitForText(R.string.recipe_title)
        editRecipeField("recipe_servings", "4")
        compose.onNodeWithTag("recipe_list").performScrollToNode(hasTestTag("recipe_save"))
        compose.onNodeWithTag("recipe_save").performClick()
        waitForText(R.string.food_details_title)
        val updated = runBlocking { app.nutritionRepository.food(recipeFood.id) }!!
        assertEquals(100.0, updated.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertEquals(125.0, updated.food.gramsPerBase!!, 0.0)
        val logged = runBlocking { app.nutritionRepository.observeDiary(java.time.LocalDate.now().toString()).first() }.single()
        assertEquals(200.0, logged.kcal!!, 0.0)
        assertEquals(200.0, logged.snapshot.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
    }

    @Test fun `daily goals and historical snapshot edit update the selected day immediately and survive recreation`() {
        val today = java.time.LocalDate.now()
        val snapshot = FoodSnapshot(foodId = "reference:absent-pack", name = "Saved meal reference", source = "Original source",
            sourceType = FoodSourceType.REFERENCE, nutrients = listOf(
                NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
                NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 10.0),
                NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", 25.0),
                NutrientValue(NutrientCodes.FAT, "Fat", "g", null)))
        val entryId = runBlocking {
            app.nutritionRepository.saveDiary(DiaryEntity(day = today.minusDays(1).toString(), meal = MealType.DINNER,
                snapshot = snapshot.copy(name = "Yesterday reference"), quantity = 50.0, unit = FoodUnit.GRAM, factor = .5))
            app.nutritionRepository.saveDiary(DiaryEntity(day = today.toString(), meal = MealType.BREAKFAST,
                foodId = snapshot.foodId, snapshot = snapshot, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0))
        }
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithTag("nutrition_goals").performClick()
        waitForTag("goal_list")
        mapOf("kcal" to "2200", "protein" to "150", "carbs" to "250", "fat" to "70").forEach { (field, value) ->
            compose.onNodeWithTag("goal_list").performScrollToNode(hasTestTag("goal_$field"))
            compose.onNodeWithTag("goal_$field").performTextReplacement(value)
        }
        compose.activityRule.scenario.recreate()
        waitForTag("goal_list")
        compose.onNodeWithTag("goal_list").performScrollToNode(hasTestTag("goal_save"))
        compose.onNodeWithTag("goal_save").performClick()
        waitForText(R.string.nutrition_daily_subtitle)
        assertEquals(2200.0, runBlocking { app.nutritionRepository.observeGoal(today.toString()).first() }!!.kcal!!, 0.0)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_entry_$entryId"))
        compose.onNodeWithTag("nutrition_entry_$entryId").performClick()
        waitForText(R.string.nutrition_diary_edit)
        waitForTag("food_details_list")
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("picker_meal_LUNCH"))
        compose.onNodeWithTag("picker_meal_LUNCH").performClick()
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_quantity"))
        compose.onNodeWithTag("food_quantity").performTextReplacement("150")
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.nutrition_daily_subtitle)
        val updated = runBlocking { app.nutritionRepository.diaryEntry(entryId) }!!
        assertEquals(300.0, updated.kcal!!, 0.0)
        assertEquals(MealType.LUNCH, updated.meal)
        assertEquals(snapshot, updated.snapshot)
        assertEquals(null, updated.fat)
        assertEquals(null, runBlocking { app.nutritionRepository.food(snapshot.foodId!!) })
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_previous_day"))
        compose.onNodeWithTag("nutrition_previous_day").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("nutrition_list").performScrollToNode(hasText("Yesterday reference", substring = false)) }.isSuccess
        }
        assertEquals(null, runBlocking { app.nutritionRepository.observeGoal(today.minusDays(1).toString()).first() })
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_today"))
        compose.onNodeWithTag("nutrition_today").performClick()
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_add_LUNCH"))
        compose.onNodeWithTag("nutrition_add_LUNCH").performClick()
        waitForText(R.string.add_food_title)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_meal_LUNCH"))
        compose.onNodeWithTag("picker_meal_LUNCH").assertIsSelected()
        backTo(R.string.nutrition_daily_subtitle)
        assertTabs(R.string.nutrition)
    }

    @Test fun `body weight estimates completed workout then budget toggle and historical override update without duplicate counting`() {
        val today = java.time.LocalDate.now()
        val start = today.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val workoutId = runBlocking {
            app.nutritionRepository.saveGoal(NutritionGoalEntity(today.toString(), kcal = 1000.0))
            app.nutritionRepository.saveDiary(DiaryEntity(day = today.toString(), meal = MealType.BREAKFAST,
                snapshot = FoodSnapshot(name = "Energy test meal", nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 100.0))),
                quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0))
            app.database.workoutDao().insertWorkout(Workout(name = "Energy test workout", startTime = start, endTime = start + 3_600_000))
        }
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_body_weights"))
        compose.onNodeWithTag("nutrition_body_weights").performClick()
        waitForTag("body_weight_list")
        waitForScrollableTag("body_weight_list", "body_weight_quantity")
        compose.onNodeWithTag("body_weight_quantity").performTextReplacement("80")
        compose.onNodeWithTag("body_weight_list").performScrollToNode(hasTestTag("body_weight_save"))
        compose.onNodeWithTag("body_weight_save").performClick()
        compose.waitUntil(10_000) { runBlocking { app.nutritionRepository.workoutEnergy(workoutId)?.calculatedKcal == 105.0 } }
        backTo(R.string.nutrition_daily_subtitle)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_remaining"))
        compose.onNodeWithTag("nutrition_remaining").assertTextContains("900", substring = true)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_energy_settings"))
        compose.onNodeWithTag("nutrition_energy_settings").performClick()
        waitForTag("energy_settings_list")
        compose.onNodeWithTag("energy_settings_list").performScrollToNode(hasTestTag("energy_settings_include"))
        compose.onNodeWithTag("energy_settings_include").performClick()
        compose.onNodeWithTag("energy_settings_list").performScrollToNode(hasTestTag("energy_settings_save"))
        compose.onNodeWithTag("energy_settings_save").performClick()
        waitForText(R.string.nutrition_daily_subtitle)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_remaining"))
        compose.onNodeWithTag("nutrition_remaining").assertTextContains("1,005", substring = true)
        openTab(R.string.history, R.string.history_subtitle)
        compose.onNodeWithTag("history_workout_list").performScrollToNode(hasText("Energy test workout", substring = false))
        compose.onNodeWithText("Energy test workout").performClick()
        waitForTag("workout_detail_list")
        waitForScrollableTag("workout_detail_list", "workout_energy_edit")
        compose.onNodeWithTag("workout_energy_edit").performClick()
        waitForTag("workout_energy_list")
        compose.onNodeWithTag("workout_energy_list").performScrollToNode(hasTestTag("workout_energy_manual"))
        compose.onNodeWithTag("workout_energy_manual").performTextReplacement("125")
        compose.activityRule.scenario.recreate()
        waitForTag("workout_energy_list")
        compose.onNodeWithTag("workout_energy_list").performScrollToNode(hasTestTag("workout_energy_save"))
        compose.onNodeWithTag("workout_energy_save").performClick()
        waitForTag("workout_detail_list")
        val estimate = runBlocking { app.nutritionRepository.workoutEnergy(workoutId) }!!
        assertEquals(125.0, estimate.manualKcal!!, 0.0)
        assertEquals(105.0, estimate.calculatedKcal, 0.0)
        assertEquals(1, runBlocking { app.nutritionRepository.observeWorkoutEnergy(today.toString()).first() }.size)
        backTo(R.string.history_subtitle)
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_remaining"))
        compose.onNodeWithTag("nutrition_remaining").assertTextContains("1,025", substring = true)
    }

    @Test fun `nutrition history opens earlier diary for snapshot-safe editing while workout History remains independent`() {
        val today = java.time.LocalDate.now()
        val older = today.minusDays(2).toString()
        val snapshot = FoodSnapshot(foodId = "reference:history-absent", name = "Historical bowl", nutrients = listOf(
            NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 250.0),
            NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 10.0),
            NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", null)))
        val oldId = runBlocking {
            app.nutritionRepository.saveGoal(NutritionGoalEntity(older, kcal = 500.0))
            app.nutritionRepository.saveGoal(NutritionGoalEntity(today.toString(), kcal = 2000.0))
            app.nutritionRepository.saveDiary(DiaryEntity(day = today.toString(), meal = MealType.SNACK,
                snapshot = snapshot.copy(name = "Today's bowl"), quantity = 50.0, unit = FoodUnit.GRAM, factor = .5))
            app.nutritionRepository.saveDiary(DiaryEntity(day = older, meal = MealType.LUNCH,
                foodId = snapshot.foodId, snapshot = snapshot, quantity = 200.0, unit = FoodUnit.GRAM, factor = 2.0))
        }
        openTab(R.string.nutrition, R.string.nutrition_daily_subtitle)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_history"))
        compose.onNodeWithTag("nutrition_history").performClick()
        waitForScrollableTag("nutrition_history_list", "nutrition_history_day_$older")
        compose.activityRule.scenario.recreate()
        waitForScrollableTag("nutrition_history_list", "nutrition_history_day_$older")
        compose.onNodeWithTag("nutrition_history_day_$older").performClick()
        waitForText(R.string.nutrition_daily_subtitle)
        waitForScrollableTag("nutrition_list", "nutrition_entry_$oldId")
        compose.onNodeWithTag("nutrition_entry_$oldId").performClick()
        waitForText(R.string.nutrition_diary_edit)
        waitForScrollableTag("food_details_list", "food_quantity")
        compose.onNodeWithTag("food_quantity").performTextReplacement("100")
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.nutrition_daily_subtitle)
        val updated = runBlocking { app.nutritionRepository.diaryEntry(oldId) }!!
        assertEquals(older, updated.day)
        assertEquals(250.0, updated.kcal!!, 0.0)
        assertEquals(snapshot, updated.snapshot)
        assertEquals(null, updated.carbs)
        assertEquals(125.0, runBlocking { app.nutritionRepository.observeDiary(today.toString()).first() }.single().kcal!!, 0.0)
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("nutrition_history"))
        compose.onNodeWithTag("nutrition_history").performClick()
        waitForScrollableTag("nutrition_history_list", "nutrition_history_day_$older")
        compose.onNodeWithTag("nutrition_history_day_$older").assertTextContains("250", substring = true)
        backTo(R.string.nutrition_daily_subtitle)
        openTab(R.string.history, R.string.history_subtitle)
        assertTabs(R.string.history)
    }

    @Test fun `fresh database restores portable nutrition through picker and immediately navigates logs food and starts workout`() {
        val today = java.time.LocalDate.now().toString()
        val foodId = "custom:restore-food"
        val original = FoodDetail(FoodEntity(id = foodId, name = "Restored food", notes = "Keep this note"),
            listOf(FoodNutrientEntity(foodId, NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
                FoodNutrientEntity(foodId, NutrientCodes.PROTEIN, "Protein", "g", 10.0),
                FoodNutrientEntity(foodId, NutrientCodes.CARBS, "Carbohydrates", "g", 20.0),
                FoodNutrientEntity(foodId, NutrientCodes.FAT, "Fat", "g", 5.0),
                FoodNutrientEntity(foodId, "usda:401", "Vitamin C", "mg", null)))
        val serialized = runBlocking {
            app.nutritionRepository.saveCustomFood(original)
            app.nutritionRepository.saveDiary(DiaryEntity(day = today, meal = MealType.LUNCH,
                foodId = foodId, snapshot = original.snapshot(), quantity = 150.0, unit = FoodUnit.GRAM, factor = 1.5))
            val recipe = RecipeFactory.validate(RecipeDraft(id = "recipe:restore-recipe", name = "Restored recipe", servings = "2",
                cookedWeightGrams = "300", ingredients = listOf(RecipeIngredientDraft(snapshot = original.snapshot(),
                    choice = NutritionMath.choices(original).first(), quantity = "100"))))
            app.nutritionRepository.saveRecipe(recipe.recipe!!, recipe.food!!, recipe.ingredients)
            app.nutritionRepository.saveCustomFood(original.copy(nutrients = original.nutrients.map {
                if (it.code == NutrientCodes.ENERGY) it.copy(value = 400.0) else it
            }))
            app.nutritionRepository.saveFavorite(FavoriteEntity(foodId, original.food.name))
            app.nutritionRepository.saveGoal(NutritionGoalEntity(today, kcal = 1000.0, protein = 120.0))
            app.nutritionRepository.saveBodyWeight(BodyWeightEntity(day = today, kg = 80.0))
            val start = java.time.LocalDate.parse(today).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val workout = app.database.workoutDao().insertWorkout(Workout(name = "Restored training", startTime = start, endTime = start + 3_600_000))
            val exercise = app.database.workoutDao().exercises().first { it.name == "Bench Press" }.id
            val workoutExercise = app.database.workoutDao().insertWorkoutExercise(WorkoutExercise(workoutId = workout, exerciseId = exercise, orderIndex = 0))
            app.database.workoutDao().insertSet(WorkoutSet(workoutExerciseId = workoutExercise, setNumber = 1, weight = 70.0, reps = 8, completed = true))
            val routine = app.database.workoutDao().insertRoutine(Routine(name = "Restored routine"))
            app.database.workoutDao().insertRoutineExercise(RoutineExercise(routineId = routine, exerciseId = exercise, orderIndex = 0))
            app.nutritionRepository.updateWorkoutEstimate(workout, WorkoutActivityPreset.RESISTANCE, .5, 125.0)
            BackupCodec.encode(app.backups.snapshot())
        }
        val application = app
        runBlocking(Dispatchers.IO) { application.database.clearAllTables(); application.repository.ensureSeeded() }
        assertTrue(runBlocking { app.nutritionRepository.observeDiary(today).first() }.isEmpty())
        val uri = Uri.parse("content://forge-test/portable-nutrition.json")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(serialized.toByteArray(Charsets.UTF_8)))
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        waitForText(R.string.settings_subtitle)
        compose.onNodeWithTag("backup_restore").performScrollTo().performClick()
        val picker = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        compose.runOnUiThread {
            compose.activity.activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
        waitForTag("backup_confirm_restore")
        compose.onNodeWithTag("backup_confirm_restore").performClick()
        waitForText(R.string.home_subtitle)
        waitForText(R.string.backup_restored)
        // Actual pointer events catch a lingering dialog or snackbar covering the tab bar.
        compose.onNode(tabRole and hasText(text(R.string.nutrition), substring = false)).performTouchInput { click(center) }
        waitForText(R.string.nutrition_daily_subtitle)
        assertTabs(R.string.nutrition)
        val saved = runBlocking { app.nutritionRepository.observeDiary(today).first() }.single()
        assertEquals(300.0, saved.kcal!!, 0.0)
        assertEquals(original.snapshot(), saved.snapshot)
        assertEquals("Keep this note", runBlocking { app.nutritionRepository.food(foodId) }!!.food.notes)
        assertEquals(1, runBlocking { app.nutritionRepository.recipe("recipe:restore-recipe") }!!.ingredients.size)
        compose.onNodeWithContentDescription(text(R.string.add_food_title)).performClick()
        waitForText(R.string.add_food_title)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("food_picker_query"))
        compose.onNodeWithTag("food_picker_query").performTextReplacement("Restored food")
        waitForPickerFood(foodId)
        compose.onNodeWithTag("picker_food_$foodId").performClick()
        waitForScrollableTag("food_details_list", "food_confirm")
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.add_food_title)
        waitForText(R.string.food_logged)
        backTo(R.string.nutrition_daily_subtitle)
        val entries = runBlocking { app.nutritionRepository.observeDiary(today).first() }
        assertEquals(2, entries.size)
        assertEquals(700.0, entries.sumOf { it.kcal!! }, 0.0)
        openTab(R.string.history, R.string.history_subtitle)
        compose.onNodeWithTag("history_workout_list").performScrollToNode(hasText("Restored training", substring = false))
        openTab(R.string.progress, R.string.progress_subtitle)
        openTab(R.string.home, R.string.home_subtitle)
        compose.onNodeWithContentDescription(text(R.string.overview_settings)).performClick()
        waitForText(R.string.settings_subtitle)
        backTo(R.string.home_subtitle)
        openTab(R.string.workout, R.string.ready_to_train)
        compose.onNodeWithText(text(R.string.start_workout)).performClick()
        waitForText(R.string.workout_name)
        compose.onNodeWithTag("workout_exercise_list").performScrollToNode(hasText(text(R.string.add_exercise), substring = false))
        compose.onAllNodesWithText(text(R.string.add_exercise))[0].performClick()
        waitForText(R.string.library_picker_subtitle)
        compose.onNodeWithTag("library_exercise_results").performScrollToNode(hasText("Bench Press", substring = false))
        compose.onNodeWithText("Bench Press", substring = false).performClick()
        // Returning to a scrolled list need not compose the off-screen name field.
        waitForTag("workout_exercise_list")
        compose.onNodeWithTag("workout_exercise_list")
            .performScrollToNode(hasText(text(R.string.workout_name), substring = false))
        waitForText(R.string.workout_name)
        assertTrue(runBlocking { repository.observeWorkouts().first() }.single { it.workout.endTime == null }.exercises.isNotEmpty())
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForScrollableTag(listTag: String, tag: String) {
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
    }

    private fun editRecipeField(tag: String, value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("recipe_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recipe_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }

    private fun waitForPickerFood(id: String) {
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("picker_food_$id"))
            }.isSuccess
        }
    }

    private fun addRecipeIngredient(id: String, query: String, quantity: String) {
        compose.onNodeWithTag("recipe_list").performScrollToNode(hasTestTag("recipe_add_ingredient"))
        compose.onNodeWithTag("recipe_add_ingredient").performClick()
        waitForText(R.string.recipe_add_ingredient)
        compose.onAllNodesWithTag("picker_day").assertCountEquals(0)
        compose.onNodeWithTag("food_picker_list").performScrollToNode(hasTestTag("food_picker_query"))
        compose.onNodeWithTag("food_picker_query").performTextReplacement(query)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("picker_food_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("picker_food_$id").performClick()
        waitForText(R.string.food_details_title)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_quantity"))
        compose.onNodeWithTag("food_quantity").performTextReplacement(quantity)
        compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag("food_confirm"))
        compose.onNodeWithTag("food_confirm").performClick()
        waitForText(R.string.recipe_title)
    }

    private fun editCustomField(tag: String, value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("custom_food_list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("custom_food_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performTextReplacement(value)
    }

    private fun assertLibraryChild(height: Float) {
        waitForText(R.string.library_subtitle)
        compose.onNodeWithContentDescription(text(R.string.back)).assertIsDisplayed()
        compose.onAllNodes(tabRole).assertCountEquals(0)
        assertEquals(height, hostHeight(), 1f)
    }

    private fun assertTabs(selected: Int) {
        compose.onAllNodes(tabRole).assertCountEquals(5)
        listOf(R.string.home, R.string.workout, R.string.nutrition, R.string.history, R.string.progress).forEach {
            compose.onNode(tabRole and hasText(text(it), substring = false)).assertIsDisplayed()
        }
        compose.onNode(tabRole and hasText(text(selected), substring = false)).assertIsSelected()
    }

    private fun openTab(destination: Int, marker: Int) {
        compose.onNode(tabRole and hasText(text(destination), substring = false)).performClick()
        waitForText(marker)
        assertTabs(destination)
    }

    private fun backTo(marker: Int) {
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForText(marker)
    }

    private fun waitForText(resource: Int) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(resource), substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun hostHeight() = compose.onNodeWithTag("forge_navigation_host").fetchSemanticsNode().boundsInRoot.height
    private fun text(resource: Int) = app.getString(resource)
}

class NutritionNavigationApplication : ForgeApplication() {
    public override val database: ForgeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, ForgeDatabase::class.java).build()
    }
    override val repository: WorkoutRepository by lazy { RoomWorkoutRepository(database) }
    override val catalogs: FoodCatalogService = NavigationCatalogService()
    override val preferences: PreferencesRepository by lazy {
        PreferencesRepository(this, "nutrition-navigation-${UUID.randomUUID()}")
    }
}

/** Navigation exercises entry wiring without installing the separately tested reference asset. */
private class NavigationCatalogService : FoodCatalogService {
    override val state = MutableStateFlow(CatalogLoadState())
    private val result = CatalogInstallResult(
        CatalogPackEntity("navigation-fixture", "Navigation fixture", "1", "https://example.com/", "CC0", "Test fixture"),
        added = 0, updated = 0, removed = 0, alreadyInstalled = true,
    )
    override suspend fun ensureBundled() = result
    override suspend fun installDocument(uri: Uri): CatalogInstallResult = error("File import is outside this navigation fixture")
    override suspend fun installUrl(url: String, expectedSha256: String): CatalogInstallResult = error("Download is outside this navigation fixture")
    override fun cancel() = Unit
    override fun clearError() = Unit
}

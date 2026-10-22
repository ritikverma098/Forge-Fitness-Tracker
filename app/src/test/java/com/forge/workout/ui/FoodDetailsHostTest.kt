package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.ui.screens.nutrition.FoodDetailsScreen
import com.forge.workout.viewmodel.FoodDetailsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Room details feed the screen; confirmation is captured without writing a diary entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FoodDetailsHostTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore
    private lateinit var model: FoodDetailsViewModel
    private lateinit var lifecycle: Lifecycle
    private val confirmations = mutableListOf<ConfirmedFood>()

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
    }

    @After fun closeDatabase() {
        store.clear()
        database.close()
    }

    @Test fun `branded source serving and hundred gram choices show exact scaled label nutrients`() {
        show(brandedFood())
        assertQuantity("1")
        assertMacro(NutrientCodes.ENERGY, "164.9")
        assertMacro(NutrientCodes.PROTEIN, "16")
        choose("GRAM")
        assertQuantity("100")
        assertMacro(NutrientCodes.ENERGY, "194")
        assertMacro(NutrientCodes.PROTEIN, "18.82")
        choose("portion:label")
        quantity().performTextReplacement("2")
        assertMacro(NutrientCodes.ENERGY, "329.8")
        confirm().assertIsEnabled().performClick()
        val selected = confirmations.single()
        assertEquals("brand", selected.snapshot.foodId)
        assertEquals("label", selected.choice.portionId)
        assertEquals(2.0, selected.quantity, 0.0)
        assertEquals(1.7, selected.factor, 0.000001)
        assertEquals(100.0, selected.snapshot.baseAmount, 0.0)
        assertEquals(194.0, selected.snapshot.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertTrue(runBlocking { repository.observeDiary("2026-10-12").first() }.isEmpty())
    }

    @Test fun `native milliliter reference keeps its actual base and only offers known gram conversion`() {
        show(FoodDetail(FoodEntity(id = "drink", name = "Custom drink", baseAmount = 250.0,
            baseUnit = FoodUnit.MILLILITER, millilitersPerBase = 250.0, gramsPerBase = 200.0),
            listOf(FoodNutrientEntity("drink", NutrientCodes.ENERGY, "Energy", "kcal", 80.0),
                FoodNutrientEntity("drink", NutrientCodes.PROTEIN, "Protein", "g", 4.0))))
        assertQuantity("250")
        assertMacro(NutrientCodes.ENERGY, "80")
        choose("GRAM")
        assertQuantity("100")
        assertMacro(NutrientCodes.ENERGY, "40")
        confirm().performClick()
        assertEquals(FoodUnit.GRAM, confirmations.single().choice.unit)
        assertEquals(.5, confirmations.single().factor, 0.0)
        choose("MILLILITER")
        quantity().performTextReplacement("125")
        assertMacro(NutrientCodes.ENERGY, "40")
        confirm().performClick()
        assertEquals(FoodUnit.MILLILITER, confirmations.last().choice.unit)
        assertEquals(.5, confirmations.last().factor, 0.0)
        assertEquals(250.0, confirmations.last().snapshot.baseAmount, 0.0)
    }

    @Test fun `unknown nutrients remain unavailable and invalid quantity cannot confirm before valid recovery`() {
        show(FoodDetail(FoodEntity(id = "plain", name = "Food without liquid equivalent", gramsPerBase = 100.0),
            listOf(FoodNutrientEntity("plain", NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
                FoodNutrientEntity("plain", NutrientCodes.CARBS, "Carbohydrates", "g", null),
                FoodNutrientEntity("plain", "fiber", "Fiber", "g", null))))
        compose.onNodeWithTag("food_serving_MILLILITER").assertDoesNotExist()
        assertMacro(NutrientCodes.CARBS, "Unavailable")
        scrollTo("food_group_OTHER")
        compose.onNodeWithTag("food_group_OTHER").performClick()
        scrollTo("food_nutrient_fiber")
        compose.onNode(hasAnyAncestor(hasTestTag("food_nutrient_fiber")) and hasText("Unavailable"),
            useUnmergedTree = true).assertIsDisplayed()
        for (invalid in listOf("", "0", "-10", "NaN", "1000001")) {
            quantity().performTextReplacement(invalid)
            confirm().assertIsNotEnabled()
            assertTrue(confirmations.isEmpty())
        }
        quantity().performTextReplacement("150")
        assertMacro(NutrientCodes.ENERGY, "300")
        assertMacro(NutrientCodes.CARBS, "Unavailable")
        confirm().assertIsEnabled().performClick()
        val selected = confirmations.single()
        assertEquals(1.5, selected.factor, 0.0)
        assertNull(selected.snapshot.nutrients.single { it.code == NutrientCodes.CARBS }.value)
        assertNull(selected.snapshot.nutrients.single { it.code == "fiber" }.value)
        assertFalse(model.state.value.error)
    }

    private fun show(detail: FoodDetail) {
        runBlocking { repository.upsertFood(detail) }
        model = FoodDetailsViewModel(repository, detail.food.id)
        store.put("details", model)
        compose.setContent {
            val owner = LocalLifecycleOwner.current
            SideEffect { lifecycle = owner.lifecycle }
            MaterialTheme {
                FoodDetailsScreen(model, onBack = {}, onConfirm = { snapshot, choice, quantity, factor ->
                    confirmations += ConfirmedFood(snapshot, choice, quantity, factor)
                }, confirmLabel = "Use this serving")
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Lifecycle.State.RESUMED, lifecycle.currentState) }
        // A StateFlow-only predicate advances Compose's clock but not Robolectric's paused Main Looper.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("food_details_list").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.error)
            assertEquals(detail.food.id, model.state.value.detail!!.food.id)
        }
    }

    private fun assertMacro(code: String, value: String) {
        val tag = "food_macro_$code"
        scrollTo(tag)
        val within: SemanticsMatcher = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
        compose.onNode(within and hasText(value, substring = true), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag(tag))
    private fun quantity() = scrollTo("food_quantity").let { compose.onNodeWithTag("food_quantity") }
    private fun assertQuantity(value: String) = quantity().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    private fun confirm() = scrollTo("food_confirm").let { compose.onNodeWithTag("food_confirm") }
    private fun choose(id: String) {
        scrollTo("food_serving_choices")
        compose.onNodeWithTag("food_serving_$id").performClick()
    }

    private data class ConfirmedFood(val snapshot: FoodSnapshot, val choice: FoodServingChoice, val quantity: Double, val factor: Double)
    private fun brandedFood() = FoodDetail(
        FoodEntity(id = "brand", name = "Breaded chicken nuggets", brand = "Example brand", source = "USDA branded label",
            sourceId = "2617100", sourceUrl = "https://fdc.nal.usda.gov/food-details/2617100/nutrients",
            sourceType = FoodSourceType.REFERENCE, gramsPerBase = 100.0),
        listOf(FoodNutrientEntity("brand", NutrientCodes.ENERGY, "Energy", "kcal", 194.0),
            FoodNutrientEntity("brand", NutrientCodes.PROTEIN, "Protein", "g", 18.82),
            FoodNutrientEntity("brand", NutrientCodes.CARBS, "Carbohydrates", "g", 8.24),
            FoodNutrientEntity("brand", NutrientCodes.FAT, "Fat", "g", 9.41)),
        listOf(FoodPortionEntity("brand", "label", "5 nuggets (85g)", 85.0, grams = 85.0, source = "USDA branded label")),
    )
}

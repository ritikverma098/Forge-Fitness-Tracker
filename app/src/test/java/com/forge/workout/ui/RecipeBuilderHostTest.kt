package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.FoodServingChoice
import com.forge.workout.nutrition.model.FoodSnapshot
import com.forge.workout.nutrition.model.FoodUnit
import com.forge.workout.nutrition.model.NutrientCodes
import com.forge.workout.nutrition.model.NutrientValue
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.ui.screens.nutrition.RecipeBuilderScreen
import com.forge.workout.viewmodel.RecipeEditorViewModel
import java.util.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w600dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RecipeBuilderHostTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var model: RecipeEditorViewModel
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        model = RecipeEditorViewModel(RoomNutritionRepository(database), SavedStateHandle())
        store = ViewModelStore().apply { put("recipe", model) }
    }

    @After fun tearDown() {
        store.clear()
        database.close()
    }

    @Test fun `rejected oversize input keeps accepted text and rapid recovery edits across fields`() {
        val ingredient = FoodSnapshot(foodId = "ingredient", name = "Ingredient", gramsPerBase = 100.0,
            nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 200.0)))
        assertTrue(model.addIngredient(ingredient, FoodServingChoice("GRAM", unit = FoodUnit.GRAM,
            baseQuantity = 1.0, suggestedQuantity = 100.0), 100.0))
        compose.setContent { MaterialTheme { RecipeBuilderScreen(model, {}, {}, {}) } }
        awaitState { it.draft.ingredients.size == 1 }
        compose.onNodeWithTag("recipe_name").performTextReplacement("Accepted bowl")
        awaitState { it.draft.name == "Accepted bowl" }
        val acceptedIngredients = model.state.value.draft.ingredients

        // This compresses above the saved draft's 256 KiB bound, rather than just violating name validation.
        val random = Random(41)
        val oversize = CharArray(600_000) { ('!'.code + random.nextInt(90)).toChar() }.concatToString()
        compose.onNodeWithTag("recipe_name").performTextReplacement(oversize)
        awaitState { "draft" in it.invalidFields }
        assertText("recipe_name", "Accepted bowl")
        assertEquals(acceptedIngredients, model.state.value.draft.ingredients)

        // Dispatch both field edits against the existing composition, before any new frame is rendered.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("recipe_name").performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("Recovered bowl"))
        }
        compose.onNodeWithTag("recipe_servings").performSemanticsAction(SemanticsActions.SetText) {
            it(AnnotatedString("2"))
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        awaitState { it.draft.name == "Recovered bowl" && it.draft.servings == "2" && it.invalidFields.isEmpty() }
        assertText("recipe_name", "Recovered bowl")
        assertText("recipe_servings", "2")
        assertEquals(acceptedIngredients, model.state.value.draft.ingredients)
        compose.runOnIdle {
            val validation = model.validatedRecipe()
            assertNotNull(validation)
            assertEquals("Recovered bowl", validation!!.recipe!!.name)
            assertEquals(2.0, validation.recipe!!.servings, 0.0)
            assertEquals(100.0, validation.perServing.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        }
    }

    private fun awaitState(predicate: (com.forge.workout.viewmodel.RecipeEditorState) -> Boolean) {
        compose.waitUntil(10_000) {
            // Advance Robolectric's paused Main Looper through Compose semantics synchronization.
            compose.onNodeWithTag("recipe_name").fetchSemanticsNode()
            predicate(model.state.value)
        }
    }

    private fun assertText(tag: String, value: String) = compose.onNodeWithTag(tag).assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
}

package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.ui.screens.nutrition.BodyWeightScreen
import com.forge.workout.ui.screens.nutrition.FoodDetailsScreen
import com.forge.workout.viewmodel.BodyWeightViewModel
import com.forge.workout.viewmodel.FoodDetailsViewModel
import com.forge.workout.viewmodel.FoodLogResult
import com.forge.workout.viewmodel.FoodMutationResult
import com.forge.workout.viewmodel.NutritionEnergyMutationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class NutritionFeedbackHostTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
    }
    @After fun tearDown() { store.clear(); database.close() }

    @Test fun `identical success feedback from a second operation cancels the first callback and acknowledges only the latest`() {
        val model = BodyWeightViewModel(repository, SavedStateHandle()).also { store.put("weight", it) }
        val token = mutableStateOf(NutritionEnergyMutationResult("weight", operationId = 1))
        val handled = mutableListOf<NutritionEnergyMutationResult>()
        val message = "Calorie data updated."
        compose.setContent {
            val expected = token.value
            MaterialTheme {
                BodyWeightScreen(model, {}, {}, {}, feedback = message, feedbackToken = expected,
                    onFeedbackHandled = { handled += expected })
            }
        }
        awaitMessage(message)
        assertTrue(handled.isEmpty())
        val latest = NutritionEnergyMutationResult("weight", operationId = 2)
        compose.runOnIdle { token.value = latest }
        compose.waitForIdle()
        awaitMessage(message)
        dismissMessage()
        compose.runOnIdle { assertEquals(listOf(latest), handled) }
    }

    @Test fun `food details forwards its token and identical errors from different writer types remain distinct`() {
        runBlocking { repository.upsertFood(FoodDetail(FoodEntity(id = "custom:food", name = "Custom food"))) }
        val model = FoodDetailsViewModel(repository, "custom:food").also { store.put("details", it) }
        val message = "Save failed."
        val original = FoodLogResult("details", error = message)
        val token = mutableStateOf<Any>(original)
        val handled = mutableListOf<Any>()
        compose.setContent {
            val expected = token.value
            MaterialTheme {
                FoodDetailsScreen(model, {}, feedback = message, feedbackToken = expected,
                    onFeedbackHandled = { handled += expected })
            }
        }
        awaitMessage(message)
        assertTrue(handled.isEmpty())
        val latest = FoodMutationResult("details", foodId = "custom:food", error = message)
        compose.runOnIdle { token.value = latest }
        compose.waitForIdle()
        awaitMessage(message)
        dismissMessage()
        compose.runOnIdle { assertEquals(listOf(latest), handled) }
    }

    private fun awaitMessage(message: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
    }

    private fun dismissMessage() {
        // Use SnackbarHost's accessible dismissal, without timing assumptions or production hooks.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitForIdle()
    }
}

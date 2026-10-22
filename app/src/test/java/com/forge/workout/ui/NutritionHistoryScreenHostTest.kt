package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.ui.screens.nutrition.NutritionHistoryScreen
import com.forge.workout.viewmodel.NutritionHistoryViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
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
class NutritionHistoryScreenHostTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: ForgeDatabase
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        store = ViewModelStore()
    }
    @After fun tearDown() { store.clear(); database.close() }

    @Test fun `history distinguishes partial and missing values compares only complete goals and selects the original day`() {
        val repository = RoomNutritionRepository(database)
        runBlocking {
            repository.saveGoal(NutritionGoalEntity("2026-10-01", kcal = 100.0))
            repository.saveDiary(entry("2026-10-12", 300.0))
            repository.saveDiary(entry("2026-10-12", null))
            repository.saveDiary(entry("2026-10-11", 150.0))
        }
        val model = NutritionHistoryViewModel(repository, SavedStateHandle()).also { store.put("history", it) }
        var selected: String? = null
        compose.setContent { MaterialTheme { NutritionHistoryScreen(model, {}, { selected = it }) } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("nutrition_history_day_2026-10-12").fetchSemanticsNodes().isNotEmpty()
        }
        day("2026-10-12")
        compose.onNodeWithTag("nutrition_history_kcal_2026-10-12", useUnmergedTree = true).assertTextEquals("At least 300 kcal")
        compose.onNodeWithTag("nutrition_history_carbs_2026-10-12", useUnmergedTree = true).assertTextEquals("Unavailable")
        compose.onNodeWithTag("nutrition_history_goal_status_2026-10-12", useUnmergedTree = true).assertTextEquals("Incomplete data")
        day("2026-10-11")
        compose.onNodeWithTag("nutrition_history_kcal_2026-10-11", useUnmergedTree = true).assertTextEquals("150 kcal")
        compose.onNodeWithTag("nutrition_history_goal_status_2026-10-11", useUnmergedTree = true).assertTextEquals("50 kcal over calorie goal")
        compose.onNodeWithTag("nutrition_history_day_2026-10-11").performClick()
        assertEquals("2026-10-11", selected)
        assertEquals(3, runBlocking { repository.observeDiary("2026-10-12").first().size +
            repository.observeDiary("2026-10-11").first().size })
    }

    private fun day(day: String) = compose.onNodeWithTag("nutrition_history_list")
        .performScrollToNode(hasTestTag("nutrition_history_day_$day"))
    private fun entry(day: String, energy: Double?) = DiaryEntity(day = day, meal = MealType.BREAKFAST,
        snapshot = FoodSnapshot(name = "Recorded food", gramsPerBase = 100.0,
            nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", energy),
                NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 10.0))),
        quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0)
}

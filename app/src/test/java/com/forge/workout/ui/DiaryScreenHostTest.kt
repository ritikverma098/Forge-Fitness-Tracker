package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.R
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.ui.screens.nutrition.DiaryEntryScreen
import com.forge.workout.ui.screens.nutrition.NutritionScreen
import com.forge.workout.viewmodel.DiaryEntryViewModel
import com.forge.workout.viewmodel.NutritionDashboardViewModel
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
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
class DiaryScreenHostTest {
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

    @Test fun `fresh loading recreation retains unsaved diary date meal and amount without a catalog record`() {
        val today = LocalDate.now().toString()
        val id = runBlocking { repository.saveDiary(entry(LocalDate.now().minusDays(7).toString(), MealType.BREAKFAST, 200.0)) }
        val original = runBlocking { repository.diaryEntry(id)!! }
        val active = mutableStateOf(DiaryEntryViewModel(repository, id).also { store.put("original", it) })
        val readGate = CompletableDeferred<Unit>()
        val delayed = object : NutritionRepository by repository {
            override fun observeDiaryEntry(id: Long): Flow<DiaryEntity?> = flow {
                readGate.await()
                emitAll(repository.observeDiaryEntry(id))
            }
        }
        val replacement = DiaryEntryViewModel(delayed, id).also { store.put("replacement", it) }
        var replaceOnDispose = false
        var saved: SavedEntry? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DisposableEffect(Unit) {
                onDispose {
                    if (replaceOnDispose) { replaceOnDispose = false; active.value = replacement }
                }
            }
            MaterialTheme {
                DiaryEntryScreen(active.value, {}, { source, choice, quantity, day, meal ->
                    saved = SavedEntry(source, choice, quantity, day, meal)
                })
            }
        }
        awaitTag("food_details_list")
        scrollDetails("picker_today")
        compose.onNodeWithTag("picker_today").performClick()
        scrollDetails("picker_meals")
        compose.onNodeWithTag("picker_meals").performScrollToNode(hasTestTag("picker_meal_LUNCH"))
        compose.onNodeWithTag("picker_meal_LUNCH").performClick()
        scrollDetails("food_quantity")
        compose.onNodeWithTag("food_quantity").performTextReplacement("75")

        compose.runOnIdle { replaceOnDispose = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.food_details_loading)).assertIsDisplayed()
        compose.onNodeWithTag("food_quantity").assertDoesNotExist()
        readGate.complete(Unit)
        awaitTag("food_details_list")
        scrollDetails("picker_day")
        val todayLabel = LocalDate.parse(today).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        compose.onNodeWithTag("picker_day").assertTextContains(todayLabel)
        scrollDetails("picker_meals")
        compose.onNodeWithTag("picker_meals").performScrollToNode(hasTestTag("picker_meal_LUNCH"))
        compose.onNodeWithTag("picker_meal_LUNCH").assertIsSelected()
        scrollDetails("food_quantity")
        compose.onNodeWithTag("food_quantity").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("75")))
        scrollDetails("food_confirm")
        compose.onNodeWithTag("food_confirm").assertIsEnabled().performClick()
        assertEquals(original, saved!!.source)
        assertEquals(FoodUnit.GRAM, saved!!.choice.unit)
        assertEquals(75.0, saved!!.quantity, 0.0)
        assertEquals(today, saved!!.day)
        assertEquals(MealType.LUNCH, saved!!.meal)
        assertEquals(original, runBlocking { repository.diaryEntry(id) })
        assertNull(runBlocking { repository.food(original.foodId!!) })
    }

    @Test fun `each meal heading shows its complete or partial calorie total`() {
        val day = "2026-10-12"
        runBlocking {
            repository.saveDiary(entry(day, MealType.BREAKFAST, 120.0))
            repository.saveDiary(entry(day, MealType.BREAKFAST, 80.0))
            repository.saveDiary(entry(day, MealType.LUNCH, 300.0))
            repository.saveDiary(entry(day, MealType.LUNCH, null))
        }
        val model = NutritionDashboardViewModel(repository, MutableStateFlow(day)).also { store.put("dashboard", it) }
        compose.setContent { MaterialTheme { NutritionScreen(model, {}, {}, {}, {}, {}, { _, _, _ -> }, {}) } }
        awaitTag("nutrition_energy_card")
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("meal_BREAKFAST"))
        compose.onNodeWithTag("nutrition_meal_kcal_BREAKFAST", useUnmergedTree = true).assertTextEquals("200 kcal")
        compose.onNodeWithTag("nutrition_list").performScrollToNode(hasTestTag("meal_LUNCH"))
        compose.onNodeWithTag("nutrition_meal_kcal_LUNCH", useUnmergedTree = true).assertTextEquals("At least 300 kcal")
    }

    private fun awaitTag(tag: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun scrollDetails(tag: String) = compose.onNodeWithTag("food_details_list").performScrollToNode(hasTestTag(tag))
    private fun entry(day: String, meal: MealType, kcal: Double?) = DiaryEntity(day = day, meal = meal, foodId = "removed-food",
        snapshot = FoodSnapshot(foodId = "removed-food", name = "Recorded food", gramsPerBase = 100.0,
            nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", kcal),
                NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", 10.0),
                NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", null),
                NutrientValue(NutrientCodes.FAT, "Fat", "g", 2.0))),
        quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0)
    private data class SavedEntry(val source: DiaryEntity, val choice: FoodServingChoice,
        val quantity: Double, val day: String, val meal: MealType)
}

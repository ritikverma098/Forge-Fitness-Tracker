package com.forge.workout.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.BodyWeightEntity
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.ui.screens.nutrition.BodyWeightScreen
import com.forge.workout.viewmodel.BodyWeightViewModel
import java.time.LocalDate
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
class BodyWeightScreenHostTest {
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

    @Test fun `pound input converts once and a display unit change preserves precise captured kilograms`() {
        val day = LocalDate.now().toString()
        runBlocking { repository.saveBodyWeight(BodyWeightEntity(day = day, kg = 70.0)) }
        val model = BodyWeightViewModel(repository, SavedStateHandle()).also { store.put("weight", it) }
        val unit = mutableStateOf(WeightUnit.LB)
        val writes = mutableListOf<BodyWeightEntity>()
        compose.setContent { MaterialTheme { BodyWeightScreen(model, {}, { writes += it }, {}, weightUnit = unit.value) } }
        awaitField { model.state.value.unit == WeightUnit.LB && model.state.value.draft.weight == "154.32" }
        field().assertTextContains("Body weight (lb)")
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("154.32")))
        field().performTextReplacement("160")
        compose.runOnIdle { unit.value = WeightUnit.KG }
        awaitField { model.state.value.unit == WeightUnit.KG && model.state.value.draft.weight == "72.57" }
        field().assertTextContains("Body weight (kg)")
        field().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("72.57")))
        compose.onNodeWithTag("body_weight_list").performScrollToNode(hasTestTag("body_weight_save"))
        compose.onNodeWithTag("body_weight_save").assertIsEnabled().performClick()
        assertEquals(1, writes.size)
        assertEquals(day, writes.single().day)
        assertEquals(WeightUnit.LB.toKg(160.0), writes.single().kg, 1e-9)
        // The screen emits a canonical command; the activity owns the database write.
        assertEquals(70.0, runBlocking { repository.bodyWeight(day)!!.kg }, 0.0)

        field().performTextReplacement("-1")
        compose.onNodeWithTag("body_weight_save").performClick()
        awaitField { "weight" in model.state.value.invalidFields }
        assertEquals(1, writes.size)
    }

    private fun awaitField(predicate: () -> Boolean) = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("body_weight_quantity").fetchSemanticsNodes().isNotEmpty() && predicate()
    }
    private fun field(): SemanticsNodeInteraction {
        compose.onNodeWithTag("body_weight_list").performScrollToNode(hasTestTag("body_weight_quantity"))
        return compose.onNodeWithTag("body_weight_quantity")
    }
}

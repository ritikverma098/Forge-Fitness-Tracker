package com.forge.workout.ui

import android.app.Application
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.forge.workout.ForgeTestApplication
import com.forge.workout.R
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.catalog.CatalogInstallResult
import com.forge.workout.nutrition.catalog.CatalogLoadState
import com.forge.workout.nutrition.catalog.FoodCatalogService
import com.forge.workout.nutrition.model.CatalogPackEntity
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.ui.screens.nutrition.FoodCatalogScreen
import com.forge.workout.viewmodel.CatalogFeedback
import com.forge.workout.viewmodel.CatalogViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses its own SQLite database; neither the installed catalog nor personal records are changed. */
@RunWith(AndroidJUnit4::class)
class CatalogProgressTest {
    @get:Rule val compose = createComposeRule()
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: GatedCatalogReads
    private lateinit var service: WaitingCatalogService
    private lateinit var model: CatalogViewModel
    private var contentStarted = false
    private var visible by mutableStateOf(true)
    private val pack = CatalogPackEntity(
        id = "progress-pack", name = "Late catalog metadata", version = "1",
        sourceUrl = "https://example.com/catalog", license = "CC0", citation = "Test source",
        sha256 = "a".repeat(64), foodCount = 1,
    )
    private val food = FoodEntity(
        id = "progress-food", name = "Late catalog preview", source = "Test source",
        sourceType = FoodSourceType.REFERENCE, packId = pack.id,
    )

    @Before fun setUp() {
        assertTrue("Device tests must use the isolated application", application is ForgeTestApplication)
        database = Room.inMemoryDatabaseBuilder(application, ForgeDatabase::class.java).build()
        val room = RoomNutritionRepository(database)
        runBlocking {
            room.upsertCatalogPack(pack)
            room.upsertFood(FoodDetail(food))
        }
        repository = GatedCatalogReads(room)
        service = WaitingCatalogService()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = CatalogViewModel(service, repository)
            model.ensureBundled()
        }
        await(service.started)
    }

    @After fun tearDown() {
        if (::model.isInitialized) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = false
                model.viewModelScope.cancel()
            }
            if (contentStarted) compose.waitForIdle()
            runBlocking { model.viewModelScope.coroutineContext[Job]?.join() }
        }
        if (::database.isInitialized) database.close()
    }

    @Test fun importStartedBeforeScreenShowsProgressAndCancelBeforeFirstDatabaseResults() {
        assertEquals(1, service.calls.get())
        assertFalse(repository.packsStarted.isCompleted)
        assertFalse(repository.foodsStarted.isCompleted)
        contentStarted = true
        compose.setContent {
            MaterialTheme { if (visible) FoodCatalogScreen(model, onBack = {}) }
        }
        await(repository.packsStarted)
        await(repository.foodsStarted)
        compose.waitUntil(10_000) { model.state.value.busy && model.state.value.operation.progress == .4f }
        compose.onNodeWithTag("catalog_import").assertIsNotEnabled()
        scrollTo("catalog_progress")
        compose.onNodeWithTag("catalog_progress").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.4f, 0f..1f),
        ))
        scrollTo("catalog_cancel")
        assertFalse(repository.releasePacks.isCompleted)
        assertFalse(repository.releaseFoods.isCompleted)
        compose.onNodeWithTag("catalog_cancel").performClick()
        compose.waitUntil(10_000) {
            !model.state.value.busy && model.state.value.feedback == CatalogFeedback.CANCELLED
        }
        scrollTo("catalog_retry_bundled")
        compose.onNodeWithTag("catalog_retry_bundled").assertIsEnabled()
        compose.onNodeWithTag("catalog_feedback").assertTextEquals(application.getString(R.string.catalog_cancelled_feedback))
        assertEquals(1, service.cancelCalls.get())
        assertEquals("The screen must not start a second bundled import", 1, service.calls.get())
        assertFalse(repository.releasePacks.isCompleted)
        assertFalse(repository.releaseFoods.isCompleted)

        repository.releasePacks.complete(Unit)
        compose.waitUntil(10_000) { model.state.value.packs == listOf(pack) }
        assertTrue(model.state.value.foods.isEmpty())
        scrollTo("catalog_pack_${pack.id}")
        repository.releaseFoods.complete(Unit)
        compose.waitUntil(10_000) { model.state.value.foods.singleOrNull()?.id == food.id }
        assertEquals(food, model.state.value.foods.single().copy(rowId = 0))
        scrollTo("catalog_food_${food.id}")
        assertEquals(1, model.state.value.totalFoods)
        assertFalse(model.state.value.readFailed)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("catalog_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun await(signal: CompletableDeferred<Unit>) {
        runBlocking { withTimeout(10_000) { signal.await() } }
    }
}

private class GatedCatalogReads(private val delegate: NutritionRepository) : NutritionRepository by delegate {
    val packsStarted = CompletableDeferred<Unit>()
    val foodsStarted = CompletableDeferred<Unit>()
    val releasePacks = CompletableDeferred<Unit>()
    val releaseFoods = CompletableDeferred<Unit>()

    override fun observeCatalogPacks(): Flow<List<CatalogPackEntity>> = flow {
        packsStarted.complete(Unit)
        releasePacks.await()
        emitAll(delegate.observeCatalogPacks())
    }

    override fun observeFoods(query: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean,
        limit: Int, offset: Int): Flow<List<FoodEntity>> = flow {
        foodsStarted.complete(Unit)
        releaseFoods.await()
        emitAll(delegate.observeFoods(query, sourceType, region, favoritesOnly, limit, offset))
    }
}

private class WaitingCatalogService : FoodCatalogService {
    override val state = MutableStateFlow(CatalogLoadState())
    val started = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    val cancelCalls = AtomicInteger()
    private val completion = CompletableDeferred<CatalogInstallResult>()

    override suspend fun ensureBundled(): CatalogInstallResult {
        calls.incrementAndGet()
        state.value = CatalogLoadState(busy = true, progress = .4f, foodCount = 10)
        started.complete(Unit)
        return try { completion.await() }
        finally { state.value = state.value.copy(busy = false) }
    }

    override suspend fun installDocument(uri: Uri): CatalogInstallResult = error("Unexpected document import")
    override suspend fun installUrl(url: String, expectedSha256: String): CatalogInstallResult = error("Unexpected download")
    override fun cancel() { cancelCalls.incrementAndGet() }
    override fun clearError() { state.value = state.value.copy(error = null) }
}

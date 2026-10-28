package com.forge.workout.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.catalog.CatalogException
import com.forge.workout.nutrition.catalog.CatalogFailure
import com.forge.workout.nutrition.catalog.CatalogInstallResult
import com.forge.workout.nutrition.catalog.CatalogIssue
import com.forge.workout.nutrition.catalog.CatalogLoadState
import com.forge.workout.nutrition.catalog.FoodCatalogService
import com.forge.workout.nutrition.model.CatalogPackEntity
import com.forge.workout.nutrition.model.FoodDetail
import com.forge.workout.nutrition.model.FoodEntity
import com.forge.workout.nutrition.model.FoodSourceType
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CatalogViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore
    private lateinit var service: ControlledCatalogService

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
        service = ControlledCatalogService()
    }

    @After fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `catalog construction does no startup work and preview is reference only capped at fifty`() = runTest(dispatcher) {
        repeat(52) { index ->
            repository.upsertFood(FoodDetail(FoodEntity(id = "reference-$index", name = "Reference ${index.toString().padStart(2, '0')}",
                sourceType = FoodSourceType.REFERENCE)))
        }
        repository.upsertFood(FoodDetail(FoodEntity(id = "custom", name = "AAA user food")))
        val observedRepository = ObservedCatalogRepository(repository)
        val model = keep(observedRepository)
        runCurrent()
        assertTrue(service.calls.isEmpty())
        assertEquals(0, observedRepository.foodSubscriptions)

        observe(model)
        val preview = model.state.first { it.foods.size == 50 }
        assertEquals(FoodSourceType.REFERENCE, observedRepository.requestedSource)
        assertEquals(50, observedRepository.requestedLimit)
        assertTrue(preview.foods.all { it.sourceType == FoodSourceType.REFERENCE })
        assertEquals("Reference 00", preview.foods.first().name)
        assertEquals("Reference 49", preview.foods.last().name)
        assertTrue(service.calls.isEmpty())

        model.ensureBundled()
        model.state.first { it.feedback == CatalogFeedback.INSTALLED }
        model.ensureBundled()
        runCurrent()
        assertEquals(listOf("bundled"), service.calls)
    }

    @Test fun `duplicate requests and cancellation before dispatch leave the next import usable`() = runTest(dispatcher) {
        val model = keep()
        observe(model)
        model.installDocument(Uri.parse("content://catalog/cancel-before-start"))
        model.installDocument(Uri.parse("content://catalog/ignored-duplicate"))
        model.cancel()
        runCurrent()
        assertTrue(service.calls.isEmpty())
        assertEquals(1, service.cancelCalls)
        assertEquals(CatalogFeedback.CANCELLED, model.state.first { !it.busy && it.feedback != null }.feedback)

        val gate = CompletableDeferred<CatalogInstallResult>()
        service.gate = gate
        model.installDocument(Uri.parse("content://catalog/retry"))
        val progress = model.state.first { it.busy && it.operation.progress > 0f }
        assertEquals(.4f, progress.operation.progress, .0001f)
        model.retryBundled()
        model.installDocument(Uri.parse("content://catalog/also-ignored"))
        runCurrent()
        assertEquals(listOf("document"), service.calls)
        gate.complete(service.result)
        model.state.first { !it.busy && it.feedback == CatalogFeedback.INSTALLED }
        assertEquals("content://catalog/retry", service.lastDocument.toString())
    }

    @Test fun `progress cancellation and retry remain visible before either database read completes`() = runTest(dispatcher) {
        val packRead = CompletableDeferred<List<CatalogPackEntity>>()
        val foodRead = CompletableDeferred<List<FoodEntity>>()
        val delayedReads = object : NutritionRepository by repository {
            override fun observeCatalogPacks(): Flow<List<CatalogPackEntity>> = flow { emit(packRead.await()) }
            override fun observeFoods(query: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean,
                limit: Int, offset: Int): Flow<List<FoodEntity>> = flow { emit(foodRead.await()) }
        }
        val model = keep(delayedReads)
        service.gate = CompletableDeferred()
        // Nutrition starts the import before the catalog screen subscribes.
        model.ensureBundled()
        runCurrent()
        observe(model)
        runCurrent()
        assertTrue(model.state.value.busy)
        assertEquals(.4f, model.state.value.operation.progress, .0001f)
        model.cancel()
        runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals(CatalogFeedback.CANCELLED, model.state.value.feedback)

        val retry = CompletableDeferred<CatalogInstallResult>()
        service.gate = retry
        model.retryBundled()
        runCurrent()
        assertTrue(model.state.value.busy)
        assertEquals(2, service.calls.size)
        packRead.complete(listOf(service.result.pack))
        runCurrent()
        assertEquals(10, model.state.value.totalFoods)
        assertTrue(model.state.value.busy)
        val preview = FoodEntity(id = "late-reference", name = "Late reference", sourceType = FoodSourceType.REFERENCE)
        foodRead.complete(listOf(preview))
        runCurrent()
        assertEquals(listOf(preview), model.state.value.foods)
        retry.complete(service.result)
        runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals(CatalogFeedback.INSTALLED, model.state.value.feedback)
    }

    @Test fun `invalid downloads do not reach service and verified failure preserves foods before retry`() = runTest(dispatcher) {
        val oldFood = FoodDetail(FoodEntity(id = "original", name = "Original reference", sourceType = FoodSourceType.REFERENCE))
        repository.upsertFood(oldFood)
        val original = repository.food("original")
        val model = keep()
        observe(model)
        model.installUrl("http://example.com/foods.forgefoods", "a".repeat(64))
        model.state.first { it.feedback == CatalogFeedback.INVALID_URL }
        model.installUrl("https://user:secret@example.com/foods.forgefoods", "a".repeat(64))
        model.state.first { it.feedback == CatalogFeedback.INVALID_URL }
        model.installUrl("https://example.com/foods.forgefoods", "bad checksum")
        model.state.first { it.feedback == CatalogFeedback.INVALID_HASH }
        assertTrue(service.calls.isEmpty())

        service.failure = CatalogException(CatalogIssue.HASH_MISMATCH, "Mismatch")
        model.installUrl(" https://example.com/foods.forgefoods ", " " + "A".repeat(64) + " ")
        val failed = model.state.first { !it.busy && it.feedback == CatalogFeedback.FAILED }
        assertEquals(CatalogIssue.HASH_MISMATCH, failed.operation.error!!.issue)
        assertEquals(original, repository.food("original"))
        assertEquals("https://example.com/foods.forgefoods", service.lastUrl)
        assertEquals("a".repeat(64), service.lastHash)
        model.clearFeedback()
        model.state.first { it.feedback == null && it.operation.error == null }

        model.installUrl("https://example.com/foods.forgefoods", "a".repeat(64))
        model.state.first { !it.busy && it.feedback == CatalogFeedback.INSTALLED }
        assertEquals(listOf("url", "url"), service.calls)
        assertEquals(original, repository.food("original"))
    }

    @Test fun `food observation can retry after a transient read failure`() = runTest(dispatcher) {
        repository.upsertFood(FoodDetail(FoodEntity(id = "read-retry", name = "Loaded reference", sourceType = FoodSourceType.REFERENCE)))
        val observedRepository = ObservedCatalogRepository(repository, failFirstRead = true)
        val model = keep(observedRepository)
        observe(model)
        model.state.first { it.readFailed }
        model.retryRead()
        val restored = model.state.first { !it.readFailed && it.foods.size == 1 }
        assertEquals("Loaded reference", restored.foods.single().name)
        assertEquals(2, observedRepository.foodSubscriptions)
        assertTrue(service.calls.isEmpty())
    }

    @Test fun `active cancellation permits retry and clearing the owner cancels an ongoing import`() = runTest(dispatcher) {
        val model = keep()
        observe(model)
        service.gate = CompletableDeferred()
        model.installDocument(Uri.parse("content://catalog/active-cancel"))
        model.state.first { it.busy && it.operation.progress > 0f }
        model.cancel()
        model.state.first { !it.busy && it.feedback == CatalogFeedback.CANCELLED }
        assertFalse(service.state.value.busy)
        assertEquals(1, service.cancelCalls)

        service.gate = null
        model.retryBundled()
        model.state.first { !it.busy && it.feedback == CatalogFeedback.INSTALLED }
        assertEquals(listOf("document", "bundled"), service.calls)

        service.gate = CompletableDeferred()
        model.installDocument(Uri.parse("content://catalog/owner-cleared"))
        model.state.first { it.busy && it.operation.progress > 0f }
        store.clear()
        runCurrent()
        assertFalse("The cleared owner must not leave the installer running", service.state.value.busy)
        assertEquals(listOf("document", "bundled", "document"), service.calls)
    }

    private fun keep(source: NutritionRepository = repository) = CatalogViewModel(service, source).also { store.put("catalog", it) }

    private fun TestScope.observe(model: CatalogViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
    }
}

private class ObservedCatalogRepository(
    private val delegate: NutritionRepository,
    private var failFirstRead: Boolean = false,
) : NutritionRepository by delegate {
    var foodSubscriptions = 0
    var requestedSource: FoodSourceType? = null
    var requestedLimit = 0
    override fun observeFoods(query: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean,
        limit: Int, offset: Int): Flow<List<FoodEntity>> {
        foodSubscriptions++
        requestedSource = sourceType
        requestedLimit = limit
        if (failFirstRead) {
            failFirstRead = false
            return flow { throw IOException("Temporary read failure") }
        }
        return delegate.observeFoods(query, sourceType, region, favoritesOnly, limit, offset)
    }
}

private class ControlledCatalogService : FoodCatalogService {
    override val state = MutableStateFlow(CatalogLoadState())
    val result = CatalogInstallResult(CatalogPackEntity("test-pack", "Test pack", "1", "https://example.com/", "CC0", "Test source", foodCount = 10),
        added = 10, updated = 0, removed = 0, alreadyInstalled = false)
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<CatalogInstallResult>? = null
    var failure: CatalogException? = null
    var cancelCalls = 0
    var lastDocument: Uri? = null
    var lastUrl: String? = null
    var lastHash: String? = null
    override suspend fun ensureBundled() = install("bundled")
    override suspend fun installDocument(uri: Uri): CatalogInstallResult { lastDocument = uri; return install("document") }
    override suspend fun installUrl(url: String, expectedSha256: String): CatalogInstallResult {
        lastUrl = url; lastHash = expectedSha256
        return install("url")
    }
    override fun cancel() { cancelCalls++ }
    override fun clearError() { state.value = state.value.copy(error = null) }
    private suspend fun install(kind: String): CatalogInstallResult {
        calls += kind
        state.value = CatalogLoadState(busy = true, progress = .4f, foodCount = 10)
        try {
            failure?.let { error -> failure = null; state.value = CatalogLoadState(error = CatalogFailure(error.issue, error.message)); throw error }
            return gate?.await() ?: result
        } finally {
            state.value = state.value.copy(busy = false)
        }
    }
}

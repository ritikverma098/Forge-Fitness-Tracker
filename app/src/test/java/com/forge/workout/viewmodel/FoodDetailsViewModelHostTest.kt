package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
class FoodDetailsViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
    }

    @After fun tearDown() {
        store.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test fun `construction is lazy and observing details reads only the requested stable ID`() = runTest(dispatcher) {
        repository.upsertFood(food("target", "Target food"))
        repository.upsertFood(food("unrelated", "Other food"))
        val observed = ObservedRepository(repository)
        val model = keep(observed)
        runCurrent()
        assertTrue(model.state.value.loading)
        assertTrue(observed.ids.isEmpty())
        observe(model)
        val detail = model.state.first { !it.loading }.detail!!
        assertEquals("target", detail.food.id)
        assertEquals(listOf("target"), observed.ids)
        assertEquals(listOf("target"), observed.favoriteIds)
        assertEquals(0, observed.libraryReads)
        assertEquals(0, observed.allFavoriteReads)
        assertFalse(model.state.value.error)
    }

    @Test fun `same VM refreshes food nutrient and portion updates and handles deletion without fabricating nutrients`() = runTest(dispatcher) {
        repository.upsertFood(food("target", "Original food"))
        val model = keep()
        observe(model)
        val first = model.state.first { !it.loading }.detail!!
        assertNull(first.nutrients.single { it.code == "fiber" }.value)
        assertEquals(85.0, first.portions.single().grams!!, 0.0)
        val updated = first.copy(food = first.food.copy(name = "Updated food", notes = "New source notes"),
            nutrients = first.nutrients.map { if (it.code == NutrientCodes.ENERGY) it.copy(value = 240.0) else it },
            portions = first.portions.map { it.copy(label = "Larger portion", baseQuantity = 120.0, grams = 120.0) })
        repository.upsertFood(updated)
        val refreshed = model.state.first { it.detail?.food?.name == "Updated food" }.detail!!
        assertEquals(first.food.rowId, refreshed.food.rowId)
        assertEquals("New source notes", refreshed.food.notes)
        assertEquals(240.0, refreshed.nutrients.single { it.code == NutrientCodes.ENERGY }.value!!, 0.0)
        assertNull(refreshed.nutrients.single { it.code == "fiber" }.value)
        assertEquals("Larger portion", refreshed.portions.single().label)
        repository.deleteFood("target")
        val deleted = model.state.first { !it.loading && it.detail == null }
        assertFalse(deleted.error)
        repository.upsertFood(food("target", "Restored food"))
        assertEquals("Restored food", model.state.first { it.detail?.food?.name == "Restored food" }.detail!!.food.name)
    }

    @Test fun `read failure is explicit and reload opens a new targeted Room observation`() = runTest(dispatcher) {
        repository.upsertFood(food("target", "Recovered food"))
        var requests = 0
        val unreliable = object : NutritionRepository by repository {
            override fun observeFood(id: String): Flow<FoodDetail?> {
                requests++
                return if (requests == 1) flow { throw IOException("Injected details read failure") }
                else repository.observeFood(id)
            }
        }
        val model = keep(unreliable)
        observe(model)
        val failed = model.state.first { it.error }
        assertFalse(failed.loading)
        assertNull(failed.detail)
        model.reload()
        val recovered = model.state.first { !it.loading && it.detail != null }
        assertFalse(recovered.error)
        assertEquals("Recovered food", recovered.detail!!.food.name)
        assertEquals(2, requests)
    }

    @Test fun `synchronous invalid ID validation becomes recoverable state rather than crashing the ViewModel`() = runTest(dispatcher) {
        val model = keep(id = " ")
        observe(model)
        assertFalse(model.state.first { it.error }.loading)
        model.reload()
        runCurrent()
        assertTrue(model.state.value.error)
    }

    @Test fun `resubscribing after background pause retrieves edits made while details were stopped`() = runTest(dispatcher) {
        repository.upsertFood(food("target", "Before pause"))
        val observed = ObservedRepository(repository)
        val model = keep(observed)
        val collector = observe(model)
        model.state.first { it.detail != null }
        collector.cancelAndJoin()
        advanceTimeBy(5_001)
        runCurrent()
        repository.upsertFood(food("target", "Changed while away"))
        observe(model)
        val resumed = model.state.first { it.detail?.food?.name == "Changed while away" }
        assertFalse(resumed.error)
        assertEquals(listOf("target", "target"), observed.ids)
        assertEquals(0, observed.libraryReads)
    }

    @Test fun `published details freeze mutable caller lists and cancellation is not displayed as a read error`() = runTest(dispatcher) {
        val nutrients = food("target", "Frozen food").nutrients.toMutableList()
        val portions = food("target", "Frozen food").portions.toMutableList()
        val source = MutableStateFlow<FoodDetail?>(food("target", "Frozen food").copy(nutrients = nutrients, portions = portions))
        val favorite = MutableStateFlow<FavoriteEntity?>(null)
        val controlled = object : NutritionRepository by repository {
            override fun observeFood(id: String): Flow<FoodDetail?> = source
            override fun observeFavorite(id: String): Flow<FavoriteEntity?> = favorite
        }
        val model = keep(controlled)
        observe(model)
        val published = model.state.first { it.detail != null }.detail!!
        nutrients.clear()
        portions.clear()
        assertEquals(3, published.nutrients.size)
        assertEquals(1, published.portions.size)
        favorite.value = FavoriteEntity("target", "Frozen food", 1)
        val favored = model.state.first { it.favorite }
        assertSame("Favorite changes must retain the frozen food emission", published, favored.detail)
        assertEquals(3, favored.detail!!.nutrients.size)
        assertEquals(1, favored.detail!!.portions.size)
        val cancelled = object : NutritionRepository by repository {
            override fun observeFood(id: String): Flow<FoodDetail?> = flow { throw CancellationException("Observation cancelled") }
        }
        val cancellationModel = keep(cancelled, key = "cancelled")
        observe(cancellationModel)
        runCurrent()
        assertFalse(cancellationModel.state.value.error)
    }

    @Test fun `real favorites react for only the selected ID without replacing details or reading catalog or history`() = runTest(dispatcher) {
        repository.upsertFood(food("target", "Target food"))
        repository.upsertFood(food("unrelated", "Other food"))
        val snapshot = repository.food("target")!!.snapshot()
        val diaryId = repository.saveDiary(DiaryEntity(day = "2026-10-12", meal = MealType.LUNCH, foodId = "target",
            snapshot = snapshot, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 1000))
        val diaryBefore = repository.diaryEntry(diaryId)
        val observed = ObservedRepository(repository)
        val model = keep(observed)
        observe(model)
        val first = model.state.first { !it.loading && it.detail != null }
        assertFalse(first.favorite)
        repository.saveFavorite(FavoriteEntity("target", "Favorite target", 1))
        val favored = model.state.first { it.favorite }
        assertSame(first.detail, favored.detail)
        repository.saveFavorite(FavoriteEntity("unrelated", "Other favorite", 2))
        runCurrent()
        assertTrue(model.state.value.favorite)
        repository.deleteFavorite("target")
        val removed = model.state.first { !it.favorite && !it.loading }
        assertSame(first.detail, removed.detail)
        assertEquals(listOf("target"), observed.ids)
        assertEquals(listOf("target"), observed.favoriteIds)
        assertEquals(0, observed.libraryReads)
        assertEquals(0, observed.allFavoriteReads)
        assertEquals(0, observed.diaryReads)
        assertEquals(diaryBefore, repository.diaryEntry(diaryId))
    }

    @Test fun `favorite read failure can retry and cancellation never appears as a read error`() = runTest(dispatcher) {
        repository.upsertFood(food("target", "Target food"))
        repository.saveFavorite(FavoriteEntity("target", "Favorite target", 1))
        var requests = 0
        val unreliable = object : NutritionRepository by repository {
            override fun observeFavorite(id: String): Flow<FavoriteEntity?> {
                requests++
                return if (requests == 1) flow { throw IOException("Injected favorite read failure") }
                else repository.observeFavorite(id)
            }
        }
        val model = keep(unreliable)
        observe(model)
        val failed = model.state.first { it.error }
        assertFalse(failed.loading)
        assertNull(failed.detail)
        model.reload()
        val recovered = model.state.first { !it.loading && it.favorite && it.detail != null }
        assertFalse(recovered.error)
        assertEquals("Target food", recovered.detail!!.food.name)
        assertEquals(2, requests)
        val cancelled = object : NutritionRepository by repository {
            override fun observeFavorite(id: String): Flow<FavoriteEntity?> = flow { throw CancellationException("Favorite read cancelled") }
        }
        val cancellationModel = keep(cancelled, key = "favorite-cancelled")
        observe(cancellationModel)
        runCurrent()
        assertFalse(cancellationModel.state.value.error)
    }

    private fun keep(source: NutritionRepository = repository, id: String = "target", key: String = "details") =
        FoodDetailsViewModel(source, id).also { store.put(key, it) }
    private fun TestScope.observe(model: FoodDetailsViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }

    private class ObservedRepository(private val delegate: NutritionRepository) : NutritionRepository by delegate {
        val ids = mutableListOf<String>()
        val favoriteIds = mutableListOf<String>()
        var libraryReads = 0
        var allFavoriteReads = 0
        var diaryReads = 0
        override fun observeFood(id: String): Flow<FoodDetail?> {
            ids += id
            return delegate.observeFood(id)
        }
        override fun observeFavorite(id: String): Flow<FavoriteEntity?> {
            favoriteIds += id
            return delegate.observeFavorite(id)
        }
        override fun observeFavorites(): Flow<List<FavoriteEntity>> {
            allFavoriteReads++
            return delegate.observeFavorites()
        }
        override fun observeDiary(day: String): Flow<List<DiaryEntity>> {
            diaryReads++
            return delegate.observeDiary(day)
        }
        override fun observeFoodPage(query: String, filter: FoodBrowseFilter, limit: Int, offset: Int): Flow<List<FoodEntity>> {
            libraryReads++
            return delegate.observeFoodPage(query, filter, limit, offset)
        }
        override fun observeFoods(query: String, sourceType: FoodSourceType?, region: String?, favoritesOnly: Boolean,
            limit: Int, offset: Int): Flow<List<FoodEntity>> {
            libraryReads++
            return delegate.observeFoods(query, sourceType, region, favoritesOnly, limit, offset)
        }
    }

    private fun food(id: String, name: String) = FoodDetail(
        FoodEntity(id = id, name = name, baseAmount = 100.0, baseUnit = FoodUnit.GRAM, gramsPerBase = 100.0),
        listOf(FoodNutrientEntity(id, NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
            FoodNutrientEntity(id, NutrientCodes.PROTEIN, "Protein", "g", 10.0),
            FoodNutrientEntity(id, "fiber", "Fiber", "g", null)),
        listOf(FoodPortionEntity(id, "label", "5 pieces (85g)", 85.0, grams = 85.0, source = "Source label")),
    )
}

package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class FoodPickerViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var real: RoomNutritionRepository
    private lateinit var store: ViewModelStore
    private var modelCount = 0

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        real = RoomNutritionRepository(database)
        store = ViewModelStore()
    }

    @After fun tearDown() { store.clear(); database.close(); Dispatchers.resetMain() }

    @Test fun `query and filter cancel stale pages reset paging and recover from a retryable read error`() = runTest(dispatcher) {
        val repository = Pages(real, entries(350))
        val model = model(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        loaded(model) { it.foods.size == 50 }
        model.loadMore()
        loaded(model) { it.foods.size == 100 }
        model.setQuery("slow")
        repository.started.first { calls -> calls.any { it.query == "slow" } }
        model.setQuery("Food 2")
        val current = loaded(model) { it.query == "Food 2" }
        repository.cancelled.first { calls -> calls.any { it.query == "slow" } }
        assertTrue(current.foods.all { it.name.contains("Food 2") })
        assertTrue(current.foods.size <= 50)
        assertFalse(current.error)
        model.setFilter(FoodBrowseFilter.BRANDED)
        val branded = loaded(model) { it.filter == FoodBrowseFilter.BRANDED && it.query == "Food 2" }
        assertTrue(branded.foods.all { it.brand.isNotBlank() })
        repository.fail.set(true)
        model.reload()
        model.state.first { it.error && !it.loading }
        repository.fail.set(false)
        model.reload()
        val recovered = loaded(model) { it.filter == FoodBrowseFilter.BRANDED && it.query == "Food 2" }
        assertEquals(branded.foods, recovered.foods)
        assertFalse(recovered.error)
    }

    @Test fun `explicit pages continue beyond200 with bounded lookahead and reject duplicate pending load taps`() = runTest(dispatcher) {
        val repository = Pages(real, entries(260))
        val model = model(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        loaded(model) { it.foods.size == 50 }
        for (target in listOf(100, 150, 200, 250)) {
            model.loadMore()
            model.loadMore()
            val state = loaded(model) { it.foods.size == target }
            assertEquals(entries(260).take(target).map { it.id }, state.foods.map { it.id })
            assertEquals(target, state.foods.map { it.id }.distinct().size)
            assertTrue(state.hasMore)
        }
        assertTrue(repository.started.value.all { it.limit in 1..200 })
        assertTrue(repository.started.value.any { it.offset == 200 })
        model.loadMore()
        val all = loaded(model) { it.foods.size == 260 }
        assertFalse(all.hasMore)
        assertFalse(all.limitReached)
    }

    @Test fun `independent page invalidations never duplicate rows and reconcile to the refreshed ordering`() = runTest(dispatcher) {
        val original = entries(260)
        val repository = Pages(real, original)
        val model = model(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        loaded(model) { it.foods.size == 50 }
        repeat(4) { index -> model.loadMore(); loaded(model) { it.foods.size == 100 + index * 50 } }
        repository.overrides.value = mapOf(0 to (listOf(original[200]) + original.take(199)))
        val partial = loaded(model) { it.foods.firstOrNull()?.id == original[200].id && it.foods.size == 249 }
        assertEquals(partial.foods.size, partial.foods.map { it.id }.distinct().size)
        val reordered = listOf(original[200]) + original.filterNot { it.id == original[200].id }
        repository.source.value = reordered
        repository.overrides.value = emptyMap()
        val refreshed = loaded(model) { it.foods.map { food -> food.id } == reordered.take(250).map { food -> food.id } }
        assertEquals(250, refreshed.foods.map { it.id }.distinct().size)
        model.setFilter(FoodBrowseFilter.INDIAN)
        val filtered = loaded(model) { it.filter == FoodBrowseFilter.INDIAN && it.foods.size == 50 }
        assertEquals(reordered.take(50), filtered.foods)
    }

    @Test fun `saved query filter and explicit pages restore while subscription stops and restarts readers`() = runTest(dispatcher) {
        val repository = Pages(real, entries(300))
        val saved = SavedStateHandle()
        val model = model(repository, saved)
        model.setQuery("Food")
        model.setFilter(FoodBrowseFilter.INDIAN)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        loaded(model) { it.filter == FoodBrowseFilter.INDIAN && it.foods.size == 50 }
        repeat(4) { index -> model.loadMore(); loaded(model) { it.foods.size == 100 + index * 50 } }
        val before = model.state.value
        val restored = model(repository, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.state.collect() }
        assertEquals(before, loaded(restored) { it.foods.size == 250 })
        collector.cancelAndJoin()
        store.clear()
        repository.active.first { it == 0 }

        val isolated = Pages(real, entries(70))
        val resubscribed = model(isolated)
        assertTrue(isolated.started.value.isEmpty())
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { resubscribed.state.collect() }
        loaded(resubscribed) { it.foods.size == 50 }
        first.cancelAndJoin()
        advanceTimeBy(5_001)
        runCurrent()
        isolated.active.first { it == 0 }
        isolated.source.value = entries(20)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { resubscribed.state.collect() }
        val returned = loaded(resubscribed) { it.foods.size == 20 }
        assertFalse(returned.hasMore)
    }

    @Test fun `explicit memory cap remains visible instead of pretending the catalog has ended`() = runTest(dispatcher) {
        val repository = Pages(real, entries(5_001))
        val saved = SavedStateHandle(mapOf("food_picker_rows" to 5_000))
        val model = model(repository, saved)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        val capped = loaded(model) { it.foods.size == 5_000 }
        assertTrue(capped.limitReached)
        assertFalse(capped.hasMore)
        assertTrue(repository.started.value.all { it.limit <= 200 })
        val calls = repository.started.value.size
        model.loadMore()
        runCurrent()
        assertEquals(calls, repository.started.value.size)
        model.setQuery("Food 1")
        val reset = loaded(model) { it.query == "Food 1" && it.foods.size == 50 }
        assertFalse(reset.limitReached)
        assertTrue(reset.hasMore)
    }

    @Test fun `real Room recent food flow reflects new diary entries deletion and live names without per row lookups`() = runTest(dispatcher) {
        for (id in listOf("apple", "banana")) real.upsertFood(FoodDetail(FoodEntity(id = id, name = id)))
        val model = model(real)
        model.setFilter(FoodBrowseFilter.RECENT)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
        loaded(model) { it.filter == FoodBrowseFilter.RECENT && it.foods.isEmpty() }
        val apple = real.food("apple")!!.snapshot()
        val banana = real.food("banana")!!.snapshot()
        val first = real.saveDiary(DiaryEntity(day = "2026-10-12", meal = MealType.BREAKFAST,
            foodId = "apple", snapshot = apple, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 1000))
        loaded(model) { it.foods.map { food -> food.id } == listOf("apple") }
        val second = real.saveDiary(DiaryEntity(day = "2026-10-12", meal = MealType.LUNCH,
            foodId = "banana", snapshot = banana, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 2000))
        loaded(model) { it.foods.map { food -> food.id } == listOf("banana", "apple") }
        val current = real.food("banana")!!
        real.upsertFood(current.copy(food = current.food.copy(name = "Renamed banana")))
        loaded(model) { it.foods.firstOrNull()?.name == "Renamed banana" }
        real.deleteDiary(second)
        loaded(model) { it.foods.map { food -> food.id } == listOf("apple") }
        real.deleteDiary(first)
        loaded(model) { it.foods.isEmpty() }
    }

    private fun model(repository: NutritionRepository, saved: SavedStateHandle = SavedStateHandle()) =
        FoodPickerViewModel(repository, saved).also { store.put("picker-${modelCount++}", it) }
    private suspend fun loaded(model: FoodPickerViewModel, predicate: (FoodPickerUiState) -> Boolean) =
        model.state.first { !it.loading && !it.error && predicate(it) }
    private fun entries(count: Int) = List(count) { FoodEntity(id = "food-$it", name = "Food $it",
        brand = if (it % 2 == 0) "Brand" else "", region = "IN") }

    private data class PageCall(val query: String, val filter: FoodBrowseFilter, val limit: Int, val offset: Int)
    private class Pages(delegate: NutritionRepository, initial: List<FoodEntity>) : NutritionRepository by delegate {
        val source = MutableStateFlow(initial)
        val overrides = MutableStateFlow<Map<Int, List<FoodEntity>>>(emptyMap())
        val started = MutableStateFlow<List<PageCall>>(emptyList())
        val cancelled = MutableStateFlow<List<PageCall>>(emptyList())
        val active = MutableStateFlow(0)
        val fail = AtomicBoolean(false)
        override fun observeFoodPage(query: String, filter: FoodBrowseFilter, limit: Int, offset: Int): Flow<List<FoodEntity>> = flow {
            val call = PageCall(query, filter, limit, offset)
            started.update { it + call }
            active.update { it + 1 }
            try {
                if (query == "slow") delay(30_000)
                if (fail.get()) throw IOException("Injected indexed query failure")
                emitAll(combine(source, overrides) { foods, partial ->
                    partial[offset] ?: foods.filter { it.name.contains(query, true) &&
                        (filter != FoodBrowseFilter.BRANDED || it.brand.isNotBlank()) }
                        .drop(offset).take(limit)
                })
            } finally {
                cancelled.update { it + call }
                active.update { it - 1 }
            }
        }
        override suspend fun food(id: String): FoodDetail? = throw AssertionError("Picker must not perform per-row lookups")
    }
}

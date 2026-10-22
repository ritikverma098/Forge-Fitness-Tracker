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
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionHistoryViewModelHostTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var store: ViewModelStore
    @Before fun open() {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
        store = ViewModelStore()
    }
    @After fun close() { store.clear(); database.close(); Dispatchers.resetMain() }

    @Test fun `real history aggregates preserve unknown counts effective goals and react to edit move and deletion without food reads`() = runTest(dispatcher) {
        val known = repository.saveDiary(entry("2026-10-05", 200.0))
        val unknown = repository.saveDiary(entry("2026-10-05", null))
        repository.saveGoal(NutritionGoalEntity("2026-10-01", kcal = 1000.0, protein = 100.0))
        repository.saveGoal(NutritionGoalEntity("2026-10-06", kcal = 1500.0))
        var aggregateReads = 0
        val targeted = object : NutritionRepository by repository {
            override fun observeNutritionHistory(fromDay: String, toDay: String, limit: Int, offset: Int): Flow<List<NutritionHistoryDay>> {
                aggregateReads++
                return repository.observeNutritionHistory(fromDay, toDay, limit, offset)
            }
            override fun observeDiary(day: String): Flow<List<DiaryEntity>> = error("History must not materialize diary snapshots")
            override fun observeFood(id: String): Flow<FoodDetail?> = error("History must not read foods")
            override fun observeFoodPage(query: String, filter: FoodBrowseFilter, limit: Int, offset: Int): Flow<List<FoodEntity>> =
                error("History must not read catalog")
        }
        val model = keep(targeted)
        runCurrent()
        assertEquals(0, aggregateReads)
        observe(model)
        val first = loaded(model) { it.days.size == 1 }.days.single()
        assertEquals(2, first.entryCount)
        assertEquals(200.0, first.totals.kcal.knownValue, 0.0)
        assertEquals(1, first.totals.kcal.missingCount)
        assertFalse(first.totals.kcal.complete)
        assertEquals(1000.0, first.goal!!.kcal!!, 0.0)
        assertNull(DiaryMath.remaining(first.goal!!.kcal, first.totals.kcal))
        val original = repository.diaryEntry(known)!!
        repository.saveDiary(original.copy(quantity = 150.0, factor = 1.5))
        assertEquals(300.0, loaded(model) { it.days.singleOrNull()?.totals?.kcal?.knownValue == 300.0 }.days.single().totals.kcal.knownValue, 0.0)
        repository.moveDiary(known, "2026-10-07", MealType.DINNER)
        val moved = loaded(model) { it.days.size == 2 }
        assertEquals(listOf("2026-10-07", "2026-10-05"), moved.days.map { it.day })
        assertEquals(1500.0, moved.days.first().goal!!.kcal!!, 0.0)
        repository.deleteDiary(unknown)
        assertEquals("2026-10-07", loaded(model) { it.days.size == 1 }.days.single().day)
        repository.deleteDiary(known)
        assertTrue(loaded(model) { it.days.isEmpty() }.days.isEmpty())
        assertEquals(1, aggregateReads)
    }

    @Test fun `paging continues past200 with bounded lookahead immediate tap guard and restored explicit page`() = runTest(dispatcher) {
        val pages = Pages(repository, rows(260))
        val saved = SavedStateHandle()
        val model = keep(pages, saved)
        observe(model)
        loaded(model) { it.days.size == 50 }
        for (count in listOf(100, 150, 200, 250)) {
            model.loadMore(); model.loadMore()
            val page = loaded(model) { it.days.size == count }
            assertEquals(rows(260).take(count), page.days)
            assertEquals(count, page.days.map { it.day }.distinct().size)
            assertTrue(page.hasMore)
        }
        assertTrue(pages.calls.value.all { it.limit in 1..200 })
        assertTrue(pages.calls.value.any { it.offset == 200 })
        val restored = keep(pages, copyHandle(saved))
        observe(restored)
        assertEquals(model.state.value, loaded(restored) { it.days.size == 250 })
        model.loadMore()
        val final = loaded(model) { it.days.size == 260 }
        assertFalse(final.hasMore)
        assertFalse(final.limitReached)
    }

    @Test fun `valid range resets paging cancels stale reads and invalid ranges preserve accepted selectors and pages`() = runTest(dispatcher) {
        val pages = Pages(repository, rows(260))
        val saved = SavedStateHandle()
        val model = keep(pages, saved)
        observe(model)
        loaded(model) { it.days.size == 50 }
        model.loadMore()
        loaded(model) { it.days.size == 100 }
        assertFalse(model.setRange("2026-02-30", ""))
        assertFalse(model.setRange("2026-10-14", "2026-10-13"))
        assertFalse(model.setRange("0000-01-01", "9999-12-31"))
        assertEquals(100, saved.get<Int>("nutrition_history_rows"))
        assertEquals("", saved.get<String>("nutrition_history_from"))
        assertTrue(model.setRange(Pages.SLOW_FROM, "2026-10-13"))
        pages.calls.first { calls -> calls.any { it.from == Pages.SLOW_FROM } }
        assertTrue(model.setRange("2026-09-01", "2026-10-13"))
        val filtered = loaded(model) { it.fromDay == "2026-09-01" && it.toDay == "2026-10-13" }
        pages.cancelled.first { calls -> calls.any { it.from == Pages.SLOW_FROM } }
        assertEquals(rows(260).filter { it.day >= "2026-09-01" }, filtered.days)
        assertFalse(filtered.error)
        assertEquals(50, saved.get<Int>("nutrition_history_rows"))
        assertTrue(model.setRange("", "2026-09-01"))
        val before = loaded(model) { it.toDay == "2026-09-01" && it.fromDay.isEmpty() }
        assertTrue(before.days.all { it.day <= "2026-09-01" })
        assertTrue(model.setRange("", ""))
        assertEquals(50, loaded(model) { it.toDay.isEmpty() && it.fromDay.isEmpty() }.days.size)
    }

    @Test fun `partial page invalidations never duplicate dates and eventually reconcile current daily payloads`() = runTest(dispatcher) {
        val original = rows(260)
        val pages = Pages(repository, original)
        val model = keep(pages)
        observe(model)
        loaded(model) { it.days.size == 50 }
        repeat(4) { index -> model.loadMore(); loaded(model) { it.days.size == 100 + index * 50 } }
        pages.overrides.value = mapOf(0 to (listOf(original[200]) + original.take(199)))
        val partial = loaded(model) { it.days.size == 249 }
        assertEquals(249, partial.days.map { it.day }.distinct().size)
        assertEquals(partial.days.map { it.day }.sortedDescending(), partial.days.map { it.day })
        val updated = original.map { it.copy(entryCount = it.entryCount + 1000) }
        pages.values.value = updated
        pages.overrides.value = emptyMap()
        assertEquals(updated.take(250), loaded(model) { it.days == updated.take(250) }.days)
    }

    @Test fun `read failure retry retains filter and page while lifecycle stops and resumes aggregate observers`() = runTest(dispatcher) {
        val pages = Pages(repository, rows(300))
        val saved = SavedStateHandle()
        val model = keep(pages, saved)
        assertTrue(model.setRange("2026-03-01", "2026-10-13"))
        val collector = observe(model)
        loaded(model) { it.days.size == 50 }
        model.loadMore()
        val before = loaded(model) { it.days.size == 100 }
        pages.fail.set(true)
        model.reload()
        val failed = model.state.first { it.error }
        assertFalse(failed.loading)
        assertEquals(before.fromDay, failed.fromDay)
        assertEquals(before.toDay, failed.toDay)
        assertEquals(100, saved.get<Int>("nutrition_history_rows"))
        pages.fail.set(false)
        model.reload()
        assertEquals(before.days, loaded(model) { it.days.size == 100 }.days)
        collector.cancelAndJoin()
        advanceTimeBy(5001)
        runCurrent()
        pages.active.first { it == 0 }
        pages.values.value = rows(300).map { it.copy(entryCount = 999) }
        observe(model)
        val resumed = loaded(model) { it.days.size == 100 && it.days.all { day -> day.entryCount == 999 } }
        assertFalse(resumed.error)
        assertEquals(before.fromDay, resumed.fromDay)
    }

    @Test fun `explicit maximum is visible rather than pretending end and malformed restored ranges safely reset`() = runTest(dispatcher) {
        val pages = Pages(repository, rows(5001))
        val saved = SavedStateHandle(mapOf("nutrition_history_rows" to 5000))
        val model = keep(pages, saved)
        observe(model)
        val capped = loaded(model) { it.days.size == 5000 }
        assertFalse(capped.hasMore)
        assertTrue(capped.limitReached)
        assertTrue(pages.calls.value.all { it.limit <= 200 })
        assertTrue(pages.calls.value.any { it.offset == 5000 && it.limit == 1 })
        val calls = pages.calls.value.size
        model.loadMore()
        runCurrent()
        assertEquals(calls, pages.calls.value.size)
        assertTrue(model.setRange("2026-09-01", "2026-10-13"))
        assertFalse(loaded(model) { it.fromDay == "2026-09-01" }.limitReached)
        assertEquals(setOf("nutrition_history_from", "nutrition_history_to", "nutrition_history_rows"), saved.keys())
        val malformed = keep(pages, SavedStateHandle(mapOf("nutrition_history_from" to "not-a-date", "nutrition_history_rows" to 5000)))
        observe(malformed)
        val reset = loaded(malformed) { it.days.size == 50 }
        assertEquals("", reset.fromDay)
        assertFalse(reset.limitReached)
    }

    private fun keep(source: NutritionRepository = repository, saved: SavedStateHandle = SavedStateHandle()) =
        NutritionHistoryViewModel(source, saved).also { store.put("history:${store.keys().size}", it) }
    private fun TestScope.observe(model: NutritionHistoryViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect() }
    private suspend fun loaded(model: NutritionHistoryViewModel, matches: (NutritionHistoryUiState) -> Boolean): NutritionHistoryUiState =
        model.state.first { !it.loading && !it.error && matches(it) }
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    private fun entry(day: String, kcal: Double?) = DiaryEntity(day = day, meal = MealType.BREAKFAST, foodId = "historical", loggedAt = 1000,
        snapshot = FoodSnapshot(foodId = "historical", name = "Recorded food", nutrients = listOf(
            NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", kcal))), quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0)
    private fun rows(count: Int): List<NutritionHistoryDay> = List(count) { index ->
        NutritionHistoryDay(LocalDate.parse("2026-10-13").minusDays(index.toLong()).toString(), index + 1,
            DailyNutritionTotals(kcal = NutrientTotal(index.toDouble())))
    }

    private data class Call(val from: String, val to: String, val limit: Int, val offset: Int)
    private class Pages(delegate: NutritionRepository, rows: List<NutritionHistoryDay>) : NutritionRepository by delegate {
        val values = MutableStateFlow(rows)
        val overrides = MutableStateFlow<Map<Int, List<NutritionHistoryDay>>>(emptyMap())
        val calls = MutableStateFlow<List<Call>>(emptyList())
        val cancelled = MutableStateFlow<List<Call>>(emptyList())
        val active = MutableStateFlow(0)
        val fail = AtomicBoolean(false)
        override fun observeNutritionHistory(fromDay: String, toDay: String, limit: Int, offset: Int): Flow<List<NutritionHistoryDay>> = flow {
            val call = Call(fromDay, toDay, limit, offset)
            calls.update { it + call }; active.update { it + 1 }
            try {
                if (fail.get()) throw IOException("Injected aggregate failure")
                if (fromDay == SLOW_FROM) awaitCancellation()
                emitAll(combine(values, overrides) { rows, custom ->
                    custom[offset]?.take(limit) ?: rows.filter { it.day >= fromDay && it.day <= toDay }.drop(offset).take(limit)
                })
            } catch (failure: CancellationException) {
                cancelled.update { it + call }; throw failure
            } finally { active.update { it - 1 } }
        }
        companion object { const val SLOW_FROM = "2026-01-01" }
    }
}

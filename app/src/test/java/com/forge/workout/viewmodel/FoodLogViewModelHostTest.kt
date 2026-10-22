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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class FoodLogViewModelHostTest {
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
    private val snapshot get() = FoodSnapshot(foodId = "custom:meal", name = "Test food", nutrients = listOf(
        NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 200.0),
        NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", null)))
    private fun model(repo: NutritionRepository = repository, saved: SavedStateHandle = SavedStateHandle()) =
        FoodLogViewModel(repo, saved) { it.message ?: "Failed" }.also { store.put("${store.keys().size}", it) }

    @Test fun `past day and meal are retained and logging caches scaled snapshots without catalog dependency`() = runTest(dispatcher) {
        val vm = model()
        vm.selectDay("2026-09-30")
        vm.selectMeal(MealType.DINNER)
        vm.log("first", snapshot, NutritionMath.choices(snapshot).first(), 150.0)
        val id = vm.result.first { it?.entryId != null }!!.entryId!!
        vm.flush()
        val row = repository.diaryEntry(id)!!
        assertEquals("2026-09-30", row.day)
        assertEquals(MealType.DINNER, row.meal)
        assertEquals(300.0, row.kcal!!, 0.0)
        assertNull(row.carbs)
        assertEquals(200.0, row.snapshot.nutrients.first().value!!, 0.0)
        assertEquals(1.5, row.factor, 0.0)
        assertTrue(repository.observeDiary("2026-10-01").first().isEmpty())
        vm.acknowledge("unrelated")
        assertNotNull(vm.result.value)
        vm.acknowledge("first")
        assertNull(vm.result.value)
    }

    @Test fun `rotation state restores date and meal and restore reset clears obsolete results`() = runTest(dispatcher) {
        val restored = SavedStateHandle(mapOf("food_log_day" to "2025-01-01", "food_log_meal" to "LUNCH"))
        val vm = model(saved = restored)
        assertEquals("2025-01-01", vm.day.value)
        assertEquals(MealType.LUNCH.name, vm.meal.value)
        vm.log("log", snapshot, NutritionMath.choices(snapshot).first(), 100.0)
        vm.result.first { it?.entryId != null }
        vm.flush()
        vm.resetAfterRestore()
        assertNull(vm.result.value)
        assertEquals(java.time.LocalDate.now().toString(), vm.day.value)
        assertEquals(MealType.BREAKFAST.name, vm.meal.value)
        assertThrows(IllegalArgumentException::class.java) { vm.selectDay("2026-02-30") }
    }

    @Test fun `rapid confirmation cannot duplicate entry and caller edits cannot change pending snapshot`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val pending = object : NutritionRepository by repository {
            override suspend fun saveDiary(entry: DiaryEntity): Long { gate.await(); return repository.saveDiary(entry) }
        }
        val vm = model(pending)
        val values = snapshot.nutrients.toMutableList()
        val input = snapshot.copy(nutrients = values)
        val choice = NutritionMath.choices(input).first()
        vm.log("original", input, choice, 100.0)
        vm.log("double-tap", input, choice, 100.0)
        values.clear()
        vm.selectDay("2026-09-20")
        runCurrent()
        assertTrue(vm.busy.value)
        gate.complete(Unit)
        val result = vm.result.first { it?.entryId != null }!!
        vm.flush()
        assertEquals("original", result.requestId)
        val entry = repository.diaryEntry(result.entryId!!)!!
        assertEquals(200.0, entry.kcal!!, 0.0)
        assertEquals(java.time.LocalDate.now().toString(), entry.day)
        assertEquals(1, repository.observeDiary(entry.day).first().size)
    }

    @Test fun `invalid amount produces feedback and a valid retry writes only one row`() = runTest(dispatcher) {
        val vm = model()
        vm.log("entry", snapshot, NutritionMath.choices(snapshot).first(), -5.0)
        val failure = vm.result.first { it?.error != null }!!
        assertNotNull(failure.error)
        vm.flush()
        assertTrue(repository.observeDiary(vm.day.value).first().isEmpty())
        vm.log("entry", snapshot, NutritionMath.choices(snapshot).first(), 50.0)
        val success = vm.result.first { it?.entryId != null }!!
        val row = repository.diaryEntry(success.entryId!!)!!
        vm.flush()
        vm.acknowledge(failure)
        assertEquals(success, vm.result.value)
        assertTrue(success.operationId > failure.operationId)
        vm.acknowledge(success.copy())
        assertSame(success, vm.result.value)
        assertEquals(100.0, row.kcal!!, 0.0)
        assertFalse(vm.busy.value)
    }

    @Test fun `database failure resets writer for retry and does not acknowledge a nonexistent save`() = runTest(dispatcher) {
        var fail = true
        val failing = object : NutritionRepository by repository {
            override suspend fun saveDiary(entry: DiaryEntity): Long {
                if (fail) throw IOException("Storage unavailable")
                return repository.saveDiary(entry)
            }
        }
        val vm = model(failing)
        vm.log("same-request", snapshot, NutritionMath.choices(snapshot).first(), 100.0)
        val failure = vm.result.first { it?.error != null }!!
        vm.flush()
        assertEquals("Storage unavailable", failure.error)
        assertNull(failure.entryId)
        assertTrue(failure.operationId > 0)
        vm.acknowledge(failure.copy())
        assertSame(failure, vm.result.value)
        vm.log("same-request", snapshot, NutritionMath.choices(snapshot).first(), 100.0)
        assertNull(vm.result.value)
        val repeated = vm.result.first { it?.error != null }!!
        vm.flush()
        assertEquals(failure.requestId, repeated.requestId)
        assertEquals(failure.error, repeated.error)
        assertTrue(repeated.operationId > failure.operationId)
        vm.acknowledge(failure)
        vm.acknowledge(repeated.copy())
        assertSame(repeated, vm.result.value)
        fail = false
        vm.log("same-request", snapshot, NutritionMath.choices(snapshot).first(), 100.0)
        assertNull(vm.result.value)
        val success = vm.result.first { it?.entryId != null }!!
        assertNotNull(success.entryId)
        assertTrue(success.operationId > repeated.operationId)
        vm.acknowledge(repeated)
        vm.acknowledge(success.copy())
        assertSame(success, vm.result.value)
        vm.flush()
        assertEquals(1, repository.observeDiary(vm.day.value).first().size)
    }

    @Test fun `restore reset suppresses a late logging outcome and the next accepted operation stays usable`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val pending = object : NutritionRepository by repository {
            override suspend fun saveDiary(entry: DiaryEntity): Long { gate.await(); return repository.saveDiary(entry) }
        }
        val vm = model(pending)
        vm.selectDay("2026-09-20")
        vm.log("obsolete", snapshot, NutritionMath.choices(snapshot).first(), 100.0)
        runCurrent()
        assertTrue(vm.busy.value)
        vm.resetAfterRestore()
        gate.complete(Unit)
        vm.flush()
        assertFalse(vm.busy.value)
        assertNull(vm.result.value)
        assertEquals(1, repository.observeDiary("2026-09-20").first().size)
        vm.log("fresh", snapshot, NutritionMath.choices(snapshot).first(), 100.0)
        val fresh = vm.result.first { it?.entryId != null }!!
        vm.flush()
        assertEquals("fresh", fresh.requestId)
        assertEquals(2L, fresh.operationId)
    }
}

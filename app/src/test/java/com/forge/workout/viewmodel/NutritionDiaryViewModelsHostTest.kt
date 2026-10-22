package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.IOException
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
class NutritionDiaryViewModelsHostTest {
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

    @Test fun `dashboard is lazy uses only selected diary and effective goal and keeps partial totals honest`() = runTest(dispatcher) {
        val first = repository.saveDiary(entry(quantity = 150.0, factor = 1.5))
        repository.saveDiary(entry(snapshot = snapshot("unknown", kcal = null, protein = 20.0, carbs = 25.0, fat = null)))
        repository.saveGoal(NutritionGoalEntity("2026-10-01", kcal = 1800.0, protein = 100.0))
        repository.saveGoal(NutritionGoalEntity("2026-10-11", kcal = 2200.0))
        val days = mutableListOf<String>()
        val goals = mutableListOf<String>()
        val targeted = object : NutritionRepository by repository {
            override fun observeDiary(day: String): Flow<List<DiaryEntity>> { days += day; return repository.observeDiary(day) }
            override fun observeGoal(day: String): Flow<NutritionGoalEntity?> { goals += day; return repository.observeGoal(day) }
            override fun observeFood(id: String): Flow<FoodDetail?> = error("Dashboard must not load foods")
            override fun observeFoodPage(query: String, filter: FoodBrowseFilter, limit: Int, offset: Int): Flow<List<FoodEntity>> =
                error("Dashboard must not load catalog")
        }
        val selectedDay = MutableStateFlow(DAY)
        val model = keep(NutritionDashboardViewModel(targeted, selectedDay))
        runCurrent()
        assertTrue(days.isEmpty())
        assertTrue(goals.isEmpty())
        observe(model.state)
        val loaded = model.state.first { !it.loading && it.entries.size == 2 }
        assertEquals("2026-10-01", loaded.goal!!.effectiveDay)
        assertEquals(300.0, loaded.totals.kcal.knownValue, 0.0)
        assertEquals(1, loaded.totals.kcal.missingCount)
        assertFalse(loaded.totals.kcal.complete)
        assertNull(DiaryMath.remaining(loaded.goal!!.kcal, loaded.totals.kcal))
        assertEquals(35.0, loaded.totals.protein.knownValue, 0.0)
        assertTrue(loaded.totals.protein.complete)
        assertEquals(25.0, loaded.totals.carbs.knownValue, 0.0)
        assertEquals(1, loaded.totals.carbs.missingCount)
        assertEquals(listOf(DAY), days)
        assertEquals(listOf(DAY), goals)
        selectedDay.value = "2026-10-11"
        val next = model.state.first { it.day == "2026-10-11" && !it.loading }
        assertTrue(next.entries.isEmpty())
        assertEquals(2200.0, next.goal!!.kcal!!, 0.0)
        assertEquals(0.0, next.totals.kcal.knownValue, 0.0)
        repository.saveGoal(NutritionGoalEntity("2026-10-11", kcal = 2400.0))
        assertEquals(2400.0, model.state.first { it.goal?.kcal == 2400.0 }.goal!!.kcal!!, 0.0)
        assertNotNull(repository.diaryEntry(first))
    }

    @Test fun `changing day cancels old observation and retry replaces an explicit read failure`() = runTest(dispatcher) {
        val cancelled = CompletableDeferred<Unit>()
        var fail = true
        val source = object : NutritionRepository by repository {
            override fun observeDiary(day: String): Flow<List<DiaryEntity>> = if (day == DAY) flow {
                try { emit(listOf(entry(id = 10))); awaitCancellation() } finally { cancelled.complete(Unit) }
            } else repository.observeDiary(day)
            override fun observeGoal(day: String): Flow<NutritionGoalEntity?> = if (day != DAY && fail)
                flow { throw IOException("Temporary goal failure") } else repository.observeGoal(day)
        }
        val selectedDay = MutableStateFlow(DAY)
        val model = keep(NutritionDashboardViewModel(source, selectedDay))
        observe(model.state)
        model.state.first { !it.loading && it.entries.isNotEmpty() }
        selectedDay.value = "2026-10-09"
        val failed = model.state.first { it.day == "2026-10-09" && it.error }
        cancelled.await()
        assertTrue(failed.entries.isEmpty())
        assertFalse(failed.loading)
        fail = false
        model.reload()
        val recovered = model.state.first { it.day == "2026-10-09" && !it.loading && !it.error }
        assertTrue(recovered.entries.isEmpty())
        assertTrue(recovered.totals.kcal.complete)
    }

    @Test fun `diary editor observes only recorded ID and remains valid after catalog deletion then reflects moves and deletion`() = runTest(dispatcher) {
        val food = FoodDetail(FoodEntity(id = "food", name = "Current food"),
            snapshot().nutrients.map { FoodNutrientEntity("food", it.code, it.name, it.unit, it.value) })
        repository.upsertFood(food)
        val id = repository.saveDiary(entry())
        val ids = mutableListOf<Long>()
        val targeted = object : NutritionRepository by repository {
            override fun observeDiaryEntry(id: Long): Flow<DiaryEntity?> { ids += id; return repository.observeDiaryEntry(id) }
            override fun observeFood(id: String): Flow<FoodDetail?> = error("Recorded food must not refresh from catalog")
        }
        val model = keep(DiaryEntryViewModel(targeted, id))
        runCurrent()
        assertTrue(ids.isEmpty())
        observe(model.state)
        val original = model.state.first { !it.loading }.entry!!
        repository.upsertFood(food.copy(nutrients = food.nutrients.map { it.copy(value = 900.0) }))
        repository.deleteFood("food")
        assertEquals(original.snapshot, repository.diaryEntry(id)!!.snapshot)
        repository.moveDiary(id, "2026-10-08", MealType.DINNER)
        val moved = model.state.first { it.entry?.day == "2026-10-08" }.entry!!
        assertEquals(original.snapshot, moved.snapshot)
        assertEquals(original.kcal, moved.kcal)
        assertEquals(MealType.DINNER, moved.meal)
        assertEquals(listOf(id), ids)
        repository.deleteDiary(id)
        val deleted = model.state.first { !it.loading && it.entry == null }
        assertFalse(deleted.error)
    }

    @Test fun `entry source lists freeze and targeted read failure can retry without displaying cancellation as failure`() = runTest(dispatcher) {
        val values = snapshot().nutrients.toMutableList()
        val recorded = entry(id = 1, snapshot = snapshot().copy(nutrients = values))
        val rows = MutableStateFlow<DiaryEntity?>(recorded)
        var requests = 0
        val source = object : NutritionRepository by repository {
            override fun observeDiaryEntry(id: Long): Flow<DiaryEntity?> = if (++requests == 1)
                flow { throw IOException("Read failed") } else rows
        }
        val model = keep(DiaryEntryViewModel(source, 1))
        observe(model.state)
        assertFalse(model.state.first { it.error }.loading)
        model.reload()
        val published = model.state.first { it.entry != null }.entry!!
        values.clear()
        assertEquals(4, published.snapshot.nutrients.size)
        assertFalse(model.state.value.error)
        val cancelled = object : NutritionRepository by repository {
            override fun observeDiaryEntry(id: Long): Flow<DiaryEntity?> = flow { throw CancellationException("Cancelled") }
        }
        val cancelledModel = keep(DiaryEntryViewModel(cancelled, 1))
        observe(cancelledModel.state)
        runCurrent()
        assertFalse(cancelledModel.state.value.error)
    }

    @Test fun `goal editor loads effective defaults and restores all raw fields for selected day without writing earlier goal`() = runTest(dispatcher) {
        val earlier = NutritionGoalEntity("2026-10-01", 1800.0, 100.0, null, 50.0)
        repository.saveGoal(earlier)
        val handle = SavedStateHandle()
        val model = keep(GoalEditorViewModel(repository, DAY, handle))
        observe(model.state)
        val initial = model.state.first { !it.loading }.draft
        assertEquals(DAY, initial.day)
        assertEquals("1800", initial.kcal)
        assertEquals("", initial.carbs)
        val raw = initial.copy(day = "2025-01-01", kcal = "2000,5", protein = "0", carbs = " ", fat = "60.25")
        assertTrue(model.updateDraft(raw))
        val restored = keep(GoalEditorViewModel(repository, DAY, copyHandle(handle)))
        observe(restored.state)
        val draft = restored.state.first { !it.loading }.draft
        assertEquals(raw.copy(day = DAY), draft)
        val validated = restored.validatedGoal()!!
        assertEquals(DAY, validated.effectiveDay)
        assertEquals(2000.5, validated.kcal!!, 0.0)
        assertEquals(0.0, validated.protein!!, 0.0)
        assertNull(validated.carbs)
        assertEquals(60.25, validated.fat!!, 0.0)
        assertEquals(earlier, repository.observeGoal("2026-10-01").first())
        assertEquals(earlier, repository.observeGoal(DAY).first())
        repository.saveGoal(NutritionGoalEntity(DAY, kcal = 2500.0))
        assertEquals("2000,5", restored.state.value.draft.kcal)
        assertEquals(2000.5, restored.validatedGoal()!!.kcal!!, 0.0)
    }

    @Test fun `goal validation allows zero and unknown while rejecting negative nonfinite and excessive values with bounded drafts`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val model = keep(GoalEditorViewModel(repository, DAY, handle))
        observe(model.state)
        model.state.first { !it.loading }
        assertTrue(model.updateDraft(GoalDraft(DAY, kcal = "NaN", protein = "-1", carbs = "1000000001", fat = "Infinity")))
        assertNull(model.validatedGoal())
        assertEquals(setOf("kcal", "protein", "carbs", "fat"), model.state.first { it.invalidFields.size == 4 }.invalidFields)
        val accepted = GoalDraft(DAY, kcal = "0", protein = "", carbs = "2,5", fat = "0")
        assertTrue(model.updateDraft(accepted))
        assertEquals(NutritionGoalEntity(DAY, 0.0, null, 2.5, 0.0), model.validatedGoal())
        assertFalse(model.updateDraft(accepted.copy(kcal = "9".repeat(251))))
        assertEquals("0", handle.get<String>("goal_kcal"))
        assertEquals(accepted, model.state.first { it.draft == accepted && it.invalidFields == setOf("kcal") }.draft)
        val restored = keep(GoalEditorViewModel(repository, DAY, copyHandle(handle)))
        observe(restored.state)
        assertEquals(accepted, restored.state.first { !it.loading }.draft)
        assertEquals(NutritionGoalEntity(DAY, 0.0, null, 2.5, 0.0), restored.validatedGoal())
        assertTrue(model.updateDraft(accepted))
        assertNotNull(model.validatedGoal())
        assertTrue(model.state.first { it.draft == accepted && it.invalidFields.isEmpty() }.invalidFields.isEmpty())
    }

    @Test fun `goal editor retry is targeted and another selected day cannot inherit saved dirty fields`() = runTest(dispatcher) {
        var requests = 0
        val source = object : NutritionRepository by repository {
            override fun observeGoal(day: String): Flow<NutritionGoalEntity?> = if (++requests == 1)
                flow { throw IOException("Goal read unavailable") } else repository.observeGoal(day)
        }
        val handle = SavedStateHandle()
        val model = keep(GoalEditorViewModel(source, DAY, handle))
        observe(model.state)
        model.state.first { it.error }
        assertNull(model.validatedGoal())
        model.reload()
        assertEquals("", model.state.first { !it.loading && !it.error }.draft.kcal)
        assertTrue(model.updateDraft(GoalDraft(DAY, kcal = "1700")))
        val other = keep(GoalEditorViewModel(repository, "2026-10-11", copyHandle(handle)))
        observe(other.state)
        assertEquals("", other.state.first { !it.loading }.draft.kcal)
        assertEquals("2026-10-11", other.validatedGoal()!!.effectiveDay)
    }

    @Test fun `retained writer edits frozen historical portions once and preserves entry identity and logged time`() = runTest(dispatcher) {
        val sourceRow = entry(quantity = 2.0, unit = FoodUnit.SERVING, portionId = "old-cup", portionLabel = "Saved cup", factor = 3.0)
        val id = repository.saveDiary(sourceRow)
        val original = repository.diaryEntry(id)!!
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        val source = object : NutritionRepository by repository {
            override suspend fun saveDiary(entry: DiaryEntity): Long { saves++; gate.await(); return repository.saveDiary(entry) }
            override suspend fun food(id: String): FoodDetail? = error("Edit must not consult catalog")
        }
        val model = writer(source)
        val nutrients = original.snapshot.nutrients.toMutableList()
        val mutable = original.copy(snapshot = original.snapshot.copy(nutrients = nutrients))
        val choice = NutritionMath.choices(DiaryMath.snapshotDetails(original)).single { it.portionId == "old-cup" }
        model.saveEntry("edit", mutable, choice, 3.0, "2026-10-08", MealType.DINNER)
        model.saveEntry("double-tap", mutable, choice, 4.0, DAY, MealType.LUNCH)
        nutrients.clear()
        runCurrent()
        assertTrue(model.busy.value)
        gate.complete(Unit)
        val result = model.result.first { it?.entryId != null }!!
        model.flush()
        val edited = repository.diaryEntry(id)!!
        assertEquals("edit", result.requestId)
        assertEquals(1, saves)
        assertEquals(id, edited.id)
        assertEquals(original.loggedAt, edited.loggedAt)
        assertEquals(original.snapshot, edited.snapshot)
        assertEquals(original.foodId, edited.foodId)
        assertEquals("2026-10-08", edited.day)
        assertEquals(MealType.DINNER, edited.meal)
        assertEquals(4.5, edited.factor, 0.0)
        assertEquals(900.0, edited.kcal!!, 0.0)
        assertNull(edited.carbs)
        assertTrue(repository.observeDiary(DAY).first().isEmpty())
    }

    @Test fun `writer move repeat delete and goal writes use current rows fresh identities and fixed request inputs`() = runTest(dispatcher) {
        val first = repository.saveDiary(entry())
        val second = repository.saveDiary(entry(snapshot = snapshot("second"), meal = MealType.DINNER))
        val model = writer()
        model.move("move", first, "2026-10-09", MealType.LUNCH)
        model.result.first { it?.requestId == "move" }
        model.flush()
        val moved = repository.diaryEntry(first)!!
        assertEquals("2026-10-09", moved.day)
        val selected = mutableListOf(first, second, first)
        model.repeat("repeat", selected, "2026-10-08", MealType.BREAKFAST)
        selected.clear()
        val repeated = model.result.first { it?.requestId == "repeat" }!!
        model.flush()
        val copies = repository.observeDiary("2026-10-08").first()
        assertEquals(2, copies.size)
        assertTrue(copies.none { it.id == first || it.id == second })
        assertEquals(copies.first().id, repeated.entryId)
        assertEquals(listOf(moved.snapshot, repository.diaryEntry(second)!!.snapshot), copies.map { it.snapshot })
        assertEquals(moved, repository.diaryEntry(first))
        model.delete("delete", first)
        model.result.first { it?.requestId == "delete" }
        model.flush()
        assertNull(repository.diaryEntry(first))
        assertNotNull(repository.diaryEntry(second))
        val goal = NutritionGoalEntity(DAY, kcal = 0.0, protein = null, carbs = 180.0, fat = 40.0)
        model.saveGoal("goal", goal)
        model.result.first { it?.requestId == "goal" }
        model.flush()
        assertEquals(goal, repository.observeGoal(DAY).first())
    }

    @Test fun `writer errors are recoverable and identity acknowledgment cannot erase a newer outcome`() = runTest(dispatcher) {
        var fail = true
        val source = object : NutritionRepository by repository {
            override suspend fun saveGoal(goal: NutritionGoalEntity) {
                if (fail) throw IOException("Storage unavailable")
                repository.saveGoal(goal)
            }
        }
        val model = writer(source)
        val goal = NutritionGoalEntity(DAY, kcal = 1800.0)
        model.saveGoal("same-request", goal)
        val failure = model.result.first { it?.error != null }!!
        model.flush()
        assertEquals("Storage unavailable", failure.error)
        assertFalse(model.busy.value)
        assertTrue(failure.operationId > 0)
        model.acknowledge(failure.copy())
        assertSame(failure, model.result.value)
        model.saveGoal("same-request", goal)
        assertNull(model.result.value)
        val repeated = model.result.first { it?.error != null }!!
        model.flush()
        assertEquals(failure.requestId, repeated.requestId)
        assertEquals(failure.error, repeated.error)
        assertTrue(repeated.operationId > failure.operationId)
        model.acknowledge(failure)
        model.acknowledge(repeated.copy())
        assertSame(repeated, model.result.value)
        fail = false
        model.saveGoal("same-request", goal)
        val success = model.result.first { it?.requestId == "same-request" && it.error == null }!!
        model.flush()
        assertTrue(success.operationId > repeated.operationId)
        model.acknowledge(failure)
        assertSame(success, model.result.value)
        model.acknowledge(success.copy())
        assertSame(success, model.result.value)
        model.acknowledge(success)
        assertNull(model.result.value)
        val id = repository.saveDiary(entry())
        val before = repository.diaryEntry(id)
        model.move("invalid", id, "2026-02-30", MealType.SNACK)
        assertNotNull(model.result.first { it?.requestId == "invalid" }!!.error)
        model.flush()
        assertEquals(before, repository.diaryEntry(id))
    }

    @Test fun `reset generation suppresses late retained outcomes and cancellation releases busy without false feedback`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = object : NutritionRepository by repository {
            override suspend fun saveGoal(goal: NutritionGoalEntity) { gate.await(); repository.saveGoal(goal) }
        }
        val model = writer(source)
        model.saveGoal("obsolete", NutritionGoalEntity(DAY, kcal = 1800.0))
        runCurrent()
        assertTrue(model.busy.value)
        model.resetAfterRestore()
        assertNull(model.result.value)
        gate.complete(Unit)
        model.flush()
        assertNull(model.result.value)
        assertFalse(model.busy.value)
        val cancelled = object : NutritionRepository by repository {
            override suspend fun saveGoal(goal: NutritionGoalEntity) { throw CancellationException("Cancelled write") }
        }
        val cancellation = writer(cancelled)
        cancellation.saveGoal("cancelled", NutritionGoalEntity(DAY, kcal = 2000.0))
        cancellation.flush()
        assertFalse(cancellation.busy.value)
        assertNull(cancellation.result.value)
        assertEquals(1800.0, repository.observeGoal(DAY).first()!!.kcal!!, 0.0)
    }

    private fun <T : ViewModel> keep(model: T): T = model.also { store.put("model:${store.keys().size}", it) }
    private fun writer(source: NutritionRepository = repository) = keep(DiaryMutationViewModel(source) { it.message ?: "Failed" })
    private fun <T> TestScope.observe(state: StateFlow<T>) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    private fun snapshot(id: String = "food", kcal: Double? = 200.0, protein: Double? = 10.0, carbs: Double? = null, fat: Double? = 5.0) =
        FoodSnapshot(foodId = id, name = "Captured $id", gramsPerBase = 100.0, nutrients = listOf(
            NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", kcal), NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", protein),
            NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", carbs), NutrientValue(NutrientCodes.FAT, "Fat", "g", fat)))
    private fun entry(id: Long = 0, snapshot: FoodSnapshot = snapshot(), meal: MealType = MealType.BREAKFAST,
        quantity: Double = 100.0, unit: FoodUnit = FoodUnit.GRAM, portionId: String? = null, portionLabel: String? = null, factor: Double = 1.0) =
        DiaryEntity(id = id, day = DAY, meal = meal, foodId = snapshot.foodId, snapshot = snapshot, quantity = quantity, unit = unit,
            portionId = portionId, portionLabel = portionLabel, factor = factor, loggedAt = 1000)
    private companion object { const val DAY = "2026-10-10" }
}

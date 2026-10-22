package com.forge.workout.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Workout
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.preferences.WeightUnit
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
class NutritionEnergyViewModelsHostTest {
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

    @Test fun `body weight conversion happens once and canonical draft survives rounded unit switches and restoration`() = runTest(dispatcher) {
        repository.saveBodyWeight(BodyWeightEntity(day = DAY, kg = 80.0))
        val handle = SavedStateHandle(mapOf("body_weight_day" to DAY))
        val model = keep(BodyWeightViewModel(repository, handle))
        model.setUnit(WeightUnit.LB)
        observe(model.state)
        val loaded = model.state.first { !it.loading && it.selected != null }
        assertEquals("176.37", loaded.draft.weight)
        assertEquals(WeightUnit.LB, loaded.unit)
        assertEquals(80.0, model.validatedWeight()!!.kg, 0.0)
        model.setUnit(WeightUnit.KG)
        assertEquals(80.0, model.validatedWeight()!!.kg, 0.0)
        val pounds = WeightUnit.LB.fromKg(82.0).toString()
        assertTrue(model.updateDraft(BodyWeightDraft(DAY, pounds), WeightUnit.LB))
        assertEquals(82.0, model.validatedWeight()!!.kg, 1e-10)
        model.setUnit(WeightUnit.KG)
        model.setUnit(WeightUnit.LB)
        val restored = keep(BodyWeightViewModel(repository, copyHandle(handle)))
        observe(restored.state)
        val recovered = restored.state.first { !it.loading }
        assertEquals(WeightUnit.LB, recovered.unit)
        assertEquals(82.0, restored.validatedWeight()!!.kg, 1e-10)
        val writer = writer()
        val command = restored.validatedWeight()!!
        writer.saveWeight("weight", command.day, command.kg)
        writer.result.first { it != null }
        writer.flush()
        assertEquals(82.0, repository.bodyWeight(DAY)!!.kg, 1e-10)
    }

    @Test fun `body history uses bounded pages and selected date outside loaded page still loads one indexed record`() = runTest(dispatcher) {
        val start = LocalDate.parse(DAY)
        repeat(152) { repository.saveBodyWeight(BodyWeightEntity(day = start.minusDays(it.toLong()).toString(), kg = 80.0 + it / 100.0)) }
        val limits = mutableListOf<Int>()
        val source = object : NutritionRepository by repository {
            override fun observeBodyWeights(fromDay: String, toDay: String, limit: Int): Flow<List<BodyWeightEntity>> {
                if (fromDay != toDay) limits += limit
                return repository.observeBodyWeights(fromDay, toDay, limit)
            }
        }
        val handle = SavedStateHandle(mapOf("body_weight_day" to DAY))
        val model = keep(BodyWeightViewModel(source, handle))
        runCurrent()
        assertTrue(limits.isEmpty())
        observe(model.state)
        val first = model.state.first { !it.loading }
        assertEquals(100, first.history.size)
        assertTrue(first.hasMore)
        model.loadMore(); model.loadMore()
        val second = model.state.first { !it.loading && it.history.size == 150 }
        assertEquals(150, second.history.map { it.id }.distinct().size)
        assertEquals(listOf(101, 151), limits)
        val oldDay = start.minusDays(151).toString()
        assertTrue(model.setDay(oldDay))
        val selected = model.state.first { !it.loading && it.draft.day == oldDay && it.selected != null }
        assertEquals(81.51, selected.selected!!.kg, 1e-10)
        assertTrue(selected.history.none { it.day == oldDay })
        val restored = keep(BodyWeightViewModel(source, copyHandle(handle)))
        observe(restored.state)
        assertEquals(150, restored.state.first { !it.loading }.history.size)
        model.loadMore()
        val all = model.state.first { !it.loading && it.history.size == 152 }
        assertFalse(all.hasMore)
        assertFalse(all.limitReached)
    }

    @Test fun `body weight invalid quantities and conversion overflow remain editable and page failures can retry`() = runTest(dispatcher) {
        var fail = true
        val source = object : NutritionRepository by repository {
            override fun observeBodyWeights(fromDay: String, toDay: String, limit: Int): Flow<List<BodyWeightEntity>> =
                if (fromDay != toDay && fail) flow { throw IOException("History unavailable") }
                else repository.observeBodyWeights(fromDay, toDay, limit)
        }
        val model = keep(BodyWeightViewModel(source, SavedStateHandle(mapOf("body_weight_day" to DAY))))
        observe(model.state)
        model.state.first { it.error }
        assertTrue(model.updateDraft(BodyWeightDraft(DAY, "1e308"), WeightUnit.KG))
        model.setUnit(WeightUnit.LB)
        assertNull(model.validatedWeight())
        for (value in listOf("NaN", "0", "-1", "2204.63")) {
            assertTrue(model.updateDraft(BodyWeightDraft(DAY, value), WeightUnit.LB))
            assertNull(model.validatedWeight())
        }
        assertTrue(model.updateDraft(BodyWeightDraft(DAY, "176,37"), WeightUnit.LB))
        val accepted = model.validatedWeight()!!
        assertEquals(80.0, accepted.kg, 0.001)
        assertFalse(model.setDay("2026-02-30"))
        assertFalse(model.updateDraft(BodyWeightDraft(DAY, "9".repeat(251)), WeightUnit.KG))
        fail = false
        model.reload()
        val recovered = model.state.first { !it.loading && !it.error }
        assertEquals("176,37", recovered.draft.weight)
        assertEquals(accepted.kg, model.validatedWeight()!!.kg, 0.0)
    }

    @Test fun `settings defaults remain disabled and dirty settings restore without being overwritten by source changes`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val model = keep(EnergySettingsViewModel(repository, handle))
        observe(model.state)
        val defaults = model.state.first { !it.loading }
        assertFalse(defaults.settings.includeWorkoutCalories)
        assertFalse(defaults.draft.includeWorkoutCalories)
        val draft = EnergySettingsDraft(true, WorkoutActivityPreset.VIGOROUS_RESISTANCE, "65,5")
        assertTrue(model.updateDraft(draft))
        repository.saveSettings(NutritionSettingsEntity(defaultActivity = WorkoutActivityPreset.BODYWEIGHT.id, defaultActiveFraction = 0.2))
        val restored = keep(EnergySettingsViewModel(repository, copyHandle(handle)))
        observe(restored.state)
        assertEquals(draft, restored.state.first { !it.loading }.draft)
        val command = restored.validatedSettings()!!
        assertTrue(command.includeWorkoutCalories)
        assertEquals(WorkoutActivityPreset.VIGOROUS_RESISTANCE.id, command.defaultActivity)
        assertEquals(0.655, command.defaultActiveFraction, 0.0)
        assertFalse(repository.observeSettings().first().includeWorkoutCalories)
        assertTrue(restored.updateDraft(draft.copy(activePercent = "NaN")))
        assertNull(restored.validatedSettings())
        assertTrue(restored.updateDraft(draft.copy(activePercent = "0")))
        assertEquals(0.0, restored.validatedSettings()!!.defaultActiveFraction, 0.0)
        assertFalse(restored.updateDraft(draft.copy(activePercent = "5".repeat(251))))
    }

    @Test fun `targeted workout state distinguishes no estimate from missing weight and retains captured fallback after weight deletion`() = runTest(dispatcher) {
        val id = completed()
        val workoutReads = mutableListOf<Long>()
        val energyReads = mutableListOf<Long>()
        val weightDays = mutableListOf<String>()
        val source = object : NutritionRepository by repository {
            override fun observeEnergyWorkout(id: Long): Flow<EnergyWorkout?> { workoutReads += id; return repository.observeEnergyWorkout(id) }
            override fun observeWorkoutEnergyById(id: Long): Flow<WorkoutEnergyEntity?> { energyReads += id; return repository.observeWorkoutEnergyById(id) }
            override fun observeLatestBodyWeight(day: String): Flow<BodyWeightEntity?> { weightDays += day; return repository.observeLatestBodyWeight(day) }
            override fun observeEnergySources(): Flow<WorkoutEnergySources> = error("Editor must not load the whole workout graph")
        }
        val model = keep(WorkoutEnergyViewModel(source, id, UTC))
        runCurrent()
        assertTrue(workoutReads.isEmpty())
        observe(model.state)
        assertTrue(model.state.first { !it.loading }.missingWeight)
        repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-06", kg = 95.0))
        val weightId = repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-04", kg = 80.0))
        val available = model.state.first { !it.loading && !it.missingWeight }
        assertNull(available.energy)
        assertFalse(available.workoutMissing)
        val estimate = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, 0.5, null, UTC)!!
        val recorded = model.state.first { it.energy != null }.energy!!
        assertEquals(estimate, recorded)
        assertEquals(210.0, recorded.calculatedKcal, 1e-10)
        repository.deleteBodyWeight(weightId)
        val fallback = model.state.first { it.energy?.bodyWeightId == null && it.energy != null }
        assertFalse(fallback.missingWeight)
        assertEquals(80.0, fallback.energy!!.bodyWeightKg, 0.0)
        assertEquals(listOf(id), workoutReads)
        assertEquals(listOf(id), energyReads)
        assertTrue(weightDays.all { it == "2026-10-05" })
    }

    @Test fun `active and subsecond workouts never become editable estimates and read failures can retry`() = runTest(dispatcher) {
        val active = database.workoutDao().insertWorkout(Workout(name = "Active", startTime = START))
        val activeModel = keep(WorkoutEnergyViewModel(repository, active, UTC))
        observe(activeModel.state)
        assertTrue(activeModel.state.first { !it.loading }.workoutMissing)
        val short = completed(duration = 500)
        val shortEditor = keep(WorkoutEnergyEditorViewModel(repository, short, SavedStateHandle(), UTC))
        observe(shortEditor.state)
        assertTrue(shortEditor.state.first { !it.loading }.workoutMissing)
        assertNull(shortEditor.validatedEstimate())
        val id = completed()
        var attempts = 0
        val flaky = object : NutritionRepository by repository {
            override fun observeEnergyWorkout(id: Long): Flow<EnergyWorkout?> = if (++attempts == 1)
                flow { throw IOException("Workout read unavailable") } else repository.observeEnergyWorkout(id)
        }
        val retry = keep(WorkoutEnergyViewModel(flaky, id, UTC))
        observe(retry.state)
        retry.state.first { it.error }
        retry.reload()
        assertFalse(retry.state.first { !it.loading && it.workout != null }.error)
    }

    @Test fun `energy editor uses existing capture and restores dirty raw fields while manual blank clears and zero remains valid`() = runTest(dispatcher) {
        val id = completed()
        repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-04", kg = 80.0))
        val captured = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, 0.5, 125.0, UTC)!!
        val handle = SavedStateHandle()
        val model = keep(WorkoutEnergyEditorViewModel(repository, id, handle, UTC))
        observe(model.state)
        val loaded = model.state.first { !it.loading }
        assertEquals(captured, loaded.energy)
        assertEquals("125", loaded.draft.manualKcal)
        assertEquals("50", loaded.draft.activePercent)
        val draft = WorkoutEnergyDraft(WorkoutActivityPreset.BODYWEIGHT, "75,5", "80,25")
        assertTrue(model.updateDraft(draft))
        repository.saveSettings(NutritionSettingsEntity(defaultActivity = WorkoutActivityPreset.LIGHT_CALISTHENICS.id, defaultActiveFraction = 0.25))
        val restored = keep(WorkoutEnergyEditorViewModel(repository, id, copyHandle(handle), UTC))
        observe(restored.state)
        assertEquals(draft, restored.state.first { !it.loading }.draft)
        assertEquals(WorkoutEnergyCommand(id, WorkoutActivityPreset.BODYWEIGHT, 0.755, 80.25), restored.validatedEstimate())
        assertTrue(restored.updateDraft(draft.copy(activePercent = "0", manualKcal = "0")))
        assertEquals(0.0, restored.validatedEstimate()!!.manualKcal!!, 0.0)
        assertEquals(0.0, restored.validatedEstimate()!!.activeFraction, 0.0)
        assertTrue(restored.updateDraft(draft.copy(manualKcal = " ")))
        assertNull(restored.validatedEstimate()!!.manualKcal)
        assertEquals(captured, repository.workoutEnergy(id))
    }

    @Test fun `editor invalid assumptions never fabricate inputs and a saved handle from another workout is ignored`() = runTest(dispatcher) {
        val id = completed()
        val handle = SavedStateHandle()
        val model = keep(WorkoutEnergyEditorViewModel(repository, id, handle, UTC))
        observe(model.state)
        assertTrue(model.state.first { !it.loading }.missingWeight)
        assertTrue(model.updateDraft(WorkoutEnergyDraft(activePercent = "100", manualKcal = "123")))
        assertNull(model.validatedEstimate())
        repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-04", kg = 80.0))
        model.state.first { !it.loading && !it.missingWeight }
        assertTrue(model.updateDraft(WorkoutEnergyDraft(activePercent = "101", manualKcal = "-1")))
        assertNull(model.validatedEstimate())
        assertEquals(setOf("activePercent", "manualKcal"), model.state.first { it.invalidFields.size == 2 }.invalidFields)
        val otherId = completed()
        val other = keep(WorkoutEnergyEditorViewModel(repository, otherId, copyHandle(handle), UTC))
        observe(other.state)
        assertEquals(WorkoutEnergyDraft(), other.state.first { !it.loading }.draft)
        assertNotNull(other.validatedEstimate())
    }

    @Test fun `writer resolves fresh timing rejects double taps and gives every repeated request its own retained result`() = runTest(dispatcher) {
        val id = completed()
        repository.saveBodyWeight(BodyWeightEntity(day = "2026-10-04", kg = 80.0))
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        val source = object : NutritionRepository by repository {
            override suspend fun updateWorkoutEstimate(id: Long, preset: WorkoutActivityPreset, activeFraction: Double,
                manualKcal: Double?, zone: ZoneId): WorkoutEnergyEntity? {
                writes++; gate.await()
                return repository.updateWorkoutEstimate(id, preset, activeFraction, manualKcal, UTC)
            }
        }
        val model = writer(source)
        model.saveEstimate("same", id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, 0.5, 125.0)
        model.saveEstimate("double", id, WorkoutActivityPreset.BODYWEIGHT, 0.1, null)
        runCurrent()
        assertTrue(model.busy.value)
        database.workoutDao().updateWorkout(Workout(id = id, name = "Edited completed workout", startTime = START, endTime = START + 7_200_000))
        gate.complete(Unit)
        val first = model.result.first { it != null }!!
        model.flush()
        assertEquals(1, writes)
        assertEquals(420.0, repository.workoutEnergy(id)!!.calculatedKcal, 1e-10)
        assertEquals(125.0, repository.workoutEnergy(id)!!.effectiveKcal, 0.0)
        model.saveEstimate("same", id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, 0.5, null)
        val second = model.result.first { it != null && it.operationId > first.operationId }!!
        model.flush()
        assertEquals(first.requestId, second.requestId)
        assertNull(repository.workoutEnergy(id)!!.manualKcal)
        model.acknowledge(first)
        assertSame(second, model.result.value)
        model.acknowledge(second)
        assertNull(model.result.value)
    }

    @Test fun `writer weight settings failures retry and late outcomes are suppressed after reset or cancellation`() = runTest(dispatcher) {
        val model = writer()
        model.saveWeight("bad", DAY, -1.0)
        assertNotNull(model.result.first { it != null }!!.error)
        model.flush()
        assertNull(repository.bodyWeight(DAY))
        model.saveWeight("weight", DAY, 80.0)
        model.result.first { it?.requestId == "weight" }
        model.flush()
        val weight = repository.bodyWeight(DAY)!!
        model.deleteWeight("delete", weight.id)
        model.result.first { it?.requestId == "delete" }
        model.flush()
        assertNull(repository.bodyWeight(DAY))
        val gate = CompletableDeferred<Unit>()
        val source = object : NutritionRepository by repository {
            override suspend fun saveSettings(settings: NutritionSettingsEntity) { gate.await(); repository.saveSettings(settings) }
        }
        val pending = writer(source)
        pending.saveSettings("obsolete", NutritionSettingsEntity(includeWorkoutCalories = true))
        runCurrent()
        pending.resetAfterRestore()
        gate.complete(Unit)
        pending.flush()
        assertTrue(repository.observeSettings().first().includeWorkoutCalories)
        assertNull(pending.result.value)
        assertFalse(pending.busy.value)
        val cancelled = object : NutritionRepository by repository {
            override suspend fun saveSettings(settings: NutritionSettingsEntity) { throw CancellationException("Cancelled write") }
        }
        val cancellation = writer(cancelled)
        cancellation.saveSettings("cancelled", NutritionSettingsEntity())
        cancellation.flush()
        assertNull(cancellation.result.value)
        assertFalse(cancellation.busy.value)
    }

    private fun <T : ViewModel> keep(model: T): T = model.also { store.put("model:${store.keys().size}", it) }
    private fun writer(source: NutritionRepository = repository) = keep(NutritionEnergyMutationViewModel(source) { it.message ?: "Failed" })
    private fun <T> TestScope.observe(state: StateFlow<T>) = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect() }
    private fun copyHandle(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
    private suspend fun completed(duration: Long = 3_600_000) = database.workoutDao().insertWorkout(
        Workout(name = "Completed", startTime = START, endTime = START + duration))
    private companion object {
        const val DAY = "2026-10-05"
        val START = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}

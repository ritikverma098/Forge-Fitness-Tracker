package com.forge.workout.nutrition.data

import android.app.Application
import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Workout
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WorkoutEnergyRepositoryHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private val queries = CopyOnWriteArrayList<String>()
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()
        repository = RoomNutritionRepository(database)
    }
    @After fun tearDown() { database.close() }

    @Test fun `only completed workouts with an eligible weight receive estimates and future measurements are excluded`() = runBlocking<Unit> {
        val id = workout()
        val active = database.workoutDao().insertWorkout(Workout(name = "Active", startTime = start))
        assertNull(repository.observeEnergyWorkout(active).first())
        assertEquals(EnergyWorkout(id, start, start + 3_600_000), repository.observeEnergyWorkout(id).first())
        assertNull(repository.refreshWorkoutEstimate(id, zone))
        assertNull(repository.updateWorkoutEstimate(id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, .5, 50.0, zone))
        assertNull(repository.workoutEnergy(id))
        repository.saveBodyWeight(weight("2026-10-14", 200.0))
        assertNull(repository.refreshWorkoutEstimate(id, zone))
        val earlierId = repository.saveBodyWeight(weight("2026-10-11", 70.0))
        repository.saveBodyWeight(weight("2026-10-01", 60.0))
        val estimated = repository.refreshWorkoutEstimate(id, zone)!!
        assertEquals(earlierId, estimated.bodyWeightId)
        assertEquals(70.0, estimated.bodyWeightKg, 0.0)
        assertEquals(91.875, estimated.calculatedKcal, 0.000001)
        assertNull(repository.refreshWorkoutEstimate(active, zone))
        assertFalse(repository.observeSettings().first().includeWorkoutCalories)
    }

    @Test fun `atomic classification updates preserve manual calories through timing weight and default setting changes`() = runBlocking<Unit> {
        val id = workout()
        repository.saveBodyWeight(weight("2026-10-12", 80.0))
        val changed = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, .25, 0.0, zone)!!
        assertEquals(105.0, changed.calculatedKcal, 0.000001)
        assertEquals(0.0, changed.effectiveKcal, 0.0)
        val latestWeight = repository.saveBodyWeight(weight("2026-10-13", 90.0))
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(endTime = start + 7_200_000))
        repository.saveSettings(NutritionSettingsEntity(defaultActivity = WorkoutActivityPreset.LIGHT_CALISTHENICS.id, defaultActiveFraction = 1.0))
        val refreshed = repository.refreshWorkoutEstimate(id, zone)!!
        assertEquals(236.25, refreshed.calculatedKcal, 0.000001)
        assertEquals(0.0, refreshed.manualKcal!!, 0.0)
        assertEquals(changed.activity, refreshed.activity)
        assertEquals(changed.activeFraction, refreshed.activeFraction, 0.0)
        assertEquals(latestWeight, refreshed.bodyWeightId)
        val cleared = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.BODYWEIGHT, .5, null, zone)!!
        assertNull(cleared.manualKcal)
        assertEquals(189.0, cleared.effectiveKcal, 0.000001)
    }

    @Test fun `deleting all measurements retains captured weight and an older new eligible measurement can recalculate it`() = runBlocking<Unit> {
        val id = workout()
        val weightId = repository.saveBodyWeight(weight("2026-10-12", 80.0))
        val before = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.RESISTANCE, .5, 123.0, zone)!!
        repository.deleteBodyWeight(weightId)
        val detached = repository.workoutEnergy(id)!!
        assertNull(detached.bodyWeightId)
        assertEquals(before.bodyWeightKg, detached.bodyWeightKg, 0.0)
        assertEquals(detached, repository.refreshWorkoutEstimate(id, zone))
        val replacementId = repository.saveBodyWeight(weight("2026-10-10", 60.0))
        val replacement = repository.refreshWorkoutEstimate(id, zone)!!
        assertEquals(replacementId, replacement.bodyWeightId)
        assertEquals(60.0, replacement.bodyWeightKg, 0.0)
        assertEquals(78.75, replacement.calculatedKcal, 0.000001)
        assertEquals(123.0, replacement.effectiveKcal, 0.0)
    }

    @Test fun `captured local day survives zone changes and changes only when the workout start changes`() = runBlocking<Unit> {
        val nearMidnight = Instant.parse("2026-03-08T04:30:00Z").toEpochMilli()
        val id = database.workoutDao().insertWorkout(Workout(name = "Midnight", startTime = nearMidnight, endTime = nearMidnight + 3_600_000))
        repository.saveBodyWeight(weight("2026-03-07", 80.0))
        repository.saveBodyWeight(weight("2026-03-08", 100.0))
        val first = repository.refreshWorkoutEstimate(id, ZoneId.of("America/New_York"))!!
        assertEquals("2026-03-07", first.day)
        assertEquals(80.0, first.bodyWeightKg, 0.0)
        assertEquals(first, repository.refreshWorkoutEstimate(id, zone))
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(startTime = nearMidnight + 86_400_000, endTime = nearMidnight + 90_000_000))
        val moved = repository.refreshWorkoutEstimate(id, zone)!!
        assertEquals("2026-03-09", moved.day)
        assertEquals(100.0, moved.bodyWeightKg, 0.0)
    }

    @Test fun `invalid overrides do not replace an estimate while active zero duration and deletion clear obsolete rows`() = runBlocking<Unit> {
        val id = workout()
        repository.saveBodyWeight(weight("2026-10-12", 80.0))
        val initial = repository.refreshWorkoutEstimate(id, zone)!!
        for (fraction in listOf(-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY))
            expectInvalid { repository.updateWorkoutEstimate(id, WorkoutActivityPreset.RESISTANCE, fraction, null, zone) }
        for (manual in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 1_000_000_001.0))
            expectInvalid { repository.updateWorkoutEstimate(id, WorkoutActivityPreset.RESISTANCE, .5, manual, zone) }
        assertEquals(initial, repository.workoutEnergy(id))
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(endTime = start))
        assertNull(repository.refreshWorkoutEstimate(id, zone))
        assertNull(repository.workoutEnergy(id))
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(endTime = start + 3_600_000))
        repository.refreshWorkoutEstimate(id, zone)
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(endTime = null))
        assertNull(repository.refreshWorkoutEstimate(id, zone))
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(endTime = start + 3_600_000))
        repository.refreshWorkoutEstimate(id, zone)
        database.workoutDao().deleteWorkout(id)
        assertNull(repository.workoutEnergy(id))
        assertNull(repository.updateWorkoutEstimate(id, WorkoutActivityPreset.RESISTANCE, .5, null, zone))
    }

    @Test fun `identical recalculations do not write and eligible weight lookups use the unique day index`() = runBlocking<Unit> {
        val id = workout()
        repository.saveBodyWeight(weight("2026-10-12", 80.0))
        val initial = repository.refreshWorkoutEstimate(id, zone)!!
        queries.clear()
        repeat(3) { assertEquals(initial, repository.refreshWorkoutEstimate(id, zone)) }
        assertFalse(queries.any { sql -> sql.contains("nutrition_workout_energy") &&
            (sql.trimStart().startsWith("INSERT", true) || sql.trimStart().startsWith("UPDATE", true)) })
        assertTrue(queries.any { it.contains("nutrition_body_weights WHERE day <=") })
        assertFalse(queries.any { it.contains("workout_sets", true) || it.contains("workout_exercises", true) })
        val plan = database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM nutrition_body_weights WHERE day <= '2026-10-13' ORDER BY day DESC LIMIT 1").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }
        assertTrue(plan.any { it.contains("index_nutrition_body_weights_day") })
        println("Workout energy closest-weight query: $plan; identical refreshes write no rows")
    }

    @Test fun `targeted energy and closest weight subscribers refresh from real Room without workout graph reads`() = runBlocking<Unit> {
        val id = workout()
        val energy = Channel<WorkoutEnergyEntity?>(Channel.UNLIMITED)
        val weights = Channel<BodyWeightEntity?>(Channel.UNLIMITED)
        val energyJob = launch(Dispatchers.Default) { repository.observeWorkoutEnergyById(id).collect { energy.send(it) } }
        val weightJob = launch(Dispatchers.Default) { repository.observeLatestBodyWeight("2026-10-13").collect { weights.send(it) } }
        try {
            await(energy) { it == null }; await(weights) { it == null }
            val weightId = repository.saveBodyWeight(weight("2026-10-12", 80.0))
            await(weights) { it?.id == weightId }
            repository.refreshWorkoutEstimate(id, zone)
            await(energy) { it?.calculatedKcal == 105.0 }
            repository.updateWorkoutEstimate(id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, .5, 55.0, zone)
            await(energy) { it != null && it.manualKcal == 55.0 && it.calculatedKcal == 210.0 }
            repository.deleteBodyWeight(weightId)
            await(weights) { it == null }; await(energy) { it != null && it.bodyWeightId == null }
            database.workoutDao().deleteWorkout(id)
            await(energy) { it == null }
        } finally { energyJob.cancelAndJoin(); weightJob.cancelAndJoin(); energy.cancel(); weights.cancel() }
        val sources = repository.observeEnergySources().first()
        assertTrue(sources.workouts.isEmpty())
        assertTrue(sources.estimates.isEmpty())
    }

    private suspend fun workout() = database.workoutDao().insertWorkout(Workout(name = "Workout", startTime = start, endTime = start + 3_600_000))
    private fun weight(day: String, kg: Double) = BodyWeightEntity(day = day, kg = kg, measuredAt = 1)
    private suspend fun <T> await(channel: Channel<T>, predicate: (T) -> Boolean): T = withTimeout(5000) {
        var value = channel.receive()
        while (!predicate(value)) value = channel.receive()
        value
    }
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected energy input validation") }
        catch (_: IllegalArgumentException) { }
    }
}

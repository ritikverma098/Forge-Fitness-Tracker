package com.forge.workout.nutrition.energy

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.model.Workout
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
class WorkoutEnergyCoordinatorHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private lateinit var scope: CoroutineScope
    private val coordinators = mutableListOf<WorkoutEnergyCoordinator>()
    private val queries = CopyOnWriteArrayList<String>()
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()
        repository = RoomNutritionRepository(database)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun tearDown() = runBlocking<Unit> {
        coordinators.forEach { it.stop() }
        scope.coroutineContext[Job]!!.cancelAndJoin()
        database.close()
    }

    @Test fun `idempotent startup estimates lightweight history and future finished workouts without feedback write loops`() = runBlocking<Unit> {
        repository.saveBodyWeight(weight(80.0))
        val ids = database.withTransaction {
            (0 until 20).map { database.workoutDao().insertWorkout(Workout(name = "Workout $it",
                startTime = start + it * 1000, endTime = start + 3_600_000 + it * 1000)) }
        }
        val active = database.workoutDao().insertWorkout(Workout(name = "Active", startTime = start))
        val subscriptions = AtomicInteger()
        val observed = object : NutritionRepository by repository {
            override fun observeEnergySources(): Flow<WorkoutEnergySources> {
                subscriptions.incrementAndGet()
                return repository.observeEnergySources()
            }
        }
        queries.clear()
        val service = coordinator(observed)
        service.start(); service.start()
        awaitSources { it.estimates.size == 20 }
        service.pauseForRestore()
        val writes = energyWrites()
        service.resumeAfterRestore()
        assertEquals(writes, energyWrites())
        // resume's explicit fresh read adds one source subscription, while duplicate start did not.
        assertEquals(2, subscriptions.get())
        assertNull(repository.workoutEnergy(active))
        assertTrue(queries.any { it.contains("SELECT id, startTime, endTime FROM workouts") })
        assertFalse(queries.any { it.contains("SELECT * FROM workouts", true) || it.contains("workout_sets", true) || it.contains("workout_exercises", true) })
        database.workoutDao().updateWorkout(database.workoutDao().workout(active)!!.copy(endTime = start + 3_600_000))
        awaitEstimate(active) { it?.calculatedKcal == 105.0 }
        database.workoutDao().deleteWorkout(ids.first())
        awaitSources { it.workouts.size == 20 && it.estimates.size == 20 }
        assertNull(repository.workoutEnergy(ids.first()))
        println("Energy coordinator evidence: 20 history headers; no workout set graph reads; stable refresh writes=$writes")
    }

    @Test fun `a queued stale background refresh cannot overwrite a newer manual classification update`() = runBlocking<Unit> {
        val id = workout()
        repository.saveBodyWeight(weight(80.0))
        repository.refreshWorkoutEstimate(id, zone)
        repository.saveBodyWeight(weight(90.0))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        val held = object : NutritionRepository by repository {
            override suspend fun refreshWorkoutEstimate(id: Long, zone: ZoneId): WorkoutEnergyEntity? {
                if (!first.compareAndSet(true, false)) return repository.refreshWorkoutEstimate(id, zone)
                entered.complete(Unit)
                release.await()
                return repository.refreshWorkoutEstimate(id, zone).also { finished.complete(Unit) }
            }
        }
        val service = coordinator(held)
        service.start()
        withTimeout(5000) { entered.await() }
        val explicit = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.VIGOROUS_RESISTANCE, .25, 31.0, zone)!!
        release.complete(Unit)
        withTimeout(5000) { finished.await() }
        service.pauseForRestore()
        assertEquals(explicit, repository.workoutEnergy(id))
        assertEquals(31.0, repository.workoutEnergy(id)!!.effectiveKcal, 0.0)
        assertEquals(90.0, explicit.bodyWeightKg, 0.0)
        assertEquals(118.125, explicit.calculatedKcal, 0.000001)
    }

    @Test fun `restore pause suppresses intermediate sources and resume recalculates fresh input without losing manual values`() = runBlocking<Unit> {
        val id = workout()
        repository.saveBodyWeight(weight(80.0))
        val old = repository.updateWorkoutEstimate(id, WorkoutActivityPreset.BODYWEIGHT, .5, 44.0, zone)!!
        val service = coordinator()
        service.start()
        service.pauseForRestore()
        repository.saveBodyWeight(weight(100.0))
        database.workoutDao().updateWorkout(database.workoutDao().workout(id)!!.copy(endTime = start + 7_200_000))
        assertEquals(old, repository.workoutEnergy(id))
        service.resumeAfterRestore()
        val refreshed = repository.workoutEnergy(id)!!
        assertEquals(210.0, refreshed.calculatedKcal, 0.000001)
        assertEquals(44.0, refreshed.manualKcal!!, 0.0)
        assertEquals(old.activity, refreshed.activity)
        assertEquals(old.activeFraction, refreshed.activeFraction, 0.0)
        assertEquals(100.0, refreshed.bodyWeightKg, 0.0)
    }

    @Test fun `one failing estimate reports its error while other rows and later recovery continue`() = runBlocking<Unit> {
        val failingId = workout()
        val goodId = workout()
        repository.saveBodyWeight(weight(80.0))
        val failures = Channel<Exception>(Channel.UNLIMITED)
        val failing = AtomicBoolean(true)
        val unreliable = object : NutritionRepository by repository {
            override suspend fun refreshWorkoutEstimate(id: Long, zone: ZoneId): WorkoutEnergyEntity? {
                if (id == failingId && failing.get()) throw IOException("Injected estimate storage failure")
                return repository.refreshWorkoutEstimate(id, zone)
            }
        }
        val service = coordinator(unreliable) { failures.trySend(it) }
        service.start()
        val error = withTimeout(5000) { failures.receive() }
        assertEquals("Injected estimate storage failure", error.message)
        awaitEstimate(goodId) { it?.calculatedKcal == 105.0 }
        assertNull(repository.workoutEnergy(failingId))
        failing.set(false)
        repository.saveBodyWeight(weight(90.0))
        awaitEstimate(failingId) { it?.bodyWeightKg == 90.0 }
        awaitEstimate(goodId) { it?.bodyWeightKg == 90.0 }
        service.pauseForRestore()
        failing.set(true)
        repository.saveBodyWeight(weight(100.0))
        // Errors while reconciling after a committed restore are reported, not thrown to its caller.
        service.resumeAfterRestore()
        awaitEstimate(goodId) { it?.bodyWeightKg == 100.0 }
        assertEquals(90.0, repository.workoutEnergy(failingId)!!.bodyWeightKg, 0.0)
        failures.cancel()
    }

    private fun coordinator(source: NutritionRepository = repository, onError: (Exception) -> Unit = {}) =
        WorkoutEnergyCoordinator(source, scope, { zone }, onError).also { coordinators += it }
    private suspend fun awaitEstimate(id: Long, predicate: (WorkoutEnergyEntity?) -> Boolean) =
        withTimeout(5000) { repository.observeWorkoutEnergyById(id).first(predicate) }
    private suspend fun awaitSources(predicate: (WorkoutEnergySources) -> Boolean) =
        withTimeout(10000) { repository.observeEnergySources().first(predicate) }
    private fun energyWrites() = queries.count { sql -> sql.contains("nutrition_workout_energy") &&
        (sql.trimStart().startsWith("INSERT", true) || sql.trimStart().startsWith("UPDATE", true)) }
    private suspend fun workout() = database.workoutDao().insertWorkout(Workout(name = "Workout", startTime = start, endTime = start + 3_600_000))
    private fun weight(kg: Double) = BodyWeightEntity(day = "2026-10-12", kg = kg, measuredAt = 1)
}

package com.forge.workout.nutrition.energy

import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.NutritionRepository
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Observes lightweight headers, never exercise/set history. Transactions re-read inputs before each write. */
class WorkoutEnergyCoordinator(
    private val repository: NutritionRepository,
    private val scope: CoroutineScope,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val onError: (Exception) -> Unit = {},
) {
    private val mutex = Mutex()
    private var observation: Job? = null
    private var restoring = false

    @Synchronized fun start() {
        if (observation?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                repository.observeEnergySources().collect { sources ->
                    mutex.withLock { if (!restoring) reconcile(sources) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }
        observation = job
        job.start()
    }

    @Synchronized fun stop() { observation?.cancel(); observation = null }

    suspend fun pauseForRestore() = mutex.withLock { restoring = true }

    suspend fun resumeAfterRestore() {
        try {
            mutex.withLock {
                restoring = false
                reconcile(repository.observeEnergySources().first())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    private suspend fun reconcile(sources: WorkoutEnergySources) = withContext(Dispatchers.Default) {
        val zone = zoneProvider()
        val weights = sources.bodyWeights.sortedWith(compareBy<BodyWeightEntity> { it.day }.thenBy { it.id })
        val previous = sources.estimates.associateBy { it.workoutId }
        val workouts = sources.workouts.associateBy { it.id }
        fun closest(day: String): BodyWeightEntity? {
            var lower = 0
            var upper = weights.size
            while (lower < upper) {
                val middle = (lower + upper) ushr 1
                if (weights[middle].day <= day) lower = middle + 1 else upper = middle
            }
            return weights.getOrNull(lower - 1)
        }
        for (workout in sources.workouts) {
            try {
                val old = previous[workout.id]
                val expected = WorkoutEnergyMath.estimate(workout, closest(WorkoutEnergyMath.day(workout, old, zone)), sources.settings, old, zone)
                if (expected != old) repository.refreshWorkoutEstimate(workout.id, zone)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }
        for (id in previous.keys - workouts.keys) {
            try { repository.refreshWorkoutEstimate(id, zone) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }
    }
}

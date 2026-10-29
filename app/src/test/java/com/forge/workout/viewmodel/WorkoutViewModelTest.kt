package com.forge.workout.viewmodel

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.RoutineDetail
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.utils.WorkoutValidation
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel(repository: FakeRepository, clock: () -> Long = System::currentTimeMillis) =
        WorkoutViewModel(repository, clock = clock, errorFormatter = { it.message.orEmpty() })

    @Test fun `mutation reads never subscribe to complete workout history`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository) { 10_000L }
        viewModel.setValue(20, SetField.WEIGHT, 82.5)
        viewModel.setValue(20, SetField.REPS, 12.0)
        viewModel.setType(20, SetType.DROP_SET)
        viewModel.completeSet(20, true, 1, 90, editing = false)
        viewModel.adjustRest(1, 15)
        viewModel.toggleMuscleGroup(1, MuscleGroup.TRICEPS)
        viewModel.removeExercise(10)
        viewModel.setValue(20, SetField.REPS, 1.0)
        viewModel.toggleMuscleGroup(999, MuscleGroup.BICEPS)

        assertTrue(viewModel.flush())
        assertEquals(0, repository.graphReads)
        assertTrue(repository.sets.isEmpty())
        assertEquals(115_000L, repository.detail.workout.restEndTime)
        assertEquals(listOf(MuscleGroup.TRICEPS), repository.detail.workout.muscleGroups)
        assertNull(viewModel.error.value)
    }

    @Test fun `flush waits for jobs queued before dispatch and independent fields survive`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        viewModel.setValue(20, SetField.REPS, 12.0)
        viewModel.setValue(20, SetField.WEIGHT, 82.5)

        assertTrue(viewModel.flush())
        assertEquals(82.5, repository.sets.single().weight, 0.00001)
        assertEquals(12, repository.sets.single().reps)
        assertEquals(3, repository.savedSets.size)
        assertEquals(80.0, repository.savedSets[1].weight, 0.00001)
    }

    @Test fun `rapid muscle group toggles observe queued changes and preserve multiple selection`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.toggleMuscleGroup(1, MuscleGroup.BICEPS)
        viewModel.toggleMuscleGroup(1, MuscleGroup.TRICEPS)
        viewModel.toggleMuscleGroup(1, MuscleGroup.BICEPS)

        assertTrue(viewModel.flush())
        assertEquals(listOf(MuscleGroup.TRICEPS), repository.detail.workout.muscleGroups)
    }

    @Test fun `pounds convert to canonical kilograms without changing repetitions`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        val pounds = WeightUnit.LB.fromKg(100.0)
        viewModel.setValue(20, SetField.WEIGHT, WeightUnit.LB.toKg(pounds))
        viewModel.setValue(20, SetField.REPS, 10.0)

        assertTrue(viewModel.flush())
        assertEquals(100.0, repository.sets.single().weight, 0.000001)
        assertEquals(10, repository.sets.single().reps)
    }

    @Test fun `adding next set waits for previous numeric changes`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.WEIGHT, 75.0)
        viewModel.setValue(20, SetField.REPS, 11.0)
        viewModel.setType(20, SetType.FAILURE)
        viewModel.addSet(10)

        assertTrue(viewModel.flush())
        assertEquals(2, repository.sets.size)
        val next = repository.sets.last()
        assertEquals(75.0, next.weight, 0.000001)
        assertEquals(11, next.reps)
        assertFalse(next.completed)
        assertEquals(SetType.FAILURE, repository.sets.first().setType)
        assertEquals(SetType.WORKING, next.setType)
    }

    @Test fun `queued type numeric and completion edits preserve fresh values and the whole set`() = runTest {
        val repository = FakeRepository()
        val original = repository.sets.single()
        val viewModel = viewModel(repository)
        viewModel.setType(20, SetType.WARM_UP)
        viewModel.setValue(20, SetField.WEIGHT, 75.0)
        viewModel.setValue(20, SetField.REPS, 12.0)
        viewModel.setType(20, SetType.DROP_SET)
        viewModel.completeSet(20, true, 1, 0, editing = false)
        viewModel.setType(20, SetType.FAILURE)

        assertTrue(viewModel.flush())
        assertEquals(original.copy(weight = 75.0, reps = 12, completed = true, setType = SetType.FAILURE),
            repository.sets.single())
        assertEquals(6, repository.savedSets.size)
        assertEquals(SetType.WARM_UP, repository.savedSets[1].setType)
        assertEquals(SetType.WARM_UP, repository.savedSets[2].setType)
        assertEquals(SetType.DROP_SET, repository.savedSets[4].setType)
        assertTrue(repository.savedSets[4].completed)
        assertTrue(repository.savedRestDeadlines.isEmpty())
    }

    @Test fun `type change queued after deletion cannot resurrect a deleted set`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setType(20, SetType.WARM_UP)
        viewModel.deleteSet(20)
        viewModel.setType(20, SetType.DROP_SET)

        assertTrue(viewModel.flush())
        assertTrue(repository.sets.isEmpty())
        assertEquals(1, repository.savedSets.size)
        assertEquals(SetType.WARM_UP, repository.savedSets.single().setType)
        assertNull(viewModel.error.value)
    }

    @Test fun `completion observes new values and active completion starts rest afterwards`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository) { 10_000L }
        viewModel.setValue(20, SetField.REPS, 15.0)
        viewModel.completeSet(20, true, 1, 90, editing = false)

        assertTrue(viewModel.flush())
        assertEquals(15, repository.sets.single().reps)
        assertTrue(repository.sets.single().completed)
        assertEquals(100_000L, repository.detail.workout.restEndTime)
        assertEquals(listOf("set", "set", "rest"), repository.events)
    }

    @Test fun `queued rest adjustments read fresh deadlines and accumulate each tap`() = runTest {
        val now = 10_000L
        val repository = FakeRepository(restEndTime = now + 90_000L)
        val viewModel = viewModel(repository) { now }

        viewModel.adjustRest(1, 15)
        viewModel.adjustRest(1, 15)
        viewModel.adjustRest(1, -15)

        assertTrue(viewModel.flush())
        assertEquals(now + 105_000L, repository.detail.workout.restEndTime)
        assertEquals(listOf(now + 105_000L, now + 120_000L, now + 105_000L), repository.savedRestDeadlines)
    }

    @Test fun `subtracting rest clamps at now and subsequent adjustment cannot revive expired timer`() = runTest {
        val now = 10_000L
        val repository = FakeRepository(restEndTime = now + 5_000L)
        val viewModel = viewModel(repository) { now }

        viewModel.adjustRest(1, -15)
        viewModel.adjustRest(1, 15)

        assertTrue(viewModel.flush())
        assertEquals(now, repository.detail.workout.restEndTime)
        assertEquals(listOf(now), repository.savedRestDeadlines)
    }

    @Test fun `skip followed by queued adjustment leaves rest stopped`() = runTest {
        val now = 10_000L
        val repository = FakeRepository(restEndTime = now + 90_000L)
        val viewModel = viewModel(repository) { now }

        viewModel.rest(1, null)
        viewModel.adjustRest(1, 15)
        viewModel.adjustRest(1, -15)

        assertTrue(viewModel.flush())
        assertNull(repository.detail.workout.restEndTime)
        assertEquals(listOf<Long?>(null), repository.savedRestDeadlines)
    }

    @Test fun `duplicate expiry clears matching deadline and notifies exactly once`() = runTest {
        val now = 10_000L
        val repository = FakeRepository(restEndTime = now)
        val viewModel = viewModel(repository) { now }
        var expiredCallbacks = 0

        viewModel.expireRest(1, now) { expiredCallbacks++ }
        viewModel.expireRest(1, now) { expiredCallbacks++ }

        assertTrue(viewModel.flush())
        assertNull(repository.detail.workout.restEndTime)
        assertEquals(1, expiredCallbacks)
        assertEquals(listOf<Long?>(null), repository.savedRestDeadlines)
    }

    @Test fun `stale expiry cannot clear an extended deadline and future deadline cannot expire early`() = runTest {
        var now = 10_000L
        val originalDeadline = now + 20_000L
        val repository = FakeRepository(restEndTime = originalDeadline)
        val viewModel = viewModel(repository) { now }
        var expiredCallbacks = 0

        viewModel.adjustRest(1, 15)
        assertTrue(viewModel.flush())
        val extendedDeadline = originalDeadline + 15_000L
        now = originalDeadline
        viewModel.expireRest(1, originalDeadline) { expiredCallbacks++ }
        viewModel.expireRest(1, extendedDeadline) { expiredCallbacks++ }
        assertTrue(viewModel.flush())
        assertEquals(extendedDeadline, repository.detail.workout.restEndTime)
        assertEquals(0, expiredCallbacks)
        assertEquals(listOf(extendedDeadline), repository.savedRestDeadlines)

        now = extendedDeadline
        viewModel.expireRest(1, extendedDeadline) { expiredCallbacks++ }
        assertTrue(viewModel.flush())
        assertNull(repository.detail.workout.restEndTime)
        assertEquals(1, expiredCallbacks)
        assertEquals(listOf(extendedDeadline, null), repository.savedRestDeadlines)
    }

    @Test fun `historical workouts missing workouts and absent timers ignore rest adjustment and expiry`() = runTest {
        val now = 10_000L
        val repositories = listOf(
            FakeRepository(historical = true, restEndTime = now),
            FakeRepository(),
        )
        repositories.forEach { repository ->
            val originalDeadline = repository.detail.workout.restEndTime
            val viewModel = viewModel(repository) { now }
            var expiredCallbacks = 0

            viewModel.adjustRest(1, 15)
            viewModel.adjustRest(1, -15)
            viewModel.expireRest(1, now) { expiredCallbacks++ }
            viewModel.adjustRest(999, 15)
            viewModel.expireRest(999, now) { expiredCallbacks++ }

            assertTrue(viewModel.flush())
            assertEquals(originalDeadline, repository.detail.workout.restEndTime)
            assertEquals(0, expiredCallbacks)
            assertTrue(repository.savedRestDeadlines.isEmpty())
        }
    }

    @Test fun `editing historical set never starts a rest timer`() = runTest {
        val repository = FakeRepository(historical = true)
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.REPS, 9.0)
        viewModel.completeSet(20, true, 1, 90, editing = true)

        assertTrue(viewModel.flush())
        assertTrue(repository.sets.single().completed)
        assertNull(repository.detail.workout.restEndTime)
        assertFalse(repository.events.contains("rest"))
    }

    @Test fun `delete after queued edit removes set and stale later edit cannot resurrect it`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.inputValidity(1, "set_20_weight", false)
        viewModel.inputValidity(2, "set_100_REPS", false)
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        viewModel.deleteSet(20)
        viewModel.setValue(20, SetField.REPS, 12.0)

        assertTrue(viewModel.flush())
        assertTrue(repository.sets.isEmpty())
        assertNull(viewModel.invalidInputs.value[1])
        assertEquals(setOf("set_100_REPS"), viewModel.invalidInputs.value[2])
    }

    @Test fun `removing exercise clears only its draft validation keys`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.inputValidity(1, "set_20_weight", false)
        viewModel.inputValidity(1, "notes_10", false)
        viewModel.inputValidity(1, "name", false)
        viewModel.removeExercise(10)

        assertTrue(viewModel.flush())
        assertTrue(repository.detail.exercises.isEmpty())
        assertEquals(setOf("name"), viewModel.invalidInputs.value[1])
        viewModel.inputValidity(1, "name", true)
        assertTrue(viewModel.invalidInputs.value.isEmpty())
    }

    @Test fun `failed queued save blocks flush and a corrected retry can succeed`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failNextSave = true
        viewModel.setValue(20, SetField.WEIGHT, 80.0)

        assertFalse(viewModel.flush())
        assertEquals(60.0, repository.sets.single().weight, 0.000001)
        assertEquals("Storage temporarily unavailable", viewModel.error.value)

        viewModel.clearError()
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        assertTrue(viewModel.flush())
        assertEquals(80.0, repository.sets.single().weight, 0.000001)
        assertNull(viewModel.error.value)
    }

    @Test fun `fractional reps and excessive weight are rejected without truncating stored values`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.REPS, 10.9)
        viewModel.setValue(20, SetField.WEIGHT, 10000.1)

        assertFalse(viewModel.flush())
        assertEquals(8, repository.sets.single().reps)
        assertEquals(60.0, repository.sets.single().weight, 0.000001)
        assertTrue(repository.savedSets.isEmpty())
    }

    @Test fun `persistent failure blocks every flush and unrelated success until explicit retry succeeds`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failSaves = true
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        assertFalse(viewModel.flush())
        val first = viewModel.errorNotice.value!!
        val resolution = async(start = CoroutineStart.UNDISPATCHED) { viewModel.awaitResolution(first) }
        viewModel.clearError()
        assertFalse(viewModel.flush())
        assertFalse(resolution.isCompleted)
        assertEquals(1, repository.attempts["set"])
        assertTrue(viewModel.errorNotice.value!!.id > first.id)

        repository.failSaves = false
        viewModel.setValue(20, SetField.REPS, 12.0)
        viewModel.setType(20, SetType.DROP_SET)
        assertFalse(viewModel.flush())
        assertFalse(resolution.isCompleted)
        assertEquals(60.0, repository.sets.single().weight, 0.000001)
        assertEquals(12, repository.sets.single().reps)

        repository.failSaves = true
        viewModel.retryFailedChanges()
        advanceUntilIdle()
        val repeated = viewModel.errorNotice.value!!
        viewModel.clearError(first)
        assertSame(repeated, viewModel.errorNotice.value)
        viewModel.clearError(repeated.copy())
        assertSame(repeated, viewModel.errorNotice.value)
        assertEquals(first.message, repeated.message)
        assertTrue(repeated.id > first.id)
        assertFalse(viewModel.flush())

        repository.failSaves = false
        viewModel.retryFailedChanges()
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        resolution.await()
        assertEquals(80.0, repository.sets.single().weight, 0.000001)
        assertEquals(12, repository.sets.single().reps)
        assertEquals(SetType.DROP_SET, repository.sets.single().setType)
        assertEquals(3, repository.savedSets.size)
        assertEquals(5, repository.attempts["set"])
        assertNull(viewModel.error.value)
    }

    @Test fun `corrected same field resolves its failure even before the first flush`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failNextSave = true
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        viewModel.setValue(20, SetField.WEIGHT, 82.5)
        assertTrue(viewModel.flush())
        assertEquals(82.5, repository.sets.single().weight, 0.000001)
        assertEquals(1, repository.savedSets.size)
        assertNull(viewModel.error.value)
    }

    @Test fun `different invalid fields stay unresolved until both have successful corrections`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.WEIGHT, -1.0)
        viewModel.setValue(20, SetField.REPS, 10.9)
        assertFalse(viewModel.flush())
        viewModel.clearError()
        viewModel.clearAllValidity()
        viewModel.clearWorkoutValidity(1)
        assertFalse(viewModel.flush())
        viewModel.setValue(20, SetField.WEIGHT, 82.5)
        assertFalse(viewModel.flush())
        viewModel.setValue(20, SetField.REPS, 12.0)
        assertTrue(viewModel.flush())
        assertEquals(82.5, repository.sets.single().weight, 0.000001)
        assertEquals(12, repository.sets.single().reps)
    }

    @Test fun `retrying multiple failed fields merges fresh values without replaying successful edits`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failures["set"] = 2
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        viewModel.setValue(20, SetField.REPS, 12.0)
        viewModel.setType(20, SetType.FAILURE)
        assertFalse(viewModel.flush())
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(80.0, repository.sets.single().weight, 0.000001)
        assertEquals(12, repository.sets.single().reps)
        assertEquals(SetType.FAILURE, repository.sets.single().setType)
        assertEquals(listOf(60.0, 80.0, 80.0), repository.savedSets.map { it.weight })
        assertEquals(5, repository.attempts["set"])
    }

    @Test fun `newer queued field intent supersedes old retry in either queue order`() = runTest {
        listOf(true, false).forEach { retryFirst ->
            val repository = FakeRepository()
            val viewModel = viewModel(repository)
            repository.failNextSave = true
            viewModel.setValue(20, SetField.WEIGHT, 80.0)
            assertFalse(viewModel.flush())
            if (retryFirst) viewModel.retryFailedChanges()
            viewModel.setValue(20, SetField.WEIGHT, 90.0)
            if (!retryFirst) viewModel.retryFailedChanges()
            assertTrue(viewModel.flush())
            assertEquals(listOf(90.0), repository.savedSets.map { it.weight })
            assertEquals(2, repository.attempts["set"])
        }
    }

    @Test fun `distinct failed additions retry once each and successful additions never replay`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failures["addSet"] = 2
        viewModel.addSet(10)
        viewModel.addSet(10)
        viewModel.addSet(10)
        assertFalse(viewModel.flush())
        assertEquals(2, repository.sets.size)
        assertFalse(viewModel.flush())
        assertEquals(3, repository.attempts["addSet"])
        viewModel.retryFailedChanges()
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(4, repository.sets.size)
        assertEquals(5, repository.attempts["addSet"])
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(4, repository.sets.size)
    }

    @Test fun `failed relative adjustment retries against current timer without duplicating successful tap`() = runTest {
        val repository = FakeRepository(restEndTime = 100_000L)
        val viewModel = viewModel(repository) { 10_000L }
        repository.failures["rest"] = 1
        viewModel.adjustRest(1, 15)
        viewModel.adjustRest(1, 15)
        assertFalse(viewModel.flush())
        assertEquals(115_000L, repository.detail.workout.restEndTime)
        viewModel.retryFailedChanges()
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(130_000L, repository.detail.workout.restEndTime)
        assertEquals(listOf(115_000L, 130_000L), repository.savedRestDeadlines)
        assertEquals(3, repository.attempts["rest"])
    }

    @Test fun `failed adjustment cannot alter a replacement timer or a new completion timer`() = runTest {
        listOf(true, false).forEach { completeAnotherSet ->
            val repository = FakeRepository(restEndTime = 100_000L)
            val viewModel = viewModel(repository) { 10_000L }
            repository.failures["rest"] = 1
            viewModel.adjustRest(1, 15)
            assertFalse(viewModel.flush())
            if (completeAnotherSet) viewModel.completeSet(20, true, 1, 60, editing = false)
            else viewModel.rest(1, 200_000L)
            viewModel.retryFailedChanges()
            assertTrue(viewModel.flush())
            val expected = if (completeAnotherSet) 70_000L else 200_000L
            assertEquals(expected, repository.detail.workout.restEndTime)
            assertEquals(listOf(expected), repository.savedRestDeadlines)
            assertEquals(2, repository.attempts["rest"])
        }
    }

    @Test fun `successful same exercise selection resolves failed membership while other exercises stay independent`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failures["addExercise"] = 1
        viewModel.addExercise(1, 101)
        assertFalse(viewModel.flush())
        viewModel.addExercise(1, 102)
        assertFalse(viewModel.flush())
        viewModel.addExercise(1, 101)
        assertTrue(viewModel.flush())
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(setOf(100L, 101L, 102L), repository.detail.exercises.map { it.exercise.id }.toSet())
        assertEquals(3, repository.attempts["addExercise"])
    }

    @Test fun `an existing exercise selection succeeds without creating a permanent retry failure`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.addExercise(1, 100)
        viewModel.addExercise(1, 100)
        assertTrue(viewModel.flush())
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(1, repository.detail.exercises.size)
        assertNull(repository.attempts["addExercise"])
        assertNull(viewModel.error.value)
        assertEquals(0, repository.graphReads)
    }

    @Test fun `failed reconciliation reads preserve the original failed addition until it can retry`() = runTest {
        val repository = FakeRepository()
        var reads = 0
        val interrupted = object : WorkoutRepository by repository {
            override suspend fun hasWorkoutExercise(workoutId: Long, exerciseId: Long): Boolean {
                if (++reads == 2) throw IOException("Membership read failed")
                return repository.hasWorkoutExercise(workoutId, exerciseId)
            }
        }
        val viewModel = WorkoutViewModel(interrupted, { it.message.orEmpty() })
        repository.failures["addExercise"] = 1
        viewModel.addExercise(1, 101)
        assertFalse(viewModel.flush())
        assertEquals("Storage temporarily unavailable", viewModel.error.value)
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(setOf(100L, 101L), repository.detail.exercises.map { it.exercise.id }.toSet())
        assertEquals(2, repository.attempts["addExercise"])
        assertEquals(0, repository.graphReads)
    }

    @Test fun `partial completion retry only writes original timer deadline and preserves later field edits`() = runTest {
        var now = 10_000L
        val repository = FakeRepository()
        val viewModel = viewModel(repository) { now }
        repository.failures["rest"] = 1
        viewModel.completeSet(20, true, 1, 90, editing = false)
        assertFalse(viewModel.flush())
        assertTrue(repository.sets.single().completed)
        assertEquals(1, repository.savedSets.size)
        now = 20_000L
        viewModel.setValue(20, SetField.WEIGHT, 82.5)
        viewModel.retryFailedChanges()
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(82.5, repository.sets.single().weight, 0.000001)
        assertEquals(2, repository.savedSets.size)
        assertEquals(listOf(100_000L), repository.savedRestDeadlines)
        assertEquals(2, repository.attempts["rest"])
    }

    @Test fun `newer completion or timer skip defeats partial completion retry`() = runTest {
        listOf(true, false).forEach { changeCompletion ->
            val repository = FakeRepository()
            val viewModel = viewModel(repository) { 10_000L }
            repository.failures["rest"] = 1
            viewModel.completeSet(20, true, 1, 90, editing = false)
            assertFalse(viewModel.flush())
            viewModel.retryFailedChanges()
            if (changeCompletion) viewModel.completeSet(20, false, 1, 90, editing = false)
            else viewModel.rest(1, null)
            assertTrue(viewModel.flush())
            assertNull(repository.detail.workout.restEndTime)
            assertEquals(!changeCompletion, repository.sets.single().completed)
            assertEquals(if (changeCompletion) 2 else 1, repository.savedSets.size)
            assertEquals(if (changeCompletion) emptyList<Long?>() else listOf<Long?>(null), repository.savedRestDeadlines)
        }
    }

    @Test fun `deleting one set clears only its failures and failed delete remains retryable`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.addSet(10)
        assertTrue(viewModel.flush())
        viewModel.setValue(20, SetField.WEIGHT, -1.0)
        viewModel.setValue(21, SetField.REPS, -1.0)
        repository.failures["deleteSet"] = 1
        viewModel.deleteSet(20)
        assertFalse(viewModel.flush())
        assertEquals(2, repository.sets.size)
        viewModel.retryFailedChanges()
        assertFalse(viewModel.flush())
        assertEquals(listOf(21L), repository.sets.map { it.id })
        viewModel.setValue(21, SetField.REPS, 12.0)
        assertTrue(viewModel.flush())
        assertEquals(2, repository.attempts["deleteSet"])
    }

    @Test fun `successful exercise removal clears child failures without clearing workout failure`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.WEIGHT, -1.0)
        viewModel.exerciseNotes(10, "x".repeat(5001))
        viewModel.rename(1, "", "")
        repository.failures["addSet"] = 1
        repository.failures["removeExercise"] = 1
        viewModel.addSet(10)
        viewModel.removeExercise(10)
        assertFalse(viewModel.flush())
        assertEquals(1, repository.detail.exercises.size)
        viewModel.removeExercise(10)
        assertFalse(viewModel.flush())
        assertTrue(repository.detail.exercises.isEmpty())
        viewModel.rename(1, "Corrected", "")
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertEquals(1, repository.attempts["addSet"])
        assertEquals(2, repository.attempts["removeExercise"])
    }

    @Test fun `discard cleanup waits for confirmed deletion and does not clear unrelated workout failures`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.WEIGHT, -1.0)
        viewModel.rename(999, "", "")
        viewModel.forgetDeletedWorkout(1, setOf(10), setOf(20))
        assertFalse(viewModel.flush())
        assertEquals(1, repository.sets.size)
        repository.deleteWorkout(1)
        viewModel.forgetDeletedWorkout(1, setOf(10), setOf(20))
        assertFalse(viewModel.flush())
        assertTrue(repository.sets.isEmpty())
        viewModel.resetAfterRestore()
        assertTrue(viewModel.flush())
        assertNull(viewModel.error.value)
    }

    @Test fun `transient deletion confirmation failure remains retryable with all child IDs`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        viewModel.setValue(20, SetField.WEIGHT, -1.0)
        viewModel.exerciseNotes(10, "x".repeat(5001))
        viewModel.rename(1, "", "")
        assertFalse(viewModel.flush())
        repository.deleteWorkout(1)
        repository.failures["workoutRead"] = 1
        viewModel.forgetDeletedWorkout(1, setOf(10), setOf(20))
        assertFalse(viewModel.flush())
        viewModel.retryFailedChanges()
        assertTrue(viewModel.flush())
        assertNull(viewModel.error.value)
        assertTrue(repository.sets.isEmpty())
    }

    @Test fun `restore reset invalidates pending retries and permits new edits with reused IDs`() = runTest {
        val repository = FakeRepository()
        val viewModel = viewModel(repository)
        repository.failNextSave = true
        viewModel.setValue(20, SetField.WEIGHT, 80.0)
        assertFalse(viewModel.flush())
        viewModel.inputValidity(1, "set_20_weight", false)
        viewModel.retryFailedChanges()
        viewModel.resetAfterRestore()
        viewModel.setValue(20, SetField.WEIGHT, 90.0)
        assertTrue(viewModel.flush())
        assertEquals(listOf(90.0), repository.savedSets.map { it.weight })
        assertTrue(viewModel.invalidInputs.value.isEmpty())
        assertNull(viewModel.errorNotice.value)
    }

    private class FakeRepository(historical: Boolean = false, restEndTime: Long? = null) : WorkoutRepository {
        private val exercise = Exercise(id = 100, name = "Bench Press", primaryMuscleGroup = MuscleGroup.CHEST)
        private val state = MutableStateFlow(listOf(WorkoutDetail(
            workout = Workout(id = 1, name = "Push", startTime = 1000, endTime = if (historical) 2000 else null,
                restEndTime = restEndTime),
            exercises = listOf(WorkoutExerciseDetail(
                entry = WorkoutExercise(id = 10, workoutId = 1, exerciseId = 100, orderIndex = 0),
                exercise = exercise,
                sets = listOf(WorkoutSet(id = 20, workoutExerciseId = 10, setNumber = 1, weight = 60.0, reps = 8)),
            )),
        )))
        val detail: WorkoutDetail get() = state.value.single()
        val sets: List<WorkoutSet> get() = state.value.flatMap { it.exercises }.flatMap { it.sets }
        val savedSets = mutableListOf<WorkoutSet>()
        val savedRestDeadlines = mutableListOf<Long?>()
        val events = mutableListOf<String>()
        var failNextSave = false
        var failSaves = false
        val failures = mutableMapOf<String, Int>()
        val attempts = mutableMapOf<String, Int>()
        var graphReads = 0

        private fun before(operation: String) {
            attempts[operation] = attempts.getOrDefault(operation, 0) + 1
            if (failures.getOrDefault(operation, 0) > 0) {
                failures[operation] = failures.getValue(operation) - 1
                throw IOException("Storage temporarily unavailable")
            }
        }

        override fun observeExercises(): Flow<List<Exercise>> = flowOf(listOf(exercise))
        override fun observeExerciseUsage(): Flow<List<ExerciseUsage>> = flowOf(emptyList())
        override fun observeWorkouts(): Flow<List<WorkoutDetail>> { graphReads++; return state }
        override suspend fun workout(id: Long): Workout? {
            before("workoutRead")
            return state.value.firstOrNull { it.workout.id == id }?.workout
        }
        override suspend fun workoutSet(id: Long): WorkoutSet? = sets.firstOrNull { it.id == id }
        override suspend fun workoutExerciseSets(workoutExerciseId: Long): List<WorkoutSet> =
            detail.exercises.firstOrNull { it.entry.id == workoutExerciseId }?.sets.orEmpty()
        override suspend fun hasWorkoutExercise(workoutId: Long, exerciseId: Long): Boolean {
            before("membershipRead")
            return state.value.firstOrNull { it.workout.id == workoutId }?.exercises
                ?.any { it.exercise.id == exerciseId } == true
        }
        override fun observeRoutines(): Flow<List<RoutineDetail>> = flowOf(emptyList())
        override suspend fun ensureSeeded() = Unit

        override suspend fun updateSet(set: WorkoutSet) {
            delay(1)
            before("set")
            if (failNextSave || failSaves) {
                failNextSave = false
                throw IOException("Storage temporarily unavailable")
            }
            WorkoutValidation.validateSet(set, exercise.exerciseType)
            check(sets.any { it.id == set.id })
            state.value = listOf(detail.copy(exercises = detail.exercises.map { entry ->
                entry.copy(sets = entry.sets.map { if (it.id == set.id) set else it })
            }))
            savedSets += set
            events += "set"
        }

        override suspend fun addSet(workoutExerciseId: Long) {
            before("addSet")
            val entry = detail.exercises.single { it.entry.id == workoutExerciseId }
            val next = entry.sets.last().copy(id = (sets.maxOfOrNull { it.id } ?: 20L) + 1, setNumber = entry.sets.size + 1, completed = false,
                setType = SetType.WORKING)
            state.value = listOf(detail.copy(exercises = detail.exercises.map { if (it.entry.id == workoutExerciseId) it.copy(sets = it.sets + next) else it }))
        }

        override suspend fun deleteSet(id: Long) {
            before("deleteSet")
            state.value = listOf(detail.copy(exercises = detail.exercises.map { it.copy(sets = it.sets.filterNot { set -> set.id == id }) }))
        }

        override suspend fun removeWorkoutExercise(id: Long) {
            before("removeExercise")
            state.value = listOf(detail.copy(exercises = detail.exercises.filterNot { it.entry.id == id }))
        }

        override suspend fun setRestEndTime(workoutId: Long, endTime: Long?) {
            delay(1)
            before("rest")
            check(workoutId == detail.workout.id)
            state.value = listOf(detail.copy(workout = detail.workout.copy(restEndTime = endTime)))
            savedRestDeadlines += endTime
            events += "rest"
        }

        override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long): Boolean = synchronized(state) {
            val current = state.value.firstOrNull { it.workout.id == workoutId } ?: return@synchronized false
            if (current.workout.endTime != null || current.workout.restEndTime != expectedDeadline || expectedDeadline > now) {
                return@synchronized false
            }
            state.value = state.value.map { if (it.workout.id == workoutId) it.copy(workout = it.workout.copy(restEndTime = null)) else it }
            savedRestDeadlines += null
            events += "rest"
            true
        }

        override suspend fun updateWorkout(id: Long, name: String, notes: String) {
            before("identity")
            WorkoutValidation.validateName(name)
            require(notes.length <= 5000)
            check(id == detail.workout.id)
            state.value = listOf(detail.copy(workout = detail.workout.copy(name = name, notes = notes)))
        }
        override suspend fun updateMuscleGroups(id: Long, groups: Set<MuscleGroup>) {
            delay(1)
            check(id == detail.workout.id)
            state.value = listOf(detail.copy(workout = detail.workout.copy(muscleGroups = groups.sortedBy { it.ordinal })))
        }
        override suspend fun saveExercise(exercise: Exercise): Long = error("Unused operation")
        override suspend fun deleteExercise(id: Long) { error("Unused operation") }
        override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long = error("Unused operation")
        override suspend fun finishWorkout(id: Long) { error("Unused operation") }
        override suspend fun deleteWorkout(id: Long) { before("deleteWorkout"); state.value = state.value.filterNot { it.workout.id == id } }
        override suspend fun addExercise(workoutId: Long, exerciseId: Long) {
            before("addExercise")
            check(detail.workout.id == workoutId)
            require(detail.exercises.none { it.exercise.id == exerciseId }) { "This exercise is already in your workout." }
            val entry = WorkoutExercise(id = detail.exercises.maxOf { it.entry.id } + 1, workoutId = workoutId,
                exerciseId = exerciseId, orderIndex = detail.exercises.size)
            val set = WorkoutSet(id = (sets.maxOfOrNull { it.id } ?: 20L) + 1, workoutExerciseId = entry.id, setNumber = 1)
            val added = WorkoutExerciseDetail(entry, exercise.copy(id = exerciseId), listOf(set))
            state.value = listOf(detail.copy(exercises = detail.exercises + added))
        }
        override suspend fun moveWorkoutExercise(id: Long, delta: Int) { error("Unused operation") }
        override suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String) {
            before("notes")
            require(notes.length <= 5000)
            state.value = listOf(detail.copy(exercises = detail.exercises.map { if (it.entry.id == workoutExerciseId) it.copy(entry = it.entry.copy(notes = notes)) else it }))
        }
        override suspend fun saveRoutine(id: Long?, name: String): Long = error("Unused operation")
        override suspend fun addRoutineExercise(routineId: Long, exerciseId: Long) { error("Unused operation") }
        override suspend fun removeRoutineExercise(id: Long) { error("Unused operation") }
        override suspend fun moveRoutineExercise(id: Long, delta: Int) { error("Unused operation") }
        override suspend fun deleteRoutine(id: Long) { error("Unused operation") }
    }
}

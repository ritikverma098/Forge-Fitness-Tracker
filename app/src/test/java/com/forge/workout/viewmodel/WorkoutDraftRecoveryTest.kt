package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.forge.workout.model.*
import com.forge.workout.repository.WorkoutRepository
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutDraftRecoveryTest {
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun `a failed accepted weight recovers before flush without composing the row`() = runTest {
        val repository = DraftRepository().apply { failures = 1 }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        assertFalse(original.flush())
        val recoveredState = copyState(state)
        stores.first().clear()

        val recovered = model(repository, recoveredState)
        assertEquals(80.0, recovered.numericDrafts(20).value.getValue(SetField.WEIGHT), 0.0)
        assertTrue(recovered.flush())
        assertEquals(80.0, repository.rows.getValue(20).weight, 0.0)
        assertTrue(WorkoutDraftJournal(recoveredState).pending().isEmpty())
    }

    @Test fun `a persistent recovered failure blocks repeated flush until Retry succeeds`() = runTest {
        val repository = DraftRepository().apply { rejectedIds += 20 }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        assertFalse(original.flush())
        val next = copyState(state)
        stores.first().clear()
        val recovered = model(repository, next)
        assertFalse(recovered.flush())
        val attempts = repository.attempts.size
        assertFalse(recovered.flush())
        assertEquals(attempts, repository.attempts.size)
        assertEquals(60.0, repository.rows.getValue(20).weight, 0.0)
        repository.rejectedIds.clear()
        recovered.retryFailedChanges()
        assertTrue(recovered.flush())
        assertEquals(80.0, repository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `canonical tiny weights and independent quantity assignments survive recovery exactly`() = runTest {
        val repository = DraftRepository().apply { rejectedIds += 20 }
        val state = SavedStateHandle()
        val original = model(repository, state)
        val exact = com.forge.workout.preferences.WeightUnit.LB.toKg(0.01)
        original.setValue(20, SetField.WEIGHT, exact)
        original.setValue(20, SetField.REPS, 12.0)
        original.setValue(20, SetField.DURATION, 90.0)
        original.setValue(20, SetField.DISTANCE, 2.75)
        assertFalse(original.flush())
        val next = copyState(state)
        stores.first().clear()
        repository.rejectedIds.clear()
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        val row = repository.rows.getValue(20)
        assertEquals(exact, row.weight, 0.0)
        assertEquals(12, row.reps)
        assertEquals(90, row.duration)
        assertEquals(2.75, row.distance, 0.0)
    }

    @Test fun `a committed assignment whose acknowledgement was not saved is not written again`() = runTest {
        val repository = DraftRepository().apply { afterWrite = CompletableDeferred() }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        advanceUntilIdle()
        assertEquals(80.0, repository.rows.getValue(20).weight, 0.0)
        assertEquals(1, WorkoutDraftJournal(state).pending().size)
        val next = copyState(state)
        stores.first().clear()
        repository.afterWrite = null
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        assertEquals(1, repository.attempts.size)
        assertTrue(WorkoutDraftJournal(next).pending().isEmpty())
    }

    @Test fun `recovery never silently overwrites a newer different stored value`() = runTest {
        val repository = DraftRepository().apply { failures = 1 }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        assertFalse(original.flush())
        val next = copyState(state)
        stores.first().clear()
        repository.rows[20] = repository.rows.getValue(20).copy(weight = 90.0)
        val recovered = model(repository, next)
        assertFalse(recovered.flush())
        assertEquals(90.0, repository.rows.getValue(20).weight, 0.0)
        assertEquals("Recovered workout changes need review before retrying.", recovered.error.value)
        recovered.retryFailedChanges()
        assertTrue(recovered.flush())
        assertEquals(80.0, repository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `an assignment captured before its first read requires review rather than assuming a base`() = runTest {
        val repository = DraftRepository()
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        val next = copyState(state)
        assertNull(WorkoutDraftJournal(next).pending().single().base)
        stores.first().clear()
        val recovered = model(repository, next)
        assertFalse(recovered.flush())
        assertTrue(repository.attempts.isEmpty())
        recovered.retryFailedChanges()
        assertTrue(recovered.flush())
        assertEquals(80.0, repository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `an older acknowledgement cannot erase a newer accepted assignment`() = runTest {
        val repository = DraftRepository().apply { afterWrite = CompletableDeferred() }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        advanceUntilIdle()
        original.setValue(20, SetField.WEIGHT, 90.0)
        repository.rejectWeight = 90.0
        repository.afterWrite!!.complete(Unit)
        repository.afterWrite = null
        assertFalse(original.flush())
        val pending = WorkoutDraftJournal(state).pending().single()
        assertEquals(90.0, pending.value, 0.0)
        assertEquals(80.0, requireNotNull(pending.base), 0.0)
        assertEquals(90.0, original.numericDrafts(20).value.getValue(SetField.WEIGHT), 0.0)
        val next = copyState(state)
        stores.first().clear()
        repository.rejectWeight = null
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        assertEquals(90.0, repository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `acknowledged values remain available to a late row but are absent from recovery state`() = runTest {
        val repository = DraftRepository()
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        assertTrue(original.flush())
        assertEquals(80.0, original.numericDrafts(20).value.getValue(SetField.WEIGHT), 0.0)
        assertSame(original.numericDrafts(20), original.numericDrafts(20))
        val next = copyState(state)
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        assertTrue(recovered.numericDrafts(20).value.isEmpty())
        assertNotEquals(original.editorSessionId, recovered.editorSessionId)
        assertEquals(1, repository.attempts.size)
    }

    @Test fun `deletion clears only its fields and full restore clears the remaining journal and row flows`() = runTest {
        val repository = DraftRepository().apply { rejectedIds += listOf(20L, 21L) }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        original.setValue(21, SetField.REPS, 12.0)
        assertFalse(original.flush())
        val removedFlow = original.numericDrafts(20)
        val remainingFlow = original.numericDrafts(21)
        original.deleteSet(20)
        assertFalse(original.flush())
        assertTrue(removedFlow.value.isEmpty())
        assertEquals(setOf(21L), WorkoutDraftJournal(state).pending().map { it.setId }.toSet())
        original.resetAfterRestore()
        assertTrue(original.flush())
        assertTrue(remainingFlow.value.isEmpty())
        assertTrue(WorkoutDraftJournal(state).pending().isEmpty())
        original.retryFailedChanges()
        assertTrue(original.flush())
        assertEquals(2, repository.attempts.size)
    }

    @Test fun `missing restored rows are forgotten and never recreated`() = runTest {
        val repository = DraftRepository().apply { failures = 1 }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.setValue(20, SetField.WEIGHT, 80.0)
        assertFalse(original.flush())
        val next = copyState(state)
        stores.first().clear()
        repository.rows.remove(20)
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        assertTrue(recovered.numericDrafts(20).value.isEmpty())
        assertTrue(WorkoutDraftJournal(next).pending().isEmpty())
        assertEquals(1, repository.attempts.size)
    }

    @Test fun `failed workout names workout notes and exercise notes recover their exact accepted values`() = runTest {
        for (field in 0..2) {
            val repository = DraftRepository().apply { textFailures = 1 }
            val state = SavedStateHandle()
            val original = model(repository, state)
            when (field) {
                0 -> original.rename(1, "  Recovered workout  ", "Stored workout note")
                1 -> original.rename(1, "Original workout", "\n  Recovered workout note\n")
                else -> original.exerciseNotes(10, "\n  Recovered exercise note\n")
            }
            assertFalse(original.flush())
            val next = copyState(state)
            stores.last().clear()
            val recovered = model(repository, next)
            assertTrue(recovered.flush())
            when (field) {
                0 -> assertEquals("Recovered workout", repository.workoutRow!!.name)
                1 -> assertEquals("\n  Recovered workout note\n", repository.workoutRow!!.notes)
                else -> assertEquals("\n  Recovered exercise note\n", repository.exerciseRow!!.notes)
            }
            assertTrue(WorkoutDraftJournal(next).pendingText().isEmpty())
            if (field != 2) {
                assertEquals(WorkoutIdentityDraft(repository.workoutRow!!.name, repository.workoutRow!!.notes), recovered.workoutDrafts(1).value)
                assertSame(recovered.workoutDrafts(1), recovered.workoutDrafts(1))
            } else {
                assertEquals(repository.exerciseRow!!.notes, recovered.exerciseNoteDrafts(10).value)
                assertSame(recovered.exerciseNoteDrafts(10), recovered.exerciseNoteDrafts(10))
            }
        }
    }

    @Test fun `recovered text writes remain blocked during persistent failure until explicit Retry`() = runTest {
        val repository = DraftRepository().apply { rejectText = true }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.rename(1, "Recovered workout", "Recovered workout note")
        original.exerciseNotes(10, "Recovered exercise note")
        assertFalse(original.flush())
        val next = copyState(state)
        stores.last().clear()
        val recovered = model(repository, next)
        assertFalse(recovered.flush())
        val attempts = repository.textAttempts.size
        assertFalse(recovered.flush())
        assertEquals(attempts, repository.textAttempts.size)
        assertEquals("Original workout", repository.workoutRow!!.name)
        repository.rejectText = false
        recovered.retryFailedChanges()
        assertTrue(recovered.flush())
        assertEquals("Recovered workout", repository.workoutRow!!.name)
        assertEquals("Recovered workout note", repository.workoutRow!!.notes)
        assertEquals("Recovered exercise note", repository.exerciseRow!!.notes)
    }

    @Test fun `committed text whose acknowledgement was not captured is acknowledged without another write`() = runTest {
        for (exerciseNote in listOf(false, true)) {
            val repository = DraftRepository().apply { afterTextWrite = CompletableDeferred() }
            val state = SavedStateHandle()
            val original = model(repository, state)
            if (exerciseNote) original.exerciseNotes(10, "Recovered note") else original.rename(1, "  Recovered  ", "Recovered note")
            advanceUntilIdle()
            assertEquals(1, repository.textAttempts.size)
            assertEquals(1, WorkoutDraftJournal(state).pendingText().size)
            val next = copyState(state)
            stores.last().clear()
            repository.afterTextWrite = null
            val recovered = model(repository, next)
            assertTrue(recovered.flush())
            assertEquals(1, repository.textAttempts.size)
            assertTrue(WorkoutDraftJournal(next).pendingText().isEmpty())
        }
    }

    @Test fun `a newer text edit survives an older acknowledgement and recovers from its updated base`() = runTest {
        val repository = DraftRepository().apply { afterTextWrite = CompletableDeferred() }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.rename(1, "  First  ", "First note")
        advanceUntilIdle()
        original.rename(1, "Second", "Second note")
        repository.rejectIdentityName = "Second"
        repository.afterTextWrite!!.complete(Unit)
        repository.afterTextWrite = null
        assertFalse(original.flush())
        val pending = WorkoutDraftJournal(state).pendingText().single()
        assertEquals("Second", pending.first)
        assertEquals("First", pending.baseFirst)
        assertEquals("First note", pending.baseSecond)
        assertEquals(WorkoutIdentityDraft("Second", "Second note"), original.workoutDrafts(1).value)
        val next = copyState(state)
        stores.last().clear()
        repository.rejectIdentityName = null
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        assertEquals("Second", repository.workoutRow!!.name)
        assertEquals("Second note", repository.workoutRow!!.notes)
    }

    @Test fun `unknown or different text bases require review before overwriting stored text`() = runTest {
        val repository = DraftRepository().apply { rejectText = true }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.rename(1, "Recovered", "Recovered note")
        original.exerciseNotes(10, "Recovered exercise note")
        assertFalse(original.flush())
        val next = copyState(state)
        stores.last().clear()
        repository.rejectText = false
        repository.workoutRow = repository.workoutRow!!.copy(name = "Newer", notes = "Newer note")
        repository.exerciseRow = repository.exerciseRow!!.copy(notes = "Newer exercise note")
        val recovered = model(repository, next)
        assertFalse(recovered.flush())
        assertEquals("Newer", repository.workoutRow!!.name)
        assertEquals("Newer exercise note", repository.exerciseRow!!.notes)
        assertEquals(2, repository.textAttempts.size)
        recovered.retryFailedChanges()
        assertTrue(recovered.flush())
        assertEquals("Recovered", repository.workoutRow!!.name)
        assertEquals("Recovered exercise note", repository.exerciseRow!!.notes)
    }

    @Test fun `an undispatched exercise note is recoverable but never automatically assumes its old base`() = runTest {
        val repository = DraftRepository()
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.exerciseNotes(10, "Pending note")
        val next = copyState(state)
        stores.last().clear()
        val recovered = model(repository, next)
        assertFalse(recovered.flush())
        assertTrue(repository.textAttempts.isEmpty())
        assertEquals("Pending note", recovered.exerciseNoteDrafts(10).value)
        recovered.retryFailedChanges()
        assertTrue(recovered.flush())
        assertEquals("Pending note", repository.exerciseRow!!.notes)
    }

    @Test fun `exercise deletion clears only that note and restore clears remaining text recovery state`() = runTest {
        val repository = DraftRepository().apply { rejectText = true }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.rename(1, "Recovered", "Workout note")
        original.exerciseNotes(10, "Exercise note")
        assertFalse(original.flush())
        val workoutFlow = original.workoutDrafts(1)
        val noteFlow = original.exerciseNoteDrafts(10)
        original.removeExercise(10)
        assertFalse(original.flush())
        assertNull(noteFlow.value)
        assertEquals(listOf(TextDraftKind.WORKOUT), WorkoutDraftJournal(state).pendingText().map { it.kind })
        original.resetAfterRestore()
        assertTrue(original.flush())
        assertNull(workoutFlow.value)
        assertTrue(WorkoutDraftJournal(state).pendingText().isEmpty())
        original.retryFailedChanges()
        assertTrue(original.flush())
        assertEquals(2, repository.textAttempts.size)
    }

    @Test fun `missing text parents are forgotten without recreating deleted records`() = runTest {
        val repository = DraftRepository().apply { rejectText = true }
        val state = SavedStateHandle()
        val original = model(repository, state)
        original.rename(1, "Recovered", "Workout note")
        original.exerciseNotes(10, "Exercise note")
        assertFalse(original.flush())
        val next = copyState(state)
        stores.last().clear()
        repository.workoutRow = null
        repository.exerciseRow = null
        val recovered = model(repository, next)
        assertTrue(recovered.flush())
        assertNull(recovered.workoutDrafts(1).value)
        assertNull(recovered.exerciseNoteDrafts(10).value)
        assertTrue(WorkoutDraftJournal(next).pendingText().isEmpty())
        assertEquals(2, repository.textAttempts.size)
    }

    @Test fun `saved numeric and text assignments from before restore cannot replay against matching restored rows`() = runTest {
        var databaseGeneration: String? = null
        val repository = DraftRepository().apply { failures = 1; textFailures = 2 }
        val state = SavedStateHandle()
        val original = model(repository, state) { databaseGeneration }
        original.setValue(20, SetField.WEIGHT, 80.0)
        original.rename(1, "Obsolete workout", "Obsolete workout note")
        original.exerciseNotes(10, "Obsolete exercise note")
        assertFalse(original.flush())
        val capturedWhileStopped = copyState(state)
        databaseGeneration = "restored-database"
        original.resetAfterRestore()
        assertTrue(original.flush())
        stores.last().clear()
        val numericAttempts = repository.attempts.size
        val textAttempts = repository.textAttempts.size

        val recreated = model(repository, capturedWhileStopped) { databaseGeneration }
        assertTrue(recreated.flush())
        assertEquals(60.0, repository.rows.getValue(20).weight, 0.0)
        assertEquals("Original workout", repository.workoutRow!!.name)
        assertEquals("Stored workout note", repository.workoutRow!!.notes)
        assertEquals("Stored exercise note", repository.exerciseRow!!.notes)
        assertTrue(recreated.numericDrafts(20).value.isEmpty())
        assertNull(recreated.workoutDrafts(1).value)
        assertNull(recreated.exerciseNoteDrafts(10).value)
        recreated.retryFailedChanges()
        assertTrue(recreated.flush())
        assertEquals(numericAttempts, repository.attempts.size)
        assertEquals(textAttempts, repository.textAttempts.size)
    }

    @Test fun `same generation keeps exact failed save recovery after a prior restore`() = runTest {
        val repository = DraftRepository().apply { failures = 1 }
        val state = SavedStateHandle()
        val exact = com.forge.workout.preferences.WeightUnit.LB.toKg(0.01)
        val original = model(repository, state) { "restored-database" }
        original.setValue(20, SetField.WEIGHT, exact)
        assertFalse(original.flush())
        val captured = copyState(state)
        stores.last().clear()
        val recreated = model(repository, captured) { "restored-database" }
        assertTrue(recreated.flush())
        assertEquals(exact, repository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `post restore edit captured before its first database read remains recoverable for review`() = runTest {
        val repository = DraftRepository()
        val state = SavedStateHandle()
        val original = model(repository, state) { "restored-database" }
        original.setValue(20, SetField.WEIGHT, 80.0)
        val captured = copyState(state)
        stores.last().clear()
        val recreated = model(repository, captured) { "restored-database" }
        assertEquals(80.0, recreated.numericDrafts(20).value.getValue(SetField.WEIGHT), 0.0)
        assertFalse(recreated.flush())
        assertTrue(repository.attempts.isEmpty())
        recreated.retryFailedChanges()
        assertTrue(recreated.flush())
        assertEquals(80.0, repository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `legacy generationless journals are obsolete after a durable restore`() = runTest {
        fun legacy() = SavedStateHandle(mapOf(WorkoutDraftJournal.KEY to arrayListOf(
            arrayListOf<Any?>(20L, SetField.WEIGHT.name, 80.0, 1L, 60.0))))
        val restoredRepository = DraftRepository()
        val obsolete = model(restoredRepository, legacy()) { "restored-database" }
        assertTrue(obsolete.flush())
        assertEquals(60.0, restoredRepository.rows.getValue(20).weight, 0.0)
        assertTrue(restoredRepository.attempts.isEmpty())
        val originalRepository = DraftRepository()
        val compatible = model(originalRepository, legacy())
        assertTrue(compatible.flush())
        assertEquals(80.0, originalRepository.rows.getValue(20).weight, 0.0)
    }

    @Test fun `retained writer refreshes its generation before reading drafts or accepting another edit`() = runTest {
        var generation: String? = "before"
        val repository = DraftRepository().apply { failures = 1; textFailures = 2 }
        val state = SavedStateHandle()
        val writer = model(repository, state) { generation }
        writer.setValue(20, SetField.WEIGHT, 80.0)
        writer.rename(1, "Old name", "Old note")
        writer.exerciseNotes(10, "Old exercise note")
        assertFalse(writer.flush())
        generation = "after"
        assertTrue(writer.numericDrafts(20).value.isEmpty())
        assertNull(writer.workoutDrafts(1).value)
        assertNull(writer.exerciseNoteDrafts(10).value)
        writer.setValue(20, SetField.WEIGHT, 70.0)
        val captured = copyState(state)
        stores.last().clear()
        val recreated = model(repository, captured) { generation }
        assertEquals(70.0, recreated.numericDrafts(20).value.getValue(SetField.WEIGHT), 0.0)
        assertFalse(recreated.flush())
        recreated.retryFailedChanges()
        assertTrue(recreated.flush())
        assertEquals(70.0, repository.rows.getValue(20).weight, 0.0)
        assertEquals("Original workout", repository.workoutRow!!.name)
    }

    private fun model(repository: WorkoutRepository, state: SavedStateHandle,
        generationProvider: () -> String? = { null }): WorkoutViewModel =
        WorkoutViewModel(repository, { it.message.orEmpty() }, savedState = state, generationProvider = generationProvider).also {
            ViewModelStore().apply { put("workout", it); stores += this }
        }

    private fun copyState(state: SavedStateHandle) = SavedStateHandle(state.keys().associateWith { key -> copyPrimitive(state.get<Any?>(key)) })
    private fun copyPrimitive(value: Any?): Any? = if (value is List<*>) ArrayList(value.map(::copyPrimitive)) else value

    private class DraftRepository : WorkoutRepository {
        val rows = mutableMapOf(20L to WorkoutSet(20, 10, 1, 60.0, 8), 21L to WorkoutSet(21, 10, 2, 60.0, 8))
        val attempts = mutableListOf<WorkoutSet>()
        val rejectedIds = mutableSetOf<Long>()
        var failures = 0
        var rejectWeight: Double? = null
        var afterWrite: CompletableDeferred<Unit>? = null
        var workoutRow: Workout? = Workout(1, "Original workout", 1, notes = "Stored workout note")
        var exerciseRow: WorkoutExercise? = WorkoutExercise(10, 1, 100, 0, "Stored exercise note")
        val textAttempts = mutableListOf<String>()
        var textFailures = 0
        var rejectText = false
        var rejectIdentityName: String? = null
        var afterTextWrite: CompletableDeferred<Unit>? = null
        override suspend fun workoutSet(id: Long) = rows[id]
        override suspend fun workoutExerciseSets(workoutExerciseId: Long) = rows.values.filter { it.workoutExerciseId == workoutExerciseId }
        override suspend fun updateSet(set: WorkoutSet) {
            attempts += set
            if (failures-- > 0 || set.id in rejectedIds || set.weight == rejectWeight) throw IOException("Injected storage failure")
            rows[set.id] = set
            afterWrite?.await()
        }
        override suspend fun deleteSet(id: Long) { rows.remove(id) }
        override suspend fun workout(id: Long): Workout? = workoutRow?.takeIf { it.id == id }
        override suspend fun workoutExercise(id: Long): WorkoutExercise? = exerciseRow?.takeIf { it.id == id }
        override suspend fun hasWorkoutExercise(workoutId: Long, exerciseId: Long) = false
        override suspend fun ensureSeeded() = Unit
        override fun observeExerciseUsage(): Flow<List<ExerciseUsage>> = error("Unused")
        override fun observeExercises(): Flow<List<Exercise>> = error("Unused")
        override fun observeWorkouts(): Flow<List<WorkoutDetail>> = error("Unused")
        override fun observeRoutines(): Flow<List<RoutineDetail>> = error("Unused")
        override suspend fun saveExercise(exercise: Exercise): Long = error("Unused")
        override suspend fun deleteExercise(id: Long) = Unit
        override suspend fun startWorkout(name: String, routineId: Long?, repeatWorkoutId: Long?): Long = error("Unused")
        override suspend fun updateWorkout(id: Long, name: String, notes: String) {
            textAttempts += "identity:$name"
            if (textFailures-- > 0 || rejectText || name == rejectIdentityName) throw IOException("Injected text storage failure")
            workoutRow = requireNotNull(workoutRow).copy(name = name.trim(), notes = notes)
            afterTextWrite?.await()
        }
        override suspend fun updateMuscleGroups(id: Long, groups: Set<MuscleGroup>) = Unit
        override suspend fun finishWorkout(id: Long) = Unit
        override suspend fun deleteWorkout(id: Long) = Unit
        override suspend fun addExercise(workoutId: Long, exerciseId: Long) = Unit
        override suspend fun removeWorkoutExercise(id: Long) {
            exerciseRow = null
            rows.keys.removeAll(rows.values.filter { it.workoutExerciseId == id }.map { it.id }.toSet())
        }
        override suspend fun moveWorkoutExercise(id: Long, delta: Int) = Unit
        override suspend fun addSet(workoutExerciseId: Long) = Unit
        override suspend fun updateExerciseNotes(workoutExerciseId: Long, notes: String) {
            textAttempts += "notes:$notes"
            if (textFailures-- > 0 || rejectText) throw IOException("Injected text storage failure")
            exerciseRow = requireNotNull(exerciseRow).copy(notes = notes)
            afterTextWrite?.await()
        }
        override suspend fun setRestEndTime(workoutId: Long, endTime: Long?) = Unit
        override suspend fun adjustRestEndTime(workoutId: Long, expectedDeadline: Long, adjustedDeadline: Long, now: Long): Boolean = error("Unused operation")
        override suspend fun consumeRestTimer(workoutId: Long, expectedDeadline: Long, now: Long) = false
        override suspend fun saveRoutine(id: Long?, name: String): Long = error("Unused")
        override suspend fun addRoutineExercise(routineId: Long, exerciseId: Long) = Unit
        override suspend fun removeRoutineExercise(id: Long) = Unit
        override suspend fun moveRoutineExercise(id: Long, delta: Int) = Unit
        override suspend fun deleteRoutine(id: Long) = Unit
    }
}

package com.forge.workout.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.forge.workout.model.WorkoutSet
import com.forge.workout.model.SetType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.utils.WorkoutValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class WorkoutViewModel(
    private val repository: WorkoutRepository,
    private val errorFormatter: (Throwable) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    savedState: SavedStateHandle,
    private val generationProvider: () -> String? = { null },
) : ViewModel() {
    @androidx.annotation.VisibleForTesting
    constructor(repository: WorkoutRepository, errorFormatter: (Throwable) -> String,
        clock: () -> Long = System::currentTimeMillis) : this(repository, errorFormatter, clock, SavedStateHandle())

    val editorSessionId: String = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private val pendingLock = Any()
    private val pendingJobs = mutableSetOf<Job>()
    private enum class Target { WORKOUT, EXERCISE, SET }
    private data class MutationKey(val target: Target, val id: Long, val operation: String, val request: Long? = null)
    private class Mutation(
        val id: Long, val generation: Long, val key: MutationKey, val action: suspend (retrying: Boolean) -> Unit,
        var message: String = "",
    )
    private var nextRequest = 0L
    private var nextNotice = 0L
    private var generation = 0L
    private val latestRequests = mutableMapOf<MutationKey, Long>()
    private val timerIntents = mutableMapOf<Long, Long>()
    private val timerSessions = mutableMapOf<Long, Long>()
    private val failedMutations = linkedMapOf<MutationKey, Mutation>()
    private val unresolvedRequests = MutableStateFlow<Set<Long>>(emptySet())
    private val deletedTargets = mutableSetOf<Pair<Target, Long>>()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _errorNotice = MutableStateFlow<WorkoutSaveError?>(null)
    val errorNotice = _errorNotice.asStateFlow()
    private val _invalidInputs = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    val invalidInputs = _invalidInputs.asStateFlow()
    private val draftJournal = WorkoutDraftJournal(savedState, generationProvider())
    private val numericDraftFlows = mutableMapOf<Long, MutableStateFlow<Map<SetField, Double>>>()
    private val numericDraftViews = mutableMapOf<Long, StateFlow<Map<SetField, Double>>>()
    private val workoutDraftFlows = mutableMapOf<Long, MutableStateFlow<WorkoutIdentityDraft?>>()
    private val workoutDraftViews = mutableMapOf<Long, StateFlow<WorkoutIdentityDraft?>>()
    private val exerciseNoteDraftFlows = mutableMapOf<Long, MutableStateFlow<String?>>()
    private val exerciseNoteDraftViews = mutableMapOf<Long, StateFlow<String?>>()

    init {
        draftJournal.pending().forEach { assignment ->
            setValueInternal(assignment.setId, assignment.field, assignment.value, assignment.base, recovering = true)
        }
        draftJournal.pendingText().forEach { assignment ->
            when (assignment.kind) {
                TextDraftKind.WORKOUT -> renameInternal(assignment.id, assignment.first, requireNotNull(assignment.second),
                    assignment.baseFirst, assignment.baseSecond, recovering = true)
                TextDraftKind.EXERCISE -> exerciseNotesInternal(assignment.id, assignment.first, assignment.baseFirst, recovering = true)
            }
        }
    }

    /** A row observes only its own accepted values; acknowledgements remain visible while Room catches up. */
    fun numericDrafts(setId: Long): StateFlow<Map<SetField, Double>> {
        synchronizeRestoreGeneration()
        return synchronized(pendingLock) {
            val mutable = numericDraftFlows.getOrPut(setId) { MutableStateFlow(emptyMap()) }
            numericDraftViews.getOrPut(setId) { mutable.asStateFlow() }
        }
    }

    fun workoutDrafts(workoutId: Long): StateFlow<WorkoutIdentityDraft?> {
        synchronizeRestoreGeneration()
        return synchronized(pendingLock) {
            val mutable = workoutDraftFlows.getOrPut(workoutId) { MutableStateFlow(null) }
            workoutDraftViews.getOrPut(workoutId) { mutable.asStateFlow() }
        }
    }

    fun exerciseNoteDrafts(exerciseId: Long): StateFlow<String?> {
        synchronizeRestoreGeneration()
        return synchronized(pendingLock) {
            val mutable = exerciseNoteDraftFlows.getOrPut(exerciseId) { MutableStateFlow(null) }
            exerciseNoteDraftViews.getOrPut(exerciseId) { mutable.asStateFlow() }
        }
    }

    fun clearError() { _errorNotice.value?.let(::clearError) }

    fun clearError(notice: WorkoutSaveError) = synchronized(pendingLock) {
        // A snackbar finishing must not acknowledge a newer failure with the same message.
        if (_errorNotice.value === notice) {
            _errorNotice.value = null
            _error.value = null
        }
    }

    private fun reportFailure(mutation: Mutation) = synchronized(pendingLock) {
        _error.value = mutation.message
        _errorNotice.value = WorkoutSaveError(++nextNotice, mutation.message, mutation.id)
    }

    suspend fun awaitResolution(notice: WorkoutSaveError) {
        unresolvedRequests.first { notice.requestId !in it }
    }

    private fun publishUnresolvedRequests() { unresolvedRequests.value = failedMutations.values.map { it.id }.toSet() }

    fun inputValidity(workoutId: Long, key: String, valid: Boolean) {
        _invalidInputs.update { current ->
            val keys = current[workoutId].orEmpty().let { if (valid) it - key else it + key }
            if (keys.isEmpty()) current - workoutId else current + (workoutId to keys)
        }
    }

    fun clearWorkoutValidity(workoutId: Long) {
        _invalidInputs.update { it - workoutId }
    }
    fun clearAllValidity() { _invalidInputs.value = emptyMap() }

    private fun clearInputsMatching(predicate: (String) -> Boolean) {
        _invalidInputs.update { current ->
            current.mapValues { (_, keys) -> keys.filterNot(predicate).toSet() }.filterValues { it.isNotEmpty() }
        }
    }

    private fun enqueue(action: suspend () -> Unit) {
        // Register before dispatching so flush sees jobs that have not entered the mutex yet.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            mutex.withLock {
                action()
            }
        }
        synchronized(pendingLock) { pendingJobs += job }
        job.invokeOnCompletion { synchronized(pendingLock) { pendingJobs -= job } }
        job.start()
    }

    private fun perform(target: Target, id: Long, operation: String, distinct: Boolean = false,
        beforeEnqueue: ((Long) -> Unit)? = null, action: suspend (Boolean) -> Unit) {
        synchronizeRestoreGeneration()
        val mutation = synchronized(pendingLock) {
            val request = ++nextRequest
            val key = MutationKey(target, id, operation, request.takeIf { distinct })
            latestRequests[key] = request
            Mutation(request, generation, key, action)
        }
        beforeEnqueue?.invoke(mutation.id)
        enqueue { attempt(mutation, retrying = false) }
    }

    private suspend fun attempt(mutation: Mutation, retrying: Boolean) {
        if (mutation.generation != synchronized(pendingLock) { generation }) return
        try {
            if ((mutation.key.target to mutation.key.id) !in deletedTargets) mutation.action(retrying)
            val failed = failedMutations[mutation.key]
            if (failed != null && failed.id <= mutation.id) {
                failedMutations.remove(mutation.key)
                publishUnresolvedRequests()
                _errorNotice.value?.takeIf { it.requestId == failed.id }?.let(::clearError)
            }
            if (mutation.key.request != null) synchronized(pendingLock) { latestRequests.remove(mutation.key) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutation.message = errorFormatter(failure)
            failedMutations[mutation.key] = mutation
            publishUnresolvedRequests()
            reportFailure(mutation)
        }
    }

    /** Reattempts only unresolved requests; successful additions and relative edits are never replayed. */
    fun retryFailedChanges() {
        synchronizeRestoreGeneration()
        enqueue {
            failedMutations.values.sortedBy { it.id }.forEach { mutation ->
                val latest = synchronized(pendingLock) { latestRequests[mutation.key] }
                if (failedMutations[mutation.key] === mutation && latest == mutation.id) attempt(mutation, retrying = true)
            }
        }
    }

    private fun timerIntent(id: Long): Long = synchronized(pendingLock) {
        (timerIntents.getOrDefault(id, 0L) + 1L).also { timerIntents[id] = it }
    }
    private fun currentTimerIntent(id: Long, intent: Long): Boolean = synchronized(pendingLock) { timerIntents[id] == intent }
    private fun timerSession(id: Long, replace: Boolean = false): Long = synchronized(pendingLock) {
        val current = timerSessions.getOrDefault(id, 0L)
        if (replace) (current + 1L).also { timerSessions[id] = it } else current
    }

    fun rename(id: Long, name: String, notes: String) = renameInternal(id, name, notes)

    private fun renameInternal(id: Long, name: String, notes: String, baseName: String? = null,
        baseNotes: String? = null, recovering: Boolean = false) {
        val desired = WorkoutIdentityDraft(name.trim(), notes)
        var revision = 0L
        val accepted = runCatching { validateIdentity(name, notes) }.isSuccess
        perform(Target.WORKOUT, id, "identity", beforeEnqueue = { request ->
            revision = request
            if (accepted) {
                draftJournal.put(TextDraftAssignment(TextDraftKind.WORKOUT, id, desired.name, notes, request, baseName, baseNotes))
                synchronized(pendingLock) { workoutDraftFlows.getOrPut(id) { MutableStateFlow(null) }.value = desired }
            }
        }) { retrying ->
            validateIdentity(name, notes)
            val current = repository.workout(id)
            if (current == null) {
                if (!recovering) throw IllegalArgumentException("Workout was not found.")
                forgetTextDrafts(TextDraftKind.WORKOUT, setOf(id))
                return@perform
            }
            val matches = current.name == desired.name && current.notes == notes
            if (recovering && !retrying && !matches && (baseName == null || baseNotes == null || current.name != baseName || current.notes != baseNotes)) {
                throw WorkoutDraftRecoveryException()
            }
            if (!recovering || !matches) {
                draftJournal.beforeTextWrite(TextDraftKind.WORKOUT, id, revision, current.name, current.notes)
                repository.updateWorkout(id, desired.name, notes)
            }
            draftJournal.acknowledgeText(TextDraftKind.WORKOUT, id, revision, desired.name, notes)
        }
    }
    fun toggleMuscleGroup(id: Long, group: MuscleGroup) = perform(Target.WORKOUT, id, "muscle", distinct = true) {
        val selected = repository.workout(id)?.muscleGroups?.toSet() ?: return@perform
        repository.updateMuscleGroups(id, if(group in selected) selected - group else selected + group)
    }
    fun addExercise(id: Long, exerciseId: Long) = perform(Target.WORKOUT, id, "addExercise:$exerciseId") {
        if (!repository.hasWorkoutExercise(id, exerciseId)) {
            try {
                repository.addExercise(id, exerciseId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Another writer may have satisfied the selection after our first read.
                val present = try {
                    repository.hasWorkoutExercise(id, exerciseId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!present) throw failure
            }
        }
    }
    fun moveExercise(id: Long, delta: Int) = perform(Target.EXERCISE, id, "move", distinct = true) { repository.moveWorkoutExercise(id, delta) }
    fun exerciseNotes(id: Long, notes: String) = exerciseNotesInternal(id, notes)

    private fun exerciseNotesInternal(id: Long, notes: String, base: String? = null, recovering: Boolean = false) {
        var revision = 0L
        perform(Target.EXERCISE, id, "notes", beforeEnqueue = { request ->
            revision = request
            if (notes.length <= 5000) {
                draftJournal.put(TextDraftAssignment(TextDraftKind.EXERCISE, id, notes, null, request, base))
                synchronized(pendingLock) { exerciseNoteDraftFlows.getOrPut(id) { MutableStateFlow(null) }.value = notes }
            }
        }) { retrying ->
            require(notes.length <= 5000) { "Notes must be 5,000 characters or fewer." }
            val current = repository.workoutExercise(id)
            if (current == null) {
                if (!recovering) throw IllegalArgumentException("Workout exercise was not found.")
                forgetTextDrafts(TextDraftKind.EXERCISE, setOf(id))
                return@perform
            }
            if (recovering && !retrying && current.notes != notes && (base == null || current.notes != base)) {
                throw WorkoutDraftRecoveryException()
            }
            if (!recovering || current.notes != notes) {
                draftJournal.beforeTextWrite(TextDraftKind.EXERCISE, id, revision, current.notes, null)
                repository.updateExerciseNotes(id, notes)
            }
            draftJournal.acknowledgeText(TextDraftKind.EXERCISE, id, revision, notes, null)
        }
    }
    fun addSet(id: Long) = perform(Target.EXERCISE, id, "addSet", distinct = true) { repository.addSet(id) }
    fun rest(id: Long, endTime: Long?) {
        val intent = timerIntent(id)
        timerSession(id, replace = true)
        perform(Target.WORKOUT, id, "rest") { retrying ->
            if (!retrying || currentTimerIntent(id, intent)) repository.setRestEndTime(id, endTime)
        }
    }
    fun adjustRest(id: Long, seconds: Int) {
        timerIntent(id)
        val session = timerSession(id)
        perform(Target.WORKOUT, id, "adjustRest", distinct = true) { retrying ->
            require(seconds == -15 || seconds == 15)
            if (retrying && timerSession(id) != session) return@perform
            val workout = repository.workout(id) ?: return@perform
            val deadline = workout.restEndTime ?: return@perform
            val now = clock()
            if(workout.endTime != null || deadline <= now) return@perform
            val delta = seconds * 1_000L
            val adjusted = if(delta > 0) deadline.coerceAtMost(Long.MAX_VALUE - delta) + delta else deadline + delta
            repository.adjustRestEndTime(id, deadline, adjusted.coerceAtLeast(now), now)
        }
    }

    fun expireRest(id: Long, expectedDeadline: Long, onExpired: () -> Unit = {}) = perform(Target.WORKOUT, id, "expireRest:$expectedDeadline") {
        val workout = repository.workout(id)
            ?: return@perform
        // A stale screen effect must not clear a timer that was extended or restarted.
        if(workout.endTime != null || workout.restEndTime != expectedDeadline || expectedDeadline > clock()) return@perform
        if(repository.consumeRestTimer(id, expectedDeadline, clock())) onExpired()
    }

    fun removeExercise(id: Long) = perform(Target.EXERCISE, id, "remove") {
        val sets = repository.workoutExerciseSets(id)
        repository.removeWorkoutExercise(id)
        forgetTargets(setOf(Target.EXERCISE to id) + sets.map { Target.SET to it.id })
        val setPrefixes = sets.map { "set_${it.id}_" }
        clearInputsMatching { key ->
            key == "notes_$id" || key.startsWith("exercise_${id}_") || setPrefixes.any { prefix -> key.startsWith(prefix) }
        }
    }

    fun deleteSet(id: Long) = perform(Target.SET, id, "delete") {
        repository.deleteSet(id)
        forgetTargets(setOf(Target.SET to id))
        clearInputsMatching { it.startsWith("set_${id}_") }
    }

    fun setValue(id: Long, field: SetField, value: Double) = setValueInternal(id, field, value)

    private fun setValueInternal(id: Long, field: SetField, value: Double, base: Double? = null, recovering: Boolean = false) {
        var revision = 0L
        val accepted = runCatching { validateValue(field, value) }.isSuccess
        perform(Target.SET, id, "value:$field", beforeEnqueue = { request ->
            revision = request
            if (accepted) {
                draftJournal.put(NumericDraftAssignment(id, field, value, request, base))
                synchronized(pendingLock) {
                    numericDraftFlows.getOrPut(id) { MutableStateFlow(emptyMap()) }.update { it + (field to value) }
                }
            }
        }) { retrying ->
            validateValue(field, value)
            val set = currentSet(id)
            if (set == null) {
                forgetNumericDrafts(setOf(id))
                return@perform
            }
            val stored = when (field) {
                SetField.WEIGHT -> set.weight
                SetField.REPS -> set.reps.toDouble()
                SetField.DURATION -> set.duration.toDouble()
                SetField.DISTANCE -> set.distance
            }
            if (recovering && !retrying && stored != value && (base == null || stored != base)) {
                throw WorkoutDraftRecoveryException()
            }
            if (!recovering || stored != value) {
                draftJournal.beforeWrite(id, field, revision, stored)
                repository.updateSet(when (field) {
                    SetField.WEIGHT -> set.copy(weight = value)
                    SetField.REPS -> set.copy(reps = value.toInt())
                    SetField.DURATION -> set.copy(duration = value.toInt())
                    SetField.DISTANCE -> set.copy(distance = value)
                })
            }
            draftJournal.acknowledge(id, field, revision, value)
        }
    }
    fun setType(id: Long, type: SetType) = perform(Target.SET, id, "type") {
        val set = currentSet(id) ?: return@perform
        repository.updateSet(set.copy(setType = type))
    }
    fun completeSet(id: Long, completed: Boolean, workoutId: Long, restSeconds: Int, editing: Boolean) {
        val startRest = completed && restSeconds > 0 && !editing
        val intent = if (startRest) timerIntent(workoutId) else null
        if (startRest) timerSession(workoutId, replace = true)
        var completionWritten = false
        var restDeadline = 0L
        perform(Target.SET, id, "completion") { retrying ->
            if (!completionWritten) {
                val set = currentSet(id) ?: return@perform
                repository.updateSet(set.copy(completed = completed))
                completionWritten = true
                restDeadline = clock() + restSeconds * 1_000L
            }
            // A timer failure must not replay the saved completion or replace a newer timer intent.
            if (startRest && (!retrying || currentTimerIntent(workoutId, requireNotNull(intent)))) {
                repository.setRestEndTime(workoutId, restDeadline)
            }
        }
    }

    private fun forgetTargets(targets: Set<Pair<Target, Long>>) {
        deletedTargets += targets
        forgetNumericDrafts(targets.filter { it.first == Target.SET }.map { it.second }.toSet())
        forgetTextDrafts(TextDraftKind.WORKOUT, targets.filter { it.first == Target.WORKOUT }.map { it.second }.toSet())
        forgetTextDrafts(TextDraftKind.EXERCISE, targets.filter { it.first == Target.EXERCISE }.map { it.second }.toSet())
        val removed = failedMutations.values.filter { (it.key.target to it.key.id) in targets }.map { it.id }.toSet()
        failedMutations.entries.removeAll { (it.key.target to it.key.id) in targets }
        publishUnresolvedRequests()
        synchronized(pendingLock) { latestRequests.keys.removeAll { (it.target to it.id) in targets } }
        _errorNotice.value?.takeIf { it.requestId in removed }?.let(::clearError)
    }

    /** Called after the observed workout was deleted, never merely when deletion is requested. */
    fun forgetDeletedWorkout(workoutId: Long, exerciseIds: Set<Long>, setIds: Set<Long>) =
        perform(Target.WORKOUT, workoutId, "deletionCleanup") {
            // A temporary read failure remains retryable with the captured deleted entity IDs.
            if (repository.workout(workoutId) == null) {
                forgetTargets(setOf(Target.WORKOUT to workoutId) + exerciseIds.map { Target.EXERCISE to it } + setIds.map { Target.SET to it })
                clearWorkoutValidity(workoutId)
            }
        }

    private fun synchronizeRestoreGeneration() {
        val current = generationProvider()
        if (!draftJournal.matchesGeneration(current)) resetAfterRestore(current)
    }

    fun resetAfterRestore(restoredGeneration: String? = generationProvider()) {
        synchronized(pendingLock) { generation++; latestRequests.clear(); timerIntents.clear(); timerSessions.clear() }
        draftJournal.clear(restoredGeneration)
        forgetNumericDrafts(numericDraftFlows.keys.toSet())
        forgetTextDrafts(TextDraftKind.WORKOUT, workoutDraftFlows.keys.toSet())
        forgetTextDrafts(TextDraftKind.EXERCISE, exerciseNoteDraftFlows.keys.toSet())
        enqueue {
            failedMutations.clear()
            publishUnresolvedRequests()
            deletedTargets.clear()
            clearAllValidity()
            clearError()
        }
    }

    private fun forgetNumericDrafts(ids: Set<Long>) {
        draftJournal.removeSets(ids)
        synchronized(pendingLock) {
            ids.forEach { id ->
                numericDraftViews.remove(id)
                numericDraftFlows.remove(id)?.value = emptyMap()
            }
        }
    }

    private fun forgetTextDrafts(kind: TextDraftKind, ids: Set<Long>) {
        draftJournal.removeText(kind, ids)
        synchronized(pendingLock) {
            when (kind) {
                TextDraftKind.WORKOUT -> ids.forEach { id -> workoutDraftViews.remove(id); workoutDraftFlows.remove(id)?.value = null }
                TextDraftKind.EXERCISE -> ids.forEach { id -> exerciseNoteDraftViews.remove(id); exerciseNoteDraftFlows.remove(id)?.value = null }
            }
        }
    }

    private fun validateIdentity(name: String, notes: String) {
        WorkoutValidation.validateName(name)
        require(notes.length <= 5000) { "Notes must be 5,000 characters or fewer." }
    }

    private suspend fun currentSet(id: Long): WorkoutSet? = repository.workoutSet(id)

    private fun validateValue(field: SetField, value: Double) {
        when (field) {
            SetField.WEIGHT -> require(value.isFinite() && value in 0.0..10000.0) { "Enter a weight from 0 to 10,000 kg." }
            SetField.REPS -> require(value.isFinite() && value in 0.0..10000.0 && value % 1.0 == 0.0) { "Enter repetitions from 0 to 10,000." }
            SetField.DURATION -> require(value.isFinite() && value in 0.0..604800.0 && value % 1.0 == 0.0) { "Enter a duration from 0 to 604,800 seconds." }
            SetField.DISTANCE -> require(value.isFinite() && value in 0.0..100000.0) { "Enter a distance from 0 to 100,000 km." }
        }
    }

    /** Waits for queued mutations and reports failures before a finish, discard, or return. */
    suspend fun flush(): Boolean {
        synchronizeRestoreGeneration()
        while (true) {
            val waiting = synchronized(pendingLock) { pendingJobs.toList() }
            if (waiting.isEmpty()) break
            waiting.joinAll()
        }
        return mutex.withLock {
            failedMutations.values.firstOrNull()?.let(::reportFailure)
            failedMutations.isEmpty()
        }
    }
}
enum class SetField { WEIGHT, REPS, DURATION, DISTANCE }

data class WorkoutSaveError(val id: Long, val message: String, internal val requestId: Long)

data class WorkoutIdentityDraft(val name: String, val notes: String)

package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle

internal data class NumericDraftAssignment(
    val setId: Long,
    val field: SetField,
    val value: Double,
    val revision: Long,
    val base: Double? = null,
)

internal enum class TextDraftKind { WORKOUT, EXERCISE }

internal data class TextDraftAssignment(
    val kind: TextDraftKind,
    val id: Long,
    val first: String,
    val second: String?,
    val revision: Long,
    val baseFirst: String? = null,
    val baseSecond: String? = null,
)

/** Saves data assignments only: structural operations and executable closures never enter saved state. */
internal class WorkoutDraftJournal(private val state: SavedStateHandle, generation: String? = null) {
    private val assignments = linkedMapOf<Pair<Long, SetField>, NumericDraftAssignment>()
    private val textAssignments = linkedMapOf<Pair<TextDraftKind, Long>, TextDraftAssignment>()
    private var restoreGeneration = generation

    init {
        // Android can restore a bundle captured before a restore completed while stopped.
        if (state.get<Any>(GENERATION_KEY) != restoreGeneration) clear()
        state[GENERATION_KEY] = restoreGeneration
        val saved = state.get<Any>(KEY) as? List<*>
        saved.orEmpty().forEach { entry ->
            val values = entry as? List<*> ?: return@forEach
            val id = values.getOrNull(0) as? Long ?: return@forEach
            val field = (values.getOrNull(1) as? String)?.let { name -> SetField.entries.firstOrNull { it.name == name } }
                ?: return@forEach
            val value = values.getOrNull(2) as? Double ?: return@forEach
            val revision = values.getOrNull(3) as? Long ?: return@forEach
            val base = values.getOrNull(4) as? Double
            if (id <= 0 || revision <= 0 || !value.isFinite() || base?.isFinite() == false) return@forEach
            val assignment = NumericDraftAssignment(id, field, value, revision, base)
            val key = id to field
            if (assignments[key]?.revision?.let { it >= revision } != true) assignments[key] = assignment
        }
        (state.get<Any>(TEXT_KEY) as? List<*>).orEmpty().forEach { entry ->
            val values = entry as? List<*> ?: return@forEach
            val kind = (values.getOrNull(0) as? String)?.let { name -> TextDraftKind.entries.firstOrNull { it.name == name } }
                ?: return@forEach
            val id = values.getOrNull(1) as? Long ?: return@forEach
            val first = values.getOrNull(2) as? String ?: return@forEach
            val second = values.getOrNull(3) as? String
            val revision = values.getOrNull(4) as? Long ?: return@forEach
            if (id <= 0 || revision <= 0 || (kind == TextDraftKind.WORKOUT && second == null)) return@forEach
            val assignment = TextDraftAssignment(kind, id, first, second, revision,
                values.getOrNull(5) as? String, values.getOrNull(6) as? String)
            val key = kind to id
            if (textAssignments[key]?.revision?.let { it >= revision } != true) textAssignments[key] = assignment
        }
    }

    fun pending(): List<NumericDraftAssignment> = assignments.values.sortedBy { it.revision }
    fun pendingText(): List<TextDraftAssignment> = textAssignments.values.sortedBy { it.revision }
    fun matchesGeneration(generation: String?): Boolean = restoreGeneration == generation

    fun put(assignment: NumericDraftAssignment) {
        assignments[assignment.setId to assignment.field] = assignment
        persist()
    }

    fun beforeWrite(setId: Long, field: SetField, revision: Long, base: Double) {
        val key = setId to field
        val current = assignments[key] ?: return
        if (current.revision == revision) {
            assignments[key] = current.copy(base = base)
            persist()
        }
    }

    fun acknowledge(setId: Long, field: SetField, revision: Long, value: Double) {
        val key = setId to field
        val current = assignments[key] ?: return
        when {
            current.revision == revision -> assignments.remove(key)
            // A newer edit may be queued while this older write is awaiting Room.
            current.revision > revision && current.base == null -> assignments[key] = current.copy(base = value)
            else -> return
        }
        persist()
    }

    fun removeSets(ids: Set<Long>) {
        if (assignments.keys.removeAll { it.first in ids }) persist()
    }

    fun put(assignment: TextDraftAssignment) {
        textAssignments[assignment.kind to assignment.id] = assignment
        persistText()
    }

    fun beforeTextWrite(kind: TextDraftKind, id: Long, revision: Long, first: String, second: String?) {
        val key = kind to id
        val current = textAssignments[key] ?: return
        if (current.revision == revision) {
            textAssignments[key] = current.copy(baseFirst = first, baseSecond = second)
            persistText()
        }
    }

    fun acknowledgeText(kind: TextDraftKind, id: Long, revision: Long, first: String, second: String?) {
        val key = kind to id
        val current = textAssignments[key] ?: return
        when {
            current.revision == revision -> textAssignments.remove(key)
            current.revision > revision && current.baseFirst == null -> textAssignments[key] = current.copy(baseFirst = first, baseSecond = second)
            else -> return
        }
        persistText()
    }

    fun removeText(kind: TextDraftKind, ids: Set<Long>) {
        if (textAssignments.keys.removeAll { it.first == kind && it.second in ids }) persistText()
    }

    fun clear(generation: String? = restoreGeneration) {
        assignments.clear()
        textAssignments.clear()
        restoreGeneration = generation
        state.remove<Any>(KEY)
        state.remove<Any>(TEXT_KEY)
        state[GENERATION_KEY] = restoreGeneration
    }

    private fun persistText() {
        state[TEXT_KEY] = ArrayList(textAssignments.values.map { assignment ->
            arrayListOf<Any?>(assignment.kind.name, assignment.id, assignment.first, assignment.second,
                assignment.revision, assignment.baseFirst, assignment.baseSecond)
        })
    }

    private fun persist() {
        // Nested primitive ArrayLists are supported by Android saved state without
        // Parcelable models, database snapshots or Java object serialization.
        state[KEY] = ArrayList(assignments.values.map { assignment ->
            arrayListOf<Any?>(assignment.setId, assignment.field.name, assignment.value, assignment.revision, assignment.base)
        })
    }

    internal companion object {
        const val KEY = "workout_pending_numeric_v1"
        const val TEXT_KEY = "workout_pending_text_v1"
        const val GENERATION_KEY = "workout_restore_generation_v1"
    }
}

class WorkoutDraftRecoveryException : IllegalStateException("Recovered workout changes need review before retrying.")

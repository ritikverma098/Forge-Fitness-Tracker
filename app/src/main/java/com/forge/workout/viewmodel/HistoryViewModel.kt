package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.utils.HistoryFilters
import com.forge.workout.utils.HistoryFiltering
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class HistoryUiState(
    val filters: HistoryFilters = HistoryFilters(),
    val grouped: Map<LocalDate, List<WorkoutDetail>> = emptyMap(),
    val exercises: List<Exercise> = emptyList(),
    val hasHistory: Boolean = false,
)

class HistoryViewModel(private val savedState: SavedStateHandle, private val zone: ZoneId = ZoneId.systemDefault()) : ViewModel() {
    private val filters = MutableStateFlow(HistoryFilters(
        name = savedState["history_name"] ?: "",
        exerciseId = savedState["history_exercise"],
        muscleGroup = savedState.get<String>("history_muscle")?.let(MuscleGroup::valueOf),
        fromDate = savedState.get<Long>("history_from")?.let(LocalDate::ofEpochDay),
        toDate = savedState.get<Long>("history_to")?.let(LocalDate::ofEpochDay),
    ))
    // Reuse AppViewModel's observed graph; changing a filter never queries Room.
    private val source = MutableStateFlow<List<WorkoutDetail>>(emptyList())
    val selectedFilters = filters.asStateFlow()
    private val history = source.map { workouts ->
        val completed = workouts.filter { it.workout.endTime != null }
        completed to completed.asSequence().flatMap { it.exercises.asSequence() }.map { it.exercise }.distinctBy { it.id }
            .sortedBy { it.name.lowercase(Locale.ROOT) }.toList()
    }
    val state = combine(history, filters) { (completed, exercises), selected ->
        HistoryUiState(selected, HistoryFiltering.filter(completed, selected, zone)
            .groupBy { Instant.ofEpochMilli(it.workout.startTime).atZone(zone).toLocalDate() }, exercises, completed.isNotEmpty())
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState(filters.value))

    fun setWorkouts(workouts: List<WorkoutDetail>) { source.value = workouts }
    fun setName(name: String) = update(filters.value.copy(name = name))
    fun selectExercise(id: Long?) = update(filters.value.copy(exerciseId = id))
    fun selectMuscle(group: MuscleGroup?) = update(filters.value.copy(muscleGroup = group))
    fun setDateRange(from: LocalDate?, to: LocalDate?) {
        require(from == null || to == null || from <= to)
        update(filters.value.copy(fromDate = from, toDate = to))
    }
    fun clearFilters() = update(HistoryFilters())
    private fun update(next: HistoryFilters) {
        savedState["history_name"] = next.name
        savedState["history_exercise"] = next.exerciseId
        savedState["history_muscle"] = next.muscleGroup?.name
        savedState["history_from"] = next.fromDate?.toEpochDay()
        savedState["history_to"] = next.toDate?.toEpochDay()
        filters.value = next
    }
}

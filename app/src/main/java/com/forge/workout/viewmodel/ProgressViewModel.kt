package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.workout.model.Exercise
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.time.ZoneId
import java.util.Locale

data class ProgressUiState(
    val exercises: List<Exercise> = emptyList(),
    val selected: Exercise? = null,
    val metric: ProgressMetric = ProgressMetric.BEST_WEIGHT,
    val range: ProgressRange = ProgressRange.ALL,
    val availableMetrics: List<ProgressMetric> = emptyList(),
    val stats: ProgressStats = ProgressStats(),
    val chartPoints: List<MetricPoint> = emptyList(),
    val weightUnit: WeightUnit = WeightUnit.KG,
)
private data class ProgressSource(val exercises: List<Exercise>, val workouts: List<WorkoutDetail>)
private data class ProgressIndex(val exercises: List<Exercise> = emptyList(), val sessions: Map<Long, List<PerformanceSession>> = emptyMap(), val latestExerciseId: Long? = null)
private data class ProgressSelection(val exerciseId: Long?, val metric: ProgressMetric, val range: ProgressRange)

class ProgressViewModel(private val savedState: SavedStateHandle, private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()) : ViewModel() {
    private val source = MutableStateFlow(ProgressSource(emptyList(), emptyList()))
    private val unit = MutableStateFlow(WeightUnit.KG)
    private val selection = MutableStateFlow(ProgressSelection(savedState["progress_exercise"],
        savedState.get<String>("progress_metric")?.let(ProgressMetric::valueOf) ?: ProgressMetric.BEST_WEIGHT,
        savedState.get<String>("progress_range")?.let(ProgressRange::valueOf) ?: ProgressRange.ALL))
    private val index = source.map { data ->
        val completed = data.workouts.filter { it.workout.endTime != null }
            .sortedWith(compareBy<WorkoutDetail> { it.workout.startTime }.thenBy { it.workout.id })
        val exercises = (data.exercises + completed.flatMap { it.exercises }.map { it.exercise }).distinctBy { it.id }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val sessions = linkedMapOf<Long, MutableList<PerformanceSession>>()
        var latest: Long? = null
        completed.forEach { workout ->
            var firstPerformed: Long? = null
            workout.sortedExercises.forEach { entry ->
                val sets = entry.sortedSets.filter { it.completed }
                val best = ProgressMetrics.bestSet(sets, entry.exercise.exerciseType)
                if(best != null) {
                    sessions.getOrPut(entry.exercise.id) { mutableListOf() } += PerformanceSession(workout.workout.id, workout.workout.startTime, sets, best)
                    if(firstPerformed == null) firstPerformed = entry.exercise.id
                }
            }
            if(firstPerformed != null) latest = firstPerformed
        }
        ProgressIndex(exercises, sessions, latest)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressIndex())

    val state = combine(index, selection, unit) { data, chosen, weightUnit ->
        val exercise = data.exercises.firstOrNull { it.id == chosen.exerciseId }
            ?: data.exercises.firstOrNull { it.id == data.latestExerciseId } ?: data.exercises.firstOrNull()
        val metrics = exercise?.let { ProgressMetrics.availableMetrics(it.exerciseType) }.orEmpty()
        val metric = if(chosen.metric in metrics) chosen.metric else metrics.firstOrNull() ?: ProgressMetric.BEST_WEIGHT
        val stats = exercise?.let { ProgressMetrics.calculate(it.exerciseType, data.sessions[it.id].orEmpty(), metric, chosen.range, clock(), zone) } ?: ProgressStats()
        val isWeight = metric in listOf(ProgressMetric.BEST_WEIGHT, ProgressMetric.ESTIMATED_1RM, ProgressMetric.VOLUME)
        val chart = if(isWeight) stats.points.map { it.copy(value = weightUnit.fromKg(it.value)) } else stats.points
        ProgressUiState(data.exercises, exercise, metric, chosen.range, metrics, stats, chart, weightUnit)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState(range = selection.value.range))

    fun setData(exercises: List<Exercise>, workouts: List<WorkoutDetail>, weightUnit: WeightUnit) {
        source.value = ProgressSource(exercises, workouts)
        unit.value = weightUnit
    }
    fun selectExercise(id: Long) = update(selection.value.copy(exerciseId = id))
    fun selectMetric(metric: ProgressMetric) = update(selection.value.copy(metric = metric))
    fun selectRange(range: ProgressRange) = update(selection.value.copy(range = range))
    fun resetSelection() = update(ProgressSelection(null, ProgressMetric.BEST_WEIGHT, ProgressRange.ALL))
    private fun update(next: ProgressSelection) {
        savedState["progress_exercise"] = next.exerciseId
        savedState["progress_metric"] = next.metric.name
        savedState["progress_range"] = next.range.name
        selection.value = next
    }
}

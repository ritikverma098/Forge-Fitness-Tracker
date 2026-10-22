package com.forge.workout.utils

import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutSet
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class ProgressMetric { BEST_WEIGHT, ESTIMATED_1RM, BEST_REPS, VOLUME, FREQUENCY, DURATION, DISTANCE }
enum class ProgressRange {
    ONE_MONTH, THREE_MONTHS, SIX_MONTHS, ONE_YEAR, ALL;
    fun fromDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when(this) {
            ONE_MONTH -> today.minusMonths(1)
            THREE_MONTHS -> today.minusMonths(3)
            SIX_MONTHS -> today.minusMonths(6)
            ONE_YEAR -> today.minusYears(1)
            ALL -> null
        }
    }
}
data class MetricPoint(val date: Long, val value: Double)
data class PerformanceSession(val workoutId: Long, val date: Long, val sets: List<WorkoutSet>, val bestSet: WorkoutSet)
data class ProgressStats(
    val sessions: List<PerformanceSession> = emptyList(),
    val points: List<MetricPoint> = emptyList(),
    val bestSet: WorkoutSet? = null,
    val bestWeightKg: Double = 0.0,
    val estimatedOneRepMaxKg: Double = 0.0,
    val maxReps: Int = 0,
    val totalVolumeKg: Double = 0.0,
    val workoutCount: Int = 0,
    val bestDurationSeconds: Int = 0,
    val bestDistanceKm: Double = 0.0,
    val metricPeak: Double = 0.0,
)

object ProgressMetrics {
    fun availableMetrics(type: ExerciseType): List<ProgressMetric> = when(type) {
        ExerciseType.WEIGHT_REPS -> listOf(ProgressMetric.BEST_WEIGHT, ProgressMetric.ESTIMATED_1RM,
            ProgressMetric.BEST_REPS, ProgressMetric.VOLUME, ProgressMetric.FREQUENCY)
        ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY -> listOf(ProgressMetric.BEST_REPS, ProgressMetric.FREQUENCY)
        ExerciseType.DURATION -> listOf(ProgressMetric.DURATION, ProgressMetric.FREQUENCY)
        ExerciseType.DISTANCE -> listOf(ProgressMetric.DISTANCE, ProgressMetric.FREQUENCY)
    }

    /** Epley: weight × (1 + repetitions / 30). This is an estimate, never a recorded lift. */
    fun estimateOneRepMax(set: WorkoutSet, type: ExerciseType): Double? {
        if(type != ExerciseType.WEIGHT_REPS || !set.completed || !set.weight.isFinite() || set.weight <= 0 || set.reps <= 0) return null
        return (set.weight * (1 + set.reps / 30.0)).takeIf { it.isFinite() }
    }

    fun sessions(exerciseId: Long, workouts: List<WorkoutDetail>): List<PerformanceSession> = workouts.asSequence()
        .filter { it.workout.endTime != null }
        .sortedWith(compareBy<WorkoutDetail> { it.workout.startTime }.thenBy { it.workout.id })
        .mapNotNull { workout ->
            val matching = workout.exercises.filter { it.exercise.id == exerciseId }
            val sets = matching.flatMap { it.sets }.filter { it.completed }
            val type = matching.firstOrNull()?.exercise?.exerciseType ?: return@mapNotNull null
            val best = bestSet(sets, type) ?: return@mapNotNull null
            PerformanceSession(workout.workout.id, workout.workout.startTime, sets, best)
        }.toList()

    fun calculate(type: ExerciseType, sessions: List<PerformanceSession>, metric: ProgressMetric,
        range: ProgressRange, now: Long, zone: ZoneId = ZoneId.systemDefault()): ProgressStats {
        require(metric in availableMetrics(type))
        val from = range.fromDate(now, zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val selected = sessions.filter { session ->
            val date = Instant.ofEpochMilli(session.date).atZone(zone).toLocalDate()
            from == null || date in from..today
        }.mapNotNull { session ->
            val completed = session.sets.filter { it.completed }
            val best = bestSet(completed, type) ?: return@mapNotNull null
            if(completed.size == session.sets.size) session else session.copy(sets = completed, bestSet = best)
        }.sortedWith(compareBy<PerformanceSession> { it.date }.thenBy { it.workoutId })
        val sets = selected.flatMap { it.sets }.filter { it.completed }
        val points = if(metric == ProgressMetric.FREQUENCY) frequencyPoints(selected, from, today, zone) else selected.map { session ->
            MetricPoint(session.date, when(metric) {
                ProgressMetric.BEST_WEIGHT -> session.sets.maxOfOrNull { it.weight } ?: 0.0
                ProgressMetric.ESTIMATED_1RM -> session.sets.mapNotNull { estimateOneRepMax(it, type) }.maxOrNull() ?: 0.0
                ProgressMetric.BEST_REPS -> session.sets.maxOfOrNull { it.reps }?.toDouble() ?: 0.0
                ProgressMetric.VOLUME -> session.sets.sumOf { it.weight * it.reps }
                ProgressMetric.DURATION -> session.sets.maxOfOrNull { it.duration }?.toDouble() ?: 0.0
                ProgressMetric.DISTANCE -> session.sets.maxOfOrNull { it.distance } ?: 0.0
                ProgressMetric.FREQUENCY -> error("Frequency is grouped by calendar week")
            })
        }
        return ProgressStats(selected, points, bestSet(sets, type),
            if(type == ExerciseType.WEIGHT_REPS) sets.maxOfOrNull { it.weight } ?: 0.0 else 0.0,
            sets.mapNotNull { estimateOneRepMax(it, type) }.maxOrNull() ?: 0.0,
            sets.maxOfOrNull { it.reps } ?: 0,
            if(type == ExerciseType.WEIGHT_REPS) sets.sumOf { it.weight * it.reps } else 0.0,
            selected.size, sets.maxOfOrNull { it.duration } ?: 0, sets.maxOfOrNull { it.distance } ?: 0.0,
            points.maxOfOrNull { it.value } ?: 0.0)
    }

    internal fun bestSet(sets: List<WorkoutSet>, type: ExerciseType): WorkoutSet? = when(type) {
        ExerciseType.WEIGHT_REPS -> sets.maxWithOrNull(compareBy<WorkoutSet> { it.weight }.thenBy { it.reps })
        ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY -> sets.maxByOrNull { it.reps }
        ExerciseType.DURATION -> sets.maxByOrNull { it.duration }
        ExerciseType.DISTANCE -> sets.maxByOrNull { it.distance }
    }

    private fun frequencyPoints(sessions: List<PerformanceSession>, from: LocalDate?, today: LocalDate, zone: ZoneId): List<MetricPoint> {
        if(sessions.isEmpty()) return emptyList()
        fun monday(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val counts = sessions.groupingBy { monday(Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate()) }.eachCount()
        val first = monday(from ?: counts.keys.min())
        val last = monday(maxOf(today, counts.keys.max()))
        val weeks = ChronoUnit.WEEKS.between(first, last)
        // Malformed imported dates must not create an unbounded list of empty weeks.
        val dates = if(weeks in 0..10_000) List(weeks.toInt() + 1) { first.plusWeeks(it.toLong()) } else counts.keys.sorted()
        return dates.map { MetricPoint(it.atStartOfDay(zone).toInstant().toEpochMilli(), (counts[it] ?: 0).toDouble()) }
    }
}

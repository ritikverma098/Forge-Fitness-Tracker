package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProgressMetricsTest {
    @Test fun `available metrics match each exercise tracking type`() {
        assertEquals(setOf(ProgressMetric.BEST_WEIGHT, ProgressMetric.ESTIMATED_1RM, ProgressMetric.BEST_REPS,
            ProgressMetric.VOLUME, ProgressMetric.FREQUENCY), ProgressMetrics.availableMetrics(ExerciseType.WEIGHT_REPS).toSet())
        for (type in listOf(ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY)) {
            assertEquals(setOf(ProgressMetric.BEST_REPS, ProgressMetric.FREQUENCY), ProgressMetrics.availableMetrics(type).toSet())
        }
        assertEquals(setOf(ProgressMetric.DURATION, ProgressMetric.FREQUENCY), ProgressMetrics.availableMetrics(ExerciseType.DURATION).toSet())
        assertEquals(setOf(ProgressMetric.DISTANCE, ProgressMetric.FREQUENCY), ProgressMetrics.availableMetrics(ExerciseType.DISTANCE).toSet())
        ExerciseType.entries.forEach { type ->
            val metrics = ProgressMetrics.availableMetrics(type)
            assertEquals(metrics.size, metrics.distinct().size)
        }
    }

    @Test fun `sessions ignore active workouts incomplete sets and different exercise IDs`() {
        val first = workout(1, "2026-03-01T12:00:00Z", listOf(set(60.0, 10), set(999.0, 99, completed = false)))
        val second = workout(2, "2026-03-02T12:00:00Z", listOf(set(70.0, 8)))
        val tied = workout(3, "2026-03-02T12:00:00Z", listOf(set(75.0, 5)))
        val active = workout(4, "2026-03-03T12:00:00Z", listOf(set(1000.0, 50)), completed = false)
        val pending = workout(5, "2026-03-04T12:00:00Z", listOf(set(1000.0, 50, completed = false)))
        val other = workout(6, "2026-03-05T12:00:00Z", listOf(set(2000.0, 50)), exerciseId = 2)
        val input = listOf(other, tied, pending, second, active, first)
        val snapshot = input.toList()
        val sessions = ProgressMetrics.sessions(1, input)
        assertEquals(listOf(1L, 2L, 3L), sessions.map { it.workoutId })
        assertEquals(listOf(first.workout.startTime, second.workout.startTime, tied.workout.startTime), sessions.map { it.date })
        assertTrue(sessions.flatMap { it.sets }.all { it.completed })
        assertEquals(listOf(60.0, 70.0, 75.0), sessions.map { it.bestSet.weight })
        assertEquals(snapshot, input)
    }

    @Test fun `same exercise appearing twice in one workout contributes one session`() {
        val original = workout(1, "2026-03-01T12:00:00Z", listOf(set(60.0, 10)))
        val duplicate = original.exercises.single().copy(
            entry = original.exercises.single().entry.copy(id = 102, orderIndex = 1),
            sets = listOf(set(70.0, 8).copy(workoutExerciseId = 102)),
        )
        val sessions = ProgressMetrics.sessions(1, listOf(original.copy(exercises = original.exercises + duplicate)))
        assertEquals(1, sessions.size)
        assertEquals(2, sessions.single().sets.size)
        assertEquals(70.0, sessions.single().bestSet.weight, EPSILON)
    }

    @Test fun `weighted metrics count all set classifications and preserve canonical numeric values`() {
        val sessions = weightedSessions()
        val expected = mapOf(
            ProgressMetric.BEST_WEIGHT to listOf(70.0, 90.0),
            ProgressMetric.ESTIMATED_1RM to listOf(70.0 * (1 + 8.0 / 30), 80.0 * (1 + 10.0 / 30)),
            ProgressMetric.BEST_REPS to listOf(10.0, 10.0),
            ProgressMetric.VOLUME to listOf(1160.0, 1070.0),
        )
        expected.forEach { (metric, values) ->
            val stats = calculate(ExerciseType.WEIGHT_REPS, sessions, metric)
            assertEquals(2, stats.workoutCount)
            assertEquals(sessions, stats.sessions)
            assertValues(values, stats.points.map { it.value })
            assertEquals(values.max(), stats.metricPeak, EPSILON)
            assertEquals(90.0, stats.bestWeightKg, EPSILON)
            assertEquals(80.0 * (1 + 10.0 / 30), stats.estimatedOneRepMaxKg, EPSILON)
            assertEquals(10, stats.maxReps)
            assertEquals(2230.0, stats.totalVolumeKg, EPSILON)
            assertEquals(90.0, stats.bestSet!!.weight, EPSILON)
        }
        assertEquals(SetType.entries.toSet(), sessions.flatMap { it.sets }.map { it.setType }.toSet())
    }

    @Test fun `bodyweight and reps only choose highest repetitions without weighted estimates or volume`() {
        for (type in listOf(ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY)) {
            val sessions = sessions(type, listOf(set(0.0, 8), set(0.0, 14)), listOf(set(0.0, 12)))
            val stats = calculate(type, sessions, ProgressMetric.BEST_REPS)
            assertValues(listOf(14.0, 12.0), stats.points.map { it.value })
            assertEquals(14, stats.bestSet!!.reps)
            assertEquals(14, stats.maxReps)
            assertEquals(0.0, stats.estimatedOneRepMaxKg, EPSILON)
            assertEquals(0.0, stats.totalVolumeKg, EPSILON)
        }
    }

    @Test fun `duration and distance metrics select the best relevant value in each session`() {
        val durationSessions = sessions(ExerciseType.DURATION,
            listOf(set().copy(duration = 30), set().copy(duration = 60)), listOf(set().copy(duration = 90)))
        val duration = calculate(ExerciseType.DURATION, durationSessions, ProgressMetric.DURATION)
        assertValues(listOf(60.0, 90.0), duration.points.map { it.value })
        assertEquals(90, duration.bestDurationSeconds)
        assertEquals(90, duration.bestSet!!.duration)
        assertEquals(0.0, duration.totalVolumeKg, EPSILON)

        val distanceSessions = sessions(ExerciseType.DISTANCE,
            listOf(set().copy(distance = 1.25), set().copy(distance = 2.5)), listOf(set().copy(distance = 5.25)))
        val distance = calculate(ExerciseType.DISTANCE, distanceSessions, ProgressMetric.DISTANCE)
        assertValues(listOf(2.5, 5.25), distance.points.map { it.value })
        assertEquals(5.25, distance.bestDistanceKm, EPSILON)
        assertEquals(5.25, distance.bestSet!!.distance, EPSILON)
        assertEquals(0.0, distance.totalVolumeKg, EPSILON)
    }

    @Test fun `Epley estimate applies only to finite positive completed weighted sets`() {
        assertEquals(100.0, ProgressMetrics.estimateOneRepMax(set(75.0, 10), ExerciseType.WEIGHT_REPS)!!, EPSILON)
        assertEquals(12.375 * (1 + 7.0 / 30), ProgressMetrics.estimateOneRepMax(set(12.375, 7), ExerciseType.WEIGHT_REPS)!!, EPSILON)
        for (type in ExerciseType.entries.filter { it != ExerciseType.WEIGHT_REPS }) {
            assertNull(ProgressMetrics.estimateOneRepMax(set(75.0, 10), type))
        }
        val invalid = listOf(set(75.0, 10, completed = false), set(0.0, 10), set(-1.0, 10),
            set(Double.NaN, 10), set(Double.POSITIVE_INFINITY, 10), set(Double.NEGATIVE_INFINITY, 10), set(Double.MAX_VALUE, 30),
            set(75.0, 0), set(75.0, -1))
        invalid.forEach { assertNull(ProgressMetrics.estimateOneRepMax(it, ExerciseType.WEIGHT_REPS)) }
    }

    @Test fun `calendar ranges clamp month and leap year boundaries instead of subtracting fixed days`() {
        val march31 = time("2024-03-31T12:00:00Z")
        assertEquals(LocalDate.of(2024, 2, 29), ProgressRange.ONE_MONTH.fromDate(march31, UTC))
        assertEquals(LocalDate.of(2023, 12, 31), ProgressRange.THREE_MONTHS.fromDate(march31, UTC))
        assertEquals(LocalDate.of(2023, 9, 30), ProgressRange.SIX_MONTHS.fromDate(march31, UTC))
        assertEquals(LocalDate.of(2023, 3, 31), ProgressRange.ONE_YEAR.fromDate(march31, UTC))
        assertNull(ProgressRange.ALL.fromDate(march31, UTC))
        assertEquals(LocalDate.of(2023, 2, 28), ProgressRange.ONE_YEAR.fromDate(time("2024-02-29T12:00:00Z"), UTC))
        assertEquals(LocalDate.of(2024, 3, 1), ProgressRange.ONE_MONTH.fromDate(time("2024-03-31T12:00:00Z"), ZoneId.of("Pacific/Kiritimati")))
    }

    @Test fun `every bounded range includes whole local start and today dates and excludes outside days`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = time("2026-03-31T16:00:00Z")
        val today = LocalDate.of(2026, 4, 1)
        for (range in ProgressRange.entries.filter { it != ProgressRange.ALL }) {
            val from = range.fromDate(now, zone)!!
            val data = listOf(
                workout(1, from.atStartOfDay(zone).toInstant().minusMillis(1).toEpochMilli(), listOf(set(1000.0, 10))),
                workout(2, from.atStartOfDay(zone).toInstant().toEpochMilli(), listOf(set(60.0, 10))),
                workout(3, from.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), listOf(set(65.0, 10))),
                workout(4, today.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1).toEpochMilli(), listOf(set(70.0, 10))),
                workout(5, today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), listOf(set(2000.0, 10))),
            )
            val stats = ProgressMetrics.calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.BEST_WEIGHT, range, now, zone)
            assertEquals(range.name, listOf(2L, 3L, 4L), stats.sessions.map { it.workoutId })
            assertValues(listOf(60.0, 65.0, 70.0), stats.points.map { it.value })
            assertEquals(70.0, stats.bestWeightKg, EPSILON)
        }
    }

    @Test fun `all range includes every recorded date without imposing today cutoff`() {
        val data = listOf(workout(1, "2020-01-01T00:00:00Z", listOf(set(60.0, 10))),
            workout(2, "2030-01-01T00:00:00Z", listOf(set(70.0, 10))))
        val stats = calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.BEST_WEIGHT)
        assertEquals(listOf(1L, 2L), stats.sessions.map { it.workoutId })
        assertValues(listOf(60.0, 70.0), stats.points.map { it.value })
    }

    @Test fun `range filtering respects DST days including both repeated hours`() {
        val zone = ZoneId.of("America/New_York")
        val data = listOf(
            workout(1, "2026-10-01T03:59:59Z", listOf(set(999.0, 10))),
            workout(2, "2026-10-01T04:00:00Z", listOf(set(50.0, 10))),
            workout(3, "2026-11-01T05:30:00Z", listOf(set(60.0, 10))),
            workout(4, "2026-11-01T06:30:00Z", listOf(set(70.0, 10))),
            workout(5, "2026-11-02T04:59:59Z", listOf(set(80.0, 10))),
            workout(6, "2026-11-02T05:00:00Z", listOf(set(999.0, 10))),
        )
        val stats = ProgressMetrics.calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.BEST_WEIGHT,
            ProgressRange.ONE_MONTH, time("2026-11-01T16:00:00Z"), zone)
        assertEquals(listOf(2L, 3L, 4L, 5L), stats.sessions.map { it.workoutId })
        assertValues(listOf(50.0, 60.0, 70.0, 80.0), stats.points.map { it.value })
    }

    @Test fun `frequency counts sessions rather than sets and fills missing Monday weeks`() {
        val data = listOf(
            workout(1, "2026-03-02T12:00:00Z", listOf(set(60.0, 10), set(60.0, 8))),
            workout(2, "2026-03-08T23:59:59Z", listOf(set(65.0, 10))),
            workout(3, "2026-03-16T00:00:00Z", listOf(set(70.0, 10))),
        )
        val stats = calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.FREQUENCY)
        assertValues(listOf(2.0, 0.0, 1.0, 0.0, 0.0), stats.points.map { it.value })
        assertEquals(listOf("2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23", "2026-03-30"),
            stats.points.map { Instant.ofEpochMilli(it.date).atZone(UTC).toLocalDate().toString() })
        assertEquals(3, stats.workoutCount)
        assertEquals(2.0, stats.metricPeak, EPSILON)
    }

    @Test fun `bounded frequency includes weeks from range start through current week`() {
        val now = time("2026-03-31T12:00:00Z")
        val data = listOf(workout(1, "2026-03-10T12:00:00Z", listOf(set(60.0, 10))))
        val stats = ProgressMetrics.calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.FREQUENCY,
            ProgressRange.ONE_MONTH, now, UTC)
        assertEquals(listOf("2026-02-23", "2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23", "2026-03-30"),
            stats.points.map { Instant.ofEpochMilli(it.date).atZone(UTC).toLocalDate().toString() })
        assertValues(listOf(0.0, 0.0, 1.0, 0.0, 0.0, 0.0), stats.points.map { it.value })
    }

    @Test fun `frequency buckets use local Monday across UTC and DST boundaries`() {
        val zone = ZoneId.of("America/New_York")
        val data = listOf(workout(1, "2026-03-09T03:59:59Z", listOf(set(60.0, 10))),
            workout(2, "2026-03-09T04:00:00Z", listOf(set(60.0, 10))))
        val stats = ProgressMetrics.calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.FREQUENCY,
            ProgressRange.ALL, time("2026-03-09T12:00:00Z"), zone)
        assertValues(listOf(1.0, 1.0), stats.points.map { it.value })
        assertEquals(listOf("2026-03-02T05:00:00Z", "2026-03-09T04:00:00Z"), stats.points.map { Instant.ofEpochMilli(it.date).toString() })
    }

    @Test fun `empty history is safe for every available metric and range`() {
        assertTrue(ProgressMetrics.sessions(1, emptyList()).isEmpty())
        ExerciseType.entries.forEach { type -> ProgressMetrics.availableMetrics(type).forEach { metric ->
            ProgressRange.entries.forEach { range ->
                val stats = ProgressMetrics.calculate(type, emptyList(), metric, range, NOW, UTC)
                assertTrue(stats.sessions.isEmpty())
                assertEquals(0, stats.workoutCount)
                assertNull(stats.bestSet)
                assertEquals(0.0, stats.bestWeightKg, EPSILON)
                assertEquals(0.0, stats.estimatedOneRepMaxKg, EPSILON)
                assertEquals(0.0, stats.totalVolumeKg, EPSILON)
                assertEquals(0.0, stats.metricPeak, EPSILON)
            }
        } }
    }

    @Test fun `single completed session produces usable metric points without inventing extra workouts`() {
        val sessions = ProgressMetrics.sessions(1, listOf(workout(1, "2026-03-03T12:00:00Z", listOf(set(62.5, 5)))))
        ProgressMetrics.availableMetrics(ExerciseType.WEIGHT_REPS).forEach { metric ->
            val stats = ProgressMetrics.calculate(ExerciseType.WEIGHT_REPS, sessions, metric, ProgressRange.ALL, sessions.single().date, UTC)
            assertEquals(1, stats.workoutCount)
            assertEquals(1, stats.points.size)
            assertTrue(stats.points.single().value > 0)
        }
    }

    @Test fun `thousand session history has exact totals and ordered points without dropping observations`() {
        val today = LocalDate.of(2026, 3, 31)
        val data = (1..1000).map { index -> workout(index.toLong(), today.minusDays((1000 - index).toLong()).atStartOfDay(UTC).toInstant().toEpochMilli(),
            listOf(set(index.toDouble(), 10))) }.reversed()
        val sessions = ProgressMetrics.sessions(1, data)
        val stats = calculate(ExerciseType.WEIGHT_REPS, sessions, ProgressMetric.VOLUME)
        assertEquals(1000, stats.workoutCount)
        assertEquals(1000, stats.points.size)
        assertEquals(1000.0, stats.bestWeightKg, EPSILON)
        assertEquals(5_005_000.0, stats.totalVolumeKg, EPSILON)
        assertEquals(10.0, stats.points.first().value, EPSILON)
        assertEquals(10_000.0, stats.points.last().value, EPSILON)
        assertTrue(stats.points.zipWithNext().all { (first, next) -> first.date <= next.date })
        val frequency = calculate(ExerciseType.WEIGHT_REPS, sessions, ProgressMetric.FREQUENCY)
        assertEquals(1000.0, frequency.points.sumOf { it.value }, EPSILON)
        assertTrue(frequency.points.all { it.value in 1.0..7.0 })
    }

    @Test fun `huge historical gap uses observed weeks without unbounded zero filling`() {
        val data = listOf(workout(1, "1000-01-01T12:00:00Z", listOf(set(60.0, 10))),
            workout(2, "2026-03-01T12:00:00Z", listOf(set(70.0, 10))))
        val stats = calculate(ExerciseType.WEIGHT_REPS, ProgressMetrics.sessions(1, data), ProgressMetric.FREQUENCY)
        assertEquals(2, stats.points.size)
        assertValues(listOf(1.0, 1.0), stats.points.map { it.value })
    }

    @Test fun `calculate sanitizes directly supplied sessions with incomplete sets and orders them`() {
        val first = workout(1, "2026-03-01T12:00:00Z", listOf(set(60.0, 10)))
        val second = workout(2, "2026-03-02T12:00:00Z", listOf(set(70.0, 8)))
        val source = ProgressMetrics.sessions(1, listOf(first, second))
        val pending = set(999.0, 99, completed = false)
        val manuallySupplied = listOf(source[1].copy(sets = source[1].sets + pending, bestSet = pending),
            source[0], source[0].copy(workoutId = 3, sets = listOf(pending), bestSet = pending))
        val stats = calculate(ExerciseType.WEIGHT_REPS, manuallySupplied, ProgressMetric.BEST_WEIGHT)
        assertEquals(listOf(1L, 2L), stats.sessions.map { it.workoutId })
        assertTrue(stats.sessions.flatMap { it.sets }.all { it.completed })
        assertEquals(70.0, stats.sessions.last().bestSet.weight, EPSILON)
        assertEquals(1160.0, stats.totalVolumeKg, EPSILON)
        assertValues(listOf(60.0, 70.0), stats.points.map { it.value })
        assertEquals(2, manuallySupplied.first().sets.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `metrics inapplicable to an exercise type are rejected`() {
        ProgressMetrics.calculate(ExerciseType.DURATION, emptyList(), ProgressMetric.ESTIMATED_1RM, ProgressRange.ALL, NOW, UTC)
    }

    private fun weightedSessions() = sessions(ExerciseType.WEIGHT_REPS,
        listOf(set(60.0, 10).copy(setType = SetType.WORKING), set(70.0, 8).copy(setType = SetType.WARM_UP)),
        listOf(set(80.0, 10).copy(setType = SetType.DROP_SET), set(90.0, 3).copy(setType = SetType.FAILURE)))

    private fun sessions(type: ExerciseType, first: List<WorkoutSet>, second: List<WorkoutSet>) = ProgressMetrics.sessions(1, listOf(
        workout(1, "2026-03-01T12:00:00Z", first, type), workout(2, "2026-03-15T12:00:00Z", second, type)))

    private fun calculate(type: ExerciseType, sessions: List<PerformanceSession>, metric: ProgressMetric) =
        ProgressMetrics.calculate(type, sessions, metric, ProgressRange.ALL, NOW, UTC)

    private fun set(weight: Double = 0.0, reps: Int = 0, completed: Boolean = true) =
        WorkoutSet(workoutExerciseId = 1, setNumber = 1, weight = weight, reps = reps, completed = completed)

    private fun workout(id: Long, instant: String, sets: List<WorkoutSet>, type: ExerciseType = ExerciseType.WEIGHT_REPS,
        completed: Boolean = true, exerciseId: Long = 1) = workout(id, time(instant), sets, type, completed, exerciseId)

    private fun workout(id: Long, time: Long, sets: List<WorkoutSet>, type: ExerciseType = ExerciseType.WEIGHT_REPS,
        completed: Boolean = true, exerciseId: Long = 1): WorkoutDetail {
        val entryId = id * 100
        return WorkoutDetail(Workout(id = id, name = "Session $id", startTime = time, endTime = if (completed) time + 1000 else null),
            listOf(WorkoutExerciseDetail(WorkoutExercise(id = entryId, workoutId = id, exerciseId = exerciseId, orderIndex = 0),
                Exercise(id = exerciseId, name = "Exercise $exerciseId", primaryMuscleGroup = MuscleGroup.OTHER, exerciseType = type),
                sets.mapIndexed { index, set -> set.copy(id = entryId + index, workoutExerciseId = entryId, setNumber = index + 1) })))
    }

    private fun assertValues(expected: List<Double>, actual: List<Double>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (value, measured) -> assertEquals(value, measured, EPSILON) }
    }

    private fun time(instant: String) = Instant.parse(instant).toEpochMilli()

    private companion object {
        const val EPSILON = 0.000001
        val UTC: ZoneId = ZoneId.of("UTC")
        val NOW: Long = Instant.parse("2026-03-31T12:00:00Z").toEpochMilli()
    }
}

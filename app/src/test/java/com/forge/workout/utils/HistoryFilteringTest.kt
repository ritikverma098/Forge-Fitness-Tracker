package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoryFilteringTest {
    private val bench = Exercise(id = 101, name = "Bench Press", primaryMuscleGroup = MuscleGroup.CHEST, secondaryMuscleGroup = MuscleGroup.TRICEPS)
    private val squat = Exercise(id = 102, name = "Squat", primaryMuscleGroup = MuscleGroup.LEGS, secondaryMuscleGroup = MuscleGroup.GLUTES)
    private val row = Exercise(id = 103, name = "Archived Row", primaryMuscleGroup = MuscleGroup.BACK, secondaryMuscleGroup = MuscleGroup.BICEPS, archived = true)
    private val source = listOf(
        workout(1, "Push day", "2026-03-08T23:59:59Z", listOf(bench)),
        workout(2, "Legs", "2026-03-09T00:00:00Z", listOf(squat)),
        workout(3, "Upper", "2026-03-09T12:00:00Z", listOf(bench, row), groups = listOf(MuscleGroup.CORE)),
        workout(4, "PUSH recovery", "2026-03-10T04:00:00Z", listOf(row), groups = listOf(MuscleGroup.CHEST)),
        workout(5, "Push active", "2026-03-11T12:00:00Z", listOf(bench), completed = false),
    )

    @Test fun `no filters returns completed history in descending time and ID order without changing source`() {
        val original = source.toList()
        assertEquals(listOf(4L, 3L, 2L, 1L), ids(HistoryFilters()))
        assertEquals(original, source)
        val tied = listOf(workout(6, "First", "2026-03-09T12:00:00Z"), workout(7, "Second", "2026-03-09T12:00:00Z"))
        assertEquals(listOf(7L, 6L), HistoryFiltering.filter(tied, HistoryFilters(), UTC).map { it.workout.id })
    }

    @Test fun `name matching trims search and ignores case while excluding active workouts`() {
        assertEquals(listOf(4L, 1L), ids(HistoryFilters(name = "  PuSh  ")))
        assertEquals(listOf(4L, 3L, 2L, 1L), ids(HistoryFilters(name = "  ")))
        assertTrue(ids(HistoryFilters(name = "missing")).isEmpty())
    }

    @Test fun `exercise selection matches IDs including archived history and never exercise name fragments`() {
        assertEquals(listOf(3L, 1L), ids(HistoryFilters(exerciseId = bench.id)))
        assertEquals(listOf(4L, 3L), ids(HistoryFilters(exerciseId = row.id)))
        assertTrue(ids(HistoryFilters(exerciseId = 999)).isEmpty())
    }

    @Test fun `muscle matches workout tags or any primary or secondary exercise group`() {
        assertEquals(listOf(4L, 3L, 1L), ids(HistoryFilters(muscleGroup = MuscleGroup.CHEST)))
        assertEquals(listOf(3L, 1L), ids(HistoryFilters(muscleGroup = MuscleGroup.TRICEPS)))
        assertEquals(listOf(3L), ids(HistoryFilters(muscleGroup = MuscleGroup.CORE)))
        assertEquals(listOf(2L), ids(HistoryFilters(muscleGroup = MuscleGroup.GLUTES)))
        assertEquals(listOf(4L, 3L), ids(HistoryFilters(muscleGroup = MuscleGroup.BICEPS)))
    }

    @Test fun `name exercise muscle and date conditions are combined with AND`() {
        val day = LocalDate.of(2026, 3, 9)
        val filters = HistoryFilters(name = " upper ", exerciseId = bench.id, muscleGroup = MuscleGroup.CORE, fromDate = day, toDate = day)
        assertEquals(listOf(3L), ids(filters))
        assertTrue(ids(filters.copy(exerciseId = squat.id)).isEmpty())
        assertTrue(ids(filters.copy(muscleGroup = MuscleGroup.LEGS)).isEmpty())
        assertTrue(ids(filters.copy(fromDate = day.plusDays(1), toDate = day.plusDays(1))).isEmpty())
    }

    @Test fun `date limits are inclusive local calendar dates with independent open bounds`() {
        val zone = ZoneId.of("Asia/Tokyo")
        val day = LocalDate.of(2026, 3, 9)
        val dates = listOf(
            workout(11, "Before", "2026-03-08T14:59:59Z"), workout(12, "Start", "2026-03-08T15:00:00Z"),
            workout(13, "End", "2026-03-09T14:59:59Z"), workout(14, "After", "2026-03-09T15:00:00Z"),
        )
        fun filtered(filters: HistoryFilters) = HistoryFiltering.filter(dates, filters, zone).map { it.workout.id }
        assertEquals(listOf(13L, 12L), filtered(HistoryFilters(fromDate = day, toDate = day)))
        assertEquals(listOf(14L, 13L, 12L), filtered(HistoryFilters(fromDate = day)))
        assertEquals(listOf(13L, 12L, 11L), filtered(HistoryFilters(toDate = day)))
    }

    @Test fun `spring DST short day includes both sides of missing hour and exact local day bounds`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 3, 8)
        val dates = listOf(
            workout(21, "Before", "2026-03-08T04:59:59Z"), workout(22, "Start", "2026-03-08T05:00:00Z"),
            workout(23, "Before jump", "2026-03-08T06:59:59Z"), workout(24, "After jump", "2026-03-08T07:00:00Z"),
            workout(25, "End", "2026-03-09T03:59:59Z"), workout(26, "After", "2026-03-09T04:00:00Z"),
        )
        assertEquals(listOf(25L, 24L, 23L, 22L), HistoryFiltering.filter(dates, HistoryFilters(fromDate = day, toDate = day), zone).map { it.workout.id })
    }

    @Test fun `fall DST long day includes both repeated hours and exact local day bounds`() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 1)
        val dates = listOf(
            workout(31, "Before", "2026-11-01T03:59:59Z"), workout(32, "Start", "2026-11-01T04:00:00Z"),
            workout(33, "First hour", "2026-11-01T05:30:00Z"), workout(34, "Repeated hour", "2026-11-01T06:30:00Z"),
            workout(35, "End", "2026-11-02T04:59:59Z"), workout(36, "After", "2026-11-02T05:00:00Z"),
        )
        assertEquals(listOf(35L, 34L, 33L, 32L), HistoryFiltering.filter(dates, HistoryFilters(fromDate = day, toDate = day), zone).map { it.workout.id })
    }

    @Test fun `has filters ignores blank search and reflects every actual filter`() {
        assertFalse(HistoryFilters().hasFilters)
        assertFalse(HistoryFilters(name = " \n ").hasFilters)
        val activeFilters = listOf(HistoryFilters(name = "Push"), HistoryFilters(exerciseId = bench.id),
            HistoryFilters(muscleGroup = MuscleGroup.CHEST), HistoryFilters(fromDate = LocalDate.of(2026, 3, 8)),
            HistoryFilters(toDate = LocalDate.of(2026, 3, 9)))
        assertTrue(activeFilters.all { it.hasFilters })
    }

    @Test fun `empty or only active history produces no results`() {
        assertTrue(HistoryFiltering.filter(emptyList(), HistoryFilters(), UTC).isEmpty())
        assertTrue(HistoryFiltering.filter(source.filter { it.workout.endTime == null }, HistoryFilters(), UTC).isEmpty())
    }

    private fun ids(filters: HistoryFilters) = HistoryFiltering.filter(source, filters, UTC).map { it.workout.id }

    private fun workout(id: Long, name: String, instant: String, exercises: List<Exercise> = emptyList(),
        groups: List<MuscleGroup> = emptyList(), completed: Boolean = true): WorkoutDetail {
        val start = Instant.parse(instant).toEpochMilli()
        return WorkoutDetail(Workout(id = id, name = name, startTime = start, endTime = if (completed) start + 1000 else null,
            muscleGroups = groups), exercises.mapIndexed { index, exercise ->
            WorkoutExerciseDetail(WorkoutExercise(id = id * 100 + index, workoutId = id, exerciseId = exercise.id, orderIndex = index), exercise, emptyList())
        })
    }

    private companion object { val UTC: ZoneId = ZoneId.of("UTC") }
}

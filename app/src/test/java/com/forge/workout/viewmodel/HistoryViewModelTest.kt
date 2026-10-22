package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.utils.HistoryFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val bench = Exercise(id = 10, name = "Bench press", primaryMuscleGroup = MuscleGroup.CHEST, secondaryMuscleGroup = MuscleGroup.TRICEPS)
    private val row = Exercise(id = 20, name = "Archived row", primaryMuscleGroup = MuscleGroup.BACK, secondaryMuscleGroup = MuscleGroup.BICEPS, archived = true)
    private val squat = Exercise(id = 30, name = "Squat", primaryMuscleGroup = MuscleGroup.LEGS, secondaryMuscleGroup = MuscleGroup.GLUTES)
    private val activeOnly = Exercise(id = 40, name = "Active only", primaryMuscleGroup = MuscleGroup.CORE)
    private val source = listOf(
        workout(1, "Push", "2026-03-08T12:00:00Z", listOf(bench)),
        workout(2, "Legs", "2026-03-09T12:00:00Z", listOf(squat)),
        workout(3, "Upper", "2026-03-09T12:00:00Z", listOf(bench, row), groups = listOf(MuscleGroup.CORE)),
        workout(4, "Push active", "2026-03-10T12:00:00Z", listOf(activeOnly), completed = false),
    )

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `unfiltered state groups completed history by local date with deterministic order and unique options`() = runTest {
        val model = model()
        model.setWorkouts(source)
        val state = model.state.first { it.hasHistory && ids(it) == listOf(3L, 2L, 1L) }
        assertEquals(listOf(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 8)), state.grouped.keys.toList())
        assertEquals(listOf(20L, 10L, 30L), state.exercises.map { it.id })
        assertTrue(state.exercises.single { it.id == row.id }.archived)
        assertFalse(state.exercises.any { it.id == activeOnly.id })
        assertFalse(state.filters.hasFilters)
    }

    @Test fun `combined filters update state and clear restores original history`() = runTest {
        val model = model()
        model.setWorkouts(source)
        val day = LocalDate.of(2026, 3, 8)
        model.setName(" PUSH ")
        model.selectExercise(bench.id)
        model.selectMuscle(MuscleGroup.TRICEPS)
        model.setDateRange(day, day)
        val filters = HistoryFilters(" PUSH ", bench.id, MuscleGroup.TRICEPS, day, day)
        val filtered = model.state.first { it.filters == filters && ids(it) == listOf(1L) }
        assertTrue(filtered.filters.hasFilters)
        assertTrue(filtered.hasHistory)

        model.clearFilters()
        val cleared = model.state.first { it.filters == HistoryFilters() && ids(it) == listOf(3L, 2L, 1L) }
        assertFalse(cleared.filters.hasFilters)
        assertEquals(filtered.exercises, cleared.exercises)
    }

    @Test fun `date range permits open bounds and rejects inverted dates without replacing valid state`() = runTest {
        val model = model()
        model.setWorkouts(source)
        val start = LocalDate.of(2026, 3, 9)
        model.setDateRange(start, null)
        val lowerOnly = model.state.first { it.filters.fromDate == start && it.filters.toDate == null && ids(it) == listOf(3L, 2L) }
        try { model.setDateRange(start.plusDays(1), start); fail("Expected invalid date range to be rejected") }
        catch (_: IllegalArgumentException) { /* Invalid range must not replace the last valid filters. */ }
        assertEquals(lowerOnly.filters, model.state.value.filters)
        model.setDateRange(null, start.minusDays(1))
        val upperOnly = model.state.first { it.filters.fromDate == null && it.filters.toDate == start.minusDays(1) && ids(it) == listOf(1L) }
        assertTrue(upperOnly.filters.hasFilters)
    }

    @Test fun `SavedStateHandle reconstruction restores every filter when source history returns`() = runTest {
        val saved = SavedStateHandle()
        val original = HistoryViewModel(saved, UTC)
        original.setWorkouts(source)
        val day = LocalDate.of(2026, 3, 9)
        original.setName("Upper")
        original.selectExercise(row.id)
        original.selectMuscle(MuscleGroup.BICEPS)
        original.setDateRange(day, day)
        val expected = HistoryFilters("Upper", row.id, MuscleGroup.BICEPS, day, day)
        val before = original.state.first { it.filters == expected && ids(it) == listOf(3L) }
        val persisted = saved.keys().associateWith { saved.get<Any?>(it) }
        assertTrue(persisted.isNotEmpty())
        val recreated = HistoryViewModel(SavedStateHandle(persisted), UTC)
        recreated.setWorkouts(source)
        val after = recreated.state.first { it.filters == expected && ids(it) == listOf(3L) }
        assertEquals(before, after)

        val renamed = source.map { detail -> detail.copy(exercises = detail.exercises.map { entry ->
            if (entry.exercise.id == row.id) entry.copy(exercise = entry.exercise.copy(name = "Renamed row")) else entry
        }) }
        recreated.setWorkouts(renamed)
        val updated = recreated.state.first { it.exercises.any { exercise -> exercise.id == row.id && exercise.name == "Renamed row" } }
        assertEquals(expected, updated.filters)
        assertEquals(listOf(3L), ids(updated))
        assertEquals(listOf(10L, 20L, 30L), updated.exercises.map { it.id })
    }

    @Test fun `repeat return and history source changes preserve selected filters and distinguish no matches`() = runTest {
        val model = model()
        model.setWorkouts(source)
        model.setName("Push")
        model.selectExercise(bench.id)
        val expected = HistoryFilters(name = "Push", exerciseId = bench.id)
        model.state.first { it.filters == expected && ids(it) == listOf(1L) }
        val additional = Exercise(id = 50, name = "Extra completed", primaryMuscleGroup = MuscleGroup.CARDIO)
        val updatedSource = source + workout(99, "Push repeated", "2026-03-10T12:00:00Z", listOf(activeOnly), completed = false) +
            workout(50, "Unrelated completed", "2026-03-10T13:00:00Z", listOf(additional))
        model.setWorkouts(updatedSource)
        val returned = model.state.first { it.exercises.any { exercise -> exercise.id == additional.id } }
        assertEquals(expected, returned.filters)
        assertEquals(listOf(1L), ids(returned))
        assertFalse(returned.exercises.any { it.id == activeOnly.id })

        val importedExercise = Exercise(id = 70, name = "Imported movement", primaryMuscleGroup = MuscleGroup.CHEST)
        model.setWorkouts(listOf(workout(60, "Push imported", "2026-03-12T12:00:00Z", listOf(importedExercise))))
        val replaced = model.state.first { it.exercises.map { exercise -> exercise.id } == listOf(importedExercise.id) }
        assertEquals(expected, replaced.filters)
        assertTrue(replaced.hasHistory)
        assertTrue(replaced.grouped.isEmpty())
        model.clearFilters()
        assertEquals(listOf(60L), ids(model.state.first { it.filters == HistoryFilters() && ids(it) == listOf(60L) }))
    }

    @Test fun `empty and only active source remain empty without completed exercise options`() = runTest {
        val model = model()
        model.setName("No match")
        model.setWorkouts(source.filter { it.workout.endTime == null })
        val state = model.state.first { it.filters.name == "No match" }
        assertFalse(state.hasHistory)
        assertTrue(state.grouped.isEmpty())
        assertTrue(state.exercises.isEmpty())
        model.setWorkouts(emptyList())
        model.clearFilters()
        val cleared = model.state.first { it.filters == HistoryFilters() }
        assertFalse(cleared.hasHistory)
        assertTrue(cleared.grouped.isEmpty())
    }

    @Test fun `grouping uses supplied local zone across UTC date boundary`() = runTest {
        val model = HistoryViewModel(SavedStateHandle(), ZoneId.of("Asia/Tokyo"))
        val localDay = LocalDate.of(2026, 3, 9)
        model.setWorkouts(listOf(workout(10, "Local morning", "2026-03-08T15:00:00Z", listOf(bench))))
        model.setDateRange(localDay, localDay)
        val state = model.state.first { it.filters.fromDate == localDay && ids(it) == listOf(10L) }
        assertEquals(listOf(localDay), state.grouped.keys.toList())
    }

    private fun model() = HistoryViewModel(SavedStateHandle(), UTC)
    private fun ids(state: HistoryUiState) = state.grouped.values.flatten().map { it.workout.id }

    private fun workout(id: Long, name: String, instant: String, exercises: List<Exercise>,
        groups: List<MuscleGroup> = emptyList(), completed: Boolean = true): WorkoutDetail {
        val start = Instant.parse(instant).toEpochMilli()
        return WorkoutDetail(Workout(id = id, name = name, startTime = start, endTime = if (completed) start + 1000 else null,
            muscleGroups = groups), exercises.mapIndexed { index, exercise ->
            WorkoutExerciseDetail(WorkoutExercise(id = id * 100 + index, workoutId = id, exerciseId = exercise.id, orderIndex = index), exercise, emptyList())
        })
    }

    private companion object { val UTC: ZoneId = ZoneId.of("UTC") }
}

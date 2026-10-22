package com.forge.workout.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.WeightUnit
import com.forge.workout.utils.ProgressMetric
import com.forge.workout.utils.ProgressRange
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModelTest {
    private val bench = exercise(1, "Bench", ExerciseType.WEIGHT_REPS)
    private val bodyweight = exercise(2, "Push up", ExerciseType.BODYWEIGHT)
    private val plank = exercise(3, "Plank", ExerciseType.DURATION)
    private val run = exercise(4, "Run", ExerciseType.DISTANCE)
    private val repsOnly = exercise(5, "Core reps", ExerciseType.REPS_ONLY)
    private val archived = exercise(6, "Archived press", ExerciseType.WEIGHT_REPS).copy(archived = true)
    private val unused = exercise(7, "Unused", ExerciseType.WEIGHT_REPS)
    private val library = listOf(bench, bodyweight, plank, run, repsOnly, unused)
    private val source = listOf(
        session(11, bench, NOW - 70 * DAY, weight = 100.0, reps = 5),
        session(12, bench, NOW - 10 * DAY, weight = 80.0, reps = 10),
        session(13, bench, NOW - 2 * DAY, weight = 85.0, reps = 8),
        session(14, bodyweight, NOW - DAY, reps = 15),
        session(15, plank, NOW - 3 * DAY, duration = 90),
        session(16, run, NOW - 4 * DAY, distance = 5.0),
        session(17, archived, NOW - 3_600_000, weight = 65.0, reps = 6),
        session(18, unused, NOW, weight = 200.0, reps = 10, finished = false),
        session(19, unused, NOW - 1_800_000, weight = 200.0, reps = 10, performed = false),
    )

    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `latest completed performed exercise is the default and archived history remains selectable`() = runTest {
        val model = model()
        model.setData(library, source, WeightUnit.KG)
        val state = model.state.first { it.selected?.id == archived.id && it.stats.workoutCount == 1 }
        assertTrue(state.exercises.single { it.id == archived.id }.archived)
        assertEquals(library.size + 1, state.exercises.size)
        assertEquals(65.0, state.stats.bestWeightKg, 0.000001)
        assertEquals(ProgressMetric.BEST_WEIGHT, state.metric)
        assertEquals(ProgressRange.ALL, state.range)
    }

    @Test fun `empty source and minimal library produce usable empty state then update with one session`() = runTest {
        val model = model()
        model.setData(emptyList(), emptyList(), WeightUnit.KG)
        val empty = model.state.first { it.selected == null && it.exercises.isEmpty() }
        assertNull(empty.stats.bestSet)
        assertEquals(0, empty.stats.workoutCount)
        assertTrue(empty.chartPoints.isEmpty())

        model.setData(listOf(unused), emptyList(), WeightUnit.KG)
        val libraryOnly = model.state.first { it.selected?.id == unused.id }
        assertTrue(libraryOnly.chartPoints.isEmpty())
        model.setData(listOf(unused), listOf(session(30, unused, NOW - DAY, weight = 40.0, reps = 12)), WeightUnit.KG)
        val oneSession = model.state.first { it.stats.workoutCount == 1 }
        assertEquals(unused.id, oneSession.selected?.id)
        assertEquals(1, oneSession.chartPoints.size)
        assertEquals(40.0, oneSession.stats.bestWeightKg, 0.000001)
    }

    @Test fun `saved selection metric and date range reconstruct against returned history`() = runTest {
        val saved = SavedStateHandle()
        val original = model(saved)
        original.setData(library, source, WeightUnit.KG)
        original.selectExercise(bench.id)
        original.selectMetric(ProgressMetric.ESTIMATED_1RM)
        original.selectRange(ProgressRange.THREE_MONTHS)
        val before = original.state.first { it.selected?.id == bench.id && it.metric == ProgressMetric.ESTIMATED_1RM &&
            it.range == ProgressRange.THREE_MONTHS && it.stats.workoutCount == 3 }
        val persisted = saved.keys().associateWith { saved.get<Any?>(it) }
        assertTrue(persisted.isNotEmpty())

        val recreated = model(SavedStateHandle(persisted))
        recreated.setData(library, source, WeightUnit.KG)
        val after = recreated.state.first { it.selected?.id == bench.id && it.metric == ProgressMetric.ESTIMATED_1RM &&
            it.range == ProgressRange.THREE_MONTHS && it.stats.workoutCount == 3 }
        assertEquals(before, after)
    }

    @Test fun `range and metric switches scope statistics without mutating source history`() = runTest {
        val model = model()
        val originalSource = source.toList()
        model.setData(library, source, WeightUnit.KG)
        model.selectExercise(bench.id)
        model.selectRange(ProgressRange.ONE_MONTH)
        val recent = model.state.first { it.selected?.id == bench.id && it.range == ProgressRange.ONE_MONTH && it.stats.workoutCount == 2 }
        assertEquals(85.0, recent.stats.bestWeightKg, 0.000001)
        assertEquals(1480.0, recent.stats.totalVolumeKg, 0.000001)
        assertEquals(listOf(12L, 13L), recent.stats.sessions.map { it.workoutId })

        model.selectMetric(ProgressMetric.VOLUME)
        val volumes = model.state.first { it.metric == ProgressMetric.VOLUME && it.range == ProgressRange.ONE_MONTH && it.chartPoints.size == 2 }
        assertEquals(listOf(800.0, 680.0), volumes.chartPoints.map { it.value })
        model.selectRange(ProgressRange.ALL)
        val all = model.state.first { it.range == ProgressRange.ALL && it.stats.workoutCount == 3 }
        assertEquals(100.0, all.stats.bestWeightKg, 0.000001)
        assertEquals(1980.0, all.stats.totalVolumeKg, 0.000001)
        assertEquals(originalSource, source)
    }

    @Test fun `only weight estimated strength and volume points convert display units`() = runTest {
        val model = model()
        model.setData(library, source, WeightUnit.KG)
        model.selectExercise(bench.id)
        model.selectRange(ProgressRange.ALL)
        val converting = listOf(ProgressMetric.BEST_WEIGHT, ProgressMetric.ESTIMATED_1RM, ProgressMetric.VOLUME)
        val unchanging = listOf(ProgressMetric.BEST_REPS, ProgressMetric.FREQUENCY)
        for (metric in converting + unchanging) {
            model.selectMetric(metric)
            model.setData(library, source, WeightUnit.KG)
            val kg = model.state.first { it.selected?.id == bench.id && it.metric == metric && it.weightUnit == WeightUnit.KG && it.stats.workoutCount == 3 }
            model.setData(library, source, WeightUnit.LB)
            val lb = model.state.first { it.selected?.id == bench.id && it.metric == metric && it.weightUnit == WeightUnit.LB && it.stats.workoutCount == 3 }
            assertEquals(kg.stats, lb.stats)
            assertEquals(kg.chartPoints.map { it.date }, lb.chartPoints.map { it.date })
            kg.chartPoints.zip(lb.chartPoints).forEach { (canonical, displayed) ->
                val expected = if (metric in converting) WeightUnit.LB.fromKg(canonical.value) else canonical.value
                assertEquals(expected, displayed.value, 0.000001)
            }
        }
        assertEquals(100.0, source.first().exercises.single().sets.single().weight, 0.000001)
    }

    @Test fun `changing exercise limits metrics to its tracking type and falls back from incompatible selection`() = runTest {
        val model = model()
        model.setData(library, source, WeightUnit.KG)
        model.selectExercise(bench.id)
        model.selectMetric(ProgressMetric.VOLUME)
        model.state.first { it.selected?.id == bench.id && it.metric == ProgressMetric.VOLUME }

        val cases = listOf(
            bodyweight to listOf(ProgressMetric.BEST_REPS, ProgressMetric.FREQUENCY),
            repsOnly to listOf(ProgressMetric.BEST_REPS, ProgressMetric.FREQUENCY),
            plank to listOf(ProgressMetric.DURATION, ProgressMetric.FREQUENCY),
            run to listOf(ProgressMetric.DISTANCE, ProgressMetric.FREQUENCY),
        )
        for ((exercise, allowed) in cases) {
            model.selectExercise(exercise.id)
            val state = model.state.first { it.selected?.id == exercise.id && it.metric in allowed }
            assertEquals(allowed.toSet(), state.availableMetrics.toSet())
            assertEquals(allowed.first(), state.metric)
            model.selectMetric(ProgressMetric.VOLUME)
            val unchanged = model.state.first { it.selected?.id == exercise.id && it.metric in allowed }
            assertEquals(allowed.first(), unchanged.metric)
        }
    }

    @Test fun `edited source refreshes selected statistics and changed restore IDs can reset selection`() = runTest {
        val model = model()
        model.setData(library, source, WeightUnit.KG)
        model.selectExercise(bench.id)
        model.selectMetric(ProgressMetric.VOLUME)
        model.selectRange(ProgressRange.ONE_MONTH)
        model.state.first { it.selected?.id == bench.id && it.metric == ProgressMetric.VOLUME && it.range == ProgressRange.ONE_MONTH }
        val edited = source.map { detail -> if (detail.workout.id == 13L) detail.copy(exercises = detail.exercises.map { entry ->
            entry.copy(sets = entry.sets.map { it.copy(weight = 90.0, reps = 10) })
        }) else detail }
        model.setData(library, edited, WeightUnit.KG)
        val updated = model.state.first { it.selected?.id == bench.id && it.stats.totalVolumeKg == 1700.0 }
        assertEquals(ProgressMetric.VOLUME, updated.metric)
        assertEquals(ProgressRange.ONE_MONTH, updated.range)

        val imported = exercise(99, "Imported duration", ExerciseType.DURATION)
        model.setData(listOf(imported), listOf(session(90, imported, NOW - DAY, duration = 120)), WeightUnit.LB)
        val restored = model.state.first { it.selected?.id == imported.id && it.stats.workoutCount == 1 }
        assertEquals(ProgressMetric.DURATION, restored.metric)
        assertEquals(120, restored.stats.bestDurationSeconds)
        model.resetSelection()
        val reset = model.state.first { it.selected?.id == imported.id && it.range == ProgressRange.ALL }
        assertEquals(ProgressMetric.DURATION, reset.metric)
        assertEquals(WeightUnit.LB, reset.weightUnit)
    }

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = ProgressViewModel(saved, clock = { NOW }, zone = UTC)

    private fun exercise(id: Long, name: String, type: ExerciseType) = Exercise(id = id, name = name,
        primaryMuscleGroup = MuscleGroup.OTHER, exerciseType = type)

    private fun session(id: Long, exercise: Exercise, date: Long, weight: Double = 0.0, reps: Int = 0,
        duration: Int = 0, distance: Double = 0.0, finished: Boolean = true, performed: Boolean = true): WorkoutDetail {
        val entryId = id * 100
        return WorkoutDetail(Workout(id = id, name = "Session $id", startTime = date, endTime = if (finished) date + 1000 else null),
            listOf(WorkoutExerciseDetail(WorkoutExercise(id = entryId, workoutId = id, exerciseId = exercise.id, orderIndex = 0), exercise,
                listOf(WorkoutSet(id = entryId + 1, workoutExerciseId = entryId, setNumber = 1, weight = weight, reps = reps,
                    duration = duration, distance = distance, completed = performed)))))
    }

    private companion object {
        const val DAY = 86_400_000L
        val NOW: Long = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}

package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutPreviousPerformanceTest {
    private val current = Workout(id = 100, name = "Current", startTime = 5_000)

    @Test fun `indexes latest applicable performance independently for every exercise`() {
        val old = history(1, 1_000, listOf(entry(11, 1, 1), entry(12, 1, 2)))
        val latest = history(2, 3_000, listOf(entry(21, 2, 1), entry(23, 2, 3)))
        val result = WorkoutPreviousPerformance.build(current, listOf(old, latest))
        assertEquals(setOf(1L, 2L, 3L), result.keys)
        assertSame(latest.exercises[0], result[1])
        assertSame(old.exercises[1], result[2])
        assertSame(latest.exercises[1], result[3])
    }

    @Test fun `active history is excluded even when it has completed sets`() {
        val finished = history(1, 1_000, listOf(entry(11, 1, 1)))
        val active = history(2, 4_000, listOf(entry(21, 2, 1)), completed = false)
        val result = WorkoutPreviousPerformance.build(current, listOf(active, finished))
        assertSame(finished.exercises.single(), result[1])
        assertTrue(WorkoutPreviousPerformance.build(current, listOf(active)).isEmpty())
    }

    @Test fun `current workout ID is excluded even if its supplied timestamp is earlier`() {
        val same = history(current.id, 1_000, listOf(entry(11, current.id, 1)))
        val old = history(2, 2_000, listOf(entry(21, 2, 1)))
        assertSame(old.exercises.single(), WorkoutPreviousPerformance.build(current, listOf(same, old))[1])
        assertTrue(WorkoutPreviousPerformance.build(current, listOf(same)).isEmpty())
    }

    @Test fun `future workouts and timestamp ties with higher IDs are excluded while lower IDs remain eligible`() {
        val future = history(1, 5_001, listOf(entry(11, 1, 1)))
        val higherTie = history(101, 5_000, listOf(entry(21, 101, 1)))
        val lowerTie = history(99, 5_000, listOf(entry(31, 99, 1)))
        val older = history(102, 4_000, listOf(entry(41, 102, 1)))
        assertSame(lowerTie.exercises.single(), WorkoutPreviousPerformance.build(current, listOf(future, higherTie, older, lowerTie))[1])
        assertTrue(WorkoutPreviousPerformance.build(current, listOf(future, higherTie)).isEmpty())
    }

    @Test fun `eligible timestamp ties retain original input order rather than sorting by workout ID`() {
        val lower = history(3, 4_000, listOf(entry(11, 3, 1)))
        val higher = history(8, 4_000, listOf(entry(21, 8, 1)))
        assertSame(lower.exercises.single(), WorkoutPreviousPerformance.build(current, listOf(lower, higher))[1])
        assertSame(higher.exercises.single(), WorkoutPreviousPerformance.build(current, listOf(higher, lower))[1])
    }

    @Test fun `duplicate exercise entries use the first completed match in original workout entry order`() {
        val incomplete = entry(11, 1, 1, completed = false)
        val firstCompleted = entry(12, 1, 1).copy(entry = entry(12, 1, 1).entry.copy(orderIndex = 9))
        val laterCompleted = entry(13, 1, 1).copy(entry = entry(13, 1, 1).entry.copy(orderIndex = 0))
        val detail = history(1, 4_000, listOf(incomplete, firstCompleted, laterCompleted))
        assertSame(firstCompleted, WorkoutPreviousPerformance.build(current, listOf(detail))[1])
    }

    @Test fun `incomplete newest performance falls back and original entries sets and history remain unchanged`() {
        val useful = entry(11, 1, 1).let { it.copy(sets = it.sets + WorkoutSet(id = 112, workoutExerciseId = 11,
            setNumber = 2, weight = 999.0, reps = 99, completed = false)) }
        val older = history(1, 1_000, listOf(useful))
        val latest = history(2, 4_000, listOf(entry(21, 2, 1, completed = false), entry(22, 2, 2, completed = false)))
        val source = listOf(latest, older)
        val snapshot = source.toList()
        val result = WorkoutPreviousPerformance.build(current, source)
        assertEquals(setOf(1L), result.keys)
        assertSame(useful, result[1])
        assertEquals(2, result[1]!!.sets.size)
        assertFalse(result[1]!!.sets.last().completed)
        assertEquals(snapshot, source)
        assertTrue(WorkoutPreviousPerformance.build(current, emptyList()).isEmpty())
    }

    @Test fun `thousand workouts exactly match the legacy per-exercise lookup without mutating input`() {
        val workout = current.copy(id = 1_001, startTime = 2_500)
        val source = (1L..1_000L).map { id ->
            val firstId = id % 31 + 1
            val first = entry(id * 10, id, firstId, completed = id % 5 != 0L)
            val second = entry(id * 10 + 1, id, id % 13 + 32, completed = id % 3 != 0L)
            val entries = if (id % 7 == 0L) listOf(first.copy(sets = emptyList()), first, second) else listOf(first, second)
            history(id, id / 4 * 10, entries, completed = id % 17 != 0L)
        }.sortedBy { it.workout.id * 17 % 1_000 }
        val snapshot = source.toList()
        val result = WorkoutPreviousPerformance.build(workout, source)
        for (exerciseId in 1L..44L) {
            val expected = source.filter { it.workout.endTime != null && it.workout.id != workout.id &&
                (it.workout.startTime < workout.startTime || it.workout.startTime == workout.startTime && it.workout.id < workout.id) }
                .sortedByDescending { it.workout.startTime }
                .firstNotNullOfOrNull { old -> old.exercises.firstOrNull { it.exercise.id == exerciseId && it.sets.any { set -> set.completed } } }
            assertSame("Exercise $exerciseId", expected, result[exerciseId])
        }
        assertEquals((1L..44L).toSet(), result.keys)
        assertEquals(snapshot, source)
    }

    private fun entry(id: Long, workoutId: Long, exerciseId: Long, completed: Boolean = true) = WorkoutExerciseDetail(
        WorkoutExercise(id = id, workoutId = workoutId, exerciseId = exerciseId, orderIndex = 0),
        Exercise(id = exerciseId, name = "Exercise $exerciseId", primaryMuscleGroup = MuscleGroup.OTHER),
        listOf(WorkoutSet(id = id * 10, workoutExerciseId = id, setNumber = 1, weight = 60.0, reps = 10, completed = completed)),
    )

    private fun history(id: Long, start: Long, exercises: List<WorkoutExerciseDetail>, completed: Boolean = true) =
        WorkoutDetail(Workout(id = id, name = "History $id", startTime = start, endTime = if (completed) start + 100 else null), exercises)
}

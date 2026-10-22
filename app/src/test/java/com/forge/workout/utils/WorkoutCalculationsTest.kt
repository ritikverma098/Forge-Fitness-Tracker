package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutExerciseDetail
import com.forge.workout.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutCalculationsTest {
    @Test fun `volume counts completed weighted sets only`() {
        val detail = fixture(sets = listOf(set(60.0, 12), set(70.0, 10), set(75.0, 8), set(100.0, 5, completed = false)))
        assertEquals(2020.0, WorkoutCalculations.volume(detail), 0.001)
        assertEquals(30, WorkoutCalculations.summary(detail).totalReps)
        assertEquals(3, WorkoutCalculations.summary(detail).setCount)
    }

    @Test fun `bodyweight does not invent bodyweight volume`() {
        assertEquals(0.0, WorkoutCalculations.volume(fixture(type = ExerciseType.BODYWEIGHT, sets = listOf(set(80.0, 10)))), 0.001)
    }

    @Test fun `summary excludes empty exercises and uses persisted finish time`() {
        val detail = fixture(sets = listOf(set(50.0, 10))).let { it.copy(exercises = it.exercises + it.exercises.first().copy(sets = emptyList())) }
        assertEquals(1, WorkoutCalculations.summary(detail).exerciseCount)
        assertEquals(60L, WorkoutCalculations.summary(detail, now = 999999).durationSeconds)
    }

    @Test fun `duration never becomes negative`() {
        val detail = fixture().let { it.copy(workout = it.workout.copy(startTime = 100000, endTime = 1000)) }
        assertEquals(0L, WorkoutCalculations.summary(detail).durationSeconds)
    }

    @Test fun `progress ignores active workouts and incomplete sets`() {
        val first = fixture(id = 1, time = 1000, sets = listOf(set(60.0, 12)))
        val second = fixture(id = 2, time = 2000, sets = listOf(set(70.0, 10), set(100.0, 10, completed = false)))
        val active = fixture(id = 3, time = 3000, sets = listOf(set(200.0, 50))).let { it.copy(workout = it.workout.copy(endTime = null)) }
        val progress = WorkoutCalculations.progress(1, listOf(second, active, first))
        assertEquals(70.0, progress.bestWeightKg, 0.001)
        assertEquals(12, progress.maxReps)
        assertEquals(2, progress.workoutCount)
        assertEquals(listOf(1000L, 2000L), progress.points.map { it.date })
        assertEquals(700.0, progress.points.last().volumeKg, 0.001)
    }

    @Test fun `empty progress is safe`() {
        val progress = WorkoutCalculations.progress(1, emptyList())
        assertEquals(0, progress.workoutCount)
        assertNull(progress.bestSet)
    }

    @Test fun `duration and distance select useful best sets`() {
        val duration = fixture(type = ExerciseType.DURATION, sets = listOf(set(0.0, 0).copy(duration = 30), set(0.0, 0).copy(duration = 60)))
        assertEquals(60, WorkoutCalculations.progress(1, listOf(duration)).bestSet?.duration)
        val distance = fixture(type = ExerciseType.DISTANCE, sets = listOf(set(0.0, 0).copy(distance = 2.0), set(0.0, 0).copy(distance = 5.0)))
        assertEquals(5.0, WorkoutCalculations.progress(1, listOf(distance)).bestSet!!.distance, 0.001)
    }

    companion object {
        fun set(weight: Double, reps: Int, completed: Boolean = true) = WorkoutSet(workoutExerciseId = 1, setNumber = 1, weight = weight, reps = reps, completed = completed)
        fun fixture(id: Long = 1, time: Long = 1000, type: ExerciseType = ExerciseType.WEIGHT_REPS, sets: List<WorkoutSet> = emptyList()): WorkoutDetail = WorkoutDetail(
            Workout(id = id, name = "Strength", startTime = time, endTime = time + 60000),
            listOf(WorkoutExerciseDetail(WorkoutExercise(id = 1, workoutId = id, exerciseId = 1, orderIndex = 0),
                Exercise(id = 1, name = "Bench Press", primaryMuscleGroup = MuscleGroup.CHEST, exerciseType = type), sets)),
        )
    }
}

package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseFilteringTest {
    private val exercises = listOf(
        Exercise(id = 1, name = "Bench press", primaryMuscleGroup = MuscleGroup.CHEST, secondaryMuscleGroup = MuscleGroup.TRICEPS),
        Exercise(id = 2, name = "Barbell row", primaryMuscleGroup = MuscleGroup.BACK, secondaryMuscleGroup = MuscleGroup.BICEPS),
        Exercise(id = 3, name = "Squat", primaryMuscleGroup = MuscleGroup.LEGS, secondaryMuscleGroup = MuscleGroup.GLUTES),
        Exercise(id = 4, name = "Overhead press", primaryMuscleGroup = MuscleGroup.SHOULDERS, secondaryMuscleGroup = MuscleGroup.TRICEPS),
        Exercise(id = 5, name = "Cable curl", primaryMuscleGroup = MuscleGroup.BICEPS),
        Exercise(id = 6, name = "Archived press", primaryMuscleGroup = MuscleGroup.CHEST, archived = true),
    )

    @Test fun `default workout filter matches any selected primary group`() {
        val result = ExerciseFiltering.filter(exercises, workoutMuscleGroups = setOf(MuscleGroup.CHEST, MuscleGroup.LEGS))
        assertEquals(listOf(1L, 3L), result.map { it.id })
    }

    @Test fun `secondary muscle groups participate in default matching`() {
        val result = ExerciseFiltering.filter(exercises, workoutMuscleGroups = setOf(MuscleGroup.TRICEPS))
        assertEquals(listOf(1L, 4L), result.map { it.id })
    }

    @Test fun `all muscles override bypasses workout groups and still excludes archived exercises`() {
        val result = ExerciseFiltering.filter(exercises, workoutMuscleGroups = setOf(MuscleGroup.CHEST), showAll = true)
        assertEquals(listOf(2L, 1L, 5L, 4L, 3L), result.map { it.id })
    }

    @Test fun `single group override replaces rather than adds to workout groups`() {
        val result = ExerciseFiltering.filter(
            exercises, workoutMuscleGroups = setOf(MuscleGroup.CHEST), selectedMuscleGroup = MuscleGroup.BICEPS,
        )
        assertEquals(listOf(2L, 5L), result.map { it.id })
    }

    @Test fun `search is trimmed case insensitive and intersects workout filtering`() {
        val result = ExerciseFiltering.filter(
            exercises, query = "  PRESS  ", workoutMuscleGroups = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS),
        )
        assertEquals(listOf(1L, 4L), result.map { it.id })
        assertTrue(ExerciseFiltering.filter(exercises, query = "row", workoutMuscleGroups = setOf(MuscleGroup.CHEST)).isEmpty())
    }

    @Test fun `all override retains search matching`() {
        val result = ExerciseFiltering.filter(exercises, query = "ROW", workoutMuscleGroups = setOf(MuscleGroup.CHEST), showAll = true)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test fun `empty workout groups preserve the normal library and picker behavior`() {
        val result = ExerciseFiltering.filter(exercises)
        assertEquals(listOf(2L, 1L, 5L, 4L, 3L), result.map { it.id })
        assertEquals(listOf(3L), ExerciseFiltering.filter(exercises, selectedMuscleGroup = MuscleGroup.GLUTES).map { it.id })
    }

    @Test fun `default groups follow updated input without retaining old initial selection`() {
        val first = ExerciseFiltering.filter(exercises, workoutMuscleGroups = setOf(MuscleGroup.CHEST))
        val updated = ExerciseFiltering.filter(exercises, workoutMuscleGroups = setOf(MuscleGroup.LEGS))
        assertEquals(listOf(1L), first.map { it.id })
        assertEquals(listOf(3L), updated.map { it.id })
        val explicit = ExerciseFiltering.filter(
            exercises, workoutMuscleGroups = setOf(MuscleGroup.LEGS), selectedMuscleGroup = MuscleGroup.CHEST,
        )
        assertEquals(listOf(1L), explicit.map { it.id })
    }
}

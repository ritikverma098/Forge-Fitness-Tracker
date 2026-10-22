package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.WorkoutSet
import org.junit.Test

class ExerciseValidationTest {
    private val exercise = Exercise(name = "Cable Row", primaryMuscleGroup = MuscleGroup.BACK)

    @Test fun `valid custom exercise is accepted`() = ExerciseValidation.validate(exercise)
    @Test(expected = IllegalArgumentException::class) fun `blank name is rejected`() = ExerciseValidation.validate(exercise.copy(name = "  "))
    @Test(expected = IllegalArgumentException::class) fun `name too long is rejected`() = ExerciseValidation.validate(exercise.copy(name = "a".repeat(101)))
    @Test(expected = IllegalArgumentException::class) fun `duplicate name ignores case and outer whitespace`() = ExerciseValidation.validate(exercise.copy(name = " cable row "), listOf(exercise.copy(id = 2)))
    @Test fun `editing same exercise keeps its name`() = ExerciseValidation.validate(exercise.copy(id = 2), listOf(exercise.copy(id = 2)))
    @Test(expected = IllegalArgumentException::class) fun `same secondary muscle is rejected`() = ExerciseValidation.validate(exercise.copy(secondaryMuscleGroup = MuscleGroup.BACK))
    @Test(expected = IllegalArgumentException::class) fun `negative weight is rejected`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1, weight = -1.0), ExerciseType.WEIGHT_REPS)
    @Test(expected = IllegalArgumentException::class) fun `nonfinite weight is rejected`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1, weight = Double.NaN), ExerciseType.WEIGHT_REPS)
    @Test(expected = IllegalArgumentException::class) fun `negative repetitions are rejected`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1, reps = -1), ExerciseType.WEIGHT_REPS)
    @Test(expected = IllegalArgumentException::class) fun `completed repetition set requires repetitions`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1, completed = true), ExerciseType.BODYWEIGHT)
    @Test(expected = IllegalArgumentException::class) fun `completed timed set requires duration`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1, completed = true), ExerciseType.DURATION)
    @Test(expected = IllegalArgumentException::class) fun `completed distance set requires distance`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1, completed = true), ExerciseType.DISTANCE)
    @Test fun `uncompleted empty set supports quick drafting`() = WorkoutValidation.validateSet(WorkoutSet(workoutExerciseId = 1, setNumber = 1), ExerciseType.WEIGHT_REPS)
}

package com.forge.workout.backup

import com.forge.workout.data.database.AppMetadata
import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.Routine
import com.forge.workout.model.RoutineExercise
import com.forge.workout.model.SetType
import com.forge.workout.model.Workout
import com.forge.workout.model.WorkoutExercise
import com.forge.workout.model.WorkoutSet
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.UserPreferences
import com.forge.workout.preferences.WeightUnit

internal fun completeBackup(): ForgeBackup {
    val types = ExerciseType.entries
    return ForgeBackup(
        createdAt = 10000,
        exercises = types.mapIndexed { index, type -> Exercise(
            id = 101L + index, name = "Élan ${type.name} 💪", primaryMuscleGroup = MuscleGroup.CHEST,
            secondaryMuscleGroup = MuscleGroup.TRICEPS, exerciseType = type,
            notes = "Pause \"then\" move\nहिन्दी \\ timer { [ ] }", isCustom = index != 0, archived = index == 3,
        ) },
        workouts = listOf(
            Workout(id = 201, name = "Training 💪", startTime = 1000, endTime = 2000, notes = "Finished\nStrong",
                muscleGroups = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS)),
            Workout(id = 202, name = "Current", startTime = 3000, notes = "Active", restEndTime = 100000,
                muscleGroups = listOf(MuscleGroup.CORE)),
        ),
        workoutExercises = types.indices.map { index -> WorkoutExercise(
            id = 301L + index, workoutId = 201, exerciseId = 101L + index, orderIndex = index, notes = "Tempo $index",
        ) } + WorkoutExercise(id = 306, workoutId = 202, exerciseId = 101, orderIndex = 0),
        sets = types.mapIndexed { index, type -> WorkoutSet(
            id = 401L + index, workoutExerciseId = 301L + index, setNumber = 1,
            weight = if (type == ExerciseType.WEIGHT_REPS) 82.5 else 0.0,
            reps = if (type in listOf(ExerciseType.WEIGHT_REPS, ExerciseType.BODYWEIGHT, ExerciseType.REPS_ONLY)) 12 else 0,
            duration = if (type in listOf(ExerciseType.DURATION, ExerciseType.DISTANCE)) 90 else 0,
            distance = if (type == ExerciseType.DISTANCE) 2.75 else 0.0, completed = true,
            setType = SetType.entries[index % SetType.entries.size],
        ) } + WorkoutSet(id = 406, workoutExerciseId = 306, setNumber = 1, weight = 85.0, reps = 10),
        routines = listOf(Routine(id = 501, name = "Upper body", createdAt = 500)),
        routineExercises = types.indices.map { index -> RoutineExercise(
            id = 601L + index, routineId = 501, exerciseId = 101L + index, orderIndex = index,
        ) },
        metadata = listOf(AppMetadata("default_exercises_v1", "seeded"), AppMetadata("future_flag", "preserve")),
        preferences = UserPreferences(ThemeMode.DARK, WeightUnit.LB, restSeconds = 120, confirmDeletion = false),
        nutrition = NutritionBackup(),
    )
}

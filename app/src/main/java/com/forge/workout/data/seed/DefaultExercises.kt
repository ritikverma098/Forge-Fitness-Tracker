package com.forge.workout.data.seed

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup

object DefaultExercises {
    val all: List<Exercise> = buildList {
        fun group(muscle: MuscleGroup, vararg names: String, type: ExerciseType = ExerciseType.WEIGHT_REPS) {
            names.forEach { add(Exercise(name = it, primaryMuscleGroup = muscle, exerciseType = type, isCustom = false)) }
        }
        group(MuscleGroup.CHEST, "Bench Press", "Incline Bench Press", "Dumbbell Press", "Incline Dumbbell Press", "Chest Fly", "Cable Crossover")
        group(MuscleGroup.CHEST, "Push Ups", "Chest Dips", type = ExerciseType.BODYWEIGHT)
        group(MuscleGroup.BACK, "Deadlift", "Lat Pulldown", "Barbell Row", "Seated Cable Row", "Dumbbell Row", "Face Pull", "Straight Arm Pulldown")
        group(MuscleGroup.BACK, "Pull Ups", "Chin Ups", "Inverted Row", type = ExerciseType.BODYWEIGHT)
        group(MuscleGroup.SHOULDERS, "Overhead Press", "Dumbbell Shoulder Press", "Lateral Raise", "Front Raise", "Rear Delt Fly", "Arnold Press", "Upright Row")
        group(MuscleGroup.BICEPS, "Barbell Curl", "Dumbbell Curl", "Hammer Curl", "Preacher Curl", "Cable Curl", "Concentration Curl")
        group(MuscleGroup.TRICEPS, "Tricep Pushdown", "Skull Crushers", "Overhead Tricep Extension", "Close Grip Bench Press")
        group(MuscleGroup.TRICEPS, "Tricep Dips", "Diamond Push Ups", type = ExerciseType.BODYWEIGHT)
        group(MuscleGroup.FOREARMS, "Wrist Curl", "Reverse Wrist Curl", "Reverse Curl")
        group(MuscleGroup.FOREARMS, "Dead Hang", type = ExerciseType.DURATION)
        group(MuscleGroup.LEGS, "Squat", "Front Squat", "Goblet Squat", "Leg Press", "Leg Extension", "Leg Curl", "Lunges", "Bulgarian Split Squat", "Romanian Deadlift")
        group(MuscleGroup.LEGS, "Bodyweight Squat", type = ExerciseType.BODYWEIGHT)
        group(MuscleGroup.GLUTES, "Hip Thrust", "Cable Kickback", "Glute Bridge", "Sumo Deadlift")
        group(MuscleGroup.CORE, "Cable Crunch", "Weighted Sit Up")
        group(MuscleGroup.CORE, "Crunch", "Hanging Leg Raise", "Sit Up", "Bicycle Crunch", type = ExerciseType.REPS_ONLY)
        group(MuscleGroup.CORE, "Plank", "Side Plank", type = ExerciseType.DURATION)
        group(MuscleGroup.CALVES, "Standing Calf Raise", "Seated Calf Raise", "Leg Press Calf Raise")
        group(MuscleGroup.CARDIO, "Running", "Cycling", "Rowing", "Walking", "Swimming", type = ExerciseType.DISTANCE)
        group(MuscleGroup.CARDIO, "Jump Rope", "Stair Climber", "Elliptical", type = ExerciseType.DURATION)
        group(MuscleGroup.FULL_BODY, "Clean and Press", "Thruster", "Kettlebell Swing")
        group(MuscleGroup.FULL_BODY, "Burpees", "Mountain Climbers", type = ExerciseType.REPS_ONLY)
        group(MuscleGroup.OTHER, "Mobility Session", "Stretching", type = ExerciseType.DURATION)
    }
}

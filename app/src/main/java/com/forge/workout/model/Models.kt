package com.forge.workout.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

enum class MuscleGroup {
    CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, FOREARMS, LEGS, GLUTES, CORE, CALVES, CARDIO, FULL_BODY, OTHER
}

enum class ExerciseType { WEIGHT_REPS, BODYWEIGHT, REPS_ONLY, DURATION, DISTANCE }
enum class SetType { WORKING, WARM_UP, DROP_SET, FAILURE }

@Entity(tableName = "exercises", indices = [Index(value = ["name"], unique = true)])
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val primaryMuscleGroup: MuscleGroup,
    val secondaryMuscleGroup: MuscleGroup? = null,
    val exerciseType: ExerciseType = ExerciseType.WEIGHT_REPS,
    val notes: String = "",
    val isCustom: Boolean = true,
    val archived: Boolean = false,
)

@Entity(tableName = "workouts", indices = [Index("startTime"), Index("endTime")])
data class Workout(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val notes: String = "",
    val restEndTime: Long? = null,
    @ColumnInfo(defaultValue = "''") val muscleGroups: List<MuscleGroup> = emptyList(),
)

@Entity(
    tableName = "workout_exercises",
    foreignKeys = [
        ForeignKey(entity = Workout::class, parentColumns = ["id"], childColumns = ["workoutId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val orderIndex: Int,
    val notes: String = "",
)

@Entity(
    tableName = "workout_sets",
    foreignKeys = [ForeignKey(entity = WorkoutExercise::class, parentColumns = ["id"], childColumns = ["workoutExerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutExerciseId")],
)
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val setNumber: Int,
    val weight: Double = 0.0,
    val reps: Int = 0,
    val duration: Int = 0,
    val distance: Double = 0.0,
    val completed: Boolean = false,
    @ColumnInfo(defaultValue = "'WORKING'") val setType: SetType = SetType.WORKING,
)

@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "routine_exercises",
    foreignKeys = [
        ForeignKey(entity = Routine::class, parentColumns = ["id"], childColumns = ["routineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("routineId"), Index("exerciseId")],
)
data class RoutineExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val exerciseId: Long,
    val orderIndex: Int,
)

data class WorkoutExerciseDetail(
    @Embedded val entry: WorkoutExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: Exercise,
    @Relation(parentColumn = "id", entityColumn = "workoutExerciseId") val sets: List<WorkoutSet>,
) {
    val sortedSets: List<WorkoutSet> get() = sets.sortedBy { it.setNumber }
}

data class WorkoutDetail(
    @Embedded val workout: Workout,
    @Relation(parentColumn = "id", entityColumn = "workoutId", entity = WorkoutExercise::class) val exercises: List<WorkoutExerciseDetail>,
) {
    val sortedExercises: List<WorkoutExerciseDetail> get() = exercises.sortedBy { it.entry.orderIndex }
}

data class RoutineExerciseDetail(
    @Embedded val entry: RoutineExercise,
    @Relation(parentColumn = "exerciseId", entityColumn = "id") val exercise: Exercise,
)

data class RoutineDetail(
    @Embedded val routine: Routine,
    @Relation(parentColumn = "id", entityColumn = "routineId", entity = RoutineExercise::class) val exercises: List<RoutineExerciseDetail>,
) {
    val sortedExercises: List<RoutineExerciseDetail> get() = exercises.sortedBy { it.entry.orderIndex }
}

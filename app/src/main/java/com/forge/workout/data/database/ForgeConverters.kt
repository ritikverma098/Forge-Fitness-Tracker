package com.forge.workout.data.database

import androidx.room.TypeConverter
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.MuscleGroup
import com.forge.workout.model.SetType

class ForgeConverters {
    @TypeConverter fun setTypeToString(value: SetType): String = value.name
    @TypeConverter fun stringToSetType(value: String): SetType = SetType.valueOf(value)
    @TypeConverter fun muscleToString(value: MuscleGroup?): String? = value?.name
    @TypeConverter fun stringToMuscle(value: String?): MuscleGroup? = value?.let(MuscleGroup::valueOf)
    @TypeConverter fun typeToString(value: ExerciseType): String = value.name
    @TypeConverter fun stringToType(value: String): ExerciseType = ExerciseType.valueOf(value)
    @TypeConverter fun muscleGroupsToString(value: List<MuscleGroup>): String = value.distinct()
        .sortedBy { it.ordinal }.joinToString(",") { it.name }
    @TypeConverter fun stringToMuscleGroups(value: String): List<MuscleGroup> {
        val names = value.split(',').map { it.trim() }.toSet()
        return MuscleGroup.entries.filter { it.name in names }
    }
}

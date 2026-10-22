package com.forge.workout

import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository

/** A separate data graph keeps UI tests away from installed training history. */
class ForgeTestApplication : ForgeApplication() {
    override val database: ForgeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, ForgeDatabase::class.java).build()
    }
    override val repository: WorkoutRepository by lazy {
        RoomWorkoutRepository(database)
    }
    override val preferences: PreferencesRepository by lazy {
        PreferencesRepository(this, "forge_ui_test_preferences")
    }
}

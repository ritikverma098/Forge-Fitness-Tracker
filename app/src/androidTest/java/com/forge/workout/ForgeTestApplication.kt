package com.forge.workout

import androidx.room.Room
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.updates.UpdateCheckResult
import kotlinx.coroutines.runBlocking

/** A separate data graph keeps UI tests away from installed training history. */
class ForgeTestApplication : ForgeApplication() {
    override val updateFilesDirectory get() = java.io.File(cacheDir, "forge_ui_test_updates")
    @Volatile internal var mockReleaseCheck: (suspend () -> UpdateCheckResult)? = null

    override fun canCheckLatestRelease() = mockReleaseCheck != null
    override suspend fun checkLatestRelease() = mockReleaseCheck?.invoke() ?: UpdateCheckResult.Unavailable
    override val database: ForgeDatabase by lazy {
        Room.inMemoryDatabaseBuilder(this, ForgeDatabase::class.java).build()
    }
    override val repository: WorkoutRepository by lazy {
        RoomWorkoutRepository(database)
    }
    override val preferences: PreferencesRepository by lazy {
        PreferencesRepository(this, "forge_ui_test_preferences")
    }

    override fun onCreate() {
        super.onCreate()
        // Existing interaction tests start after onboarding while keeping the device's actual permission state.
        runBlocking { preferences.claimRestNotificationOffer() }
    }
}

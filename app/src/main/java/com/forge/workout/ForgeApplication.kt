package com.forge.workout

import android.app.Application
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.backup.BackupRepository
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.nutrition.repository.NutritionRepository
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import com.forge.workout.nutrition.catalog.AndroidFoodCatalogService
import com.forge.workout.nutrition.catalog.FoodCatalogService
import com.forge.workout.nutrition.energy.WorkoutEnergyCoordinator
import kotlinx.coroutines.flow.first
import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import com.forge.workout.rest.AndroidRestAlerts
import com.forge.workout.rest.RestTimerCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class ForgeApplication : Application() {
    protected open val database: ForgeDatabase by lazy { ForgeDatabase.create(this) }
    open val repository: WorkoutRepository by lazy { RoomWorkoutRepository(database) }
    open val nutritionRepository: NutritionRepository by lazy { RoomNutritionRepository(database) }
    open val catalogs: FoodCatalogService by lazy { AndroidFoodCatalogService(this, database) }
    open val preferences: PreferencesRepository by lazy { PreferencesRepository(this) }
    open val backups: BackupRepository by lazy { RoomBackupRepository(database) { preferences.preferences.first() } }
    private val restScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nutritionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val workoutEnergy: WorkoutEnergyCoordinator by lazy {
        WorkoutEnergyCoordinator(nutritionRepository, nutritionScope,
            onError = { Log.e("ForgeWorkoutEnergy", "Workout estimate refresh failed", it) })
    }
    val restTimers: RestTimerCoordinator by lazy {
        RestTimerCoordinator(repository, AndroidRestAlerts(this), restScope,
            screenUsable = {
                getSystemService(PowerManager::class.java).isInteractive &&
                    !getSystemService(KeyguardManager::class.java).isKeyguardLocked
            }, onError = { Log.e("ForgeRestTimer", "Rest timer operation failed", it) })
    }

    override fun onCreate() {
        super.onCreate()
        AndroidRestAlerts(this).createChannel()
        restTimers.start()
    }
}

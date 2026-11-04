package com.forge.workout

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.repository.RoomWorkoutRepository
import com.forge.workout.repository.WorkoutRepository
import com.forge.workout.backup.BackupRepository
import com.forge.workout.backup.RoomBackupRepository
import com.forge.workout.backup.RestoreStartupGate
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
import com.forge.workout.updates.GitHubReleaseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

open class ForgeApplication : Application() {
    protected open val database: ForgeDatabase by lazy { ForgeDatabase.create(this) }
    open val repository: WorkoutRepository by lazy { RoomWorkoutRepository(database) }
    open val nutritionRepository: NutritionRepository by lazy { RoomNutritionRepository(database) }
    open val catalogs: FoodCatalogService by lazy { AndroidFoodCatalogService(this, database) }
    open val preferences: PreferencesRepository by lazy { PreferencesRepository(this) }
    private val githubReleases by lazy { GitHubReleaseSource() }
    internal open suspend fun checkLatestRelease() = githubReleases.check(BuildConfig.VERSION_NAME)
    internal open fun canCheckLatestRelease(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    open val backups: BackupRepository by lazy {
        RoomBackupRepository(database, preferencesRestorer = preferences::replace) { preferences.preferences.first() }
    }
    private val restScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nutritionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var restorePaused = false
    private val startup by lazy {
        RestoreStartupGate(nutritionScope, {
            backups.recoverInterruptedRestore()
            if (restorePaused) {
                try { restTimers.resumeAfterRestore() }
                finally { workoutEnergy.resumeAfterRestore() }
                restorePaused = false
            }
            // Cold-start alarm delivery must claim a due timer before resume reconciliation.
            restTimers.start()
        }, { Log.e("ForgeRestore", "Interrupted restore recovery failed", it) })
    }
    val startupState get() = startup.also { it.start() }.state
    suspend fun awaitStartup() { startup.awaitReady() }
    fun retryStartup() { startup.retry() }

    suspend fun pauseForRestore() {
        workoutEnergy.pauseForRestore()
        restorePaused = true
        restTimers.pauseForRestore()
    }

    suspend fun resumeAfterRestoreAttempt() { startup.verifyRecovery() }
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
        startup.start()
    }
}

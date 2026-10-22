package com.forge.workout.backup

import androidx.room.withTransaction
import com.forge.workout.data.database.AppMetadata
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.NutritionSettingsEntity
import com.forge.workout.preferences.UserPreferences

class RoomBackupRepository(
    private val database: ForgeDatabase,
    private val preferencesProvider: suspend () -> UserPreferences = { UserPreferences() },
) : BackupRepository {
    private val dao = database.workoutDao()
    private val nutritionDao = database.nutritionDao()

    override suspend fun snapshot(): ForgeBackup {
        val preferences = preferencesProvider()
        return database.withTransaction {
            ForgeBackup(
                exercises = dao.backupExercises(), workouts = dao.backupWorkouts(),
                workoutExercises = dao.backupWorkoutExercises(), sets = dao.backupSets(),
                routines = dao.backupRoutines(), routineExercises = dao.backupRoutineExercises(),
                metadata = dao.backupMetadata(), preferences = preferences,
                nutrition = NutritionBackup(
                    foods = nutritionDao.backupPersonalFoods().map { detail -> detail.copy(food = detail.food.copy(rowId = 0),
                        nutrients = detail.nutrients.sortedBy { it.code }, portions = detail.portions.sortedBy { it.id }) },
                    recipes = nutritionDao.backupRecipes().map { it.copy(ingredients = it.sortedIngredients) },
                    diary = nutritionDao.backupDiary(), goals = nutritionDao.backupGoals(),
                    bodyWeights = nutritionDao.backupBodyWeights(), favorites = nutritionDao.backupFavorites(),
                    settings = nutritionDao.energySettings() ?: NutritionSettingsEntity(),
                    workoutEnergy = nutritionDao.backupWorkoutEnergy(),
                ),
            )
        }
    }

    override suspend fun restore(backup: ForgeBackup, beforeCommit: suspend () -> Unit) {
        // Freeze caller-owned collections before validation or suspending in a transaction.
        val stable = backup.copy(
            exercises = backup.exercises.toList(),
            workouts = backup.workouts.map { it.copy(muscleGroups = it.muscleGroups.toList()) },
            workoutExercises = backup.workoutExercises.toList(), sets = backup.sets.toList(),
            routines = backup.routines.toList(), routineExercises = backup.routineExercises.toList(),
            metadata = backup.metadata.toList(),
            nutrition = backup.nutrition?.freeze(),
        )
        BackupValidation.validate(stable)
        database.withTransaction {
            val nutrition = stable.nutrition
            if (nutrition != null) {
                val collides = !nutrition.foods.map { it.food.id }.chunked(900).all {
                    nutritionDao.backupReferenceCollisions(it).isEmpty()
                }
                if (collides) throw BackupValidationException(BackupIssue.INVALID_DATA,
                    "A personal food ID conflicts with an installed reference catalog.")
                nutritionDao.deleteBackupWorkoutEnergy()
                nutritionDao.deleteBackupDiary()
                nutritionDao.deleteBackupIngredients()
                nutritionDao.deleteBackupRecipes()
                nutritionDao.deleteBackupPersonalFoods()
                nutritionDao.deleteBackupGoals()
                nutritionDao.deleteBackupBodyWeights()
                nutritionDao.deleteBackupFavorites()
                nutritionDao.deleteBackupSettings()
            }
            dao.deleteBackupSets()
            dao.deleteBackupWorkoutExercises()
            dao.deleteBackupRoutineExercises()
            dao.deleteBackupWorkouts()
            dao.deleteBackupRoutines()
            dao.deleteBackupExercises()
            dao.deleteBackupMetadata()
            stable.exercises.forEach { dao.insertExercise(it) }
            stable.workouts.forEach { dao.insertWorkout(it) }
            stable.routines.forEach { dao.insertRoutine(it) }
            stable.workoutExercises.forEach { dao.insertWorkoutExercise(it) }
            stable.sets.forEach { dao.insertSet(it) }
            stable.routineExercises.forEach { dao.insertRoutineExercise(it) }
            stable.metadata.forEach { dao.putMetadata(it) }
            // An imported library is authoritative, including an intentionally empty one.
            if (stable.metadata.none { it.key == BackupValidation.SEED_KEY }) {
                dao.putMetadata(AppMetadata(BackupValidation.SEED_KEY, "seeded"))
            }
            if (nutrition != null) {
                nutritionDao.insertFoods(nutrition.foods.map { it.food.copy(rowId = 0) })
                nutrition.foods.forEach { detail ->
                    nutritionDao.insertNutrients(detail.nutrients)
                    nutritionDao.insertPortions(detail.portions)
                }
                nutritionDao.insertBackupRecipes(nutrition.recipes.map { it.recipe })
                nutritionDao.insertIngredients(nutrition.recipes.flatMap { it.ingredients })
                nutritionDao.insertBackupBodyWeights(nutrition.bodyWeights)
                nutritionDao.insertDiaryEntries(nutrition.diary)
                nutritionDao.insertBackupGoals(nutrition.goals)
                nutritionDao.insertBackupFavorites(nutrition.favorites)
                nutritionDao.insertBackupSettings(nutrition.settings)
                nutritionDao.insertBackupWorkoutEnergy(nutrition.workoutEnergy)
            }
            beforeCommit()
        }
    }
}

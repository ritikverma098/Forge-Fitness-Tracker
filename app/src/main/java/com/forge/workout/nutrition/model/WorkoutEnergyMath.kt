package com.forge.workout.nutrition.model

import java.time.Instant
import java.time.ZoneId

/** 2024 Adult Compendium activity codes; labels are supplied by the UI's string resources. */
enum class WorkoutActivityPreset(val id: String, val met: Double, val intensity: WorkoutIntensity, val code: String) {
    LIGHT_CALISTHENICS("light_calisthenics", 2.8, WorkoutIntensity.LIGHT, "02024"),
    RESISTANCE("resistance_training", 3.5, WorkoutIntensity.MODERATE, "02054"),
    VIGOROUS_RESISTANCE("vigorous_resistance", 6.0, WorkoutIntensity.VIGOROUS, "02050"),
    BODYWEIGHT("bodyweight", 3.0, WorkoutIntensity.MODERATE, "02056"),
    MODERATE_CALISTHENICS("moderate_calisthenics", 3.8, WorkoutIntensity.MODERATE, "02022"),
    VIGOROUS_CALISTHENICS("vigorous_calisthenics", 7.5, WorkoutIntensity.VIGOROUS, "02020");

    companion object {
        const val SOURCE_URL = "https://pacompendium.com/conditioning-exercise/"
        const val CONVERSION_URL = "https://pacompendium.com/unite-conversions/"
        fun fromId(id: String): WorkoutActivityPreset = entries.firstOrNull { it.id == id } ?: RESISTANCE
    }
}

data class EnergyWorkout(val id: Long, val startTime: Long, val endTime: Long)

data class WorkoutEnergySources(
    val workouts: List<EnergyWorkout>,
    val bodyWeights: List<BodyWeightEntity>,
    val settings: NutritionSettingsEntity,
    val estimates: List<WorkoutEnergyEntity>,
)

object WorkoutEnergyMath {
    /** Active energy excludes the resting 1 MET; activeFraction is a user assumption, not measured rest. */
    fun activeKcal(durationSeconds: Long, bodyWeightKg: Double, met: Double, activeFraction: Double): Double {
        require(durationSeconds > 0)
        require(bodyWeightKg.isFinite() && bodyWeightKg > 0 && bodyWeightKg <= 1000)
        require(met.isFinite() && met > 0 && met <= 100)
        require(activeFraction.isFinite() && activeFraction in 0.0..1.0)
        return ((met - 1.0).coerceAtLeast(0.0) * 3.5 * bodyWeightKg / 200.0 *
            (durationSeconds / 60.0) * activeFraction).also {
            require(it.isFinite() && it in 0.0..1_000_000_000.0) { "Workout energy is too large." }
        }
    }

    fun day(workout: EnergyWorkout, previous: WorkoutEnergyEntity?, zone: ZoneId): String =
        previous?.takeIf { it.startTime == workout.startTime }?.day
            ?: Instant.ofEpochMilli(workout.startTime).atZone(zone).toLocalDate().toString()

    fun estimate(workout: EnergyWorkout, weight: BodyWeightEntity?, settings: NutritionSettingsEntity,
        previous: WorkoutEnergyEntity?, zone: ZoneId = ZoneId.systemDefault()): WorkoutEnergyEntity? {
        require(workout.id > 0 && workout.startTime >= 0 && workout.endTime >= workout.startTime)
        require(previous == null || previous.workoutId == workout.id)
        val duration = (workout.endTime - workout.startTime) / 1000
        if (duration <= 0) return null
        val kg = weight?.kg ?: previous?.bodyWeightKg ?: return null
        val preset = WorkoutActivityPreset.fromId(settings.defaultActivity)
        val met = previous?.met ?: preset.met
        val fraction = previous?.activeFraction ?: settings.defaultActiveFraction
        return WorkoutEnergyEntity(workout.id, day(workout, previous, zone), workout.startTime, workout.endTime,
            duration, weight?.id ?: previous?.bodyWeightId, kg, met, previous?.activity ?: preset.id,
            previous?.intensity ?: preset.intensity, fraction, activeKcal(duration, kg, met, fraction), previous?.manualKcal)
    }
}

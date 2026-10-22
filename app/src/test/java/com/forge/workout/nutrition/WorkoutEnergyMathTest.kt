package com.forge.workout.nutrition

import com.forge.workout.nutrition.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEnergyMathTest {
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()

    @Test fun `all presets retain their published Compendium codes METs and distinct stable IDs`() {
        assertEquals(listOf("02024", "02054", "02050", "02056", "02022", "02020"), WorkoutActivityPreset.entries.map { it.code })
        assertEquals(listOf(2.8, 3.5, 6.0, 3.0, 3.8, 7.5), WorkoutActivityPreset.entries.map { it.met })
        assertEquals(6, WorkoutActivityPreset.entries.map { it.id }.distinct().size)
        for (preset in WorkoutActivityPreset.entries) assertEquals(preset, WorkoutActivityPreset.fromId(preset.id))
        assertEquals(WorkoutActivityPreset.RESISTANCE, WorkoutActivityPreset.fromId("unknown-legacy-setting"))
        assertTrue(WorkoutActivityPreset.SOURCE_URL.startsWith("https://pacompendium.com/"))
    }

    @Test fun `active calories subtract resting MET and use the explicit active time fraction`() {
        assertEquals(105.0, WorkoutEnergyMath.activeKcal(3600, 80.0, 3.5, .5), 0.000001)
        assertEquals(210.0, WorkoutEnergyMath.activeKcal(3600, 80.0, 6.0, .5), 0.000001)
        assertEquals(105.0, WorkoutEnergyMath.activeKcal(1800, 80.0, 6.0, .5), 0.000001)
        assertEquals(0.0, WorkoutEnergyMath.activeKcal(3600, 80.0, 6.0, 0.0), 0.0)
        assertEquals(0.0, WorkoutEnergyMath.activeKcal(3600, 80.0, 1.0, 1.0), 0.0)
        assertEquals(0.0, WorkoutEnergyMath.activeKcal(3600, 80.0, .5, 1.0), 0.0)
        assertEquals(210.0, WorkoutEnergyMath.activeKcal(3600, 80.0, 3.5, 1.0), 0.000001)
    }

    @Test fun `missing body weight and subsecond workouts remain unavailable rather than fabricated zero estimates`() {
        assertNull(WorkoutEnergyMath.estimate(workout(), null, NutritionSettingsEntity(), null, zone))
        assertNull(WorkoutEnergyMath.estimate(workout().copy(endTime = start + 999), weight(), NutritionSettingsEntity(), null, zone))
        val normal = WorkoutEnergyMath.estimate(workout(), weight(), NutritionSettingsEntity(), null, zone)!!
        assertEquals(105.0, normal.calculatedKcal, 0.000001)
        assertEquals(80.0, normal.bodyWeightKg, 0.0)
        assertEquals(1L, normal.bodyWeightId)
        assertEquals("2026-10-13", normal.day)
        assertNull(normal.manualKcal)
        val selectedDefaults = WorkoutEnergyMath.estimate(workout(), weight(), NutritionSettingsEntity(
            defaultActivity = WorkoutActivityPreset.VIGOROUS_CALISTHENICS.id, defaultActiveFraction = .25), null, zone)!!
        assertEquals(WorkoutActivityPreset.VIGOROUS_CALISTHENICS.id, selectedDefaults.activity)
        assertEquals(.25, selectedDefaults.activeFraction, 0.0)
        assertEquals(136.5, selectedDefaults.calculatedKcal, 0.000001)
    }

    @Test fun `background recalculation retains selected classification fraction and manual zero while using a newer eligible weight`() {
        val initial = WorkoutEnergyMath.estimate(workout(), weight(), NutritionSettingsEntity(), null, zone)!!
        val classified = initial.copy(activity = WorkoutActivityPreset.VIGOROUS_RESISTANCE.id, met = 6.0,
            intensity = WorkoutIntensity.VIGOROUS, activeFraction = .25, manualKcal = 0.0)
        val changed = WorkoutEnergyMath.estimate(workout().copy(endTime = start + 7_200_000), weight().copy(id = 2, kg = 90.0),
            NutritionSettingsEntity(defaultActivity = WorkoutActivityPreset.LIGHT_CALISTHENICS.id, defaultActiveFraction = 1.0), classified, zone)!!
        assertEquals(236.25, changed.calculatedKcal, 0.000001)
        assertEquals(0.0, changed.effectiveKcal, 0.0)
        assertEquals(classified.activity, changed.activity)
        assertEquals(classified.activeFraction, changed.activeFraction, 0.0)
        assertEquals(classified.intensity, changed.intensity)
        assertEquals(2L, changed.bodyWeightId)
        assertEquals(7200L, changed.durationSeconds)
    }

    @Test fun `a deleted measurement does not erase previously captured body weight or manual inputs`() {
        val initial = WorkoutEnergyMath.estimate(workout(), weight(), NutritionSettingsEntity(), null, zone)!!
            .copy(bodyWeightId = null, manualKcal = 77.0)
        val recalculated = WorkoutEnergyMath.estimate(workout(), null, NutritionSettingsEntity(), initial, zone)!!
        assertEquals(initial, recalculated)
        assertEquals(77.0, recalculated.effectiveKcal, 0.0)
        assertNull(recalculated.bodyWeightId)
    }

    @Test fun `local start day handles midnight DST and keeps a captured day until start time changes`() {
        val ny = ZoneId.of("America/New_York")
        val nearMidnight = Instant.parse("2026-03-08T04:30:00Z").toEpochMilli()
        val session = EnergyWorkout(1, nearMidnight, nearMidnight + 3_600_000)
        val saved = WorkoutEnergyMath.estimate(session, weight().copy(day = "2026-03-07"), NutritionSettingsEntity(), null, ny)!!
        assertEquals("2026-03-07", saved.day)
        assertEquals(saved.day, WorkoutEnergyMath.day(session, saved, zone))
        val shifted = session.copy(startTime = nearMidnight + 86_400_000, endTime = nearMidnight + 90_000_000)
        assertEquals("2026-03-09", WorkoutEnergyMath.day(shifted, saved, zone))
        assertEquals(3600L, saved.durationSeconds)
        val beforeJump = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli()
        val acrossJump = WorkoutEnergyMath.estimate(EnergyWorkout(2, beforeJump, beforeJump + 7_200_000),
            weight().copy(day = "2026-03-08"), NutritionSettingsEntity(), null, ny)!!
        assertEquals("2026-03-08", acrossJump.day)
        assertEquals(7200L, acrossJump.durationSeconds)
        assertEquals(210.0, acrossJump.calculatedKcal, 0.000001)
    }

    @Test fun `invalid nonfinite negative and excessive inputs reject without producing invalid calories`() {
        for (kg in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 1001.0))
            assertThrows(IllegalArgumentException::class.java) { WorkoutEnergyMath.activeKcal(3600, kg, 3.5, .5) }
        for (met in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 101.0))
            assertThrows(IllegalArgumentException::class.java) { WorkoutEnergyMath.activeKcal(3600, 80.0, met, .5) }
        for (fraction in listOf(-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { WorkoutEnergyMath.activeKcal(3600, 80.0, 3.5, fraction) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutEnergyMath.activeKcal(0, 80.0, 3.5, .5) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutEnergyMath.activeKcal(Long.MAX_VALUE, 1000.0, 100.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutEnergyMath.estimate(workout().copy(endTime = start - 1), weight(), NutritionSettingsEntity(), null, zone)
        }
    }

    private fun workout() = EnergyWorkout(1, start, start + 3_600_000)
    private fun weight() = BodyWeightEntity(id = 1, day = "2026-10-12", kg = 80.0, measuredAt = 1)
}

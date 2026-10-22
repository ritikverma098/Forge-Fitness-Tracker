package com.forge.workout.utils

import com.forge.workout.model.Exercise
import com.forge.workout.model.ExerciseUsage
import com.forge.workout.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ExerciseRankingTest {
    @Test fun `recent use comes first by latest date then older history by frequency then unused`() {
        val exercises = listOf(
            exercise(5, "A unused"), exercise(4, "Z older few"), exercise(3, "A older frequent"),
            exercise(2, "A recent frequent"), exercise(1, "Z latest custom"),
        )
        val original = exercises.toList()
        val usage = listOf(
            usage(1, daysAgo = 1, count = 1), usage(2, daysAgo = 2, count = 100),
            usage(3, daysAgo = 31, count = 40), usage(4, daysAgo = 45, count = 2),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), ExerciseRanking.rank(exercises, usage, NOW).map { it.id })
        assertEquals(original, exercises)
    }

    @Test fun `recent date ties use frequency then name and stable ID`() {
        val exercises = listOf(exercise(5, "alpha"), exercise(4, "Alpha"), exercise(3, "Beta"), exercise(2, "Zulu"), exercise(1, "A low frequency"))
        val usage = exercises.map { usage(it.id, daysAgo = 1, count = if (it.id == 1L) 1 else if (it.id == 2L) 8 else 4) }
        assertEquals(listOf(2L, 4L, 5L, 3L, 1L), ExerciseRanking.rank(exercises, usage, NOW).map { it.id })
    }

    @Test fun `older frequency ties use latest date before alphabetical name`() {
        val exercises = listOf(exercise(1, "A oldest"), exercise(2, "Z newer"), exercise(3, "A frequent"))
        val usage = listOf(usage(1, 60, 2), usage(2, 40, 2), usage(3, 70, 3))
        assertEquals(listOf(3L, 2L, 1L), ExerciseRanking.rank(exercises, usage, NOW).map { it.id })
    }

    @Test fun `exactly thirty days ago remains recent while an earlier millisecond is older`() {
        val exercises = listOf(exercise(1, "Z boundary"), exercise(2, "A older frequent"))
        val cutoff = NOW - 30 * DAY
        val usage = listOf(ExerciseUsage(1, cutoff, 1), ExerciseUsage(2, cutoff - 1, 100))
        assertEquals(listOf(1L, 2L), ExerciseRanking.rank(exercises, usage, NOW).map { it.id })
    }

    @Test fun `no history remains alphabetical with custom and default exercises mixed together`() {
        val exercises = listOf(exercise(1, "Zulu", custom = false), exercise(2, "Alpha"), exercise(3, "Beta", custom = false))
        assertEquals(listOf(2L, 3L, 1L), ExerciseRanking.rank(exercises, emptyList(), NOW).map { it.id })
        assertTrue(ExerciseRanking.rank(emptyList(), emptyList(), NOW).isEmpty())
    }

    @Test fun `alphabetical ties use ROOT locale and IDs independently of device locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val exercises = listOf(exercise(2, "iron"), exercise(1, "Ink"), exercise(4, "alpha"), exercise(3, "Alpha"))
            assertEquals(listOf(3L, 4L, 1L, 2L), ExerciseRanking.rank(exercises, emptyList(), NOW).map { it.id })
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun `usage never adds unknown or duplicate exercise rows and recent custom exercises rank naturally`() {
        val exercises = listOf(exercise(1, "A default", custom = false), exercise(2, "Z custom"), exercise(3, "B unused custom"))
        val usage = listOf(usage(1, 40, 100), usage(2, 1, 1), usage(2, 1, 1), usage(999, 0, 999))
        val result = ExerciseRanking.rank(exercises, usage, NOW)
        assertEquals(listOf(2L, 1L, 3L), result.map { it.id })
        assertEquals(exercises.size, result.map { it.id }.distinct().size)
    }

    @Test fun `picker search and combined primary secondary groups retain ranking without duplicates`() {
        val exercises = listOf(
            exercise(1, "Z custom press").copy(secondaryMuscleGroup = MuscleGroup.TRICEPS),
            exercise(2, "A bench press", custom = false).copy(secondaryMuscleGroup = MuscleGroup.TRICEPS),
            exercise(3, "B overhead press").copy(primaryMuscleGroup = MuscleGroup.SHOULDERS, secondaryMuscleGroup = MuscleGroup.TRICEPS),
            exercise(4, "A row").copy(primaryMuscleGroup = MuscleGroup.BACK),
            exercise(5, "Archived press").copy(archived = true),
        )
        val usage = listOf(usage(1, 1, 1), usage(2, 40, 100), usage(3, 3, 3), usage(4, 2, 2), usage(5, 0, 999))
        val ranked = ExerciseRanking.rank(exercises, usage, NOW)
        val groups = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS)
        val result = ExerciseFiltering.filter(ranked, query = "  PRESS  ", workoutMuscleGroups = groups, sortAlphabetically = false)
        assertEquals(listOf(1L, 3L, 2L), result.map { it.id })
        assertEquals(3, result.map { it.id }.distinct().size)
        assertTrue(result.none { it.archived })
        assertEquals(listOf(4L), ExerciseFiltering.filter(
            ranked, query = "ROW", workoutMuscleGroups = groups, selectedMuscleGroup = MuscleGroup.BACK, sortAlphabetically = false,
        ).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), ExerciseFiltering.filter(
            ranked, query = "press", workoutMuscleGroups = setOf(MuscleGroup.LEGS), showAll = true, sortAlphabetically = false,
        ).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), ExerciseFiltering.filter(ranked, query = "press", workoutMuscleGroups = groups).map { it.id })
    }

    private fun exercise(id: Long, name: String, custom: Boolean = true) =
        Exercise(id = id, name = name, primaryMuscleGroup = MuscleGroup.CHEST, isCustom = custom)

    private fun usage(id: Long, daysAgo: Int, count: Int) = ExerciseUsage(id, NOW - daysAgo * DAY, count)

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 100 * DAY
    }
}

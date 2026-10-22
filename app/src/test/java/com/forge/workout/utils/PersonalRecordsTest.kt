package com.forge.workout.utils

import com.forge.workout.model.ExerciseType
import com.forge.workout.utils.WorkoutCalculationsTest.Companion.fixture
import com.forge.workout.utils.WorkoutCalculationsTest.Companion.set
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRecordsTest {
    @Test fun `new best weight reps and volume are detected`() {
        val previous = fixture(id = 1, time = 1000, sets = listOf(set(60.0, 8)))
        val current = fixture(id = 2, time = 2000, sets = listOf(set(60.0, 10), set(70.0, 5)))
        val records = PersonalRecords.detect(current, listOf(previous, current))
        assertEquals(setOf(RecordType.HIGHEST_WEIGHT, RecordType.REPS_AT_WEIGHT, RecordType.WORKOUT_VOLUME), records.map { it.type }.toSet())
        assertEquals(70.0, records.first { it.type == RecordType.HIGHEST_WEIGHT }.value, 0.001)
    }

    @Test fun `matching earlier performance is not a record`() {
        val previous = fixture(id = 1, time = 1000, sets = listOf(set(70.0, 10)))
        val current = fixture(id = 2, time = 2000, sets = listOf(set(70.0, 10)))
        assertTrue(PersonalRecords.detect(current, listOf(previous)).isEmpty())
    }

    @Test fun `incomplete set cannot achieve a record`() {
        val current = fixture(sets = listOf(set(200.0, 20, completed = false)))
        assertTrue(PersonalRecords.detect(current, emptyList()).isEmpty())
    }

    @Test fun `reopening earlier summary ignores later workout`() {
        val previous = fixture(id = 1, time = 1000, sets = listOf(set(60.0, 10)))
        val later = fixture(id = 2, time = 2000, sets = listOf(set(100.0, 20)))
        assertEquals(PersonalRecords.detect(previous, emptyList()), PersonalRecords.detect(previous, listOf(previous, later)))
    }

    @Test fun `bodyweight repetition record is detected without weight record`() {
        val current = fixture(type = ExerciseType.BODYWEIGHT, sets = listOf(set(0.0, 20)))
        val records = PersonalRecords.detect(current, emptyList())
        assertEquals(1, records.size)
        assertEquals(RecordType.REPS_AT_WEIGHT, records.first().type)
        assertEquals(20.0, records.first().value, 0.001)
    }

    @Test fun `weight conversion noise does not create new repetition buckets`() {
        val previous = fixture(id = 1, time = 1000, sets = listOf(set(45.359237, 10)))
        val current = fixture(id = 2, time = 2000, sets = listOf(set(45.3592, 8)))
        assertFalse(PersonalRecords.detect(current, listOf(previous)).any { it.type == RecordType.REPS_AT_WEIGHT })
    }
}

package com.forge.workout.nutrition.data

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiaryRepositoryHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java).build()
        repository = RoomNutritionRepository(database)
    }
    @After fun tearDown() { database.close() }

    @Test fun `repeat deduplicates requested IDs in order and retains snapshots after source removal`() = runBlocking<Unit> {
        repository.upsertFood(FoodDetail(FoodEntity(id = "source", name = "Original food"),
            snapshot().nutrients.map { FoodNutrientEntity("source", it.code, it.name, it.unit, it.value) }))
        val firstId = repository.saveDiary(entry().copy(portionId = "portion", portionLabel = "Original portion",
            unit = FoodUnit.SERVING, quantity = 2.0, factor = 1.7))
        val secondId = repository.saveDiary(entry().copy(quantity = 50.0, factor = .5, loggedAt = 2))
        val originals = listOf(repository.diaryEntry(firstId)!!, repository.diaryEntry(secondId)!!)
        repository.deleteFood("source")
        val beforeRepeat = System.currentTimeMillis()
        val newIds = repository.repeatDiary(listOf(secondId, firstId, secondId), NEXT_DAY, MealType.DINNER)
        assertEquals(2, newIds.size)
        assertEquals(2, newIds.distinct().size)
        assertTrue(newIds.none { it == firstId || it == secondId })
        val copies = newIds.map { repository.diaryEntry(it)!! }
        assertEquals(originals.reversed().map { it.snapshot }, copies.map { it.snapshot })
        copies.forEachIndexed { index, copy ->
            val original = originals.reversed()[index]
            assertEquals(original.copy(id = copy.id, day = NEXT_DAY, meal = MealType.DINNER, loggedAt = copy.loggedAt), copy)
            assertTrue(copy.loggedAt >= beforeRepeat)
        }
        assertEquals(originals, repository.observeDiary(DAY).first())
        assertEquals(copies, repository.observeDiary(NEXT_DAY).first())
        assertNull(repository.food("source"))
    }

    @Test fun `repeat validates every source and destination before any inserts and rolls SQL failures back`() = runBlocking<Unit> {
        val firstId = repository.saveDiary(entry())
        val secondId = repository.saveDiary(entry().copy(loggedAt = 2))
        val before = repository.observeDiary(DAY).first()
        for (ids in listOf(emptyList(), listOf(0L), listOf(-1L), listOf(firstId, Long.MAX_VALUE), List(1001) { firstId })) {
            expectInvalid { repository.repeatDiary(ids, NEXT_DAY, MealType.LUNCH) }
        }
        expectInvalid { repository.repeatDiary(listOf(firstId), "2026-02-30", MealType.LUNCH) }
        assertTrue(repository.observeDiary(NEXT_DAY).first().isEmpty())
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_diary_copy BEFORE INSERT ON nutrition_diary WHEN NEW.day = '$NEXT_DAY' AND NEW.quantity = 50 BEGIN SELECT RAISE(ABORT, 'Injected diary storage failure'); END")
        repository.saveDiary(repository.diaryEntry(secondId)!!.copy(quantity = 50.0, factor = .5))
        val sourceBeforeFailure = repository.observeDiary(DAY).first()
        try { repository.repeatDiary(listOf(firstId, secondId), NEXT_DAY, MealType.SNACK); throw AssertionError("Expected SQL abort") }
        catch (_: SQLiteConstraintException) { }
        assertTrue(repository.observeDiary(NEXT_DAY).first().isEmpty())
        assertEquals(sourceBeforeFailure, repository.observeDiary(DAY).first())
        assertEquals(before.first(), repository.diaryEntry(firstId))
    }

    @Test fun `one thousand sources repeat within older SQLite bind limits without duplicates`() = runBlocking<Unit> {
        val ids = database.withTransaction {
            database.nutritionDao().insertDiaryEntries((0 until 1000).map { entry().copy(loggedAt = it.toLong()) })
        }
        val repeated = repository.repeatDiary(ids, NEXT_DAY, MealType.BREAKFAST)
        assertEquals(1000, repeated.size)
        assertEquals(1000, repeated.distinct().size)
        assertEquals(1000, repository.observeDiary(DAY).first().size)
        assertEquals(1000, repository.observeDiary(NEXT_DAY).first().size)
        assertEquals(repository.observeDiary(DAY).first().map { it.snapshot },
            repository.observeDiary(NEXT_DAY).first().map { it.snapshot })
    }

    @Test fun `queued repeat freezes caller IDs before waiting for another database transaction`() = runBlocking<Unit> {
        val id = repository.saveDiary(entry())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = launch(Dispatchers.Default) {
            database.withTransaction { started.complete(Unit); release.await() }
        }
        started.await()
        val selected = mutableListOf(id)
        val copy = async(start = CoroutineStart.UNDISPATCHED) { repository.repeatDiary(selected, NEXT_DAY, MealType.LUNCH) }
        selected.clear()
        assertFalse(copy.isCompleted)
        release.complete(Unit)
        blocker.join()
        val repeated = copy.await()
        assertEquals(1, repeated.size)
        assertEquals(repository.diaryEntry(id)!!.snapshot, repository.diaryEntry(repeated.single())!!.snapshot)
    }

    @Test fun `quantity and destination edits retain immutable identity timestamp and recompute nullable caches`() = runBlocking<Unit> {
        val id = repository.saveDiary(entry())
        val original = repository.diaryEntry(id)!!
        val saved = original.copy(day = NEXT_DAY, meal = MealType.SNACK, quantity = 50.0, factor = .5,
            loggedAt = 99999, kcal = 999.0, protein = 999.0)
        assertEquals(id, repository.saveDiary(saved))
        val current = repository.diaryEntry(id)!!
        assertEquals(original.snapshot, current.snapshot)
        assertEquals(original.foodId, current.foodId)
        assertEquals(original.loggedAt, current.loggedAt)
        assertEquals(NEXT_DAY, current.day)
        assertEquals(MealType.SNACK, current.meal)
        assertEquals(50.0, current.kcal!!, 0.0)
        assertNull(current.protein)
        assertEquals(0.0, current.fat!!, 0.0)
        expectInvalid { repository.saveDiary(current.copy(snapshot = current.snapshot.copy(name = "Replacement food"))) }
        expectInvalid { repository.saveDiary(current.copy(foodId = "different", snapshot = current.snapshot.copy(foodId = "different"))) }
        expectInvalid { repository.saveDiary(current.copy(day = "invalid")) }
        assertEquals(current, repository.diaryEntry(id))
        repository.deleteDiary(id)
        expectInvalid { repository.saveDiary(current) }
        assertTrue(repository.observeDiary(NEXT_DAY).first().isEmpty())
    }

    @Test fun `move uses the latest row and preserves a prior quantity edit and every recorded value`() = runBlocking<Unit> {
        val id = repository.saveDiary(entry())
        repository.saveDiary(repository.diaryEntry(id)!!.copy(quantity = 250.0, factor = 2.5))
        val latest = repository.diaryEntry(id)!!
        repository.moveDiary(id, NEXT_DAY, MealType.BREAKFAST)
        assertEquals(latest.copy(day = NEXT_DAY, meal = MealType.BREAKFAST), repository.diaryEntry(id))
        assertTrue(repository.observeDiary(DAY).first().isEmpty())
        expectInvalid { repository.moveDiary(id, "2026-99-99", MealType.LUNCH) }
        repository.deleteDiary(id)
        expectInvalid { repository.moveDiary(id, DAY, MealType.LUNCH) }
        assertTrue(repository.observeDiary(NEXT_DAY).first().isEmpty())
    }

    @Test fun `targeted live observation refreshes edits moves and deletion with primary key indexed reads`() = runBlocking<Unit> {
        val id = repository.saveDiary(entry())
        val channel = Channel<DiaryEntity?>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { repository.observeDiaryEntry(id).collect { channel.send(it) } }
        try {
            await(channel) { it != null && it.id == id && it.factor == 1.0 }
            repository.saveDiary(repository.diaryEntry(id)!!.copy(quantity = 150.0, factor = 1.5))
            assertEquals(150.0, await(channel) { it?.factor == 1.5 }!!.kcal!!, 0.0)
            repository.moveDiary(id, NEXT_DAY, MealType.DINNER)
            await(channel) { it != null && it.day == NEXT_DAY && it.meal == MealType.DINNER }
            repository.deleteDiary(id)
            await(channel) { it == null }
            assertNull(repository.observeDiaryEntry(id).first())
        } finally { collector.cancelAndJoin(); channel.cancel() }
        val plan = database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM nutrition_diary WHERE id = ?", arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }
        assertTrue(plan.any { it.contains("INTEGER PRIMARY KEY", true) })
        println("Diary detail indexed read: $plan")
    }

    private suspend fun await(channel: Channel<DiaryEntity?>, predicate: (DiaryEntity?) -> Boolean): DiaryEntity? = withTimeout(5000) {
        var row = channel.receive()
        while (!predicate(row)) row = channel.receive()
        row
    }
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected validation failure") }
        catch (_: IllegalArgumentException) { }
    }
    private fun snapshot() = FoodSnapshot(foodId = "source", name = "Original food", source = "Original source",
        gramsPerBase = 100.0, nutrients = listOf(NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", 100.0),
            NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", null),
            NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", 20.0),
            NutrientValue(NutrientCodes.FAT, "Fat", "g", 0.0)))
    private fun entry() = DiaryEntity(day = DAY, meal = MealType.LUNCH, foodId = "source", snapshot = snapshot(),
        quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 1)
    private companion object {
        const val DAY = "2026-10-13"
        const val NEXT_DAY = "2026-10-14"
    }
}

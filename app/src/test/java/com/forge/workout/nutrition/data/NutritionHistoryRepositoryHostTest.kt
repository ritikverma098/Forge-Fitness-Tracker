package com.forge.workout.nutrition.data

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NutritionHistoryRepositoryHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private val queries = CopyOnWriteArrayList<RecordedQuery>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java)
            .setQueryCallback({ sql, arguments -> queries += RecordedQuery(sql, arguments.toList()) }, Executor { it.run() }).build()
        repository = RoomNutritionRepository(database)
    }
    @After fun tearDown() { database.close() }

    @Test fun `cached daily aggregation keeps independent missing counts real zero and original scaled values`() = runBlocking<Unit> {
        repository.saveDiary(entry(DAY, 100.0, null, 10.0, 0.0))
        repository.saveDiary(entry(DAY, null, 4.0, null, 0.0).copy(quantity = 150.0, factor = 1.5))
        repository.saveDiary(entry(DAY, 50.0, null, 5.0, null))
        repository.saveDiary(entry("2026-10-14", null, null, null, null))
        val originals = repository.observeDiary(DAY).first()
        val history = repository.observeNutritionHistory().first()
        assertEquals(listOf("2026-10-14", DAY), history.map { it.day })
        val day = history.last()
        assertEquals(3, day.entryCount)
        assertEquals(DiaryMath.totals(originals), day.totals)
        assertEquals(NutrientTotal(150.0, 1), day.totals.kcal)
        assertEquals(NutrientTotal(6.0, 2), day.totals.protein)
        assertEquals(NutrientTotal(15.0, 1), day.totals.carbs)
        assertEquals(NutrientTotal(0.0, 1), day.totals.fat)
        assertEquals(NutrientTotal(0.0, 1), history.first().totals.kcal)
        assertNull(day.goal)
        assertEquals(originals, repository.observeDiary(DAY).first())
        assertTrue(repository.observeNutritionHistory(fromDay = "2026-11-01").first().isEmpty())
    }

    @Test fun `effective goals are resolved independently by diary day including all unset versions`() = runBlocking<Unit> {
        for (day in listOf("2026-10-01", "2026-10-02", "2026-10-05", "2026-10-09")) repository.saveDiary(entry(day))
        val earlier = NutritionGoalEntity("2026-10-02", 2000.0, 100.0, 200.0, 70.0)
        val unset = NutritionGoalEntity("2026-10-05")
        val later = NutritionGoalEntity("2026-10-08", kcal = 2400.0)
        repository.saveGoal(earlier); repository.saveGoal(unset); repository.saveGoal(later)
        val goals = repository.observeNutritionHistory().first().associate { it.day to it.goal }
        assertNull(goals["2026-10-01"])
        assertEquals(earlier, goals["2026-10-02"])
        assertEquals(unset, goals["2026-10-05"])
        assertEquals(later, goals["2026-10-09"])
        assertNull(DiaryMath.remaining(goals.getValue("2026-10-05")!!.kcal, NutrientTotal(100.0)))
        assertEquals(1900.0, DiaryMath.remaining(earlier.kcal, NutrientTotal(100.0))!!, 0.0)
    }

    @Test fun `inclusive assigned calendar dates ignore logged timestamp and cover valid ISO boundary years`() = runBlocking<Unit> {
        val days = listOf("0001-01-01", "2026-03-07", "2026-03-08", "2026-03-09", "9999-12-31")
        days.forEachIndexed { index, day -> repository.saveDiary(entry(day).copy(loggedAt = 100L - index)) }
        assertEquals(listOf("2026-03-09", "2026-03-08", "2026-03-07"),
            repository.observeNutritionHistory("2026-03-07", "2026-03-09").first().map { it.day })
        assertEquals(listOf("2026-03-08"), repository.observeNutritionHistory("2026-03-08", "2026-03-08").first().map { it.day })
        assertEquals(days.reversed(), repository.observeNutritionHistory().first().map { it.day })
        assertEquals(listOf("0001-01-01"), repository.observeNutritionHistory(toDay = "0001-01-01").first().map { it.day })
        assertEquals(listOf("9999-12-31"), repository.observeNutritionHistory(fromDay = "9999-12-31").first().map { it.day })
    }

    @Test fun `stable bounded paging groups entries before slicing and covers every date without duplicates`() = runBlocking<Unit> {
        val first = LocalDate.of(2026, 1, 1)
        database.withTransaction {
            for (index in 0 until 237) {
                val day = first.plusDays(index.toLong()).toString()
                repository.saveDiary(entry(day))
                if (index % 2 == 0) repository.saveDiary(entry(day).copy(loggedAt = 2))
            }
        }
        val pages = (0 until 5).map { repository.observeNutritionHistory(limit = 50, offset = it * 50).first() }
        assertEquals(listOf(50, 50, 50, 50, 37), pages.map { it.size })
        val all = pages.flatten()
        assertEquals(237, all.map { it.day }.distinct().size)
        assertEquals((236 downTo 0).map { first.plusDays(it.toLong()).toString() }, all.map { it.day })
        all.forEach { day ->
            val index = LocalDate.parse(day.day).toEpochDay() - first.toEpochDay()
            assertEquals(if (index % 2L == 0L) 2 else 1, day.entryCount)
            assertEquals(day.entryCount * 100.0, day.totals.kcal.knownValue, 0.0)
        }
        assertEquals(all.take(200), repository.observeNutritionHistory(limit = 200).first())
        assertTrue(repository.observeNutritionHistory(offset = 237).first().isEmpty())
    }

    @Test fun `one live history collector refreshes quantity moves deletions and effective goal changes`() = runBlocking<Unit> {
        val id = repository.saveDiary(entry(DAY))
        val channel = Channel<List<NutritionHistoryDay>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) { repository.observeNutritionHistory().collect { channel.send(it) } }
        try {
            await(channel) { it.singleOrNull()?.totals?.kcal?.knownValue == 100.0 }
            val original = repository.diaryEntry(id)!!
            repository.saveDiary(original.copy(quantity = 150.0, factor = 1.5))
            await(channel) { it.singleOrNull()?.totals?.kcal?.knownValue == 150.0 }
            val goal = NutritionGoalEntity("2026-10-01", kcal = 2200.0, protein = 130.0)
            repository.saveGoal(goal)
            await(channel) { it.singleOrNull()?.goal == goal }
            val replacement = goal.copy(kcal = 2300.0)
            repository.saveGoal(replacement)
            await(channel) { it.singleOrNull()?.goal == replacement }
            repository.moveDiary(id, "2026-10-14", MealType.DINNER)
            await(channel) { it.singleOrNull()?.day == "2026-10-14" }
            repository.deleteGoal(goal.effectiveDay)
            await(channel) { it.size == 1 && it.single().goal == null }
            assertEquals(original.snapshot, repository.diaryEntry(id)!!.snapshot)
            repository.deleteDiary(id)
            await(channel) { it.isEmpty() }
        } finally { collector.cancelAndJoin(); channel.cancel() }
    }

    @Test fun `one aggregate SQL projection omits snapshots and catalog reads and uses existing diary and goal indexes`() = runBlocking<Unit> {
        val id = repository.saveDiary(entry(DAY).copy(snapshot = entry(DAY).snapshot.copy(name = "Recorded snapshot unchanged")))
        repository.saveGoal(NutritionGoalEntity("2026-10-01", kcal = 2000.0))
        val original = repository.diaryEntry(id)!!
        queries.clear()
        val summary = repository.observeNutritionHistory(limit = 50).first().single()
        assertEquals(100.0, summary.totals.kcal.knownValue, 0.0)
        val aggregates = queries.filter { it.sql.trimStart().startsWith("WITH daily", true) }
        assertEquals(1, aggregates.size)
        val query = aggregates.single()
        assertFalse(query.sql.contains("snapshot", true))
        assertFalse(query.sql.contains("SELECT *", true))
        assertFalse(queries.any { it.sql.contains("nutrition_food", true) || it.sql.contains("FROM nutrition_diary WHERE id", true) })
        assertEquals(listOf("0001-01-01", "9999-12-31"), query.arguments.take(2))
        assertEquals(listOf(50, 0), query.arguments.drop(2).map { (it as Number).toInt() })
        val plan = database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN ${query.sql}", query.arguments.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }
        assertTrue(plan.any { it.contains("index_nutrition_diary_day_loggedAt") })
        assertTrue(plan.any { it.contains("nutrition_goals") && it.contains("INDEX", true) })
        assertEquals(original, repository.diaryEntry(id))
        println("Nutrition history evidence: one cached aggregate query; no snapshot JSON; plans=$plan")
    }

    @Test fun `invalid history ranges and page bounds fail before accessing SQLite`() {
        assertThrows(IllegalArgumentException::class.java) { repository.observeNutritionHistory("2026-02-30", "2026-03-01") }
        assertThrows(IllegalArgumentException::class.java) { repository.observeNutritionHistory("2026-10-14", "2026-10-13") }
        assertThrows(IllegalArgumentException::class.java) { repository.observeNutritionHistory("0000-01-01", "2026-10-13") }
        assertThrows(IllegalArgumentException::class.java) { repository.observeNutritionHistory(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { repository.observeNutritionHistory(limit = 201) }
        assertThrows(IllegalArgumentException::class.java) { repository.observeNutritionHistory(offset = -1) }
        assertFalse(queries.any { it.sql.trimStart().startsWith("WITH daily", true) })
    }

    private suspend fun await(channel: Channel<List<NutritionHistoryDay>>, predicate: (List<NutritionHistoryDay>) -> Boolean) =
        withTimeout(5000) {
            var rows = channel.receive()
            while (!predicate(rows)) rows = channel.receive()
            rows
        }
    private fun entry(day: String, kcal: Double? = 100.0, protein: Double? = 4.0, carbs: Double? = 20.0, fat: Double? = 0.0) =
        DiaryEntity(day = day, meal = MealType.LUNCH, snapshot = FoodSnapshot(name = "Frozen food", nutrients = listOf(
            NutrientValue(NutrientCodes.ENERGY, "Energy", "kcal", kcal), NutrientValue(NutrientCodes.PROTEIN, "Protein", "g", protein),
            NutrientValue(NutrientCodes.CARBS, "Carbohydrates", "g", carbs), NutrientValue(NutrientCodes.FAT, "Fat", "g", fat))),
            quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = 1)
    private data class RecordedQuery(val sql: String, val arguments: List<Any?>)
    private companion object { const val DAY = "2026-10-13" }
}

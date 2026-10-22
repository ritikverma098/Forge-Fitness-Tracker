package com.forge.workout.nutrition.data

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.forge.workout.data.database.ForgeDatabase
import com.forge.workout.nutrition.model.*
import com.forge.workout.nutrition.repository.RoomNutritionRepository
import java.util.Locale
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Bounded generated Room queries, FTS matching and live diary/favorite invalidations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FoodSearchRepositoryHostTest {
    private lateinit var database: ForgeDatabase
    private lateinit var repository: RoomNutritionRepository
    private val queries = CopyOnWriteArrayList<String>()
    @Volatile private var capture = false

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ForgeDatabase::class.java)
            .setQueryCallback(object : RoomDatabase.QueryCallback {
                override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                    if (capture) queries += sqlQuery
                }
            }, Executor { it.run() }).build()
        repository = RoomNutritionRepository(database)
    }

    @After fun closeDatabase() { capture = false; database.close() }

    @Test fun `prefix name brand and aliases match case insensitively with literal AND search terms`() = runBlocking {
        save("chickpea", "Chickpeas", brand = "Harvest House", aliases = "chana garbanzo bengal gram")
        save("yogurt", "Plain yogurt", aliases = "dahi curd yoghurt")
        save("coffee", "Café drink")
        save("rice", "Rice")
        assertEquals(listOf("chickpea"), ids("CHICK"))
        assertEquals(listOf("chickpea"), ids("hArV"))
        assertEquals(listOf("chickpea"), ids("ChAn"))
        assertEquals(listOf("chickpea"), ids("harv chana"))
        assertEquals(listOf("yogurt"), ids("CUR"))
        assertEquals(listOf("yogurt"), ids("dahi"))
        assertEquals(listOf("coffee"), ids("CAFE"))
        assertEquals(listOf("chickpea"), ids("  \"chana\"  "))
        assertTrue(ids("chana OR rice").isEmpty())
        assertTrue(ids("' * --").isEmpty())
        assertTrue(ids("ana").isEmpty())
        assertTrue(ids("nonexistent").isEmpty())
    }

    @Test fun `all favorite Indian and branded filters intersect the same query and exclude archived or orphan identities`() = runBlocking {
        save("indian", "Rice Indian", region = "in")
        save("branded", "Rice packaged", brand = "Example brand")
        save("both", "Rice special", brand = "Local brand", region = "IN")
        save("plain", "Rice plain", brand = "   ")
        save("archived", "Rice archived", brand = "Archived brand", region = "IN", archived = true)
        repository.saveFavorite(FavoriteEntity("indian", savedAt = 1))
        repository.saveFavorite(FavoriteEntity("both", savedAt = 2))
        repository.saveFavorite(FavoriteEntity("archived", savedAt = 3))
        repository.saveFavorite(FavoriteEntity("missing", savedAt = 4))
        assertEquals(listOf("indian", "branded", "plain", "both"), ids("rice"))
        assertEquals(listOf("indian", "both"), ids("RICE", FoodBrowseFilter.INDIAN))
        assertEquals(listOf("branded", "both"), ids("ric", FoodBrowseFilter.BRANDED))
        assertEquals(listOf("indian", "both"), ids("Ri", FoodBrowseFilter.FAVORITES))
        assertEquals(listOf("both"), ids("local", FoodBrowseFilter.FAVORITES))
        assertTrue(ids("packaged", FoodBrowseFilter.INDIAN).isEmpty())
        assertTrue(ids("Rice archived").isEmpty())
    }

    @Test fun `recent and frequent deduplicate logical diary references use current food rows and include all logged calendar days`() = runBlocking {
        save("frequent", "Frequent rice")
        save("recent", "Recent rice")
        save("unused", "Unused rice")
        val original = repository.food("frequent")!!
        val saved = log(original.snapshot(), 10, "2020-01-01")
        log(original.snapshot(), 20, "2020-01-02")
        log(original.snapshot(), 30, "2020-01-03")
        log(repository.food("recent")!!.snapshot(), 100, "2099-01-01")
        log(FoodSnapshot(foodId = "orphan", name = "Snapshot only rice"), 1_000)
        log(FoodSnapshot(name = "Unlinked snapshot rice"), 2_000)
        assertEquals(listOf("recent", "frequent"), ids("rice", FoodBrowseFilter.RECENT))
        assertEquals(listOf("frequent", "recent"), ids("rice", FoodBrowseFilter.FREQUENT))
        repository.upsertFood(original.copy(food = original.food.copy(name = "Current quinoa")))
        val renamed = page("quinoa", FoodBrowseFilter.FREQUENT).single()
        assertEquals("frequent", renamed.id)
        assertEquals("Current quinoa", renamed.name)
        assertEquals("Frequent rice", repository.diaryEntry(saved)!!.snapshot.name)
        assertEquals(listOf("recent"), ids("rice", FoodBrowseFilter.RECENT))
        for (filter in listOf(FoodBrowseFilter.RECENT, FoodBrowseFilter.FREQUENT)) {
            val rows = page(filter = filter)
            assertEquals(rows.size, rows.distinctBy { it.id }.size)
            assertFalse(rows.any { it.id == "orphan" || it.id == "unused" })
        }
    }

    @Test fun `paged FTS and browse queries materialize only the requested page without relation or per food queries`() = runBlocking {
        database.withTransaction {
            database.nutritionDao().insertFoods((0 until 137).map { index ->
                FoodEntity(id = "food-$index", name = "Food ${index.toString().padStart(3, '0')}")
            })
        }
        queries.clear()
        capture = true
        val first = page("FOO")
        capture = false
        assertEquals(50, first.size)
        val reads = queries.map(::normalized).filter { it.startsWith("SELECT F.* FROM NUTRITION_FOODS") }
        assertEquals("Each page uses one SQL query rather than one detail query per food", 1, reads.size)
        assertTrue(reads.single().contains("NUTRITION_FOOD_SEARCH MATCH ?"))
        assertTrue(reads.single().contains("LIMIT ? OFFSET ?"))
        assertFalse(queries.any { it.contains("nutrition_food_nutrients", true) || it.contains("nutrition_food_portions", true) })
        val second = page("foo", offset = 50)
        val third = page("foo", offset = 100)
        assertEquals(listOf(50, 50, 37), listOf(first.size, second.size, third.size))
        val all = first + second + third
        assertEquals(137, all.map { it.id }.distinct().size)
        assertEquals(all, page(limit = 200))
        assertTrue(page("foo", offset = 150).isEmpty())
        val plan = queryPlan("EXPLAIN QUERY PLAN SELECT f.id FROM nutrition_foods f INNER JOIN nutrition_food_search ON f.rowid = nutrition_food_search.rowid WHERE nutrition_food_search MATCH '\"food*\"' AND f.archived = 0 ORDER BY f.name COLLATE NOCASE, f.id LIMIT 50")
        assertTrue(plan.any { it.contains("VIRTUAL TABLE", true) })
        println("Food paging evidence: fixture=137 rows, first=50, SQL reads=${reads.size}, FTS=$plan")
    }

    @Test fun `stable ties and filters are applied before paging rather than filtering an already limited list`() = runBlocking {
        database.withTransaction {
            database.nutritionDao().insertFoods((0 until 60).map { index ->
                FoodEntity(id = "plain-$index", name = "Alpha ${index.toString().padStart(2, '0')}")
            })
        }
        save("z", "Same rice", region = "IN", brand = "Z brand")
        save("a", "same RICE", region = "IN", brand = "A brand")
        repository.saveFavorite(FavoriteEntity("z", savedAt = 1))
        repository.saveFavorite(FavoriteEntity("a", savedAt = 2))
        val a = repository.food("a")!!.snapshot()
        val z = repository.food("z")!!.snapshot()
        log(z, 100); log(a, 100)
        for (filter in FoodBrowseFilter.entries.filter { it != FoodBrowseFilter.ALL }) {
            assertEquals(listOf("a", "z"), ids("rice", filter))
            assertEquals(listOf("a"), page("rice", filter, limit = 1).map { it.id })
            assertEquals(listOf("z"), page("rice", filter, limit = 1, offset = 1).map { it.id })
        }
        assertEquals(listOf("a", "z"), ids(filter = FoodBrowseFilter.INDIAN))
        val usagePlan = queryPlan("EXPLAIN QUERY PLAN SELECT foodId, COUNT(*), MAX(loggedAt) FROM nutrition_diary WHERE foodId IS NOT NULL GROUP BY foodId")
        assertTrue(usagePlan.any { it.contains("index_nutrition_diary_foodId") })
    }

    @Test fun `an existing favorite search subscriber refreshes saves renames archives and removals`() = runBlocking<Unit> {
        save("rice", "Rice")
        val channel = Channel<List<FoodEntity>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            repository.observeFoodPage("rice", FoodBrowseFilter.FAVORITES).collect { channel.send(it) }
        }
        try {
            await(channel) { it.isEmpty() }
            repository.saveFavorite(FavoriteEntity("rice", savedAt = 1))
            await(channel) { it.map { row -> row.id } == listOf("rice") }
            val original = repository.food("rice")!!
            repository.upsertFood(original.copy(food = original.food.copy(name = "Renamed rice")))
            assertEquals("Renamed rice", await(channel) { it.firstOrNull()?.name == "Renamed rice" }.single().name)
            repository.upsertFood(original.copy(food = original.food.copy(archived = true)))
            await(channel) { it.isEmpty() }
            repository.upsertFood(original)
            await(channel) { it.map { row -> row.id } == listOf("rice") }
            repository.deleteFavorite("rice")
            await(channel) { it.isEmpty() }
        } finally { collector.cancelAndJoin(); channel.cancel() }
    }

    @Test fun `usage subscribers refresh diary insert deletion and current food removal or restoration without duplicate rows`() = runBlocking<Unit> {
        save("a", "A food")
        save("b", "B food")
        val recent = Channel<List<FoodEntity>>(Channel.UNLIMITED)
        val frequent = Channel<List<FoodEntity>>(Channel.UNLIMITED)
        val recentJob = launch(Dispatchers.Default) { repository.observeFoodPage(filter = FoodBrowseFilter.RECENT).collect { recent.send(it) } }
        val frequentJob = launch(Dispatchers.Default) { repository.observeFoodPage(filter = FoodBrowseFilter.FREQUENT).collect { frequent.send(it) } }
        try {
            await(recent) { it.isEmpty() }; await(frequent) { it.isEmpty() }
            val aSnapshot = repository.food("a")!!.snapshot()
            log(aSnapshot, 10)
            await(recent) { it.ids() == listOf("a") }; await(frequent) { it.ids() == listOf("a") }
            val bLog = log(repository.food("b")!!.snapshot(), 20)
            await(recent) { it.ids() == listOf("b", "a") }; await(frequent) { it.ids() == listOf("b", "a") }
            log(aSnapshot, 15)
            await(frequent) { it.ids() == listOf("a", "b") }
            repository.deleteDiary(bLog)
            await(recent) { it.ids() == listOf("a") }; await(frequent) { it.ids() == listOf("a") }
            repository.deleteFood("a")
            await(recent) { it.isEmpty() }; await(frequent) { it.isEmpty() }
            save("a", "Restored current food")
            assertEquals("Restored current food", await(recent) { it.ids() == listOf("a") }.single().name)
            await(frequent) { it.ids() == listOf("a") }
        } finally {
            recentJob.cancelAndJoin(); frequentJob.cancelAndJoin(); recent.cancel(); frequent.cancel()
        }
    }

    @Test fun `invalid pages and excessive query length fail validation before querying SQLite`() {
        assertThrows(IllegalArgumentException::class.java) { repository.observeFoodPage(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { repository.observeFoodPage(limit = 201) }
        assertThrows(IllegalArgumentException::class.java) { repository.observeFoodPage(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { repository.observeFoodPage("x".repeat(251)) }
    }

    private suspend fun save(id: String, name: String, brand: String = "", aliases: String = "", region: String? = null,
        archived: Boolean = false) = repository.upsertFood(FoodDetail(FoodEntity(id = id, name = name, brand = brand,
        aliases = aliases, region = region, archived = archived, gramsPerBase = 100.0),
        listOf(FoodNutrientEntity(id, NutrientCodes.ENERGY, "Energy", "kcal", 200.0))))

    private suspend fun log(snapshot: FoodSnapshot, loggedAt: Long, day: String = "2026-10-12") =
        repository.saveDiary(DiaryEntity(day = day, meal = MealType.LUNCH, foodId = snapshot.foodId,
            snapshot = snapshot, quantity = 100.0, unit = FoodUnit.GRAM, factor = 1.0, loggedAt = loggedAt))
    private suspend fun page(query: String = "", filter: FoodBrowseFilter = FoodBrowseFilter.ALL, limit: Int = 50, offset: Int = 0) =
        repository.observeFoodPage(query, filter, limit, offset).first()
    private suspend fun ids(query: String = "", filter: FoodBrowseFilter = FoodBrowseFilter.ALL) = page(query, filter).ids()
    private fun List<FoodEntity>.ids() = map { it.id }
    private suspend fun await(channel: Channel<List<FoodEntity>>, predicate: (List<FoodEntity>) -> Boolean): List<FoodEntity> =
        withTimeout(10_000) {
            var page = channel.receive()
            while (!predicate(page)) page = channel.receive()
            page
        }
    private fun normalized(sql: String) = sql.replace(Regex("\\s+"), " ").trim().uppercase(Locale.ROOT)
    private fun queryPlan(sql: String) = database.openHelper.writableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
    }
}

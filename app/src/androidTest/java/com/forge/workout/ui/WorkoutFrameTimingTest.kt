package com.forge.workout.ui

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.forge.workout.ForgeTestApplication
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.ExerciseType
import com.forge.workout.model.WorkoutDetail
import com.forge.workout.preferences.ThemeMode
import com.forge.workout.preferences.WeightUnit
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measurement only: run the same method/build type/device before and after a focused change.
 * Uses the production frame clock and real-time input, without a Compose test clock or recording.
 * ForgeFrameTiming logs contain nanoseconds, raw frames and separately labelled first-three frames.
 * There are no absolute timing assertions; dropped reports and missing metrics remain explicit.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalComposeTracingApi::class)
class WorkoutFrameTimingTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var activity: MainActivity
    private val app get() = activity.application as ForgeTestApplication
    private val repository get() = app.repository

    @Before fun prepare() {
        activityRule.scenario.onActivity { activity = it }
        assertTrue("Use ForgeTestRunner: measurements must never alter installed training data",
            activity.application is ForgeTestApplication)
        runBlocking {
            repository.ensureSeeded()
            removeTestData()
            app.preferences.setTheme(ThemeMode.LIGHT)
            app.preferences.setUnit(WeightUnit.KG)
            app.preferences.setRest(0)
        }
        waitForText(text(R.string.home_ready))
        if (InstrumentationRegistry.getArguments().getString("traceCompositions").toBoolean()) {
            instrumentation.runOnMainSync { Composer.setTracer(FilteredCompositionTracer()) }
            Log.i(TAG, "type=COMPOSITION_TRACE enabled=true filter=app_and_material_navigation")
        }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { Composer.setTracer(null) }
        if (::activity.isInitialized && activity.application is ForgeTestApplication) {
            runBlocking { removeTestData() }
        }
    }

    @Test fun activeWorkoutIdleThenDragFrameTimings() {
        val original = createLargeWorkout()
        waitForText(text(R.string.home_resume_workout))
        tap(waitForText(text(R.string.home_resume_workout)))
        val list = waitForVerticalList()
        waitForAccessibilityIdle()
        val capture = FrameCapture(activity.window)
        capture.start()
        try {
            repeat(4) { cycle ->
                // This idle period is the workload under investigation, not a readiness delay.
                SystemClock.sleep(4_000)
                val direction = if (cycle % 2 == 0) "up" else "down"
                capture.measure("workout_${direction}_$cycle") {
                    drag(list, up = direction == "up")
                }
                assertEquals("Scrolling must not mutate sets, notes, order or timer deadlines",
                    original, runBlocking { workout(original.workout.id) })
            }
        } finally { capture.closeAndLog("active_workout") }
        assertEquals(8, original.exercises.size)
        assertEquals(48, original.exercises.sumOf { it.sets.size })
        assertEquals(null, runBlocking { workout(original.workout.id) }.workout.restEndTime)
    }

    @Test fun libraryIdleScrollAndToolbarBackFrameTimings() {
        val originalExercises = runBlocking { repository.observeExercises().first() }
        val capture = FrameCapture(activity.window)
        capture.start()
        try {
            repeat(5) { cycle ->
                tap(revealHomeLibrary())
                waitForText(text(R.string.library_subtitle))
                val list = waitForVerticalList()
                waitForAccessibilityIdle()
                SystemClock.sleep(4_000)
                capture.measure("library_up_$cycle") { drag(list, up = true) }
                val back = waitForDescription(text(R.string.back))
                capture.measure("library_toolbar_back_$cycle") { tap(back) }
                waitForText(text(R.string.home_exercises))
            }
        } finally { capture.closeAndLog("library_back") }
        assertEquals(originalExercises, runBlocking { repository.observeExercises().first() })
        assertTrue(runBlocking { repository.observeWorkouts().first().isEmpty() })
    }

    private fun createLargeWorkout(): WorkoutDetail = runBlocking {
        val exercises = repository.observeExercises().first()
            .filter { it.exerciseType == ExerciseType.WEIGHT_REPS }.take(8)
        val id = repository.startWorkout("Frame timing fixture")
        exercises.forEach { exercise ->
            repository.addExercise(id, exercise.id)
            val entry = workout(id).exercises.single { it.exercise.id == exercise.id }
            repeat(5) { repository.addSet(entry.entry.id) }
            workout(id).exercises.single { it.entry.id == entry.entry.id }.sets.forEach { set ->
                repository.updateSet(set.copy(weight = 60.0, reps = 8, completed = false))
            }
        }
        workout(id)
    }

    private suspend fun workout(id: Long) = repository.observeWorkouts().first().single { it.workout.id == id }
    private suspend fun removeTestData() {
        repository.observeWorkouts().first().forEach { repository.deleteWorkout(it.workout.id) }
        repository.observeRoutines().first().forEach { repository.deleteRoutine(it.routine.id) }
        repository.observeExercises().first().filter { it.isCustom }.forEach { repository.deleteExercise(it.id) }
    }

    private fun text(id: Int) = activity.getString(id)
    private fun waitForText(value: String) = awaitNode { it.text == value }
    private fun waitForDescription(value: String) = awaitNode { it.description == value }
    private fun waitForVerticalList() = awaitNode(largest = true) {
        it.scrollable && it.bounds.height() > 120 * activity.resources.displayMetrics.density
    }

    private fun revealHomeLibrary(): Rect {
        repeat(6) {
            findNode { it.text == text(R.string.home_exercises) }?.let { return it }
            drag(waitForVerticalList(), up = true)
            waitForAccessibilityIdle()
        }
        throw AssertionError("Home Exercise Library shortcut did not become visible")
    }

    private data class Node(val text: String?, val description: String?, val bounds: Rect, val scrollable: Boolean)
    private fun awaitNode(largest: Boolean = false, predicate: (Node) -> Boolean): Rect {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) {
            findNode(largest, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Required visible accessibility node did not appear")
    }

    private fun findNode(largest: Boolean = false, predicate: (Node) -> Boolean): Rect? {
        val matches = ArrayList<Node>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val value = Node(node.text?.toString(), node.contentDescription?.toString(), bounds, node.isScrollable)
            if (!bounds.isEmpty && predicate(value)) matches.add(value)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return (if (largest) matches.maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            else matches.firstOrNull())?.bounds
    }

    private fun tap(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        pointer(down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
        pointer(down, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun drag(bounds: Rect, up: Boolean) {
        // The left list gutter avoids selecting numeric fields or invoking row actions.
        val x = bounds.left + minOf(bounds.width() * 0.05f, 10 * activity.resources.displayMetrics.density)
        val start = bounds.top + bounds.height() * if (up) 0.8f else 0.2f
        val finish = bounds.top + bounds.height() * if (up) 0.2f else 0.8f
        val down = SystemClock.uptimeMillis()
        pointer(down, MotionEvent.ACTION_DOWN, x, start)
        try {
            for (step in 1..30) {
                val target = down + 500L * step / 30
                SystemClock.sleep((target - SystemClock.uptimeMillis()).coerceAtLeast(0))
                pointer(down, MotionEvent.ACTION_MOVE, x, start + (finish - start) * step / 30)
            }
            pointer(down, MotionEvent.ACTION_UP, x, finish)
        } catch (failure: Throwable) {
            pointer(down, MotionEvent.ACTION_CANCEL, x, finish)
            throw failure
        }
    }

    private fun pointer(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Native input injection failed", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun waitForAccessibilityIdle(): Boolean = try {
        automation.waitForIdle(150, 3_000)
        true
    } catch (_: TimeoutException) { false }

    private data class Frame(val intended: Long, val total: Long, val layout: Long, val draw: Long, val unknown: Long,
        val input: Long, val droppedReports: Int)
    private data class Segment(val name: String, val start: Long, val inputEnd: Long, val end: Long, val idleReached: Boolean)

    /** Diagnostic opt-in only; every compiler start must consume its matching end, including filtered calls. */
    private class FilteredCompositionTracer : CompositionTracer {
        private val tracedCalls = ThreadLocal.withInitial { ArrayDeque<Boolean>() }

        override fun isTraceInProgress() = true

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            val function = info.substringBefore(" (")
            val name = function.substringAfterLast('.')
            val included = function.startsWith("com.forge.workout.") ||
                (function.startsWith("androidx.compose.material3.") &&
                    (name.startsWith("NavigationBar") || name.startsWith("NavigationItem")))
            requireNotNull(tracedCalls.get()).addLast(included)
            if (included) Trace.beginSection("ComposeFn:${function.takeLast(117)}")
        }

        override fun traceEventEnd() {
            if (requireNotNull(tracedCalls.get()).pollLast() == true) Trace.endSection()
        }
    }

    private inner class FrameCapture(private val window: Window) {
        private val thread = HandlerThread("ForgeFrameTiming")
        private lateinit var handler: Handler
        private val frames = ConcurrentLinkedQueue<Frame>()
        private val segments = ArrayList<Segment>()
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            // FrameMetrics is reused by Android; copy only primitive values before returning.
            frames.add(Frame(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                metrics.getMetric(FrameMetrics.TOTAL_DURATION), metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                metrics.getMetric(FrameMetrics.DRAW_DURATION),
                metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION), metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION), dropped))
        }

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            instrumentation.runOnMainSync { window.addOnFrameMetricsAvailableListener(listener, handler) }
        }

        fun measure(name: String, action: () -> Unit) {
            val start = System.nanoTime()
            action()
            val inputEnd = System.nanoTime()
            // Accessibility can report idle before a visual transition ends.
            // Keep the same sampling tail for drags and the full 300 ms Back fade.
            SystemClock.sleep(600)
            val idle = waitForAccessibilityIdle()
            segments.add(Segment(name, start, inputEnd, System.nanoTime(), idle))
        }

        fun closeAndLog(suite: String) {
            instrumentation.runOnMainSync { window.removeOnFrameMetricsAvailableListener(listener) }
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            val drainComplete = drained.await(3, TimeUnit.SECONDS)
            thread.quitSafely()
            thread.join(3_000)
            val recorded = frames.toList().sortedBy { it.intended }
            val runId = InstrumentationRegistry.getArguments().getString("frameRun") ?: "unspecified"
            Log.i(TAG, "type=RUN suite=$suite run=$runId sdk=${Build.VERSION.SDK_INT} device=${Build.DEVICE} " +
                "frames=${recorded.size} droppedReports=${recorded.sumOf { it.droppedReports }} handlerDrained=$drainComplete " +
                "widthPx=${activity.resources.displayMetrics.widthPixels} heightPx=${activity.resources.displayMetrics.heightPixels}")
            segments.forEach { segment ->
                val selected = recorded.filter { it.intended in segment.start..segment.end }
                val first = selected.take(3)
                val rest = selected.drop(3)
                fun mean(values: List<Frame>) = values.map { it.total }.filter { it >= 0 }.takeIf { it.isNotEmpty() }?.average()?.toLong()
                val firstMean = mean(first)
                val restMean = mean(rest)
                val delta = if (firstMean != null && restMean != null) firstMean - restMean else null
                Log.i(TAG, "type=SEGMENT suite=$suite name=${segment.name} startNs=${segment.start} inputEndNs=${segment.inputEnd} " +
                    "endNs=${segment.end} idleReached=${segment.idleReached} frames=${selected.size} first3Count=${first.size} " +
                    "first3MeanTotalNs=$firstMean remainingMeanTotalNs=$restMean first3MinusRemainingNs=$delta " +
                    "droppedReports=${selected.sumOf { it.droppedReports }}")
                selected.forEachIndexed { index, frame ->
                    Log.i(TAG, "type=FRAME suite=$suite segment=${segment.name} index=${index + 1} " +
                        "bucket=${if (index < 3) "FIRST3" else "REMAINING"} intendedVsyncNs=${frame.intended} " +
                        "totalNs=${frame.total} layoutMeasureNs=${frame.layout} drawNs=${frame.draw} unknownDelayNs=${frame.unknown} " +
                        "inputHandlingNs=${frame.input} droppedReports=${frame.droppedReports}")
                }
            }
            assertTrue("No platform frames collected: do not treat this run as a performance result", recorded.isNotEmpty())
        }
    }

    private companion object { const val TAG = "ForgeFrameTiming" }
}

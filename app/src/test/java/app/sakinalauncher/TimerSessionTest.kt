package app.sakinalauncher

import android.app.Activity
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.data.TimerSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * State-machine tests for the deep TimerSession module — no Android UI involved.
 * A FakeScheduler drives the clock deterministically, so timing assertions are
 * exact (no real sleeps, no Handler/looper coupling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerSessionTest {

    private fun prefs(): Prefs = Prefs(Robolectric.buildActivity(Activity::class.java).setup().get())

    private fun sessionWith(prefs: Prefs): Pair<TimerSession, FakeScheduler> {
        val sched = FakeScheduler()
        return TimerSession(prefs, scheduler = sched) to sched
    }

    /** Manual countdown driver: the test calls [tick] / [finish] to advance. */
    private class FakeScheduler : TimerSession.Scheduler {
        var onTick: ((Long) -> Unit)? = null
        var onFinish: (() -> Unit)? = null
        var cancelled = false
        var running = false
        private var current: Long = 0

        override fun start(totalMillis: Long, tickMillis: Long, onTick: (Long) -> Unit, onFinish: () -> Unit) {
            this.onTick = onTick
            this.onFinish = onFinish
            running = true
            cancelled = false
            current = totalMillis
        }

        override fun cancel() {
            running = false
            cancelled = true
        }

        /** Simulates one tick: remaining -= [stepMs] and fires onTick. */
        fun tick(stepMs: Long = 250) {
            current = (current - stepMs).coerceAtLeast(0L)
            onTick?.invoke(current)
        }

        /** Simulates the countdown reaching zero. */
        fun finish() {
            running = false
            onFinish?.invoke()
        }
    }

    @Test
    fun `idle session starts with focus duration`() {
        val p = prefs()
        p.pomodoroFocusMinutes = 25
        p.pomodoroFocusSeconds = 0
        val (s, _) = sessionWith(p)
        s.restore()
        assertEquals(25 * 60_000L, s.totalMillis)
        assertEquals(25 * 60_000L, s.remainingMillis)
        assertFalse(s.isRunning)
    }

    @Test
    fun `start counts down and finishes`() {
        val p = prefs()
        p.pomodoroFocusMinutes = 0
        p.pomodoroFocusSeconds = 1
        val (s, sched) = sessionWith(p)
        s.restore()
        var ticks = 0
        var finished = false
        s.listener = object : TimerSession.Listener {
            override fun onTick(remainingMillis: Long) { ticks++ }
            override fun onFinish() { finished = true }
        }
        s.start()
        assertTrue(s.isRunning)
        sched.tick()
        sched.tick()
        sched.tick()
        assertEquals(250L, s.remainingMillis)
        sched.finish()
        assertTrue(finished)
        assertFalse(s.isRunning)
        assertEquals(0L, s.remainingMillis)
        assertTrue("should have ticked 3 times", ticks == 3)
    }

    @Test
    fun `pause preserves remaining and resume continues`() {
        val p = prefs()
        p.pomodoroFocusMinutes = 1
        p.pomodoroFocusSeconds = 0
        val (s, sched) = sessionWith(p)
        s.restore()
        s.start()
        sched.tick(400)
        s.pause()
        val remainingAtPause = s.remainingMillis
        assertFalse(s.isRunning)
        assertTrue(remainingAtPause > 0L && remainingAtPause < s.totalMillis)
        val before = s.remainingMillis
        // No scheduler running while paused: remaining must not drift.
        assertEquals(before, s.remainingMillis)
        s.resume()
        assertTrue(s.isRunning)
    }

    @Test
    fun `reset restores focus duration`() {
        val p = prefs()
        p.pomodoroFocusMinutes = 5
        p.pomodoroFocusSeconds = 30
        val (s, sched) = sessionWith(p)
        s.restore()
        s.start()
        sched.tick()
        s.reset()
        assertFalse(s.isRunning)
        assertEquals(5 * 60_000L + 30_000L, s.totalMillis)
        assertEquals(5 * 60_000L + 30_000L, s.remainingMillis)
    }

    @Test
    fun `setDuration replaces session`() {
        val p = prefs()
        p.pomodoroFocusMinutes = 25
        val (s, _) = sessionWith(p)
        s.restore()
        s.setDuration(10, 5)
        assertEquals(10 * 60_000L + 5_000L, s.totalMillis)
        assertEquals(10 * 60_000L + 5_000L, s.remainingMillis)
        assertFalse(s.isRunning)
        // Persisted for restore.
        assertEquals(10 * 60_000L + 5_000L, p.pomodoroTimerTotalMillis)
    }

    @Test
    fun `clear finishes session state`() {
        val p = prefs()
        p.pomodoroFocusMinutes = 1
        val (s, sched) = sessionWith(p)
        s.restore()
        s.start()
        sched.tick()
        s.clear()
        assertFalse(s.isRunning)
        assertEquals(0L, s.remainingMillis)
        assertEquals(0L, p.pomodoroTimerRemainingMillis)
    }
}

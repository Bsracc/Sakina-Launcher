package app.sakinalauncher.data

import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import app.sakinalauncher.data.Prefs

/**
 * Deep module for the Note Panel countdown timer.
 *
 * Everything about the timer — its state, transitions (start / pause / resume /
 * reset / add time), persistence via [Prefs], and the countdown clock — lives
 * behind this small interface. The UI only renders state and reacts to
 * [Listener.onTick] / [Listener.onFinish].
 *
 * State model:
 *  - idle:      no active session (remaining == 0)
 *  - ready:     a duration is set but not running (remaining == total)
 *  - running:   countdown active
 *  - paused:    countdown stopped, remaining preserved
 *
 * The clock keeps running while the owning screen is alive; the Fragment must
 * call [release] from onDestroyView. Persisted end-time lets the state survive
 * configuration changes (restore recomputes remaining from elapsedRealtime).
 */
class TimerSession(
    private val prefs: Prefs,
    private val scheduler: Scheduler = RealCountDownScheduler(),
) {

    /** Drives the countdown. Real impl uses [android.os.CountDownTimer] on the calling thread's looper. */
    interface Scheduler {
        fun start(totalMillis: Long, tickMillis: Long, onTick: (Long) -> Unit, onFinish: () -> Unit)
        fun cancel()
    }

    private class RealCountDownScheduler : Scheduler {
        private var timer: CountDownTimer? = null
        override fun start(totalMillis: Long, tickMillis: Long, onTick: (Long) -> Unit, onFinish: () -> Unit) {
            timer = object : CountDownTimer(totalMillis, tickMillis) {
                override fun onTick(millisUntilFinished: Long) = onTick(millisUntilFinished)
                override fun onFinish() = onFinish()
            }.start()
        }

        override fun cancel() {
            timer?.cancel()
            timer = null
        }
    }

    interface Listener {
        /** Fired on every tick (default 250 ms) with the remaining millis. */
        fun onTick(remainingMillis: Long)

        /** Fired once when the countdown reaches zero. */
        fun onFinish()
    }

    var listener: Listener? = null

    private var active = false

    var totalMillis: Long = 0L
        private set
    var remainingMillis: Long = 0L
        private set
    val isRunning: Boolean get() = active
    val isPaused: Boolean get() = !isRunning && remainingMillis > 0L && remainingMillis < totalMillis
    val isFinished: Boolean get() = remainingMillis == 0L

    /** Default focus duration from prefs (minutes/seconds). */
    val focusMillis: Long get() = prefs.pomodoroFocusMillis

    /** Seconds worth of remaining time, rounded up (for the duration dialog). */
    val remainingSeconds: Long get() = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L

    /**
     * Loads the persisted session (from a previous configuration change or
     * process death). If the persisted end time is in the past, the session is
     * considered finished: remaining is zeroed and persisted.
     */
    fun restore() {
        totalMillis = prefs.pomodoroTimerTotalMillis.takeIf { it > 0L } ?: focusMillis
        remainingMillis = prefs.pomodoroTimerRemainingMillis.takeIf { it >= 0L } ?: totalMillis
        val endElapsed = prefs.pomodoroTimerEndElapsedRealtime
        if (endElapsed > 0L) {
            remainingMillis = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remainingMillis > 0L) {
                start()
            } else {
                finishAndPersist()
            }
        }
        if (totalMillis <= 0L) totalMillis = focusMillis
        if (remainingMillis < 0L) remainingMillis = totalMillis
    }

    /** Saves the current session to prefs (for onSaveInstanceState handoff). */
    fun snapshot(out: Bundle? = null, keys: TimerKeys = TimerKeys()) {
        out?.putLong(keys.total, totalMillis)
        out?.putLong(keys.remaining, remainingMillis)
        out?.putBoolean(keys.running, isRunning)
    }

    /** Starts the countdown, or restarts with the remaining time. */
    fun start() {
        if (remainingMillis <= 0L) {
            totalMillis = focusMillis
            remainingMillis = totalMillis
        }
        scheduler.cancel()
        active = true
        prefs.pomodoroTimerTotalMillis = totalMillis
        prefs.pomodoroTimerEndElapsedRealtime = SystemClock.elapsedRealtime() + remainingMillis
        prefs.pomodoroTimerRemainingMillis = remainingMillis
        val startRemaining = remainingMillis
        scheduler.start(
            totalMillis = startRemaining,
            tickMillis = TICK_MS,
            onTick = { millis ->
                if (active) {
                    remainingMillis = millis
                    listener?.onTick(millis)
                }
            },
            onFinish = {
                if (active) {
                    active = false
                    finishAndPersist()
                    listener?.onFinish()
                }
            },
        )
    }

    /** Pauses the running countdown, preserving remaining time. */
    fun pause() {
        scheduler.cancel()
        active = false
        prefs.pomodoroTimerEndElapsedRealtime = 0L
        prefs.pomodoroTimerRemainingMillis = remainingMillis
    }

    /** Resumes a paused countdown from its remaining time. */
    fun resume() = start()

    /** Cancels the countdown and sets a fresh session from the default duration. */
    fun reset() {
        scheduler.cancel()
        active = false
        totalMillis = focusMillis
        remainingMillis = totalMillis
        prefs.pomodoroTimerTotalMillis = totalMillis
        prefs.pomodoroTimerRemainingMillis = remainingMillis
        prefs.pomodoroTimerEndElapsedRealtime = 0L
    }

    /** Sets a brand-new duration (minutes + seconds) as a ready-to-start session. */
    fun setDuration(minutes: Int, seconds: Int) {
        scheduler.cancel()
        active = false
        prefs.pomodoroFocusMinutes = minutes
        prefs.pomodoroFocusSeconds = seconds
        totalMillis = focusMillis
        remainingMillis = totalMillis
        prefs.pomodoroTimerTotalMillis = totalMillis
        prefs.pomodoroTimerRemainingMillis = remainingMillis
        prefs.pomodoroTimerEndElapsedRealtime = 0L
    }

    /**
     * Extends the finished session so the user can keep going. The new duration
     * starts from the default focus length (the duration dialog then lets the
     * user pick any value before starting).
     */
    fun addTime(minutes: Int = prefs.pomodoroFocusMinutes, seconds: Int = prefs.pomodoroFocusSeconds) {
        setDuration(minutes, seconds)
    }

    /** Stops everything and clears persisted state (after finishing / closing). */
    fun clear() {
        scheduler.cancel()
        active = false
        remainingMillis = 0L
        prefs.pomodoroTimerRemainingMillis = 0L
        prefs.pomodoroTimerEndElapsedRealtime = 0L
    }

    /** Cancels the countdown without touching persisted state (onDestroyView). */
    fun release() {
        scheduler.cancel()
        active = false
    }

    private fun finishAndPersist() {
        active = false
        remainingMillis = 0L
        prefs.pomodoroTimerRemainingMillis = 0L
        prefs.pomodoroTimerEndElapsedRealtime = 0L
    }

    companion object {
        const val TICK_MS = 250L
    }
}

/** Bundle keys for timer snapshot (kept out of the class to stay Android-free in tests). */
data class TimerKeys(
    val total: String = "note_panel_timer_total",
    val remaining: String = "note_panel_timer_remaining",
    val running: String = "note_panel_timer_running",
)

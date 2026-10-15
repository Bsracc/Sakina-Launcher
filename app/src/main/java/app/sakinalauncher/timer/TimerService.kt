package app.sakinalauncher.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import app.sakinalauncher.R
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.helper.SoundEffects
import app.sakinalauncher.MainActivity

/**
 * Foreground service that keeps the focus timer running even when the launcher
 * is not on screen, and shows a "time's up" popup OVER ANY APP when it finishes.
 *
 * The countdown source of truth is [Prefs.pomodoroTimerEndElapsedRealtime]
 * (elapsedRealtime deadline) written by [app.sakinalauncher.data.TimerSession].
 * This service only reads that deadline, so it survives process death and works
 * even if the Note Panel was never opened in this process.
 *
 * Lifecycle:
 *  - [start] : begin foreground service + notification; schedule finish.
 *  - finish  : play chime, show overlay popup (needs SYSTEM_ALERT_WINDOW), stop foreground.
 *  - [stop]  : cancel everything, dismiss overlay, remove notification.
 */
class TimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "timer_countdown"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "app.sakinalauncher.timer.START"
        private const val OVERLAY_GRACE_MS = 1200L
        const val EXTRA_SHOW_TIMER_DONE = "app.sakinalauncher.timer.SHOW_TIMER_DONE"

        /** Starts (or restarts) the foreground countdown service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TimerService::class.java).setAction(ACTION_START))
        }

        /** Stops the service and removes its notification / overlay. Safe to call anytime. */
        fun stop(context: Context) {
            // stopService directly — never startService from background (Android 8+
            // throws IllegalStateException for background startService).
            context.stopService(Intent(context, TimerService::class.java))
        }
    }

    private lateinit var prefs: Prefs
    private val mainHandler = Handler(Looper.getMainLooper())

    private var countdown: CountDownTimer? = null
    private var overlayView: android.view.View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var finishPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCountdown()
        return START_STICKY
    }

    private fun startCountdown() {
        val endElapsed = prefs.pomodoroTimerEndElapsedRealtime
        if (endElapsed <= 0L) {
            // Nothing running; don't keep a ghost service.
            stopSelf()
            return
        }
        val remaining = (endElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        goForeground(remaining)

        countdown?.cancel()
        countdown = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                updateNotification(millisUntilFinished)
            }

            override fun onFinish() {
                onCountdownFinished()
            }
        }.start()
    }

    private fun goForeground(remainingMillis: Long) {
        val notification = buildNotification(remainingMillis)
        if (remainingMillis > 0L) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(remainingMillis: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(remainingMillis))
    }

    private fun buildNotification(remainingMillis: Long): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val timeLeft = formatTimer(remainingMillis)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.timer_notification_title))
            .setContentText(getString(R.string.timer_notification_message, timeLeft))
            .setSmallIcon(R.drawable.ic_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.timer_notification_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun onCountdownFinished() {
        countdown = null
        finishPending = true

        // Timer is already zeroed by TimerSession's onFinish when the panel was
        // alive; if the panel was never alive in this process, zero it here.
        prefs.pomodoroTimerRemainingMillis = 0L
        prefs.pomodoroTimerEndElapsedRealtime = 0L

        SoundEffects.mindfulDone(this)

        // Short grace window: if the Note Panel is open in the launcher, its own
        // dialog handles the "time's up" (fragment stops this service). Only show
        // the overlay if this service is still alive after the window — i.e. the
        // launcher was NOT the active app when the timer finished.
        mainHandler.postDelayed({
            if (finishPending) {
                finishPending = false
                showOverlay()
            }
        }, OVERLAY_GRACE_MS)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // No overlay permission: bring the launcher to front and let its own
            // dialog handle "time's up" — guaranteed to work without any permission,
            // and it also nudges the user toward granting overlay in Settings.
            showFallbackActivity()
            return
        }
        // CRITICAL: a Service context has no Activity theme, and this layout uses
        // ?attr/primaryColor + ?attr/glassActive + styles. Inflating it with the
        // bare service context throws Resources.NotFoundException -> crash when
        // the timer finishes while another app is open. Wrap with the app theme.
        val themed = ContextThemeWrapper(this, R.style.AppTheme)
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_timer_done, null)
        view.findViewById<TextView>(R.id.dialogTitle).setText(R.string.timer_overlay_title)
        view.findViewById<TextView>(R.id.dialogMessage).setText(R.string.timer_overlay_message)
        val positive = view.findViewById<TextView>(R.id.dialogPositive)
        val negative = view.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.timer_overlay_add)
        negative.setText(R.string.timer_overlay_close)

        // Full-screen modal: the scrim (layout background) owns the screen and
        // blocks touches outside the card until the user picks Add time / Close.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE so it never steals keyboard focus; touches anywhere
            // are consumed by the modal (no NOT_TOUCH_MODAL = fully blocking).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        negative.setOnClickListener {
            dismissOverlay()
            // Close: everything already zeroed; stop the service entirely.
            stopSelf()
        }
        positive.setOnClickListener {
            dismissOverlay()
            // Add time: restart with default focus duration.
            val mins = prefs.pomodoroFocusMinutes
            val secs = prefs.pomodoroFocusSeconds
            val total = (mins * 60_000L) + (secs * 1000L)
            prefs.pomodoroTimerTotalMillis = total
            prefs.pomodoroTimerRemainingMillis = total
            prefs.pomodoroTimerEndElapsedRealtime = SystemClock.elapsedRealtime() + total
            startCountdown()
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, lp)
            overlayView = view
            overlayParams = lp
        } catch (e: Exception) {
            // Overlay rejected for any reason — launcher fallback.
            showFallbackActivity()
        }
    }

    /**
     * Timer finished with no overlay permission: surface a full-screen/heads-up
     * notification (with the launcher's dialog as the tap target). Works on every
     * Android version with zero extra permissions; the Settings row explains how
     * to enable the true overlay popup.
     */
    private fun showFallbackActivity() {
        // Background activity starts are restricted on Android 10+ for apps
        // without overlay permission, so a notification with a full-screen intent
        // is the reliable way to surface "time's up" over other apps. The
        // launcher opens when the user taps it (or immediately for full-screen
        // intents on devices that allow them).
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_SHOW_TIMER_DONE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.timer_overlay_title))
            .setContentText(getString(R.string.timer_overlay_message))
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(contentIntent, true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (_: Exception) {
        }
    }

    private fun dismissOverlay() {
        overlayView?.let { view ->
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayParams = null
    }

    private fun stopEverything() {
        countdown?.cancel()
        countdown = null
        mainHandler.removeCallbacksAndMessages(null)
        finishPending = false
        dismissOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun formatTimer(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
}

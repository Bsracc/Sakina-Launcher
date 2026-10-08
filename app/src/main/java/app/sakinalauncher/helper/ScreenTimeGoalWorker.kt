package app.sakinalauncher.helper

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sakinalauncher.MainActivity
import app.sakinalauncher.R
import app.sakinalauncher.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Daily screen-time goal worker (B2).
 *
 * Runs periodically (every 30 minutes, scheduled when the goal is configured) and
 * fires a system notification when today's goal is reached. Also handles the
 * end-of-day "missed" notice. The in-launcher dialog (via MainViewModel) covers the
 * same checks while the launcher is visible; this worker covers the background.
 */
class ScreenTimeGoalWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        if (!ScreenTimeGoal.isEnabled(prefs)) {
            return@withContext Result.success()
        }
        val usedMillis = ScreenTimeGoal.getTodaysUsageMillis(applicationContext)
        if (usedMillis == null) {
            return@withContext Result.success()
        }
        when (ScreenTimeGoal.checkNotice(prefs, usedMillis)) {
            "reached" -> postGoalNotification(
                title = applicationContext.getString(R.string.screen_time_goal_reached_title),
                text = applicationContext.getString(
                    R.string.screen_time_goal_reached_text,
                    ScreenTimeGoal.formatUsed(applicationContext, usedMillis),
                    ScreenTimeGoal.formatGoal(prefs.dailyScreenTimeGoalMinutes),
                ),
            )

            "missed" -> postGoalNotification(
                title = applicationContext.getString(R.string.screen_time_goal_missed_title),
                text = applicationContext.getString(
                    R.string.screen_time_goal_missed_text,
                    ScreenTimeGoal.formatGoal(prefs.dailyScreenTimeGoalMinutes),
                ),
            )

            else -> {}
        }
        Result.success()
    }

    private fun postGoalNotification(title: String, text: String) {
        // POST_NOTIFICATIONS is a runtime permission on API 33+; without it the
        // notify() call throws. We never request it at runtime — the in-launcher
        // dialog is the primary channel — so just skip the notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "screen_time_goal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, applicationContext.getString(R.string.screen_time_goal_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val NOTIFICATION_ID = 2001
    }
}

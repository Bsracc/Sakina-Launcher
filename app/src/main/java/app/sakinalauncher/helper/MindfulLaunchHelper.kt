package app.sakinalauncher.helper

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import app.sakinalauncher.R
import app.sakinalauncher.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mindful launch (B1): a short, cancellable countdown before an opted-in app opens.
 *
 * Design rules from the B1 brief:
 * - Never delay system-critical targets (Settings, Home, dialer, emergency).
 * - Opt-in only: a package is mindful iff it is in [Prefs.mindfulApps] AND the
 *   master toggle [Prefs.mindfulLaunchEnabled] is on.
 * - The countdown shows a Batal (Cancel) action; tapping it aborts the launch.
 */
object MindfulLaunchHelper {

    /**
     * System/emergency packages that must never be delayed or blocked.
     * The dialer and settings are reachable even during a call/emergency, so they
     * are deliberately excluded from mindful treatment.
     */
    private val ALWAYS_INSTANT = setOf(
        "com.android.settings",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",
        "com.google.android.apps.emergencyassist",
    )

    /** True when this package should go through the mindful countdown. */
    fun isMindful(context: Context, prefs: Prefs, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (!prefs.mindfulLaunchEnabled) return false
        if (packageName in ALWAYS_INSTANT) return false
        return prefs.mindfulApps.contains(packageName)
    }

    /**
     * Shows the countdown dialog and invokes [onLaunch] when it completes,
     * or [onCancel] when the user aborts. Returns the running [Job]; cancel it
     * to abort early (e.g. the host is destroyed).
     *
     * @param appLabel label shown in the dialog.
     */
    fun startMindfulCountdown(
        context: Context,
        prefs: Prefs,
        appLabel: String,
        onLaunch: () -> Unit,
        onCancel: () -> Unit = {},
    ): Job {
        val seconds = prefs.mindfulDelaySeconds.coerceIn(3, 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_mindful_launch, null)
        val title = view.findViewById<TextView>(R.id.mindfulTitle)
        val countdown = view.findViewById<TextView>(R.id.mindfulCountdown)
        title.text = appLabel

        // Full-screen overlay: matchHeight makes the window fill the screen, the
        // scrim dims the wallpaper, and the window blur frosts it (API 31+; the
        // tint alone carries the look below that).
        val dialog = AppDialog.create(context, view, matchHeight = true)
        val cancelButton = view.findViewById<TextView>(R.id.mindfulCancel)
        val closeButton = view.findViewById<TextView>(R.id.mindfulClose)
        val countdownScope = scope
        val countdownJob: Job = countdownScope.launch {
            var remaining = seconds
            countdown.text = remaining.toString()
            while (remaining > 0) {
                delay(1000L)
                remaining--
                countdown.text = remaining.toString()
            }
            if (dialog.isShowing) {
                dialog.dismiss()
                onLaunch()
            }
        }

        cancelButton.setOnClickListener {
            countdownJob.cancel()
            if (dialog.isShowing) dialog.dismiss()
            onCancel()
        }
        closeButton.setOnClickListener {
            countdownJob.cancel()
            if (dialog.isShowing) dialog.dismiss()
            onCancel()
        }
        dialog.setOnDismissListener {
            countdownJob.cancel()
            dialog.window?.let { GlassBlur.clearWindow(it) }
        }
        dialog.setOnShowListener {
            // Blur must be applied once the window exists (post-show).
            dialog.window?.let { GlassBlur.applyToWindow(it, GlassBlur.RADIUS_PANEL_DP) }
        }
        dialog.show()
        return countdownJob
    }
}

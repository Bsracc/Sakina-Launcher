package app.sakinalauncher.helper

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.sakinalauncher.R
import app.sakinalauncher.data.Prefs

/**
 * Launcher sound effects — small, elegant, non-intrusive.
 *
 * Three tiny synthesized sounds (OGG in res/raw):
 *  - [launch]: soft pop when an app is launched from home/drawer.
 *  - [mindfulDone]: gentle two-tone chime when a mindful countdown completes.
 *  - [toggle]: quiet tick when a settings toggle flips.
 *
 * The pool is loaded lazily on first use and re-used forever after; playback is
 * gated on [Prefs.soundEffectsEnabled] (master switch in Settings). Every call
 * is fire-and-forget and never throws, so a sound failure can never break a
 * launch or a toggle.
 */
object SoundEffects {
    private var pool: SoundPool? = null
    private var loaded = false

    private var launchId = 0
    private var mindfulDoneId = 0
    private var toggleId = 0

    /**
     * Preload the pool so the first interaction is not silent. Call once from
     * MainActivity.onCreate. Safe to call repeatedly.
     */
    fun preload(context: Context) {
        ensureLoaded(context.applicationContext)
    }

    private fun ensureLoaded(context: Context) {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attributes)
            .build()
        launchId = p.load(context, R.raw.sfx_launch, 1)
        mindfulDoneId = p.load(context, R.raw.sfx_mindful_done, 1)
        toggleId = p.load(context, R.raw.sfx_toggle, 1)
        p.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) loaded = true
        }
        pool = p
    }

    /** Soft pop when an app is launched (home slot or drawer). */
    fun launch(context: Context) {
        play(context, launchId)
    }

    /** Gentle chime when a mindful countdown finishes and the app opens. */
    fun mindfulDone(context: Context) {
        play(context, mindfulDoneId)
    }

    /** Quiet tick when a settings toggle flips. */
    fun toggle(context: Context) {
        play(context, toggleId)
    }

    private fun play(context: Context, soundId: Int) {
        if (soundId <= 0) return
        val ctx = context.applicationContext
        val p = pool
        if (p == null) {
            ensureLoaded(ctx)
            // The pool loads async; the sound starts on the next interaction.
            return
        }
        if (!Prefs(ctx).soundEffectsEnabled) return
        if (!loaded) return
        val volume = 0.65f
        p.play(soundId, volume, volume, 1, 0, 1.0f)
    }

    fun release() {
        pool?.release()
        pool = null
        loaded = false
    }
}

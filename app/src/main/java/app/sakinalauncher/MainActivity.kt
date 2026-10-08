package app.sakinalauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.databinding.ActivityMainBinding
import app.sakinalauncher.helper.getColorFromAttr
import app.sakinalauncher.helper.getScreenDimensions
import app.sakinalauncher.helper.hasBeenDays
import app.sakinalauncher.helper.hasBeenHours
import app.sakinalauncher.helper.hasBeenMinutes
import app.sakinalauncher.helper.isDarkThemeOn
import app.sakinalauncher.helper.isDaySince
import app.sakinalauncher.helper.isDefaultLauncher
import app.sakinalauncher.helper.isEinkDisplay
import app.sakinalauncher.helper.isSakinaDefault
import app.sakinalauncher.helper.isTablet
import app.sakinalauncher.helper.openUrl
import app.sakinalauncher.helper.rateApp
import app.sakinalauncher.helper.resetLauncherViaFakeActivity
import app.sakinalauncher.helper.shareApp
import app.sakinalauncher.helper.showLauncherSelector
import app.sakinalauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding
    private var timerJob: Job? = null
    private var wallpaperLoadJob: Job? = null

    /** Cached default-launcher answer — see [isDefaultLauncherCached]. */
    private var cachedIsDefault: Boolean? = null
    private var cachedIsDefaultAt: Long = 0L
    private var isResumed = false
    private var profileReceiver: BroadcastReceiver? = null

    /**
     * Last observed system night-mode bit. Needed because by the time
     * [onConfigurationChanged] runs, `resources.configuration` already holds the NEW
     * value, so it cannot tell us what changed.
     */
    private var lastNightMode: Boolean = false

    /**
     * When true, [onStop]/[onUserLeaveHint] must not pop the nav stack back to home.
     * Productive widget pick/bind/configure start another activity; without this the
     * NotePanelFragment is destroyed before the activity result can be handled, so
     * widgets bind at the system level but never appear in the Productive store.
     */
    var suppressHomeOnBackground: Boolean = false

    // Launcher to request READ_EXTERNAL_STORAGE (needed on API <= 32 to read the
    // user's wallpaper when we are not the default launcher).
    private val wallpaperPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) applySolidBackground()
        }
    private var wallpaperPermissionRequested = false


    override fun attachBaseContext(context: Context) {
        val newConfig = Configuration(context.resources.configuration)
        newConfig.fontScale = Prefs(context).textSizeScale
        applyOverrideConfiguration(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (isEinkDisplay()) prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        lastNightMode = isNightConfig(resources.configuration)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySolidBackground()

        navController = this.findNavController(R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.mainFragment) {
                    // then we might want to finish the activity or disable this callback.
                    if (navController.popBackStack()) {
                        // Successfully popped back
                    } else {
                        // if you want other system/activity level handling
                    }
                } else {
                    binding.messageLayout.visibility = View.GONE
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (prefs.firstOpen) {
            viewModel.firstOpen(true)
            prefs.firstOpen = false
            prefs.firstOpenTime = System.currentTimeMillis()
            viewModel.setDefaultClockApp()
            viewModel.resetLauncherLiveData.call()
        }

        initClickListeners()
        initObservers(viewModel)
        viewModel.getAppList()
        setupOrientation()

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            profileReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    viewModel.isPrivateSpaceToggling = false
                    viewModel.getPrivateSpaceAppList()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            }
            registerReceiver(profileReceiver, filter)
        }
    }

    override fun onStart() {
        super.onStart()
        restartLauncherOrCheckTheme()
        viewModel.syncScreenTimeGoalWorker()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        viewModel.isPrivateSpaceToggling = false
        // Re-evaluate the daily screen-time goal while the launcher is visible
        // (permission + usage window permitting).
        viewModel.checkScreenTimeGoal()
        // Background is applied in onCreate and explicit preference/permission callbacks.
        // Reloading WallpaperManager on every launcher resume causes visible Home jank.
    }

    override fun onStop() {
        isResumed = false
        if (!suppressHomeOnBackground) backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        if (!suppressHomeOnBackground) backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent?) {
        val alreadyHome = navController.currentDestination?.id == R.id.mainFragment
        // Home/recents button should still return home even during widget flow.
        suppressHomeOnBackground = false
        backToHomeScreen()
        if (alreadyHome && isResumed && prefs.homeButtonShowRecents)
            viewModel.showRecentApps.call()
        super.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        applySolidBackground()

        // MainActivity declares configChanges="uiMode" (so a system dark-mode flip does
        // not tear down the launcher), which means Android will NOT recreate us and the
        // themed drawables keep their old, stale colours. On Automatic that is the whole
        // feature broken: the OS flips to dark and the launcher stays light. Recreate
        // ourselves, but only when the night bit actually changed and only when the user
        // asked to follow the system — otherwise every rotation would restart the panel.
        //
        // The previous value is tracked in a field rather than read from
        // resources.configuration, which is already updated by the time this runs.
        val isNight = isNightConfig(newConfig)
        val wasNight = lastNightMode
        lastNightMode = isNight
        val followsSystem = prefs.appTheme == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        if (followsSystem && isNight != wasNight) {
            if (prefs.dailyWallpaper) viewModel.setWallpaperWorker()
            recreate()
        }
    }

    private fun isNightConfig(config: Configuration): Boolean =
        config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun initClickListeners() {
        binding.ivClose.setOnClickListener {
            binding.messageLayout.visibility = View.GONE
        }
    }

    private fun initObservers(viewModel: MainViewModel) {
        viewModel.launcherResetFailed.observe(this) {
            openLauncherChooser(it)
        }
        viewModel.resetLauncherLiveData.observe(this) {
            if (isDefaultLauncher() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                resetLauncherViaFakeActivity()
            else
                showLauncherSelector(Constants.REQUEST_CODE_LAUNCHER_SELECTOR)
        }
        viewModel.checkForMessages.observe(this) {
            checkForMessages()
        }
        viewModel.screenTimeGoalEvent.observe(this) {
            it?.let { message ->
                binding.tvTitle.text = getString(R.string.screen_time_goal)
                binding.tvMessage.text = message
                binding.tvAction.text = getString(R.string.okay)
                binding.tvAction.setOnClickListener {
                    binding.messageLayout.visibility = View.GONE
                }
                binding.messageLayout.visibility = View.VISIBLE
            }
        }
        viewModel.showDialog.observe(this) {
            when (it) {
                Constants.Dialog.ABOUT -> {
                    showMessageDialog(R.string.about_title, R.string.about_message, R.string.okay) {
                        binding.messageLayout.visibility = View.GONE
                    }
                }

                Constants.Dialog.WALLPAPER -> {
                    prefs.wallpaperMsgShown = true
                    prefs.userState = Constants.UserState.REVIEW
                    showMessageDialog(R.string.did_you_know, R.string.wallpaper_message, R.string.enable) {
                        prefs.dailyWallpaper = true
                        viewModel.setWallpaperWorker()
                        showToast(getString(R.string.your_wallpaper_will_update_shortly))
                    }
                }

                Constants.Dialog.REVIEW -> {
                    prefs.userState = Constants.UserState.RATE
                    showMessageDialog(R.string.hey, R.string.review_message, R.string.leave_a_review) {
                        prefs.rateClicked = true
                        showToast(getString(R.string.thank_you))
                        rateApp()
                    }
                }

                Constants.Dialog.RATE -> {
                    prefs.userState = Constants.UserState.SHARE
                    showMessageDialog(R.string.app_name, R.string.rate_us_message, R.string.rate_now) {
                        prefs.rateClicked = true
                        showToast(getString(R.string.thank_you))
                        rateApp()
                    }
                }

                Constants.Dialog.SHARE -> {
                    prefs.shareShownTime = System.currentTimeMillis()
                    showMessageDialog(R.string.hey, R.string.share_message, R.string.share_now) {
                        showToast(getString(R.string.thank_you))
                        shareApp()
                    }
                }

                Constants.Dialog.HIDDEN -> {
                    showMessageDialog(R.string.hidden_apps, R.string.hidden_apps_message, R.string.okay) {
                    }
                }

                Constants.Dialog.KEYBOARD -> {
                    showMessageDialog(R.string.app_name, R.string.keyboard_message, R.string.okay) {
                    }
                }

                Constants.Dialog.DIGITAL_WELLBEING -> {
                    showMessageDialog(R.string.screen_time, R.string.app_usage_message, R.string.permission) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }

                Constants.Dialog.PRO_MESSAGE -> {
                    showMessageDialog(R.string.hey, R.string.pro_message, R.string.sakina_pro) {
                        openUrl(Constants.URL_SAKINA_PRO)
                    }
                }
            }
        }
    }

    private fun showMessageDialog(title: Int, message: Int, action: Int, clickListener: () -> Unit) {
        binding.tvTitle.text = getString(title)
        binding.tvMessage.text = getString(message)
        binding.tvAction.text = getString(action)
        binding.tvAction.setOnClickListener {
            clickListener()
            binding.messageLayout.visibility = View.GONE
        }
        binding.messageLayout.visibility = View.VISIBLE
    }

    private fun checkForMessages() {
        if (prefs.firstOpenTime == 0L)
            prefs.firstOpenTime = System.currentTimeMillis()

        val calendar = Calendar.getInstance()
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        if (dayOfYear == 1 && dayOfYear != prefs.shownOnDayOfYear) {
            prefs.shownOnDayOfYear = dayOfYear
            showMessageDialog(R.string.hey, R.string.new_year_wish, R.string.cheers) {}
            return
        } else if (dayOfYear == 32 && dayOfYear != prefs.shownOnDayOfYear) {
            prefs.shownOnDayOfYear = dayOfYear
            showMessageDialog(R.string.hey, R.string.new_year_wish_1, R.string.cheers) {}
            return
        }

        // Resolved once per pass. isSakinaDefault() is a synchronous PackageManager
        // resolveActivity binder call, and the branches below asked it up to four times
        // for an answer that cannot change mid-method.
        val isDefault = isDefaultLauncherCached()

        when (prefs.userState) {
            Constants.UserState.START -> {
                if (prefs.firstOpenTime.hasBeenMinutes(10))
                    prefs.userState = Constants.UserState.WALLPAPER
            }

            Constants.UserState.WALLPAPER -> {
                if (prefs.wallpaperMsgShown || prefs.dailyWallpaper)
                    prefs.userState = Constants.UserState.REVIEW
                else if (isDefault)
                    viewModel.showDialog.postValue(Constants.Dialog.WALLPAPER)
            }

            Constants.UserState.REVIEW -> {
                if (prefs.rateClicked)
                    prefs.userState = Constants.UserState.SHARE
                else if (isDefault && prefs.firstOpenTime.hasBeenHours(1))
                    viewModel.showDialog.postValue(Constants.Dialog.REVIEW)
            }

            Constants.UserState.RATE -> {
                if (prefs.rateClicked)
                    prefs.userState = Constants.UserState.SHARE
                else if (isDefault
                    && prefs.firstOpenTime.isDaySince() >= 7
                    && calendar.get(Calendar.HOUR_OF_DAY) >= 16
                ) viewModel.showDialog.postValue(Constants.Dialog.RATE)
            }

            Constants.UserState.SHARE -> {
                if (isDefault && prefs.firstOpenTime.hasBeenDays(14)
                    && prefs.shareShownTime.isDaySince() >= 70
                    && calendar.get(Calendar.HOUR_OF_DAY) >= 16
                ) viewModel.showDialog.postValue(Constants.Dialog.SHARE)
            }
        }
    }

    /**
     * Whether Sakinah is the current default launcher, cached for [DEFAULT_LAUNCHER_TTL_MS].
     *
     * The underlying call is a `resolveActivity` binder round-trip. It is asked from
     * [applySolidBackground] and from every [checkForMessages] branch, both of which run
     * whenever the launcher comes back to the foreground — which for a launcher is every
     * Home press. The answer only changes when the user reassigns the default, so a short
     * TTL is enough to collapse the burst without going stale in practice.
     */
    private fun isDefaultLauncherCached(): Boolean {
        val now = System.currentTimeMillis()
        val cached = cachedIsDefault
        if (cached != null && now - cachedIsDefaultAt < DEFAULT_LAUNCHER_TTL_MS) return cached
        val resolved = isSakinaDefault(this)
        cachedIsDefault = resolved
        cachedIsDefaultAt = now
        return resolved
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private fun setupOrientation() {
        if (isTablet(this) || Build.VERSION.SDK_INT == Build.VERSION_CODES.O)
            return
        // In Android 8.0, windowIsTranslucent cannot be used with screenOrientation=portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun backToHomeScreen() {
        if (viewModel.isPrivateSpaceToggling) return
        binding.messageLayout.visibility = View.GONE
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
    }

    fun applySolidBackground() {
        when {
            // User explicitly wants a solid color background
            prefs.solidBackground -> {
                val color = if (isDarkThemeOn()) getColor(android.R.color.black)
                else getColor(android.R.color.white)
                binding.wallpaperLayer.visibility = View.GONE
                binding.wallpaperLayer.setImageDrawable(null)
                binding.root.background = null
                binding.root.setBackgroundColor(color)
            }
            // Default launcher: the system composites the wallpaper behind our
            // translucent window, so we hide our manual layer and stay transparent.
            isDefaultLauncherCached() -> {
                binding.wallpaperLayer.visibility = View.GONE
                binding.wallpaperLayer.setImageDrawable(null)
                binding.root.background = null
                binding.root.setBackgroundColor(Color.TRANSPARENT)
            }
            // Not the default launcher yet: the system will NOT draw the wallpaper
            // behind us, so fetch the current wallpaper and paint it into our own
            // full-screen ImageView layer.
            else -> {
                binding.root.background = null
                binding.root.setBackgroundColor(Color.TRANSPARENT)
                loadUserWallpaperAsync()
            }
        }
    }

    /**
     * Reads the user's wallpaper off the main thread and paints it into
     * [ActivityMainBinding.wallpaperLayer].
     *
     * This used to be a synchronous `WallpaperManager.getDrawable()` inside
     * [applySolidBackground], which is called from `onCreate` — so a device whose
     * wallpaper came from a large photo decoded a multi-megabyte bitmap on the launch
     * critical path, sized to the *source image* rather than the screen, and then handed
     * the oversized texture to a `centerCrop` ImageView. Decoding on IO with an
     * explicit `inSampleSize` fixes both the stall and the footprint.
     */
    private fun loadUserWallpaperAsync() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (!wallpaperPermissionRequested) {
                wallpaperPermissionRequested = true
                wallpaperPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            clearWallpaperLayer()
            return
        }

        wallpaperLoadJob?.cancel()
        // Screen size is read here, on the main thread: getScreenDimensions goes through
        // WindowManager.defaultDisplay, which is a UI-thread API.
        val (screenWidth, screenHeight) = getScreenDimensions(this)
        wallpaperLoadJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeUserWallpaper(screenWidth, screenHeight) }
            if (bitmap == null) {
                clearWallpaperLayer()
            } else {
                binding.wallpaperLayer.setImageBitmap(bitmap)
                binding.wallpaperLayer.visibility = View.VISIBLE
            }
        }
    }

    private fun clearWallpaperLayer() {
        binding.wallpaperLayer.setImageDrawable(null)
        binding.wallpaperLayer.visibility = View.GONE
    }

    /**
     * Decodes the system wallpaper downsampled to the screen, or null when it cannot be
     * read (no permission on API 33+, no wallpaper set, or a decode failure).
     *
     * `RGB_565` halves the memory: the wallpaper is drawn as an opaque full-screen
     * backdrop, so its alpha channel carries nothing.
     */
    @SuppressLint("MissingPermission") // Every WallpaperManager read is wrapped and falls back to null.
    private fun decodeUserWallpaper(screenWidth: Int, screenHeight: Int): Bitmap? {
        val wm = WallpaperManager.getInstance(this)

        // Preferred path: the raw file, which lets us size the decode ourselves.
        val fromFile = runCatching {
            wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { descriptor ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@use null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, screenWidth, screenHeight)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
            }
        }.getOrNull()
        if (fromFile != null) return fromFile

        // Fallback for the device that has no user-set wallpaper: peekDrawable() passes
        // returnDefault=false and returns null there, getDrawable() passes true and hands
        // back the built-in one. Both wrap the bitmap in a BitmapDrawable, so the cast
        // below holds for either.
        //
        // getFastDrawable() used to sit in this slot and could never work: it returns a
        // FastBitmapDrawable, which extends Drawable directly rather than BitmapDrawable,
        // so `as? BitmapDrawable` was always null and the whole fallback was dead.
        val drawable = runCatching { wm.peekDrawable() }.getOrNull()
            ?: runCatching { wm.drawable }.getOrNull()
            ?: return null
        return (drawable as? BitmapDrawable)?.bitmap
    }

    /** Largest power-of-two downscale that still covers the screen. */
    private fun sampleSizeFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun openLauncherChooser(resetFailed: Boolean) {
        if (resetFailed) {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun restartLauncherOrCheckTheme(forceRestart: Boolean = false) {
        if (forceRestart) {
            recreate()
        } else {
            checkTheme()
        }
    }

    private fun checkTheme() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            delay(200)
            if ((prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.white))
                || (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_NO && getColorFromAttr(R.attr.primaryColor) != getColor(R.color.black))
            )
                restartLauncherOrCheckTheme(true)
        }
    }

    override fun onDestroy() {
        profileReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            Constants.REQUEST_CODE_ENABLE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK)
                    prefs.lockModeOn = true
            }

            Constants.REQUEST_CODE_LAUNCHER_SELECTOR -> {
                if (resultCode == Activity.RESULT_OK) {
                    // The default may have just changed; do not serve the stale answer.
                    cachedIsDefault = null
                    resetLauncherViaFakeActivity()
                }
            }
        }
    }

    private companion object {
        /**
         * How long the default-launcher answer stays valid. Short enough that the very
         * next foreground pass after the user changes their default picks up the change.
         */
        const val DEFAULT_LAUNCHER_TTL_MS = 2_000L
    }
}
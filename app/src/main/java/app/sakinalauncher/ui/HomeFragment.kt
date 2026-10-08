package app.sakinalauncher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.sakinalauncher.MainViewModel
import app.sakinalauncher.R
import app.sakinalauncher.data.AppModel
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.NotePanelMode
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.databinding.FragmentHomeBinding
import app.sakinalauncher.helper.appUsagePermissionGranted
import app.sakinalauncher.helper.FontHelper
import app.sakinalauncher.helper.ScreenTimeGoal
import app.sakinalauncher.helper.dpToPx
import app.sakinalauncher.helper.expandNotificationDrawer
import app.sakinalauncher.helper.getChangedAppTheme
import app.sakinalauncher.helper.getUserHandleFromString
import app.sakinalauncher.helper.isPackageInstalled
import app.sakinalauncher.helper.openAlarmApp
import app.sakinalauncher.helper.openCalendar
import app.sakinalauncher.helper.openSearch
import app.sakinalauncher.helper.setPlainWallpaperByTheme
import app.sakinalauncher.helper.launchSwipeApp
import app.sakinalauncher.helper.showToast
import app.sakinalauncher.listener.OnSwipeTouchListener
import app.sakinalauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    /**
     * Hoisted out of [populateDateTime]: that runs on every onResume, and constructing a
     * SimpleDateFormat parses the pattern and builds a DateFormatSymbols set for the
     * locale each time. The Date is reused too — only its millis change.
     */
    private val dateFormat by lazy { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }
    private val reusableDate = Date()
    private var batteryManager: BatteryManager? = null

    /** Font family last applied to this view tree — see [onResume]. */
    private var appliedFontFamily: Int? = null

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        batteryManager = requireContext().getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        // Legibility treatment for text drawn straight on the wallpaper. Pointless when
        // the user has chosen a solid background — there is no wallpaper to fight.
        val wantWash = !prefs.solidBackground
        binding.homeWash.isVisible = wantWash
        binding.homeClockWash.isVisible = wantWash
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        // Only re-walk the tree when the family actually changed. applyFont recurses the
        // whole home layout and touches every TextView; on every resume that was pure
        // waste, since the font can only change from Settings.
        val family = prefs.fontFamily
        if (family != appliedFontFamily) {
            appliedFontFamily = family
            FontHelper.applyFont(view, prefs)
        }
        viewModel.isSakinaDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.homeApp9 -> showAppList(Constants.FLAG_SET_HOME_APP_9, prefs.appName9.isNotEmpty(), true)
            R.id.homeApp10 -> showAppList(Constants.FLAG_SET_HOME_APP_10, prefs.appName10.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isSakinaDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isSakinaDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        viewModel.showRecentApps.observe(viewLifecycleOwner) {
            binding.recents.performClick()
        }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
        binding.homeApp9.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp9))
        binding.homeApp10.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp10))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        // Landscape XML uses wrap_content + layout_gravity; update parent gravity so
        // start/end alignment actually moves the clock block across the screen.
        (binding.dateTimeLayout.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = Gravity.TOP or horizontalGravity
            binding.dateTimeLayout.layoutParams = lp
        }
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
        binding.homeApp9.gravity = horizontalGravity
        binding.homeApp10.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

        reusableDate.time = System.currentTimeMillis()
        var dateText = dateFormat.format(reusableDate)

        if (!prefs.showStatusBar) {
            val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        // Base offset below the clock block. With large system font scales the clock
        // (60sp + date) grows past this offset, so placeScreenTimeBelowClock() re-anchors
        // the meta lines under the measured clock height instead of overlapping it.
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
        placeScreenTimeBelowClock(marginTop, horizontalMargin)

        // B2: daily screen-time goal progress line (shown only when a goal is set)
        val goalMinutes = prefs.dailyScreenTimeGoalMinutes
        if (goalMinutes > 0) {
            val used = viewModel.getTodayUsageMillis()
            if (used != null) {
                val progress = ScreenTimeGoal.progress(prefs, used)
                binding.tvScreenTimeGoal.text = getString(
                    R.string.screen_time_goal_progress,
                    ScreenTimeGoal.formatUsed(requireContext(), used),
                    ScreenTimeGoal.formatGoal(goalMinutes),
                )
                binding.tvScreenTimeGoal.visibility = View.VISIBLE
                val goalParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = marginTop + 36.dpToPx()
                    marginStart = horizontalMargin
                    marginEnd = horizontalMargin
                    gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
                }
                binding.tvScreenTimeGoal.layoutParams = goalParams
                binding.tvScreenTimeGoal.setPadding(10.dpToPx(), 0, 10.dpToPx(), 6.dpToPx())
            }
        } else {
            binding.tvScreenTimeGoal.visibility = View.GONE
        }
    }

    /**
     * Re-anchors the screen-time meta lines below the measured clock block. The XML
     * defaults (72dp / 108dp) assume the default font scale; at larger scales the
     * 60sp clock outgrows them and the meta lines would overlap the clock text. This
     * waits for one layout pass, reads the real clock bottom, and only bumps the
     * offset when the clock is taller than the base offset (never the reverse, so the
     * normal look is untouched).
     */
    private fun placeScreenTimeBelowClock(baseMarginTop: Int, horizontalMargin: Int) {
        binding.dateTimeLayout.doOnLayout { dt ->
            if (dt.visibility != View.VISIBLE) return@doOnLayout
            val clockBottom = dt.top + dt.height
            val neededTop = clockBottom + 12.dpToPx()
            if (neededTop <= baseMarginTop) return@doOnLayout
            val params = binding.tvScreenTime.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin < neededTop) {
                params.topMargin = neededTop
                binding.tvScreenTime.layoutParams = params
            }
            val goalParams = binding.tvScreenTimeGoal.layoutParams as? FrameLayout.LayoutParams
            if (goalParams != null && binding.tvScreenTimeGoal.visibility == View.VISIBLE) {
                if (goalParams.topMargin < neededTop + 36.dpToPx()) {
                    goalParams.topMargin = neededTop + 36.dpToPx()
                    binding.tvScreenTimeGoal.layoutParams = goalParams
                }
            }
        }
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
        if (homeAppsNum == 8) return

        binding.homeApp9.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp9, prefs.appName9, prefs.appPackage9, prefs.appUser9, prefs.isShortcut9, prefs.shortcutId9)) {
            prefs.appName9 = ""
            prefs.appPackage9 = ""
        }
        if (homeAppsNum == 9) return

        binding.homeApp10.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp10, prefs.appName10, prefs.appPackage10, prefs.appUser10, prefs.isShortcut10, prefs.shortcutId10)) {
            prefs.appName10 = ""
            prefs.appPackage10 = ""
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
                textView.text = ""
                return false
            }
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
        binding.homeApp9.visibility = View.GONE
        binding.homeApp10.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        launchSwipeApp(requireContext(), viewModel, prefs, isLeft = false)
    }

    private fun openSwipeTarget(isLeft: Boolean) {
        when (if (isLeft) prefs.swipeLeftTarget else prefs.swipeRightTarget) {
            Constants.SwipeTarget.OFF -> return
            Constants.SwipeTarget.APP -> if (isLeft) openSwipeLeftApp() else openSwipeRightApp()
            Constants.SwipeTarget.PRODUCTIVE -> openNotePanel(prefs.resolveProductiveOpenMode(), isLeft)
            Constants.SwipeTarget.NOTES -> openNotePanel(prefs.resolveProductiveOpenMode(NotePanelMode.NOTES), isLeft)
            Constants.SwipeTarget.TODO -> openNotePanel(prefs.resolveProductiveOpenMode(NotePanelMode.TODO), isLeft)
            Constants.SwipeTarget.TIMER -> openNotePanel(prefs.resolveProductiveOpenMode(NotePanelMode.TIMER), isLeft)
            Constants.SwipeTarget.MUSLIM_CENTER -> openMuslimCenter(isLeft)
        }
    }

    private fun openNotePanel(mode: NotePanelMode, isLeft: Boolean) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.notePanelFragment) return
        val direction = if (isLeft) Constants.SwipeDirection.LEFT else Constants.SwipeDirection.RIGHT
        val args = bundleOf(
            Constants.Key.NOTE_PANEL_MODE to mode.name,
            Constants.Key.SWIPE_DIRECTION to direction
        )
        try {
            navController.navigate(R.id.action_mainFragment_to_notePanelFragment, args)
        } catch (e: Exception) {
            try {
                navController.navigate(R.id.notePanelFragment, args)
            } catch (fallbackException: Exception) {
                fallbackException.printStackTrace()
            }
        }
    }

    private fun openMuslimCenter(isLeft: Boolean) {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.muslimCenterFragment) return
        val direction = if (isLeft) Constants.SwipeDirection.LEFT else Constants.SwipeDirection.RIGHT
        val args = bundleOf(Constants.Key.SWIPE_DIRECTION to direction)
        try {
            navController.navigate(R.id.action_mainFragment_to_muslimCenterFragment, args)
        } catch (e: Exception) {
            try {
                navController.navigate(R.id.muslimCenterFragment, args)
            } catch (fallbackException: Exception) {
                fallbackException.printStackTrace()
            }
        }
    }

    private fun openSwipeLeftApp() {
        launchSwipeApp(requireContext(), viewModel, prefs, isLeft = true)
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun lockPhone() {
        // The accessibility service runs in a separate process (:serviceProcess),
        // so we cannot call it directly via a static reference. Instead we fire a
        // TYPE_VIEW_CLICKED accessibility event on the lock placeholder view; the
        // service catches it (matched by contentDescription) and performs the lock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            app.sakinalauncher.helper.isAccessServiceEnabled(requireContext())
        ) {
            binding.lock.performClick()
            return
        }
        // Fallback for older versions or when the a11y service is unavailable:
        // use DevicePolicyManager if device admin is active.
        requireActivity().runOnUiThread {
            try {
                val dpm = requireContext()
                    .getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                val cn = android.content.ComponentName(
                    requireContext(),
                    app.sakinalauncher.listener.DeviceAdmin::class.java
                )
                if (dpm.isAdminActive(cn)) {
                    dpm.lockNow()
                } else {
                    requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeTarget(isLeft = true)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeTarget(isLeft = false)
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeTarget(isLeft = true)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeTarget(isLeft = false)
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        batteryManager = null
        appliedFontFamily = null
        _binding = null
    }
}

package app.sakinalauncher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.sakinalauncher.data.AppModel
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.helper.SingleLiveEvent
import app.sakinalauncher.helper.MindfulLaunchHelper
import app.sakinalauncher.helper.ScreenTimeGoal
import app.sakinalauncher.helper.ScreenTimeGoalWorker
import app.sakinalauncher.helper.WallpaperWorker
import app.sakinalauncher.helper.formattedTimeSpent
import app.sakinalauncher.helper.getAppsList
import app.sakinalauncher.helper.getPrivateSpaceApps
import app.sakinalauncher.helper.getPrivateSpaceUserHandle
import app.sakinalauncher.helper.hasBeenMinutes
import app.sakinalauncher.helper.isSakinaDefault
import app.sakinalauncher.helper.isPackageInstalled
import app.sakinalauncher.helper.isPrivateSpaceLocked
import app.sakinalauncher.helper.showToast
import app.sakinalauncher.helper.usageStats.EventLogWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit


class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext by lazy { application.applicationContext }
    private val prefs = Prefs(appContext)

    val firstOpen = MutableLiveData<Boolean>()
    val refreshHome = MutableLiveData<Boolean>()
    val toggleDateTime = MutableLiveData<Unit>()
    val updateSwipeApps = MutableLiveData<Any>()
    val appList = MutableLiveData<List<AppModel>?>()
    val hiddenApps = MutableLiveData<List<AppModel>?>()
    val isSakinaDefault = MutableLiveData<Boolean>()
    val launcherResetFailed = MutableLiveData<Boolean>()
    val homeAppAlignment = MutableLiveData<Int>()
    val screenTimeValue = MutableLiveData<String>()
    val screenTimeGoalEvent = SingleLiveEvent<String>()
    private var screenTimeJob: Job? = null
    private var screenTimeGoalJob: Job? = null
    private var appLoadJob: Job? = null
    private var hiddenAppLoadJob: Job? = null
    private var privateSpaceLoadJob: Job? = null

    val privateSpaceApps = MutableLiveData<List<AppModel>?>()
    val privateSpaceLocked = MutableLiveData<Boolean>()
    val privateSpaceAvailable = MutableLiveData<Boolean>()

    // Suppress backToHomeScreen during Private Space lock/unlock auth
    var isPrivateSpaceToggling = false

    val showDialog = SingleLiveEvent<String>()
    val checkForMessages = SingleLiveEvent<Unit?>()
    val resetLauncherLiveData = SingleLiveEvent<Unit?>()
    val showRecentApps = SingleLiveEvent<Unit?>()

    /**
     * B1: request for the UI layer to show the mindful countdown dialog with an
     * Activity context (a Dialog cannot be attached to the application context —
     * that throws BadTokenException and force-closes the launcher).
     */
    data class MindfulLaunchRequest(
        val appLabel: String,
        val component: ComponentName,
        val userHandle: UserHandle,
    )
    val mindfulLaunchEvent = SingleLiveEvent<MindfulLaunchRequest>()

    fun selectedApp(appModel: AppModel, flag: Int) {
        if (appModel is AppModel.PrivateSpaceHeader) return
        when (flag) {
            Constants.FLAG_LAUNCH_APP -> {
                when (appModel) {
                    is AppModel.PinnedShortcut -> launchShortcut(appModel)
                    is AppModel.App ->
                        launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)

                    else -> {}
                }
            }

            Constants.FLAG_HIDDEN_APPS -> {
                if (appModel is AppModel.App) {
                    launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
                }
            }

            Constants.FLAG_SET_HOME_APP_1 -> saveHomeApp(appModel, 1)
            Constants.FLAG_SET_HOME_APP_2 -> saveHomeApp(appModel, 2)
            Constants.FLAG_SET_HOME_APP_3 -> saveHomeApp(appModel, 3)
            Constants.FLAG_SET_HOME_APP_4 -> saveHomeApp(appModel, 4)
            Constants.FLAG_SET_HOME_APP_5 -> saveHomeApp(appModel, 5)
            Constants.FLAG_SET_HOME_APP_6 -> saveHomeApp(appModel, 6)
            Constants.FLAG_SET_HOME_APP_7 -> saveHomeApp(appModel, 7)
            Constants.FLAG_SET_HOME_APP_8 -> saveHomeApp(appModel, 8)
            Constants.FLAG_SET_HOME_APP_9 -> saveHomeApp(appModel, 9)
            Constants.FLAG_SET_HOME_APP_10 -> saveHomeApp(appModel, 10)

            Constants.FLAG_SET_SWIPE_LEFT_APP -> saveSwipeApp(appModel, isLeft = true)
            Constants.FLAG_SET_SWIPE_RIGHT_APP -> saveSwipeApp(appModel, isLeft = false)
            Constants.FLAG_SET_CLOCK_APP -> saveClockApp(appModel)
            Constants.FLAG_SET_CALENDAR_APP -> saveCalendarApp(appModel)
            Constants.FLAG_SET_SCREEN_TIME_APP -> saveScreenTimeApp(appModel)
        }
    }

    private fun launchShortcut(appModel: AppModel.PinnedShortcut) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            appContext.showToast(appContext.getString(R.string.unable_to_open_app))
            return
        }
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val query = LauncherApps.ShortcutQuery().apply {
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        }
        launcher.getShortcuts(query, appModel.user)?.find { it.id == appModel.shortcutId }
            ?.let { shortcut ->
                launcher.startShortcut(shortcut, null, null)
            }
    }

    private fun saveHomeApp(appModel: AppModel, position: Int) {
        when (appModel) {
            is AppModel.PrivateSpaceHeader -> return
            is AppModel.App -> {
                when (position) {
                    1 -> {
                        prefs.appName1 = appModel.appLabel
                        prefs.appPackage1 = appModel.appPackage
                        prefs.appUser1 = appModel.user.toString()
                        prefs.appActivityClassName1 = appModel.activityClassName
                        prefs.isShortcut1 = false
                        prefs.shortcutId1 = ""
                    }

                    2 -> {
                        prefs.appName2 = appModel.appLabel
                        prefs.appPackage2 = appModel.appPackage
                        prefs.appUser2 = appModel.user.toString()
                        prefs.appActivityClassName2 = appModel.activityClassName
                        prefs.isShortcut2 = false
                        prefs.shortcutId2 = ""
                    }

                    3 -> {
                        prefs.appName3 = appModel.appLabel
                        prefs.appPackage3 = appModel.appPackage
                        prefs.appUser3 = appModel.user.toString()
                        prefs.appActivityClassName3 = appModel.activityClassName
                        prefs.isShortcut3 = false
                        prefs.shortcutId3 = ""
                    }

                    4 -> {
                        prefs.appName4 = appModel.appLabel
                        prefs.appPackage4 = appModel.appPackage
                        prefs.appUser4 = appModel.user.toString()
                        prefs.appActivityClassName4 = appModel.activityClassName
                        prefs.isShortcut4 = false
                        prefs.shortcutId4 = ""
                    }

                    5 -> {
                        prefs.appName5 = appModel.appLabel
                        prefs.appPackage5 = appModel.appPackage
                        prefs.appUser5 = appModel.user.toString()
                        prefs.appActivityClassName5 = appModel.activityClassName
                        prefs.isShortcut5 = false
                        prefs.shortcutId5 = ""
                    }

                    6 -> {
                        prefs.appName6 = appModel.appLabel
                        prefs.appPackage6 = appModel.appPackage
                        prefs.appUser6 = appModel.user.toString()
                        prefs.appActivityClassName6 = appModel.activityClassName
                        prefs.isShortcut6 = false
                        prefs.shortcutId6 = ""
                    }

                    7 -> {
                        prefs.appName7 = appModel.appLabel
                        prefs.appPackage7 = appModel.appPackage
                        prefs.appUser7 = appModel.user.toString()
                        prefs.appActivityClassName7 = appModel.activityClassName
                        prefs.isShortcut7 = false
                        prefs.shortcutId7 = ""
                    }

                    8 -> {
                        prefs.appName8 = appModel.appLabel
                        prefs.appPackage8 = appModel.appPackage
                        prefs.appUser8 = appModel.user.toString()
                        prefs.appActivityClassName8 = appModel.activityClassName
                        prefs.isShortcut8 = false
                        prefs.shortcutId8 = ""
                    }

                    9 -> {
                        prefs.appName9 = appModel.appLabel
                        prefs.appPackage9 = appModel.appPackage
                        prefs.appUser9 = appModel.user.toString()
                        prefs.appActivityClassName9 = appModel.activityClassName
                        prefs.isShortcut9 = false
                        prefs.shortcutId9 = ""
                    }

                    10 -> {
                        prefs.appName10 = appModel.appLabel
                        prefs.appPackage10 = appModel.appPackage
                        prefs.appUser10 = appModel.user.toString()
                        prefs.appActivityClassName10 = appModel.activityClassName
                        prefs.isShortcut10 = false
                        prefs.shortcutId10 = ""
                    }
                }
            }

            is AppModel.PinnedShortcut -> {
                when (position) {
                    1 -> {
                        prefs.appName1 = appModel.appLabel
                        prefs.appPackage1 = appModel.appPackage
                        prefs.appUser1 = appModel.user.toString()
                        prefs.appActivityClassName1 = null
                        prefs.isShortcut1 = true
                        prefs.shortcutId1 = appModel.shortcutId
                    }

                    2 -> {
                        prefs.appName2 = appModel.appLabel
                        prefs.appPackage2 = appModel.appPackage
                        prefs.appUser2 = appModel.user.toString()
                        prefs.appActivityClassName2 = null
                        prefs.isShortcut2 = true
                        prefs.shortcutId2 = appModel.shortcutId
                    }

                    3 -> {
                        prefs.appName3 = appModel.appLabel
                        prefs.appPackage3 = appModel.appPackage
                        prefs.appUser3 = appModel.user.toString()
                        prefs.appActivityClassName3 = null
                        prefs.isShortcut3 = true
                        prefs.shortcutId3 = appModel.shortcutId
                    }

                    4 -> {
                        prefs.appName4 = appModel.appLabel
                        prefs.appPackage4 = appModel.appPackage
                        prefs.appUser4 = appModel.user.toString()
                        prefs.appActivityClassName4 = null
                        prefs.isShortcut4 = true
                        prefs.shortcutId4 = appModel.shortcutId
                    }

                    5 -> {
                        prefs.appName5 = appModel.appLabel
                        prefs.appPackage5 = appModel.appPackage
                        prefs.appUser5 = appModel.user.toString()
                        prefs.appActivityClassName5 = null
                        prefs.isShortcut5 = true
                        prefs.shortcutId5 = appModel.shortcutId
                    }

                    6 -> {
                        prefs.appName6 = appModel.appLabel
                        prefs.appPackage6 = appModel.appPackage
                        prefs.appUser6 = appModel.user.toString()
                        prefs.appActivityClassName6 = null
                        prefs.isShortcut6 = true
                        prefs.shortcutId6 = appModel.shortcutId
                    }

                    7 -> {
                        prefs.appName7 = appModel.appLabel
                        prefs.appPackage7 = appModel.appPackage
                        prefs.appUser7 = appModel.user.toString()
                        prefs.appActivityClassName7 = null
                        prefs.isShortcut7 = true
                        prefs.shortcutId7 = appModel.shortcutId
                    }

                    8 -> {
                        prefs.appName8 = appModel.appLabel
                        prefs.appPackage8 = appModel.appPackage
                        prefs.appUser8 = appModel.user.toString()
                        prefs.appActivityClassName8 = null
                        prefs.isShortcut8 = true
                        prefs.shortcutId8 = appModel.shortcutId
                    }

                    9 -> {
                        prefs.appName9 = appModel.appLabel
                        prefs.appPackage9 = appModel.appPackage
                        prefs.appUser9 = appModel.user.toString()
                        prefs.appActivityClassName9 = null
                        prefs.isShortcut9 = true
                        prefs.shortcutId9 = appModel.shortcutId
                    }

                    10 -> {
                        prefs.appName10 = appModel.appLabel
                        prefs.appPackage10 = appModel.appPackage
                        prefs.appUser10 = appModel.user.toString()
                        prefs.appActivityClassName10 = null
                        prefs.isShortcut10 = true
                        prefs.shortcutId10 = appModel.shortcutId
                    }
                }
            }
        }
        refreshHome(false)
    }

    private fun saveSwipeApp(appModel: AppModel, isLeft: Boolean) {
        when (appModel) {
            is AppModel.PrivateSpaceHeader -> return
            is AppModel.App -> {
                if (isLeft) {
                    if (prefs.swipeLeftTarget == Constants.SwipeTarget.OFF) prefs.swipeLeftTarget = Constants.SwipeTarget.APP
                    prefs.appNameSwipeLeft = appModel.appLabel
                    prefs.appPackageSwipeLeft = appModel.appPackage
                    prefs.appUserSwipeLeft = appModel.user.toString()
                    prefs.appActivityClassNameSwipeLeft = appModel.activityClassName
                    prefs.isShortcutSwipeLeft = false
                    prefs.shortcutIdSwipeLeft = ""
                } else {
                    if (prefs.swipeRightTarget == Constants.SwipeTarget.OFF) prefs.swipeRightTarget = Constants.SwipeTarget.APP
                    prefs.appNameSwipeRight = appModel.appLabel
                    prefs.appPackageSwipeRight = appModel.appPackage
                    prefs.appUserSwipeRight = appModel.user.toString()
                    prefs.appActivityClassNameRight = appModel.activityClassName
                    prefs.isShortcutSwipeRight = false
                    prefs.shortcutIdSwipeRight = ""
                }
            }

            is AppModel.PinnedShortcut -> {
                if (isLeft) {
                    if (prefs.swipeLeftTarget == Constants.SwipeTarget.OFF) prefs.swipeLeftTarget = Constants.SwipeTarget.APP
                    prefs.appNameSwipeLeft = appModel.appLabel
                    prefs.appPackageSwipeLeft = appModel.appPackage
                    prefs.appUserSwipeLeft = appModel.user.toString()
                    prefs.appActivityClassNameSwipeLeft = null
                    prefs.isShortcutSwipeLeft = true
                    prefs.shortcutIdSwipeLeft = appModel.shortcutId
                } else {
                    if (prefs.swipeRightTarget == Constants.SwipeTarget.OFF) prefs.swipeRightTarget = Constants.SwipeTarget.APP
                    prefs.appNameSwipeRight = appModel.appLabel
                    prefs.appPackageSwipeRight = appModel.appPackage
                    prefs.appUserSwipeRight = appModel.user.toString()
                    prefs.appActivityClassNameRight = null
                    prefs.isShortcutSwipeRight = true
                    prefs.shortcutIdSwipeRight = appModel.shortcutId
                }
            }
        }
        updateSwipeApps()
    }

    private fun saveClockApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.clockAppPackage = appModel.appPackage
            prefs.clockAppUser = appModel.user.toString()
            prefs.clockAppClassName = appModel.activityClassName
        }
    }

    private fun saveCalendarApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.calendarAppPackage = appModel.appPackage
            prefs.calendarAppUser = appModel.user.toString()
            prefs.calendarAppClassName = appModel.activityClassName
        }
    }

    private fun saveScreenTimeApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.screenTimeAppPackage = appModel.appPackage
            prefs.screenTimeAppUser = appModel.user.toString()
            prefs.screenTimeAppClassName = appModel.activityClassName
        }
    }

    fun firstOpen(value: Boolean) {
        firstOpen.postValue(value)
    }

    fun refreshHome(appCountUpdated: Boolean) {
        refreshHome.value = appCountUpdated
    }

    fun toggleDateTime() {
        toggleDateTime.postValue(Unit)
    }

    private fun updateSwipeApps() {
        updateSwipeApps.postValue(Unit)
    }

    private fun launchApp(packageName: String, activityClassName: String?, userHandle: UserHandle) {
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityInfo = launcher.getActivityList(packageName, userHandle)

        val isActivityValid = activityClassName.isNullOrBlank().not()
                && activityInfo.any { it.componentName.className == activityClassName }

        val component = if (isActivityValid)
            ComponentName(packageName, activityClassName)
        else {
            when (activityInfo.size) {
                0 -> {
                    appContext.showToast(appContext.getString(R.string.app_not_found))
                    return
                }

                1 -> ComponentName(packageName, activityInfo[0].name)
                else -> ComponentName(packageName, activityInfo[activityInfo.size - 1].name)
            }.also { prefs.updateAppActivityClassName(packageName, it.className) }
        }

        // Mindful launch (B1): a cancellable countdown before an opted-in app opens.
        // Skipped entirely when the package is a system/emergency target (see
        // MindfulLaunchHelper.ALWAYS_INSTANT), when mindful launch is off, or when the
        // app has not opted in.
        val appLabel = activityInfo.firstOrNull { it.componentName.className == component.className }
            ?.label?.toString()
            ?: packageName
        if (MindfulLaunchHelper.isMindful(appContext, prefs, packageName)) {
            // The countdown dialog must be shown from the UI layer with an Activity
            // context — posting a request here avoids the BadTokenException that
            // force-closed the launcher when the dialog was built on appContext.
            mindfulLaunchEvent.value = MindfulLaunchRequest(
                appLabel = appLabel,
                component = component,
                userHandle = userHandle,
            )
            return
        }

        launchComponent(component, userHandle)
    }

    /**
     * B1: launches the resolved component directly. Called by the UI layer after
     * the mindful countdown completes (with an Activity context on the dialog).
     */
    fun launchComponent(component: ComponentName, userHandle: UserHandle) {
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        try {
            launcher.startMainActivity(component, userHandle, null, null)
        } catch (e: SecurityException) {
            try {
                launcher.startMainActivity(component, android.os.Process.myUserHandle(), null, null)
            } catch (e: Exception) {
                appContext.showToast(appContext.getString(R.string.unable_to_open_app))
            }
        } catch (e: Exception) {
            appContext.showToast(appContext.getString(R.string.unable_to_open_app))
        }
    }

    fun getAppList(includeHiddenApps: Boolean = false) {
        appLoadJob?.cancel()
        appLoadJob = viewModelScope.launch {
            val apps = getAppsList(appContext, prefs, includeRegularApps = true, includeHiddenApps)
            appList.value = apps
        }
        getPrivateSpaceAppList()
    }

    fun getHiddenApps() {
        hiddenAppLoadJob?.cancel()
        hiddenAppLoadJob = viewModelScope.launch {
            hiddenApps.value =
                getAppsList(appContext, prefs, includeRegularApps = false, includeHiddenApps = true)
        }
    }

    fun isSakinaDefault() {
        viewModelScope.launch {
            isSakinaDefault.value = withContext(Dispatchers.IO) { isSakinaDefault(appContext) }
        }
    }

    fun setWallpaperWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val uploadWorkRequest = PeriodicWorkRequestBuilder<WallpaperWorker>(4, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager
            .getInstance(appContext)
            .enqueueUniquePeriodicWork(
                Constants.WALLPAPER_WORKER_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                uploadWorkRequest
            )
    }

    fun cancelWallpaperWorker() {
        WorkManager.getInstance(appContext).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
        prefs.dailyWallpaperUrl = ""
        prefs.dailyWallpaper = false
    }

    /**
     * B1: toggle mindful-launch opt-in for one package. Returns the new state.
     * Never touches system/emergency packages — [MindfulLaunchHelper.isMindful]
     * already excludes them, and the UI hides them via [isMindfulEligible].
     */
    fun toggleMindfulApp(packageName: String): Boolean {
        val current = prefs.mindfulApps
        val enabled = !current.contains(packageName)
        if (enabled) current.add(packageName) else current.remove(packageName)
        prefs.mindfulApps = current
        return enabled
    }

    fun isMindfulApp(packageName: String): Boolean = prefs.mindfulApps.contains(packageName)

    /**
     * B2: schedule or cancel the screen-time goal worker. Called whenever the goal
     * minutes change, and on launcher start, so the worker is always in sync.
     */
    fun syncScreenTimeGoalWorker() {
        val workManager = WorkManager.getInstance(appContext)
        if (prefs.dailyScreenTimeGoalMinutes <= 0) {
            workManager.cancelUniqueWork(Constants.SCREEN_TIME_GOAL_WORKER_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScreenTimeGoalWorker>(30, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            Constants.SCREEN_TIME_GOAL_WORKER_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** B2: check the goal against today's usage; fires the in-launcher dialog notice. */
    fun checkScreenTimeGoal() {
        if (prefs.dailyScreenTimeGoalMinutes <= 0) return
        screenTimeGoalJob?.cancel()
        screenTimeGoalJob = viewModelScope.launch {
            val usedMillis = withContext(Dispatchers.Default) {
                val eventLogWrapper = EventLogWrapper(appContext)
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endTime = System.currentTimeMillis()
                eventLogWrapper.aggregateSimpleUsageStats(
                    eventLogWrapper.aggregateForegroundStats(
                        eventLogWrapper.getForegroundStatsByTimestamps(calendar.timeInMillis, endTime)
                    )
                )
            }
            when (ScreenTimeGoal.checkNotice(prefs, usedMillis)) {
                "reached" -> screenTimeGoalEvent.postValue(
                    appContext.getString(
                        R.string.screen_time_goal_reached_text,
                        appContext.formattedTimeSpent(usedMillis),
                        ScreenTimeGoal.formatGoal(prefs.dailyScreenTimeGoalMinutes),
                    )
                )

                "missed" -> screenTimeGoalEvent.postValue(
                    appContext.getString(
                        R.string.screen_time_goal_missed_text,
                        ScreenTimeGoal.formatGoal(prefs.dailyScreenTimeGoalMinutes),
                    )
                )

                else -> {}
            }
        }
    }

    /** B2: today's used millis, or null when usage permission is missing. */
    fun getTodayUsageMillis(): Long? = ScreenTimeGoal.getTodaysUsageMillis(appContext)

    /** B2: goal progress 0f..1f for the current day (0 when disabled). */
    fun screenTimeGoalProgress(): Float {
        val used = getTodayUsageMillis() ?: return 0f
        return ScreenTimeGoal.progress(prefs, used)
    }

    fun updateHomeAlignment(gravity: Int) {
        prefs.homeAlignment = gravity
        homeAppAlignment.value = prefs.homeAlignment
    }

    fun getTodaysScreenTime() {
        if (prefs.screenTimeLastUpdated.hasBeenMinutes(1).not() || screenTimeJob?.isActive == true) return

        screenTimeJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val eventLogWrapper = EventLogWrapper(appContext)
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val endTime = System.currentTimeMillis()
                val timeSpent = eventLogWrapper.aggregateSimpleUsageStats(
                    eventLogWrapper.aggregateForegroundStats(
                        eventLogWrapper.getForegroundStatsByTimestamps(calendar.timeInMillis, endTime)
                    )
                )
                appContext.formattedTimeSpent(timeSpent) to endTime
            }
            screenTimeValue.value = result.first
            prefs.screenTimeLastUpdated = result.second
        }
    }

    fun getPrivateSpaceAppList() {
        privateSpaceLoadJob?.cancel()
        privateSpaceLoadJob = viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val handle = getPrivateSpaceUserHandle(appContext)
                val locked = handle == null || isPrivateSpaceLocked(appContext, handle)
                val apps = if (handle != null && !locked) getPrivateSpaceApps(appContext, prefs) else emptyList()
                Triple(handle != null, locked, apps)
            }
            privateSpaceAvailable.value = state.first
            privateSpaceLocked.value = state.second
            privateSpaceApps.value = state.third
        }
    }

    fun openPrivateSpaceSettings() {
        try {
            val intent = Intent("android.settings.PRIVATE_SPACE_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            } catch (_: Exception) {
                appContext.showToast(appContext.getString(R.string.unable_to_open_app))
            }
        }
    }

    fun togglePrivateSpaceLock() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val handle = getPrivateSpaceUserHandle(appContext) ?: return
        try {
            isPrivateSpaceToggling = true
            val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
            val currentlyLocked = userManager.isQuietModeEnabled(handle)
            userManager.requestQuietModeEnabled(!currentlyLocked, handle)
        } catch (e: Exception) {
            isPrivateSpaceToggling = false
            e.printStackTrace()
        }
    }

    fun setDefaultClockApp() {
        viewModelScope.launch {
            try {
                Constants.CLOCK_APP_PACKAGES.firstOrNull { appContext.isPackageInstalled(it) }?.let { packageName ->
                    appContext.packageManager.getLaunchIntentForPackage(packageName)?.component?.className?.let {
                        prefs.clockAppPackage = packageName
                        prefs.clockAppClassName = it
                        prefs.clockAppUser = android.os.Process.myUserHandle().toString()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

package app.sakinalauncher.data

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(context: Context) {
    private val PREFS_FILENAME = "app.sakinalauncher"

    private val FIRST_OPEN = "FIRST_OPEN"
    private val FIRST_OPEN_TIME = "FIRST_OPEN_TIME"
    private val FIRST_SETTINGS_OPEN = "FIRST_SETTINGS_OPEN"
    private val FIRST_HIDE = "FIRST_HIDE"
    private val USER_STATE = "USER_STATE"
    private val LOCK_MODE = "LOCK_MODE"
    private val HOME_APPS_NUM = "HOME_APPS_NUM"
    private val AUTO_SHOW_KEYBOARD = "AUTO_SHOW_KEYBOARD"
    private val KEYBOARD_MESSAGE = "KEYBOARD_MESSAGE"
    private val DAILY_WALLPAPER = "DAILY_WALLPAPER"
    private val DAILY_WALLPAPER_URL = "DAILY_WALLPAPER_URL"
    private val SOLID_BACKGROUND = "SOLID_BACKGROUND"
    private val HOME_ALIGNMENT = "HOME_ALIGNMENT"
    private val HOME_BOTTOM_ALIGNMENT = "HOME_BOTTOM_ALIGNMENT"
    private val APP_LABEL_ALIGNMENT = "APP_LABEL_ALIGNMENT"
    private val STATUS_BAR = "STATUS_BAR"
    private val DATE_TIME_VISIBILITY = "DATE_TIME_VISIBILITY"
    private val SWIPE_LEFT_ENABLED = "SWIPE_LEFT_ENABLED"
    private val SWIPE_RIGHT_ENABLED = "SWIPE_RIGHT_ENABLED"
    private val SWIPE_LEFT_TARGET = "SWIPE_LEFT_TARGET"
    private val SWIPE_RIGHT_TARGET = "SWIPE_RIGHT_TARGET"
    private val POMODORO_FOCUS_MINUTES = "POMODORO_FOCUS_MINUTES"
    private val POMODORO_FOCUS_SECONDS = "POMODORO_FOCUS_SECONDS"
    private val POMODORO_TIMER_TOTAL_MILLIS = "POMODORO_TIMER_TOTAL_MILLIS"
    private val POMODORO_TIMER_END_ELAPSED_REALTIME = "POMODORO_TIMER_END_ELAPSED_REALTIME"
    private val POMODORO_TIMER_REMAINING_MILLIS = "POMODORO_TIMER_REMAINING_MILLIS"
    private val HIDDEN_APPS = "HIDDEN_APPS"
    private val HIDDEN_APPS_UPDATED = "HIDDEN_APPS_UPDATED"
    private val SHOW_HINT_COUNTER = "SHOW_HINT_COUNTER"
    private val APP_THEME = "APP_THEME"
    private val ABOUT_CLICKED = "ABOUT_CLICKED"
    private val RATE_CLICKED = "RATE_CLICKED"
    private val WALLPAPER_MSG_SHOWN = "WALLPAPER_MSG_SHOWN"
    private val SHARE_SHOWN_TIME = "SHARE_SHOWN_TIME"
    private val SWIPE_DOWN_ACTION = "SWIPE_DOWN_ACTION"
    private val TEXT_SIZE_SCALE = "TEXT_SIZE_SCALE"
    private val PRO_MESSAGE_SHOWN = "PRO_MESSAGE_SHOWN"
    private val HIDE_SET_DEFAULT_LAUNCHER = "HIDE_SET_DEFAULT_LAUNCHER"
    private val SCREEN_TIME_LAST_UPDATED = "SCREEN_TIME_LAST_UPDATED"
    private val MINDFUL_APPS = "MINDFUL_APPS"
    private val MINDFUL_LAUNCH_ENABLED = "MINDFUL_LAUNCH_ENABLED"
    private val MINDFUL_DELAY_SECONDS = "MINDFUL_DELAY_SECONDS"
    private val DAILY_SCREEN_TIME_GOAL_MINUTES = "DAILY_SCREEN_TIME_GOAL_MINUTES"
    private val SCREEN_TIME_GOAL_REACHED_DAY = "SCREEN_TIME_GOAL_REACHED_DAY"
    private val SCREEN_TIME_GOAL_MISSED_DAY = "SCREEN_TIME_GOAL_MISSED_DAY"
    private val LAUNCHER_RESTART_TIMESTAMP = "LAUNCHER_RECREATE_TIMESTAMP"
    private val SHOWN_ON_DAY_OF_YEAR = "SHOWN_ON_DAY_OF_YEAR"
    private val HOME_BUTTON_SHOW_RECENTS = "HOME_BUTTON_SHOW_RECENTS"
    private val FONT_FAMILY = "FONT_FAMILY"
    private val PRODUCTIVE_PANEL_SIZE = "PRODUCTIVE_PANEL_SIZE"
    private val PRODUCTIVE_WIDGET_NOTES = "PRODUCTIVE_WIDGET_NOTES"
    private val PRODUCTIVE_WIDGET_TODO = "PRODUCTIVE_WIDGET_TODO"
    private val PRODUCTIVE_WIDGET_TIMER = "PRODUCTIVE_WIDGET_TIMER"
    private val PRODUCTIVE_WIDGET_WIDGETS = "PRODUCTIVE_WIDGET_WIDGETS"
    private val PRODUCTIVE_DIALOG_WIDTH_SCALE = "PRODUCTIVE_DIALOG_WIDTH_SCALE"
    private val PRODUCTIVE_LAST_MODE = "PRODUCTIVE_LAST_MODE"

    private val APP_NAME_1 = "APP_NAME_1"
    private val APP_NAME_2 = "APP_NAME_2"
    private val APP_NAME_3 = "APP_NAME_3"
    private val APP_NAME_4 = "APP_NAME_4"
    private val APP_NAME_5 = "APP_NAME_5"
    private val APP_NAME_6 = "APP_NAME_6"
    private val APP_NAME_7 = "APP_NAME_7"
    private val APP_NAME_8 = "APP_NAME_8"
    private val APP_NAME_9 = "APP_NAME_9"
    private val APP_NAME_10 = "APP_NAME_10"
    private val APP_PACKAGE_1 = "APP_PACKAGE_1"
    private val APP_PACKAGE_2 = "APP_PACKAGE_2"
    private val APP_PACKAGE_3 = "APP_PACKAGE_3"
    private val APP_PACKAGE_4 = "APP_PACKAGE_4"
    private val APP_PACKAGE_5 = "APP_PACKAGE_5"
    private val APP_PACKAGE_6 = "APP_PACKAGE_6"
    private val APP_PACKAGE_7 = "APP_PACKAGE_7"
    private val APP_PACKAGE_8 = "APP_PACKAGE_8"
    private val APP_PACKAGE_9 = "APP_PACKAGE_9"
    private val APP_PACKAGE_10 = "APP_PACKAGE_10"
    private val APP_ACTIVITY_CLASS_NAME_1 = "APP_ACTIVITY_CLASS_NAME_1"
    private val APP_ACTIVITY_CLASS_NAME_2 = "APP_ACTIVITY_CLASS_NAME_2"
    private val APP_ACTIVITY_CLASS_NAME_3 = "APP_ACTIVITY_CLASS_NAME_3"
    private val APP_ACTIVITY_CLASS_NAME_4 = "APP_ACTIVITY_CLASS_NAME_4"
    private val APP_ACTIVITY_CLASS_NAME_5 = "APP_ACTIVITY_CLASS_NAME_5"
    private val APP_ACTIVITY_CLASS_NAME_6 = "APP_ACTIVITY_CLASS_NAME_6"
    private val APP_ACTIVITY_CLASS_NAME_7 = "APP_ACTIVITY_CLASS_NAME_7"
    private val APP_ACTIVITY_CLASS_NAME_8 = "APP_ACTIVITY_CLASS_NAME_8"
    private val APP_ACTIVITY_CLASS_NAME_9 = "APP_ACTIVITY_CLASS_NAME_9"
    private val APP_ACTIVITY_CLASS_NAME_10 = "APP_ACTIVITY_CLASS_NAME_10"
    private val APP_USER_1 = "APP_USER_1"
    private val APP_USER_2 = "APP_USER_2"
    private val APP_USER_3 = "APP_USER_3"
    private val APP_USER_4 = "APP_USER_4"
    private val APP_USER_5 = "APP_USER_5"
    private val APP_USER_6 = "APP_USER_6"
    private val APP_USER_7 = "APP_USER_7"
    private val APP_USER_8 = "APP_USER_8"
    private val APP_USER_9 = "APP_USER_9"
    private val APP_USER_10 = "APP_USER_10"

    private val APP_NAME_SWIPE_LEFT = "APP_NAME_SWIPE_LEFT"
    private val APP_NAME_SWIPE_RIGHT = "APP_NAME_SWIPE_RIGHT"
    private val APP_PACKAGE_SWIPE_LEFT = "APP_PACKAGE_SWIPE_LEFT"
    private val APP_PACKAGE_SWIPE_RIGHT = "APP_PACKAGE_SWIPE_RIGHT"
    private val APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT = "APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT"
    private val APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT = "APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT"
    private val APP_USER_SWIPE_LEFT = "APP_USER_SWIPE_LEFT"
    private val APP_USER_SWIPE_RIGHT = "APP_USER_SWIPE_RIGHT"
    private val CLOCK_APP_PACKAGE = "CLOCK_APP_PACKAGE"
    private val CLOCK_APP_USER = "CLOCK_APP_USER"
    private val CLOCK_APP_CLASS_NAME = "CLOCK_APP_CLASS_NAME"
    private val CALENDAR_APP_PACKAGE = "CALENDAR_APP_PACKAGE"
    private val CALENDAR_APP_USER = "CALENDAR_APP_USER"
    private val CALENDAR_APP_CLASS_NAME = "CALENDAR_APP_CLASS_NAME"
    private val SCREEN_TIME_APP_PACKAGE = "SCREEN_TIME_APP_PACKAGE"
    private val SCREEN_TIME_APP_USER = "SCREEN_TIME_APP_USER"
    private val SCREEN_TIME_APP_CLASS_NAME = "SCREEN_TIME_APP_CLASS_NAME"

    private val IS_SHORTCUT_1 = "IS_SHORTCUT_1"
    private val SHORTCUT_ID_1 = "SHORTCUT_ID_1"
    private val IS_SHORTCUT_2 = "IS_SHORTCUT_2"
    private val SHORTCUT_ID_2 = "SHORTCUT_ID_2"
    private val IS_SHORTCUT_3 = "IS_SHORTCUT_3"
    private val SHORTCUT_ID_3 = "SHORTCUT_ID_3"
    private val IS_SHORTCUT_4 = "IS_SHORTCUT_4"
    private val SHORTCUT_ID_4 = "SHORTCUT_ID_4"
    private val IS_SHORTCUT_5 = "IS_SHORTCUT_5"
    private val SHORTCUT_ID_5 = "SHORTCUT_ID_5"
    private val IS_SHORTCUT_6 = "IS_SHORTCUT_6"
    private val SHORTCUT_ID_6 = "SHORTCUT_ID_6"
    private val IS_SHORTCUT_7 = "IS_SHORTCUT_7"
    private val SHORTCUT_ID_7 = "SHORTCUT_ID_7"
    private val IS_SHORTCUT_8 = "IS_SHORTCUT_8"
    private val SHORTCUT_ID_8 = "SHORTCUT_ID_8"
    private val IS_SHORTCUT_9 = "IS_SHORTCUT_9"
    private val SHORTCUT_ID_9 = "SHORTCUT_ID_9"
    private val IS_SHORTCUT_10 = "IS_SHORTCUT_10"
    private val SHORTCUT_ID_10 = "SHORTCUT_ID_10"

    private val SHORTCUT_ID_SWIPE_LEFT = "SHORTCUT_ID_SWIPE_LEFT"
    private val IS_SHORTCUT_SWIPE_LEFT = "IS_SHORTCUT_SWIPE_LEFT"
    private val SHORTCUT_ID_SWIPE_RIGHT = "SHORTCUT_ID_SWIPE_RIGHT"
    private val IS_SHORTCUT_SWIPE_RIGHT = "IS_SHORTCUT_SWIPE_RIGHT"

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_FILENAME, 0)

    // region SharedPreferences property delegates
    // These remove the repetitive get()/set() boilerplate. Each delegate maps a
    // public property to a single SharedPreferences key with a fixed default, with
    // the exact same read/write semantics as the original explicit accessors.
    private fun boolPref(key: String, default: Boolean) =
        object : ReadWriteProperty<Prefs, Boolean> {
            override fun getValue(thisRef: Prefs, property: KProperty<*>) = prefs.getBoolean(key, default)
            override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Boolean) {
                prefs.edit { putBoolean(key, value) }
            }
        }

    private fun intPref(key: String, default: Int) =
        object : ReadWriteProperty<Prefs, Int> {
            override fun getValue(thisRef: Prefs, property: KProperty<*>) = prefs.getInt(key, default)
            override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Int) {
                prefs.edit { putInt(key, value) }
            }
        }

    private fun longPref(key: String, default: Long) =
        object : ReadWriteProperty<Prefs, Long> {
            override fun getValue(thisRef: Prefs, property: KProperty<*>) = prefs.getLong(key, default)
            override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Long) {
                prefs.edit { putLong(key, value) }
            }
        }

    private fun floatPref(key: String, default: Float) =
        object : ReadWriteProperty<Prefs, Float> {
            override fun getValue(thisRef: Prefs, property: KProperty<*>) = prefs.getFloat(key, default)
            override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Float) {
                prefs.edit { putFloat(key, value) }
            }
        }

    private fun stringPref(key: String, default: String) =
        object : ReadWriteProperty<Prefs, String> {
            override fun getValue(thisRef: Prefs, property: KProperty<*>) =
                prefs.getString(key, default) ?: default
            override fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) {
                prefs.edit { putString(key, value) }
            }
        }

    private fun nullableStringPref(key: String, default: String) =
        object : ReadWriteProperty<Prefs, String?> {
            override fun getValue(thisRef: Prefs, property: KProperty<*>) =
                prefs.getString(key, default) ?: default
            override fun setValue(thisRef: Prefs, property: KProperty<*>, value: String?) {
                prefs.edit { putString(key, value) }
            }
        }
    // endregion

    var firstOpen: Boolean by boolPref(FIRST_OPEN, true)
    var firstOpenTime: Long by longPref(FIRST_OPEN_TIME, 0L)
    var firstSettingsOpen: Boolean by boolPref(FIRST_SETTINGS_OPEN, true)
    var firstHide: Boolean by boolPref(FIRST_HIDE, true)
    var userState: String by stringPref(USER_STATE, Constants.UserState.START)
    var lockModeOn: Boolean by boolPref(LOCK_MODE, false)
    var autoShowKeyboard: Boolean by boolPref(AUTO_SHOW_KEYBOARD, true)
    var keyboardMessageShown: Boolean by boolPref(KEYBOARD_MESSAGE, false)
    var dailyWallpaper: Boolean by boolPref(DAILY_WALLPAPER, false)
    var dailyWallpaperUrl: String by stringPref(DAILY_WALLPAPER_URL, "")
    var solidBackground: Boolean by boolPref(SOLID_BACKGROUND, false)
    var homeAppsNum: Int by intPref(HOME_APPS_NUM, 4)
    var homeAlignment: Int by intPref(HOME_ALIGNMENT, Gravity.START)
    var homeBottomAlignment: Boolean by boolPref(HOME_BOTTOM_ALIGNMENT, false)
    var appLabelAlignment: Int by intPref(APP_LABEL_ALIGNMENT, Gravity.START)
    var showStatusBar: Boolean by boolPref(STATUS_BAR, false)
    var dateTimeVisibility: Int by intPref(DATE_TIME_VISIBILITY, Constants.DateTime.ON)
    var swipeLeftEnabled: Boolean by boolPref(SWIPE_LEFT_ENABLED, true)
    var swipeRightEnabled: Boolean by boolPref(SWIPE_RIGHT_ENABLED, true)

    var swipeLeftTarget: Int
        get() = if (swipeLeftEnabled) {
            prefs.getInt(SWIPE_LEFT_TARGET, defaultSwipeLeftTarget())
        } else {
            Constants.SwipeTarget.OFF
        }
        set(value) = prefs.edit {
            putInt(SWIPE_LEFT_TARGET, value)
            putBoolean(SWIPE_LEFT_ENABLED, value != Constants.SwipeTarget.OFF)
            apply()
        }

    var swipeRightTarget: Int
        get() = if (swipeRightEnabled) {
            prefs.getInt(SWIPE_RIGHT_TARGET, defaultSwipeRightTarget())
        } else {
            Constants.SwipeTarget.OFF
        }
        set(value) = prefs.edit {
            putInt(SWIPE_RIGHT_TARGET, value)
            putBoolean(SWIPE_RIGHT_ENABLED, value != Constants.SwipeTarget.OFF)
            apply()
        }

    var pomodoroFocusMinutes: Int
        get() = prefs.getInt(POMODORO_FOCUS_MINUTES, 25)
        set(value) = prefs.edit { putInt(POMODORO_FOCUS_MINUTES, value.coerceIn(0, 999)).apply() }

    var pomodoroFocusSeconds: Int
        get() = prefs.getInt(POMODORO_FOCUS_SECONDS, 0)
        set(value) = prefs.edit { putInt(POMODORO_FOCUS_SECONDS, value.coerceIn(0, 59)).apply() }

    val pomodoroFocusMillis: Long
        get() {
            val totalSeconds = pomodoroFocusMinutes * 60L + pomodoroFocusSeconds
            return totalSeconds.coerceAtLeast(1L) * 1000L
        }

    private fun defaultSwipeLeftTarget(): Int {
        val hasLegacyApp = prefs.getString(APP_PACKAGE_SWIPE_LEFT, "").orEmpty().isNotBlank()
        return if (hasLegacyApp) Constants.SwipeTarget.APP else Constants.SwipeTarget.PRODUCTIVE
    }

    private fun defaultSwipeRightTarget(): Int {
        val hasLegacyApp = prefs.getString(APP_PACKAGE_SWIPE_RIGHT, "").orEmpty().isNotBlank()
        return if (hasLegacyApp) Constants.SwipeTarget.APP else Constants.SwipeTarget.MUSLIM_CENTER
    }

    var pomodoroTimerTotalMillis: Long
        get() = prefs.getLong(POMODORO_TIMER_TOTAL_MILLIS, pomodoroFocusMillis)
        set(value) = prefs.edit { putLong(POMODORO_TIMER_TOTAL_MILLIS, value.coerceAtLeast(1000L)).apply() }

    var pomodoroTimerEndElapsedRealtime: Long
        get() = prefs.getLong(POMODORO_TIMER_END_ELAPSED_REALTIME, 0L)
        set(value) = prefs.edit { putLong(POMODORO_TIMER_END_ELAPSED_REALTIME, value.coerceAtLeast(0L)).apply() }

    var pomodoroTimerRemainingMillis: Long
        get() = prefs.getLong(POMODORO_TIMER_REMAINING_MILLIS, pomodoroFocusMillis)
        set(value) = prefs.edit { putLong(POMODORO_TIMER_REMAINING_MILLIS, value.coerceAtLeast(0L)).apply() }

    var appTheme: Int by intPref(APP_THEME, AppCompatDelegate.MODE_NIGHT_YES)
    var textSizeScale: Float by floatPref(TEXT_SIZE_SCALE, 1.0f)
    var proMessageShown: Boolean by boolPref(PRO_MESSAGE_SHOWN, false)
    var hideSetDefaultLauncher: Boolean by boolPref(HIDE_SET_DEFAULT_LAUNCHER, false)
    var screenTimeLastUpdated: Long by longPref(SCREEN_TIME_LAST_UPDATED, 0L)

    /** Package names opted in to mindful launch (B1). Empty set = feature off per-app. */
    var mindfulApps: MutableSet<String>
        get() = prefs.getStringSet(MINDFUL_APPS, emptySet()).orEmpty().toMutableSet()
        set(value) = prefs.edit { putStringSet(MINDFUL_APPS, value.toSet()).apply() }

    /** Master toggle for mindful launch. Individual apps opt in via [mindfulApps]. */
    var mindfulLaunchEnabled: Boolean by boolPref(MINDFUL_LAUNCH_ENABLED, false)

    /** Countdown seconds before a mindful app opens (3–5). */
    var mindfulDelaySeconds: Int by intPref(MINDFUL_DELAY_SECONDS, 3)

    /** Daily screen-time goal in minutes; 0 = disabled. */
    var dailyScreenTimeGoalMinutes: Int by intPref(DAILY_SCREEN_TIME_GOAL_MINUTES, 0)

    /** Day-of-year when the goal-reached / goal-missed notice was last shown (dedupe). */
    var screenTimeGoalReachedNotifiedDay: Int by intPref(SCREEN_TIME_GOAL_REACHED_DAY, 0)
    var screenTimeGoalMissedNotifiedDay: Int by intPref(SCREEN_TIME_GOAL_MISSED_DAY, 0)

    var launcherRestartTimestamp: Long by longPref(LAUNCHER_RESTART_TIMESTAMP, 0L)
    var shownOnDayOfYear: Int by intPref(SHOWN_ON_DAY_OF_YEAR, 0)
    var homeButtonShowRecents: Boolean by boolPref(HOME_BUTTON_SHOW_RECENTS, false)
    var fontFamily: Int by intPref(FONT_FAMILY, Constants.FontFamily.POPPINS)

    /** [Constants.ProductivePanelSize] — compact / comfortable / full. */
    var productivePanelSize: Int
        get() = prefs.getInt(PRODUCTIVE_PANEL_SIZE, Constants.ProductivePanelSize.COMFORTABLE)
            .coerceIn(Constants.ProductivePanelSize.COMPACT, Constants.ProductivePanelSize.FULL)
        set(value) = prefs.edit {
            putInt(
                PRODUCTIVE_PANEL_SIZE,
                value.coerceIn(Constants.ProductivePanelSize.COMPACT, Constants.ProductivePanelSize.FULL),
            )
        }

    /** Which Productive modules are enabled. At least one stays enabled (enforced in UI). */
    var productiveWidgetNotes: Boolean by boolPref(PRODUCTIVE_WIDGET_NOTES, true)
    var productiveWidgetTodo: Boolean by boolPref(PRODUCTIVE_WIDGET_TODO, true)
    var productiveWidgetTimer: Boolean by boolPref(PRODUCTIVE_WIDGET_TIMER, true)
    var productiveWidgetWidgets: Boolean by boolPref(PRODUCTIVE_WIDGET_WIDGETS, true)

    /**
     * Width scale for Productive dialogs only (edit note, set duration, etc.), 0.70–1.0.
     * Default 0.90 matches the Settings picker option "90%". Does not affect
     * Settings / language / prayer list dialogs.
     */
    var productiveDialogWidthScale: Float
        get() = prefs.getFloat(PRODUCTIVE_DIALOG_WIDTH_SCALE, 0.90f).coerceIn(0.70f, 1.0f)
        set(value) = prefs.edit {
            putFloat(PRODUCTIVE_DIALOG_WIDTH_SCALE, value.coerceIn(0.70f, 1.0f))
        }

    var productiveLastMode: String by stringPref(PRODUCTIVE_LAST_MODE, NotePanelMode.NOTES.name)

    fun isProductiveModeEnabled(mode: NotePanelMode): Boolean = when (mode) {
        NotePanelMode.NOTES -> productiveWidgetNotes
        NotePanelMode.TODO -> productiveWidgetTodo
        NotePanelMode.TIMER -> productiveWidgetTimer
        NotePanelMode.WIDGETS -> productiveWidgetWidgets
    }

    fun firstEnabledProductiveMode(): NotePanelMode {
        return NotePanelMode.entries.firstOrNull { isProductiveModeEnabled(it) } ?: NotePanelMode.NOTES
    }

    fun resolveProductiveOpenMode(requested: NotePanelMode? = null): NotePanelMode {
        if (requested != null && isProductiveModeEnabled(requested)) return requested
        val last = runCatching { NotePanelMode.valueOf(productiveLastMode) }.getOrNull()
        if (last != null && isProductiveModeEnabled(last)) return last
        return firstEnabledProductiveMode()
    }

    fun setProductiveModuleEnabled(mode: NotePanelMode, enabled: Boolean): Boolean {
        val currentlyOn = listOf(
            productiveWidgetNotes,
            productiveWidgetTodo,
            productiveWidgetTimer,
            productiveWidgetWidgets,
        ).count { it }
        val thisOn = isProductiveModeEnabled(mode)
        if (!enabled && thisOn && currentlyOn <= 1) return false
        when (mode) {
            NotePanelMode.NOTES -> productiveWidgetNotes = enabled
            NotePanelMode.TODO -> productiveWidgetTodo = enabled
            NotePanelMode.TIMER -> productiveWidgetTimer = enabled
            NotePanelMode.WIDGETS -> productiveWidgetWidgets = enabled
        }
        return true
    }

    var hiddenApps: MutableSet<String>
        get() = prefs.getStringSet(HIDDEN_APPS, emptySet()).orEmpty().toMutableSet()
        set(value) = prefs.edit { putStringSet(HIDDEN_APPS, value.toSet()).apply() }

    var hiddenAppsUpdated: Boolean by boolPref(HIDDEN_APPS_UPDATED, false)
    var toShowHintCounter: Int by intPref(SHOW_HINT_COUNTER, 1)
    var aboutClicked: Boolean by boolPref(ABOUT_CLICKED, false)
    var rateClicked: Boolean by boolPref(RATE_CLICKED, false)
    var wallpaperMsgShown: Boolean by boolPref(WALLPAPER_MSG_SHOWN, false)
    var shareShownTime: Long by longPref(SHARE_SHOWN_TIME, 0L)

    var swipeDownAction: Int by intPref(SWIPE_DOWN_ACTION, Constants.SwipeDownAction.NOTIFICATIONS)

    var appName1: String by stringPref(APP_NAME_1, "")
    var appName2: String by stringPref(APP_NAME_2, "")
    var appName3: String by stringPref(APP_NAME_3, "")
    var appName4: String by stringPref(APP_NAME_4, "")
    var appName5: String by stringPref(APP_NAME_5, "")
    var appName6: String by stringPref(APP_NAME_6, "")
    var appName7: String by stringPref(APP_NAME_7, "")
    var appName8: String by stringPref(APP_NAME_8, "")
    var appName9: String by stringPref(APP_NAME_9, "")
    var appName10: String by stringPref(APP_NAME_10, "")

    var appPackage1: String by stringPref(APP_PACKAGE_1, "")
    var appPackage2: String by stringPref(APP_PACKAGE_2, "")
    var appPackage3: String by stringPref(APP_PACKAGE_3, "")
    var appPackage4: String by stringPref(APP_PACKAGE_4, "")
    var appPackage5: String by stringPref(APP_PACKAGE_5, "")
    var appPackage6: String by stringPref(APP_PACKAGE_6, "")
    var appPackage7: String by stringPref(APP_PACKAGE_7, "")
    var appPackage8: String by stringPref(APP_PACKAGE_8, "")
    var appPackage9: String by stringPref(APP_PACKAGE_9, "")
    var appPackage10: String by stringPref(APP_PACKAGE_10, "")

    var appActivityClassName1: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_1, "")
    var appActivityClassName2: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_2, "")
    var appActivityClassName3: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_3, "")
    var appActivityClassName4: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_4, "")
    var appActivityClassName5: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_5, "")
    var appActivityClassName6: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_6, "")
    var appActivityClassName7: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_7, "")
    var appActivityClassName8: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_8, "")
    var appActivityClassName9: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_9, "")
    var appActivityClassName10: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_10, "")

    var appUser1: String by stringPref(APP_USER_1, "")
    var appUser2: String by stringPref(APP_USER_2, "")
    var appUser3: String by stringPref(APP_USER_3, "")
    var appUser4: String by stringPref(APP_USER_4, "")
    var appUser5: String by stringPref(APP_USER_5, "")
    var appUser6: String by stringPref(APP_USER_6, "")
    var appUser7: String by stringPref(APP_USER_7, "")
    var appUser8: String by stringPref(APP_USER_8, "")
    var appUser9: String by stringPref(APP_USER_9, "")
    var appUser10: String by stringPref(APP_USER_10, "")

    var appNameSwipeLeft: String by stringPref(APP_NAME_SWIPE_LEFT, "Camera")
    var appNameSwipeRight: String by stringPref(APP_NAME_SWIPE_RIGHT, "Phone")
    var appPackageSwipeLeft: String by stringPref(APP_PACKAGE_SWIPE_LEFT, "")
    var appActivityClassNameSwipeLeft: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_SWIPE_LEFT, "")
    var appPackageSwipeRight: String by stringPref(APP_PACKAGE_SWIPE_RIGHT, "")
    var appActivityClassNameRight: String? by nullableStringPref(APP_ACTIVITY_CLASS_NAME_SWIPE_RIGHT, "")
    var appUserSwipeLeft: String by stringPref(APP_USER_SWIPE_LEFT, "")
    var appUserSwipeRight: String by stringPref(APP_USER_SWIPE_RIGHT, "")

    var clockAppPackage: String by stringPref(CLOCK_APP_PACKAGE, "")
    var clockAppUser: String by stringPref(CLOCK_APP_USER, "")
    var clockAppClassName: String? by nullableStringPref(CLOCK_APP_CLASS_NAME, "")
    var calendarAppPackage: String by stringPref(CALENDAR_APP_PACKAGE, "")
    var calendarAppUser: String by stringPref(CALENDAR_APP_USER, "")
    var calendarAppClassName: String? by nullableStringPref(CALENDAR_APP_CLASS_NAME, "")
    var screenTimeAppPackage: String by stringPref(SCREEN_TIME_APP_PACKAGE, "")
    var screenTimeAppUser: String by stringPref(SCREEN_TIME_APP_USER, "")
    var screenTimeAppClassName: String? by nullableStringPref(SCREEN_TIME_APP_CLASS_NAME, "")

    var isShortcut1: Boolean by boolPref(IS_SHORTCUT_1, false)
    var shortcutId1: String by stringPref(SHORTCUT_ID_1, "")
    var isShortcut2: Boolean by boolPref(IS_SHORTCUT_2, false)
    var shortcutId2: String by stringPref(SHORTCUT_ID_2, "")
    var isShortcut3: Boolean by boolPref(IS_SHORTCUT_3, false)
    var shortcutId3: String by stringPref(SHORTCUT_ID_3, "")
    var isShortcut4: Boolean by boolPref(IS_SHORTCUT_4, false)
    var shortcutId4: String by stringPref(SHORTCUT_ID_4, "")
    var isShortcut5: Boolean by boolPref(IS_SHORTCUT_5, false)
    var shortcutId5: String by stringPref(SHORTCUT_ID_5, "")
    var isShortcut6: Boolean by boolPref(IS_SHORTCUT_6, false)
    var shortcutId6: String by stringPref(SHORTCUT_ID_6, "")
    var isShortcut7: Boolean by boolPref(IS_SHORTCUT_7, false)
    var shortcutId7: String by stringPref(SHORTCUT_ID_7, "")
    var isShortcut8: Boolean by boolPref(IS_SHORTCUT_8, false)
    var shortcutId8: String by stringPref(SHORTCUT_ID_8, "")
    var isShortcut9: Boolean by boolPref(IS_SHORTCUT_9, false)
    var shortcutId9: String by stringPref(SHORTCUT_ID_9, "")
    var isShortcut10: Boolean by boolPref(IS_SHORTCUT_10, false)
    var shortcutId10: String by stringPref(SHORTCUT_ID_10, "")

    var shortcutIdSwipeLeft: String by stringPref(SHORTCUT_ID_SWIPE_LEFT, "")
    var isShortcutSwipeLeft: Boolean by boolPref(IS_SHORTCUT_SWIPE_LEFT, false)
    var shortcutIdSwipeRight: String by stringPref(SHORTCUT_ID_SWIPE_RIGHT, "")
    var isShortcutSwipeRight: Boolean by boolPref(IS_SHORTCUT_SWIPE_RIGHT, false)

    fun getAppName(location: Int): String = when (location) {
        1 -> appName1
        2 -> appName2
        3 -> appName3
        4 -> appName4
        5 -> appName5
        6 -> appName6
        7 -> appName7
        8 -> appName8
        9 -> appName9
        10 -> appName10
        else -> ""
    }

    fun getAppPackage(location: Int): String = when (location) {
        1 -> appPackage1
        2 -> appPackage2
        3 -> appPackage3
        4 -> appPackage4
        5 -> appPackage5
        6 -> appPackage6
        7 -> appPackage7
        8 -> appPackage8
        9 -> appPackage9
        10 -> appPackage10
        else -> ""
    }

    fun getAppActivityClassName(location: Int): String = when (location) {
        1 -> appActivityClassName1
        2 -> appActivityClassName2
        3 -> appActivityClassName3
        4 -> appActivityClassName4
        5 -> appActivityClassName5
        6 -> appActivityClassName6
        7 -> appActivityClassName7
        8 -> appActivityClassName8
        9 -> appActivityClassName9
        10 -> appActivityClassName10
        else -> null
    }.orEmpty()

    fun getAppUser(location: Int): String = when (location) {
        1 -> appUser1
        2 -> appUser2
        3 -> appUser3
        4 -> appUser4
        5 -> appUser5
        6 -> appUser6
        7 -> appUser7
        8 -> appUser8
        9 -> appUser9
        10 -> appUser10
        else -> ""
    }

    fun getShortcutId(location: Int): String = when (location) {
        1 -> shortcutId1
        2 -> shortcutId2
        3 -> shortcutId3
        4 -> shortcutId4
        5 -> shortcutId5
        6 -> shortcutId6
        7 -> shortcutId7
        8 -> shortcutId8
        9 -> shortcutId9
        10 -> shortcutId10
        else -> ""
    }

    fun getIsShortcut(location: Int): Boolean = when (location) {
        1 -> isShortcut1
        2 -> isShortcut2
        3 -> isShortcut3
        4 -> isShortcut4
        5 -> isShortcut5
        6 -> isShortcut6
        7 -> isShortcut7
        8 -> isShortcut8
        9 -> isShortcut9
        10 -> isShortcut10
        else -> false
    }

    fun setAppActivityClassName(location: Int, activityClassName: String) {
        when (location) {
            1 -> appActivityClassName1 = activityClassName
            2 -> appActivityClassName2 = activityClassName
            3 -> appActivityClassName3 = activityClassName
            4 -> appActivityClassName4 = activityClassName
            5 -> appActivityClassName5 = activityClassName
            6 -> appActivityClassName6 = activityClassName
            7 -> appActivityClassName7 = activityClassName
            8 -> appActivityClassName8 = activityClassName
            9 -> appActivityClassName9 = activityClassName
            10 -> appActivityClassName10 = activityClassName
        }
    }

    fun updateAppActivityClassName(packageName: String, activityClassName: String) {
        for (i in 1..10) {
            if (getAppPackage(i) == packageName) setAppActivityClassName(i, activityClassName)
        }
        if (clockAppPackage == packageName) clockAppClassName = activityClassName
        if (calendarAppPackage == packageName) calendarAppClassName = activityClassName
        if (screenTimeAppPackage == packageName) screenTimeAppClassName = activityClassName
        if (appPackageSwipeLeft == packageName) appActivityClassNameSwipeLeft = activityClassName
        if (appPackageSwipeRight == packageName) appActivityClassNameRight = activityClassName
    }

    fun getAppRenameLabel(appPackage: String): String = prefs.getString(appPackage, "").toString()

    fun setAppRenameLabel(appPackage: String, renameLabel: String) = prefs.edit { putString(appPackage, renameLabel) }
    fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    fun putInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
}

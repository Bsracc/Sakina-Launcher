package app.sakinalauncher.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.bundleOf
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.sakinalauncher.BuildConfig
import app.sakinalauncher.MainActivity
import app.sakinalauncher.MainViewModel
import app.sakinalauncher.R
import app.sakinalauncher.data.ConfigBackup
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.NotePanelMode
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.data.muslim.GlobalPrayerLocation
import app.sakinalauncher.data.muslim.PrayerApiClient
import app.sakinalauncher.data.muslim.PrayerCity
import app.sakinalauncher.data.muslim.PrayerProvider
import app.sakinalauncher.data.muslim.PrayerTimeRepository
import app.sakinalauncher.data.muslim.PrayerTimeStore
import app.sakinalauncher.databinding.FragmentSettingsBinding
import app.sakinalauncher.helper.AppDialog
import app.sakinalauncher.helper.ScreenTimeGoal
import app.sakinalauncher.helper.addSystemBarInsetsPadding
import app.sakinalauncher.helper.animateAlpha
import app.sakinalauncher.helper.appUsagePermissionGranted
import app.sakinalauncher.helper.frostWallpaperWhileResumed
import app.sakinalauncher.helper.getColorFromAttr
import app.sakinalauncher.helper.isAccessServiceEnabled
import app.sakinalauncher.helper.isEinkDisplay
import app.sakinalauncher.helper.isSakinaDefault
import app.sakinalauncher.helper.isTablet
import app.sakinalauncher.helper.openAppInfo
import app.sakinalauncher.helper.openUrl
import app.sakinalauncher.helper.PrayerLocationHelper
import app.sakinalauncher.helper.showAppListDialog
import app.sakinalauncher.helper.showToast
import app.sakinalauncher.listener.DeviceAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class SettingsFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var prayerStore: PrayerTimeStore
    private lateinit var prayerRepository: PrayerTimeRepository
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var componentName: ComponentName
    private var cachedKemenagCities: List<PrayerCity>? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(ConfigBackup(requireContext()).exportJson().toByteArray())
                        } != null
                    }.getOrDefault(false)
                }
                if (isAdded) {
                    if (ok) requireContext().showToast(R.string.export_config_done)
                    else requireContext().showToast(R.string.import_config_invalid)
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            showImportConfirm(uri)
        }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        frostWallpaperWhileResumed()
        prefs = Prefs(requireContext())
        prayerStore = PrayerTimeStore(requireContext())
        prayerRepository = PrayerTimeRepository(PrayerApiClient.api, PrayerApiClient.aladhanApi, prayerStore)
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.isSakinaDefault()

        deviceManager = requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        componentName = ComponentName(requireContext(), DeviceAdmin::class.java)
        checkAdminPermission()

        // Once the user has reached Settings, the first-run home tips have served
        // their purpose. Clear the flag so the tips never appear again.
        prefs.firstSettingsOpen = false

        binding.homeAppsNum.text = prefs.homeAppsNum.toString()
        populateProMessage()
        populateKeyboardText()
        populateScreenTimeOnOff()
        populateMindfulSettings()
        populateScreenTimeGoal()
        populateLockSettings()
        populateHomeButtonRecents()
        populateWallpaperText()
        populateSolidBackground()
        populateAppThemeText()
        populateLanguage()
        populateTextSize()
        populateFontText()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeApps()
        populatePrayerRegion()
        populateSwipeDownAction()
        populateActionHints()
        populateProductiveSettings()
        initClickListeners()
        initObservers()
        binding.scrollView.addSystemBarInsetsPadding()

        app.sakinalauncher.helper.FontHelper.applyFont(binding.root, prefs)

        // Fade the dim overlay in when entering settings, and fade it back out
        // on exit. Duration is deliberately long enough to be clearly visible.
        binding.settingsDim.alpha = 0f
        binding.settingsDim.animate()
            .alpha(1f)
            .setDuration(SETTINGS_DIM_DURATION)
            .start()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            val dim = _binding?.settingsDim
            if (dim == null) {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
                return@addCallback
            }
            // Disable callback first to prevent recursion, then fade out dim
            // and pop back simultaneously so text + dim disappear together
            isEnabled = false
            dim.animate()
                .alpha(0f)
                .setDuration(SETTINGS_DIM_DURATION)
                .start()
            // Pop immediately so the fragment exit transition overlaps with dim fade
            if (isAdded) findNavController().popBackStack()
        }
    }

    override fun onClick(view: View) {
        binding.appsNumSelectLayout.visibility = View.GONE
        binding.appsNumLabel.visibility = View.VISIBLE
        binding.homeAppsNum.visibility = View.VISIBLE
        binding.dateTimeSelectLayout.visibility = View.GONE
        binding.appThemeSelectLayout.visibility = View.GONE
        binding.swipeDownSelectLayout.visibility = View.GONE
        // Expanding picker grids replace the row they sit in (FrameLayout stacking):
        // the row label and value must go with them or the three layers overlap.
        showDateTimeRow(true)
        showThemeRow(true)
        if (view.id != R.id.textSizeMinus && view.id != R.id.textSizePlus) {
            if (binding.textSizesLayout.isVisible) {
                binding.textSizesLayout.visibility = View.GONE
                applyTextSizeScale()
            }
            showTextSizeRow(true)
        }
        if (view.id != R.id.alignmentBottom)
            binding.alignmentSelectLayout.visibility = View.GONE
        showAlignmentRow(true)

        when (view.id) {
            R.id.sakinaHiddenApps -> showHiddenApps()
            R.id.moreFeatures -> requireContext().openUrl("https://github.com/Bsraccc1/Sakina-Launcher")
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.mindfulLaunchToggle -> toggleMindfulLaunch()
            R.id.mindfulLaunchDelay -> showMindfulDelayPicker()
            R.id.mindfulAppsValue -> showMindfulAppsPicker()
            R.id.screenTimeGoalValue -> showScreenTimeGoalPicker()
            R.id.exportConfig -> exportConfig()
            R.id.importConfig -> importConfig()
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.toggleLock -> toggleLockMode()
            R.id.homeButtonRecents -> toggleHomeButtonRecents()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.homeAppsNum -> {
                // The numbers grid expands into the same space the label and value
                // occupy; hide them while it is open or the three render on top of
                // each other (same pattern as the alignment/date-time/theme pickers).
                binding.appsNumLabel.visibility = View.GONE
                binding.homeAppsNum.visibility = View.GONE
                binding.appsNumSelectLayout.visibility = View.VISIBLE
            }
            R.id.dailyWallpaperUrl -> requireContext().openUrl(prefs.dailyWallpaperUrl)
            R.id.dailyWallpaper -> toggleDailyWallpaperUpdate()
            R.id.solidBackground -> toggleSolidBackground()
            R.id.alignment -> {
                binding.alignmentSelectLayout.visibility = View.VISIBLE
                showAlignmentRow(false)
            }
            R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
            R.id.alignmentCenter -> viewModel.updateHomeAlignment(Gravity.CENTER)
            R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
            R.id.alignmentBottom -> updateHomeBottomAlignment()
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> {
                binding.dateTimeSelectLayout.visibility = View.VISIBLE
                showDateTimeRow(false)
            }
            R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
            R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
            R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)
            R.id.appThemeText -> {
                binding.appThemeSelectLayout.visibility = View.VISIBLE
                showThemeRow(false)
            }
            R.id.themeLight -> updateTheme(AppCompatDelegate.MODE_NIGHT_NO)
            R.id.themeDark -> updateTheme(AppCompatDelegate.MODE_NIGHT_YES)
            R.id.themeSystem -> updateTheme(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            R.id.textSizeValue -> {
                binding.textSizesLayout.visibility = View.VISIBLE
                showTextSizeRow(false)
            }
            R.id.actionAccessibility -> openAccessibilityService()
            R.id.closeAccessibility -> toggleAccessibilityVisibility(false)
            R.id.notWorking -> requireContext().openUrl(Constants.URL_DOUBLE_TAP)

            R.id.tvGestures -> binding.flSwipeDown.visibility = View.VISIBLE

            R.id.maxApps0 -> updateHomeAppsNum(0)
            R.id.maxApps1 -> updateHomeAppsNum(1)
            R.id.maxApps2 -> updateHomeAppsNum(2)
            R.id.maxApps3 -> updateHomeAppsNum(3)
            R.id.maxApps4 -> updateHomeAppsNum(4)
            R.id.maxApps5 -> updateHomeAppsNum(5)
            R.id.maxApps6 -> updateHomeAppsNum(6)
            R.id.maxApps7 -> updateHomeAppsNum(7)
            R.id.maxApps8 -> updateHomeAppsNum(8)
            R.id.maxApps9 -> updateHomeAppsNum(9)
            R.id.maxApps10 -> updateHomeAppsNum(10)

            R.id.textSizeMinus -> adjustTextSizePreview(-0.1f)
            R.id.textSizePlus -> adjustTextSizePreview(0.1f)

            R.id.swipeLeftApp -> showSwipeTargetPicker(isLeft = true)
            R.id.swipeRightApp -> showSwipeTargetPicker(isLeft = false)
            R.id.swipeDownAction -> binding.swipeDownSelectLayout.visibility = View.VISIBLE
            R.id.notifications -> updateSwipeDownAction(Constants.SwipeDownAction.NOTIFICATIONS)
            R.id.search -> updateSwipeDownAction(Constants.SwipeDownAction.SEARCH)
            R.id.prayerRegion -> showPrayerRegionPicker()
            R.id.prayerProvider -> showPrayerProviderPicker()
            R.id.prayerAutoDetect -> autoDetectPrayerRegion()
            R.id.languageText -> showLanguagePicker()
            R.id.fontText -> showFontPicker()
            R.id.productivePanelSize -> showProductivePanelSizePicker()
            R.id.productiveDialogWidth -> showProductiveDialogWidthPicker()
            R.id.productiveModuleNotes -> toggleProductiveModule(NotePanelMode.NOTES)
            R.id.productiveModuleTodo -> toggleProductiveModule(NotePanelMode.TODO)
            R.id.productiveModuleTimer -> toggleProductiveModule(NotePanelMode.TIMER)
            R.id.productiveModuleWidgets -> toggleProductiveModule(NotePanelMode.WIDGETS)

            R.id.aboutSakina -> {
                prefs.aboutClicked = true
                viewModel.showDialog.postValue(Constants.Dialog.ABOUT)
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.alignment -> {
                prefs.appLabelAlignment = prefs.homeAlignment
                findNavController().navigate(R.id.action_settingsFragment_to_appListFragment)
                requireContext().showToast(getString(R.string.alignment_changed))
            }

            R.id.dailyWallpaper -> removeWallpaper()
            R.id.appThemeText -> {
                binding.appThemeSelectLayout.visibility = View.VISIBLE
                binding.themeSystem.visibility = View.VISIBLE
            }

            R.id.swipeLeftApp -> showSwipeAppList(isLeft = true)
            R.id.swipeRightApp -> showSwipeAppList(isLeft = false)
            R.id.toggleLock -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        return true
    }

    private fun initClickListeners() {
        binding.sakinaHiddenApps.setOnClickListener(this)
        binding.scrollLayout.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.aboutSakina.setOnClickListener(this)
        binding.moreFeatures.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.toggleLock.setOnClickListener(this)
        binding.homeButtonRecents.setOnClickListener(this)
        binding.homeAppsNum.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.mindfulLaunchToggle.setOnClickListener(this)
        binding.mindfulLaunchDelay.setOnClickListener(this)
        binding.mindfulAppsValue.setOnClickListener(this)
        binding.screenTimeGoalValue.setOnClickListener(this)
        binding.exportConfig.setOnClickListener(this)
        binding.importConfig.setOnClickListener(this)
        binding.dailyWallpaperUrl.setOnClickListener(this)
        binding.dailyWallpaper.setOnClickListener(this)
        binding.solidBackground.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.alignmentLeft.setOnClickListener(this)
        binding.alignmentCenter.setOnClickListener(this)
        binding.alignmentRight.setOnClickListener(this)
        binding.alignmentBottom.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.dateTimeOn.setOnClickListener(this)
        binding.dateTimeOff.setOnClickListener(this)
        binding.dateOnly.setOnClickListener(this)
        binding.swipeLeftApp.setOnClickListener(this)
        binding.swipeRightApp.setOnClickListener(this)
        binding.prayerRegion.setOnClickListener(this)
        binding.prayerProvider.setOnClickListener(this)
        binding.prayerAutoDetect.setOnClickListener(this)
        binding.tvGestures.setOnClickListener(this)
        binding.swipeDownAction.setOnClickListener(this)
        binding.search.setOnClickListener(this)
        binding.notifications.setOnClickListener(this)
        binding.appThemeText.setOnClickListener(this)
        binding.languageText.setOnClickListener(this)
        binding.fontText.setOnClickListener(this)
        binding.productivePanelSize.setOnClickListener(this)
        binding.productiveDialogWidth.setOnClickListener(this)
        binding.productiveModuleNotes.setOnClickListener(this)
        binding.productiveModuleTodo.setOnClickListener(this)
        binding.productiveModuleTimer.setOnClickListener(this)
        binding.productiveModuleWidgets.setOnClickListener(this)
        binding.themeLight.setOnClickListener(this)
        binding.themeDark.setOnClickListener(this)
        binding.themeSystem.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.actionAccessibility.setOnClickListener(this)
        binding.closeAccessibility.setOnClickListener(this)
        binding.notWorking.setOnClickListener(this)

        binding.maxApps0.setOnClickListener(this)
        binding.maxApps1.setOnClickListener(this)
        binding.maxApps2.setOnClickListener(this)
        binding.maxApps3.setOnClickListener(this)
        binding.maxApps4.setOnClickListener(this)
        binding.maxApps5.setOnClickListener(this)
        binding.maxApps6.setOnClickListener(this)
        binding.maxApps7.setOnClickListener(this)
        binding.maxApps8.setOnClickListener(this)
        binding.maxApps9.setOnClickListener(this)
        binding.maxApps10.setOnClickListener(this)

        binding.textSizeMinus.setOnClickListener(this)
        binding.textSizePlus.setOnClickListener(this)

        binding.dailyWallpaper.setOnLongClickListener(this)
        binding.alignment.setOnLongClickListener(this)
        binding.appThemeText.setOnLongClickListener(this)
        binding.swipeLeftApp.setOnLongClickListener(this)
        binding.swipeRightApp.setOnLongClickListener(this)
        binding.toggleLock.setOnLongClickListener(this)
    }

    private fun initObservers() {
        viewModel.isSakinaDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
                prefs.toShowHintCounter += 1
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
        viewModel.updateSwipeApps.observe(viewLifecycleOwner) {
            populateSwipeApps()
        }
    }

    private fun toggleSwipeLeft() {
        prefs.swipeLeftTarget = if (prefs.swipeLeftTarget == Constants.SwipeTarget.OFF) {
            Constants.SwipeTarget.PRODUCTIVE
        } else {
            Constants.SwipeTarget.OFF
        }
        populateSwipeApps()
    }

    private fun toggleSwipeRight() {
        prefs.swipeRightTarget = if (prefs.swipeRightTarget == Constants.SwipeTarget.OFF) {
            Constants.SwipeTarget.MUSLIM_CENTER
        } else {
            Constants.SwipeTarget.OFF
        }
        populateSwipeApps()
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
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

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private fun checkAdminPermission() {
        val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            prefs.lockModeOn = isAdmin
    }

    private fun toggleAccessibilityVisibility(show: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            binding.notWorking.visibility = View.VISIBLE
        if (isAccessServiceEnabled(requireContext()))
            binding.actionAccessibility.text = getString(R.string.disable)
        binding.accessibilityLayout.isVisible = show
        binding.scrollView.animateAlpha(if (show) 0.5f else 1f)
    }

    private fun openAccessibilityService() {
        toggleAccessibilityVisibility(false)
        // prefs.lockModeOn = true
        populateLockSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun toggleLockMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (!prefs.lockModeOn && !isAccessServiceEnabled(requireContext())) {
                toggleAccessibilityVisibility(true)
                return
            }
            prefs.lockModeOn = !prefs.lockModeOn
        } else {
            val isAdmin: Boolean = deviceManager.isAdminActive(componentName)
            if (isAdmin) {
                removeActiveAdmin("Admin permission removed.")
                prefs.lockModeOn = false
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.admin_permission_message)
                )
                requireActivity().startActivityForResult(intent, Constants.REQUEST_CODE_ENABLE_ADMIN)
            }
        }
        populateLockSettings()
    }

    private fun removeActiveAdmin(toastMessage: String? = null) {
        try {
            deviceManager.removeActiveAdmin(componentName) // for backward compatibility
            requireContext().showToast(toastMessage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeWallpaper() {
        if (!prefs.dailyWallpaper) return
        prefs.dailyWallpaper = false
        populateWallpaperText()
        viewModel.cancelWallpaperWorker()
    }

    private fun toggleDailyWallpaperUpdate() {
        if (prefs.dailyWallpaper.not() && prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && viewModel.isSakinaDefault.value == false) {
            requireContext().showToast(R.string.set_as_default_launcher_first)
            return
        }
        prefs.dailyWallpaper = !prefs.dailyWallpaper
        populateWallpaperText()
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
            showWallpaperToasts()
        } else viewModel.cancelWallpaperWorker()
    }

    private fun showWallpaperToasts() {
        if (isSakinaDefault(requireContext()))
            requireContext().showToast(getString(R.string.your_wallpaper_will_update_shortly))
        else
            requireContext().showToast(getString(R.string.sakina_is_not_default_launcher), Toast.LENGTH_LONG)
    }

    private fun toggleSolidBackground() {
        prefs.solidBackground = !prefs.solidBackground
        populateSolidBackground()
        (activity as? MainActivity)?.applySolidBackground()
    }

    private fun populateSolidBackground() {
        binding.solidBackground.text = getString(
            if (prefs.solidBackground) R.string.on else R.string.off
        )
    }

    private fun updateHomeAppsNum(num: Int) {
        binding.homeAppsNum.text = num.toString()
        binding.appsNumSelectLayout.visibility = View.GONE
        binding.appsNumLabel.visibility = View.VISIBLE
        binding.homeAppsNum.visibility = View.VISIBLE
        prefs.homeAppsNum = num
        viewModel.refreshHome(true)
    }

    /**
     * The picker rows (alignment, date-time, theme, text size) are FrameLayouts:
     * the expanding grid stacks on top of the row's label and value. Show the row
     * chrome again when the grid closes, hide it while the grid is open — the same
     * contract the numbers grid already follows.
     */
    private fun showAlignmentRow(show: Boolean) {
        binding.alignmentLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.alignment.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showDateTimeRow(show: Boolean) {
        binding.dateTimeLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.dateTime.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showThemeRow(show: Boolean) {
        binding.appThemeLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.appThemeText.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showTextSizeRow(show: Boolean) {
        binding.textSizeLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.textSizeValue.visibility = if (show) View.VISIBLE else View.GONE
    }

    private var pendingTextSizeScale: Float = -1f

    private fun adjustTextSizePreview(delta: Float) {
        val maxScale = if (isTablet(requireContext())) 2.0f else 1.5f
        val current = if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale
        val newScale = Math.round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(0.5f, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = String.format(Locale.getDefault(), "%.1f", clamped)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        pendingTextSizeScale = -1f
        requireActivity().recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun updateTheme(appTheme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == appTheme) return
        prefs.appTheme = appTheme
        populateAppThemeText(appTheme)
        setAppTheme(appTheme)
    }

    private fun setAppTheme(theme: Int) {
        if (AppCompatDelegate.getDefaultNightMode() == theme) return
        if (prefs.dailyWallpaper) {
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun populateAppThemeText(appTheme: Int = prefs.appTheme) {
        binding.appThemeText.text = when (appTheme) {
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.dark)
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.light)
            // "Automatic" alone does not tell the user which theme is actually in effect,
            // so the resolved one is named too. Read from the live configuration rather
            // than the pref, because that is what the user is looking at.
            else -> {
                val night = resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                getString(
                    R.string.theme_automatic_resolved,
                    getString(if (night) R.string.dark else R.string.light),
                )
            }
        }
    }

    private fun populateLanguage() {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        binding.languageText.text = if (tag.startsWith("in") || tag.startsWith("id")) {
            getString(R.string.language_indonesian)
        } else {
            getString(R.string.language_english)
        }
    }

    private fun showLanguagePicker() {
        val tags = arrayOf("en", "in")
        val labels = listOf(getString(R.string.language_english), getString(R.string.language_indonesian))
        requireContext().showAppListDialog(getString(R.string.language), labels) { which ->
            // setApplicationLocales restarts the activity on API 33+, and the restart can
            // land before this lambda finishes. Touching `binding` afterwards then hit a
            // destroyed view (NPE in populateLanguage); the recreated fragment repopulates
            // the row in onViewCreated anyway.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[which]))
            if (isAdded) requireActivity().recreate()
        }
    }

    private fun populateFontText() {
        binding.fontText.text = app.sakinalauncher.helper.FontHelper.labelFor(requireContext(), prefs.fontFamily)
    }

    private fun populateProductiveSettings() {
        binding.productivePanelSizeValue.text = when (prefs.productivePanelSize) {
            Constants.ProductivePanelSize.COMPACT -> getString(R.string.productive_panel_compact)
            Constants.ProductivePanelSize.FULL -> getString(R.string.productive_panel_full)
            else -> getString(R.string.productive_panel_comfortable)
        }
        binding.productiveDialogWidthValue.text = when {
            prefs.productiveDialogWidthScale >= 0.995f -> getString(R.string.dialog_width_100)
            prefs.productiveDialogWidthScale >= 0.85f -> getString(R.string.dialog_width_90)
            prefs.productiveDialogWidthScale >= 0.75f -> getString(R.string.dialog_width_80)
            else -> getString(R.string.dialog_width_70)
        }
        binding.productiveModuleNotesValue.text = onOff(prefs.productiveWidgetNotes)
        binding.productiveModuleTodoValue.text = onOff(prefs.productiveWidgetTodo)
        binding.productiveModuleTimerValue.text = onOff(prefs.productiveWidgetTimer)
        binding.productiveModuleWidgetsValue.text = onOff(prefs.productiveWidgetWidgets)
    }

    private fun onOff(enabled: Boolean): String =
        getString(if (enabled) R.string.on else R.string.off)

    private fun showProductivePanelSizePicker() {
        val values = intArrayOf(
            Constants.ProductivePanelSize.COMPACT,
            Constants.ProductivePanelSize.COMFORTABLE,
            Constants.ProductivePanelSize.FULL,
        )
        val labels = listOf(
            getString(R.string.productive_panel_compact),
            getString(R.string.productive_panel_comfortable),
            getString(R.string.productive_panel_full),
        )
        requireContext().showAppListDialog(getString(R.string.productive_panel_size), labels) { which ->
            prefs.productivePanelSize = values[which]
            populateProductiveSettings()
        }
    }

    private fun showProductiveDialogWidthPicker() {
        val scales = floatArrayOf(0.70f, 0.80f, 0.90f, 1.0f)
        val labels = listOf(
            getString(R.string.dialog_width_70),
            getString(R.string.dialog_width_80),
            getString(R.string.dialog_width_90),
            getString(R.string.dialog_width_100),
        )
        requireContext().showAppListDialog(getString(R.string.productive_dialog_width), labels) { which ->
            prefs.productiveDialogWidthScale = scales[which]
            populateProductiveSettings()
            // Immediate preview so the chosen % is obviously different.
            previewProductiveDialogWidth(scales[which])
        }
    }

    /** Opens a short-lived Productive-styled dialog at [scale] so width is visible. */
    private fun previewProductiveDialogWidth(scale: Float) {
        val view = layoutInflater.inflate(R.layout.dialog_app_input, null)
        view.findViewById<TextView>(R.id.dialogTitle).text =
            getString(R.string.productive_dialog_width_preview, (scale * 100).toInt())
        view.findViewById<LinearLayout>(R.id.dialogInputContainer).isVisible = false
        val positive = view.findViewById<TextView>(R.id.dialogPositive)
        val negative = view.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.close)
        negative.isVisible = false
        val dialog = AppDialog.create(requireContext(), view, widthScale = scale)
        positive.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun toggleProductiveModule(mode: NotePanelMode) {
        val currently = prefs.isProductiveModeEnabled(mode)
        if (!prefs.setProductiveModuleEnabled(mode, !currently)) {
            requireContext().showToast(getString(R.string.productive_keep_one_widget))
            return
        }
        populateProductiveSettings()
    }

    private fun showFontPicker() {
        val values = intArrayOf(
            Constants.FontFamily.SYSTEM,
            Constants.FontFamily.POPPINS,
            Constants.FontFamily.OUTFIT,
            Constants.FontFamily.SERIF,
            Constants.FontFamily.MONOSPACE,
        )
        val labels = values.map {
            app.sakinalauncher.helper.FontHelper.labelFor(requireContext(), it)
        }
        requireContext().showAppListDialog(getString(R.string.font), labels) { which ->
            if (prefs.fontFamily != values[which]) {
                prefs.fontFamily = values[which]
                populateFontText()
                requireActivity().recreate()
            }
        }
    }

    private fun populateTextSize() {
        val formatted = String.format(Locale.getDefault(), "%.1f", prefs.textSizeScale)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.visibility = View.GONE
    }

    /** Populate the mindful-launch section (B1) rows from prefs. */
    private fun populateMindfulSettings() {
        binding.mindfulLaunchToggle.text = onOff(prefs.mindfulLaunchEnabled)
        binding.mindfulLaunchDelay.text = when (prefs.mindfulDelaySeconds.coerceIn(
            Constants.MINDFUL_DELAY_MIN_SECONDS,
            Constants.MINDFUL_DELAY_MAX_SECONDS,
        )) {
            4 -> getString(R.string.mindful_launch_delay_4)
            5 -> getString(R.string.mindful_launch_delay_5)
            else -> getString(R.string.mindful_launch_delay_3)
        }
        binding.mindfulAppsValue.text = prefs.mindfulApps.size
            .coerceAtLeast(0)
            .toString()
    }

    private fun toggleMindfulLaunch() {
        prefs.mindfulLaunchEnabled = !prefs.mindfulLaunchEnabled
        populateMindfulSettings()
    }

    private fun showMindfulDelayPicker() {
        val values = intArrayOf(3, 4, 5)
        val labels = listOf(
            getString(R.string.mindful_launch_delay_3),
            getString(R.string.mindful_launch_delay_4),
            getString(R.string.mindful_launch_delay_5),
        )
        requireContext().showAppListDialog(getString(R.string.mindful_launch_delay), labels) { which ->
            prefs.mindfulDelaySeconds = values[which]
            populateMindfulSettings()
        }
    }

    /** Opens a drawer picker to mark apps for mindful launch. */
    private fun showMindfulAppsPicker() {
        viewModel.getAppList(includeHiddenApps = true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_SET_MINDFUL_APP)
        )
    }

    /** Populate the daily screen-time goal row (B2). */
    private fun populateScreenTimeGoal() {
        if (prefs.dailyScreenTimeGoalMinutes <= 0) {
            binding.screenTimeGoalValue.text = getString(R.string.screen_time_goal_off)
        } else {
            binding.screenTimeGoalValue.text = ScreenTimeGoal.formatGoal(prefs.dailyScreenTimeGoalMinutes)
        }
    }

    private fun showScreenTimeGoalPicker() {
        val options = listOf(
            getString(R.string.screen_time_goal_off),
            "30m", "1h", "1h 30m", "2h", "3h", "4h", "5h", "6h"
        )
        val minutes = listOf(0, 30, 60, 90, 120, 180, 240, 300, 360)
        requireContext().showAppListDialog(getString(R.string.screen_time_goal_target), options) { which ->
            val selected = minutes[which]
            prefs.dailyScreenTimeGoalMinutes = selected
            populateScreenTimeGoal()
            viewModel.syncScreenTimeGoalWorker()
        }
    }

    /** C2: write the configuration to a user-chosen file via ACTION_CREATE_DOCUMENT. */
    private fun exportConfig() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "sakina-launcher-backup.json")
        }
        exportLauncher.launch(intent)
    }

    /** C2: pick a backup file via ACTION_OPEN_DOCUMENT, then confirm before applying. */
    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        importLauncher.launch(intent)
    }

    private fun showImportConfirm(uri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text =
            getString(R.string.import_config_confirm_title)
        val inputContainer = dialogView.findViewById<LinearLayout>(R.id.dialogInputContainer)
        inputContainer.isVisible = false
        val message = dialogView.findViewById<TextView>(R.id.dialogMessage)
        message.isVisible = true
        message.text = getString(R.string.import_config_confirm_message)

        val positive = dialogView.findViewById<TextView>(R.id.dialogPositive)
        val negative = dialogView.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.import_config_confirm_import)
        negative.setText(R.string.close)
        val dialog = AppDialog.create(requireContext(), dialogView)
        positive.setOnClickListener {
            dialog.dismiss()
            performImport(uri)
        }
        negative.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun performImport(uri: Uri) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input)).use { it.readText() }
                    }
                }.getOrNull()
            }
            if (json.isNullOrBlank() || !isAdded) {
                if (isAdded) requireContext().showToast(R.string.import_config_invalid)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                ConfigBackup(requireContext()).importJson(json)
            }
            if (!isAdded) return@launch
            if (ok) {
                requireContext().showToast(R.string.import_config_done)
                requireActivity().recreate()
            } else {
                requireContext().showToast(R.string.import_config_invalid)
            }
        }
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    override fun onResume() {
        super.onResume()
        // Returning from the mindful picker must refresh the count of selected apps
        // immediately — without this the number only updates after leaving Settings
        // and coming back (populateMindfulSettings runs on create only).
        if (_binding != null) populateMindfulSettings()
    }

    private fun populateWallpaperText() {
        if (prefs.dailyWallpaper) binding.dailyWallpaper.text = getString(R.string.on)
        else binding.dailyWallpaper.text = getString(R.string.off)
    }

    private fun updateHomeBottomAlignment() {
        if (viewModel.isSakinaDefault.value != true) {
            requireContext().showToast(getString(R.string.please_set_sakina_as_default_first), Toast.LENGTH_LONG)
            return
        }
        prefs.homeBottomAlignment = !prefs.homeBottomAlignment
        populateAlignment()
        viewModel.updateHomeAlignment(prefs.homeAlignment)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.CENTER -> binding.alignment.text = getString(R.string.center)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
        binding.alignmentBottom.text = if (prefs.homeBottomAlignment)
            getString(R.string.bottom_on)
        else getString(R.string.bottom_off)
    }

    private fun toggleHomeButtonRecents() {
        if (!prefs.homeButtonShowRecents && !isAccessServiceEnabled(requireContext())) {
            toggleAccessibilityVisibility(true)
            return
        }
        prefs.homeButtonShowRecents = !prefs.homeButtonShowRecents
        populateHomeButtonRecents()
    }

    private fun populateHomeButtonRecents() {
        binding.homeButtonRecents.text = getString(
            if (prefs.homeButtonShowRecents && isAccessServiceEnabled(requireContext())) R.string.on
            else R.string.off
        )
    }

    private fun populateLockSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn && isAccessServiceEnabled(requireContext())) R.string.on
                else R.string.off
            )
        } else {
            binding.toggleLock.text = getString(
                if (prefs.lockModeOn) R.string.on
                else R.string.off
            )
        }
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            else -> getString(R.string.search)
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        if (prefs.swipeDownAction == swipeDownFor) return
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
    }

    private fun populateSwipeApps() {
        binding.swipeLeftApp.text = swipeTargetSummary(isLeft = true)
        binding.swipeRightApp.text = swipeTargetSummary(isLeft = false)
        binding.swipeLeftApp.setTextColor(
            requireContext().getColorFromAttr(
                if (prefs.swipeLeftTarget == Constants.SwipeTarget.OFF) R.attr.primaryColorTrans50
                else R.attr.primaryColor
            )
        )
        binding.swipeRightApp.setTextColor(
            requireContext().getColorFromAttr(
                if (prefs.swipeRightTarget == Constants.SwipeTarget.OFF) R.attr.primaryColorTrans50
                else R.attr.primaryColor
            )
        )
    }

    private fun showSwipeTargetPicker(isLeft: Boolean) {
        val targetValues = intArrayOf(
            Constants.SwipeTarget.PRODUCTIVE,
            Constants.SwipeTarget.TODO,
            Constants.SwipeTarget.TIMER,
            Constants.SwipeTarget.MUSLIM_CENTER,
            Constants.SwipeTarget.APP,
            Constants.SwipeTarget.OFF,
        )
        val labels = targetValues.map { swipeTargetLabel(it) }
        requireContext().showAppListDialog(
            getString(if (isLeft) R.string.swipe_left_for else R.string.swipe_right_for),
            labels,
        ) { which ->
            if (isLeft) {
                prefs.swipeLeftTarget = targetValues[which]
            } else {
                prefs.swipeRightTarget = targetValues[which]
            }
            populateSwipeApps()
            when (targetValues[which]) {
                Constants.SwipeTarget.OFF -> { /* nothing */ }
                else -> showSwipeAppList(isLeft)
            }
        }
    }

    private fun swipeTargetSummary(isLeft: Boolean): String {
        val target = if (isLeft) prefs.swipeLeftTarget else prefs.swipeRightTarget
        val appName = if (isLeft) prefs.appNameSwipeLeft else prefs.appNameSwipeRight
        if (target == Constants.SwipeTarget.APP) {
            return appName.ifBlank { swipeTargetLabel(target) }
        }
        if (target == Constants.SwipeTarget.OFF) {
            return swipeTargetLabel(target)
        }
        return "${swipeTargetLabel(target)} / ${appName.ifBlank { swipeTargetLabel(Constants.SwipeTarget.APP) }}"
    }

    private fun swipeTargetLabel(target: Int): String {
        return getString(
            when (target) {
                Constants.SwipeTarget.APP -> R.string.app
                Constants.SwipeTarget.PRODUCTIVE -> R.string.productive
                Constants.SwipeTarget.NOTES -> R.string.notes
                Constants.SwipeTarget.TODO -> R.string.todo
                Constants.SwipeTarget.TIMER -> R.string.timer
                Constants.SwipeTarget.MUSLIM_CENTER -> R.string.muslim
                else -> R.string.off
            }
        )
    }

    private fun populatePrayerRegion() {
        binding.prayerProviderValue.text = getString(
            when (prayerStore.provider) {
                PrayerProvider.KEMENAG -> R.string.prayer_provider_kemenag
                PrayerProvider.GLOBAL -> R.string.prayer_provider_global
            }
        )
        binding.prayerRegionValue.text = when (prayerStore.provider) {
            PrayerProvider.KEMENAG -> {
                val fallbackCity = prayerStore.cityQuery.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
                prayerStore.cityLabel.ifBlank { fallbackCity }
            }
            PrayerProvider.GLOBAL -> listOf(prayerStore.globalLocationLabel, prayerStore.globalCountry)
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }
        binding.prayerAutoDetectValue.text = getString(
            if (prayerStore.autoDetectLocation) R.string.on else R.string.off
        )
    }

    private fun showPrayerProviderPicker() {
        val providers = arrayOf(PrayerProvider.KEMENAG, PrayerProvider.GLOBAL)
        val labels = listOf(getString(R.string.prayer_provider_kemenag), getString(R.string.prayer_provider_global))
        requireContext().showAppListDialog(getString(R.string.prayer_source_setting), labels) { which ->
            prayerStore.provider = providers[which]
            prayerStore.autoDetectLocation = false
            populatePrayerRegion()
            refreshSelectedPrayerProvider()
        }
    }

    private fun refreshSelectedPrayerProvider() {
        viewLifecycleOwner.lifecycleScope.launch {
            when (prayerStore.provider) {
                PrayerProvider.KEMENAG -> prayerRepository.refreshToday()
                PrayerProvider.GLOBAL -> prayerRepository.selectGlobalLocation(
                    GlobalPrayerLocation(
                        label = prayerStore.globalLocationLabel,
                        country = prayerStore.globalCountry,
                        latitude = prayerStore.globalLatitude,
                        longitude = prayerStore.globalLongitude,
                        timeZoneId = prayerStore.globalTimeZoneId,
                        method = prayerStore.globalMethod,
                    )
                )
            }
        }
    }

    private fun showPrayerRegionPicker() {
        when (prayerStore.provider) {
            PrayerProvider.KEMENAG -> showKemenagRegionPicker()
            PrayerProvider.GLOBAL -> showGlobalRegionPicker()
        }
    }

    private fun showKemenagRegionPicker() {
        val presetQueries = arrayOf("jakarta", "bandung", "surabaya", "yogyakarta", "medan", "makassar")
        viewLifecycleOwner.lifecycleScope.launch {
            requireContext().showToast(getString(R.string.loading_locations))
            val cities = cachedKemenagCities ?: prayerRepository.allCities().ifEmpty {
                presetQueries.mapNotNull { query ->
                    prayerRepository.searchCities(query).firstOrNull()
                }.distinctBy { it.id }
            }.also { cachedKemenagCities = it }
            if (cities.isEmpty()) {
                requireContext().showToast(getString(R.string.unable_to_load_prayer_times))
                return@launch
            }

            val dialogView = layoutInflater.inflate(R.layout.dialog_prayer_city_picker, null)
            val searchInput = dialogView.findViewById<android.widget.EditText>(R.id.searchInput)
            val cityList = dialogView.findViewById<android.widget.ListView>(R.id.cityList)
            val emptyView = dialogView.findViewById<android.widget.TextView>(R.id.emptyView)

            val adapter = android.widget.ArrayAdapter(
                requireContext(),
                R.layout.adapter_prayer_location,
                cities.map { it.label }.toMutableList()
            )
            cityList.adapter = adapter

            val dialog = AppDialog.create(requireContext(), dialogView, matchHeight = true)

            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val query = s?.toString()?.lowercase() ?: ""
                    val filtered = if (query.isEmpty()) {
                        cities
                    } else {
                        cities.filter {
                            it.label.lowercase().contains(query)
                        }
                    }

                    if (filtered.isEmpty()) {
                        cityList.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                    } else {
                        cityList.visibility = View.VISIBLE
                        emptyView.visibility = View.GONE
                        adapter.clear()
                        adapter.addAll(filtered.map { it.label })
                        adapter.notifyDataSetChanged()
                    }
                }
            })

            cityList.setOnItemClickListener { _, _, position, _ ->
                val query = searchInput.text.toString().lowercase()
                val filtered = if (query.isEmpty()) {
                    cities
                } else {
                    cities.filter {
                        it.label.lowercase().contains(query)
                    }
                }
                if (position < filtered.size) {
                    selectPrayerCity(filtered[position], autoDetected = false)
                    dialog.dismiss()
                }
            }

            dialog.show()
            searchInput.requestFocus()
        }
    }

    private fun showGlobalRegionPicker() {
        val locations = PrayerTimeRepository.globalPresetLocations
        val dialogView = layoutInflater.inflate(R.layout.dialog_prayer_city_picker, null)
        val searchInput = dialogView.findViewById<android.widget.EditText>(R.id.searchInput)
        val cityList = dialogView.findViewById<android.widget.ListView>(R.id.cityList)
        val emptyView = dialogView.findViewById<android.widget.TextView>(R.id.emptyView)
        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            R.layout.adapter_prayer_location,
            locations.map { "${it.label}, ${it.country}" }.toMutableList()
        )
        cityList.adapter = adapter
        val dialog = AppDialog.create(requireContext(), dialogView, matchHeight = true)
        fun filteredLocations(): List<GlobalPrayerLocation> {
            val query = searchInput.text.toString().lowercase()
            return if (query.isBlank()) locations else locations.filter {
                "${it.label} ${it.country}".lowercase().contains(query)
            }
        }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val filtered = filteredLocations()
                cityList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
                emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                adapter.clear()
                adapter.addAll(filtered.map { "${it.label}, ${it.country}" })
                adapter.notifyDataSetChanged()
            }
        })
        cityList.setOnItemClickListener { _, _, position, _ ->
            val filtered = filteredLocations()
            if (position < filtered.size) {
                selectGlobalPrayerLocation(filtered[position], autoDetected = false)
                dialog.dismiss()
            }
        }
        dialog.show()
        searchInput.hint = getString(R.string.search_city_country)
        searchInput.requestFocus()
    }

    private fun autoDetectPrayerRegion() {
        if (!PrayerLocationHelper.hasLocationPermission(requireContext())) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION_PERMISSION
            )
            return
        }
        if (prayerStore.provider == PrayerProvider.GLOBAL) {
            detectGlobalPrayerRegionFromLocation()
        } else {
            detectPrayerRegionFromLocation()
        }
    }

    private fun detectPrayerRegionFromLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            val cityQuery = PrayerLocationHelper.detectCityQuery(requireContext())
            if (cityQuery.isNullOrBlank()) {
                requireContext().showToast(getString(R.string.location_not_found))
                return@launch
            }
            val city = prayerRepository.searchCities(cityQuery).firstOrNull()
            if (city == null) {
                requireContext().showToast(getString(R.string.location_not_found))
                return@launch
            }
            selectPrayerCity(city, autoDetected = true)
        }
    }

    private fun detectGlobalPrayerRegionFromLocation() {
        viewLifecycleOwner.lifecycleScope.launch {
            val detected = PrayerLocationHelper.detectLocation(requireContext())
            if (detected == null) {
                requireContext().showToast(getString(R.string.location_not_found))
                return@launch
            }
            selectGlobalPrayerLocation(
                GlobalPrayerLocation(
                    label = detected.cityQuery,
                    country = detected.country,
                    latitude = detected.latitude,
                    longitude = detected.longitude,
                    timeZoneId = detected.timeZoneId,
                    method = 3,
                ),
                autoDetected = true,
            )
        }
    }

    private fun selectPrayerCity(city: PrayerCity, autoDetected: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            prayerStore.autoDetectLocation = autoDetected
            prayerRepository.selectCity(city)
            populatePrayerRegion()
            requireContext().showToast(city.label)
        }
    }

    private fun selectGlobalPrayerLocation(location: GlobalPrayerLocation, autoDetected: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            prayerStore.autoDetectLocation = autoDetected
            prayerRepository.selectGlobalLocation(location)
            populatePrayerRegion()
            requireContext().showToast("${location.label}, ${location.country}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION_PERMISSION) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            if (prayerStore.provider == PrayerProvider.GLOBAL) {
                detectGlobalPrayerRegionFromLocation()
            } else {
                detectPrayerRegionFromLocation()
            }
        } else {
            prayerStore.autoDetectLocation = false
            populatePrayerRegion()
            requireContext().showToast(getString(R.string.location_permission_denied))
        }
    }


    private fun showSwipeAppList(isLeft: Boolean) {
        val flag = if (isLeft) Constants.FLAG_SET_SWIPE_LEFT_APP else Constants.FLAG_SET_SWIPE_RIGHT_APP
        viewModel.getAppList(true)
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to flag)
        )
    }

    private fun populateActionHints() {
        if (prefs.aboutClicked.not())
            binding.aboutSakina.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_info, 0)
    }

    private fun populateProMessage() {
        if (prefs.proMessageShown.not() && prefs.userState == Constants.UserState.SHARE) {
            prefs.proMessageShown = true
            viewModel.showDialog.postValue(Constants.Dialog.PRO_MESSAGE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_LOCATION_PERMISSION = 901
        private const val SETTINGS_DIM_DURATION = 450L
    }
}

package app.sakinalauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.sakinalauncher.MainViewModel
import app.sakinalauncher.R
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.data.muslim.DhikrContent
import app.sakinalauncher.data.muslim.DhikrPeriod
import app.sakinalauncher.data.muslim.PrayerApiClient
import app.sakinalauncher.data.muslim.PrayerName
import app.sakinalauncher.data.muslim.PrayerSchedule
import app.sakinalauncher.data.muslim.PrayerScheduleResult
import app.sakinalauncher.data.muslim.PrayerTimeRepository
import app.sakinalauncher.data.muslim.PrayerTimeStore
import app.sakinalauncher.databinding.FragmentMuslimCenterBinding
import app.sakinalauncher.helper.addPressScale
import app.sakinalauncher.helper.addSystemBarInsetsPadding
import app.sakinalauncher.helper.applyGlassInk
import app.sakinalauncher.helper.launchSwipeApp
import app.sakinalauncher.helper.openUrl
import app.sakinalauncher.helper.PrayerLocationHelper
import app.sakinalauncher.helper.frostWallpaperWhileResumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MuslimCenterFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var prayerStore: PrayerTimeStore
    private lateinit var repository: PrayerTimeRepository
    private var sourceDirection: String? = null
    private var currentSchedule: PrayerSchedule? = null
    private var prayerTickerJob: Job? = null
    private var refreshPrayerJob: Job? = null

    private var _binding: FragmentMuslimCenterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMuslimCenterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        frostWallpaperWhileResumed()
        prefs = Prefs(requireContext())
        prayerStore = PrayerTimeStore(requireContext())
        repository = PrayerTimeRepository(
            kemenagApi = PrayerApiClient.api,
            aladhanApi = PrayerApiClient.aladhanApi,
            store = prayerStore
        )
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        sourceDirection = savedInstanceState?.getString(KEY_SOURCE_DIRECTION)
            ?: arguments?.getString(Constants.Key.SWIPE_DIRECTION)
        binding.scrollView.addSystemBarInsetsPadding()
        bindCachedContent()
        refreshPrayerTimes()
        initSwipe()
        binding.source.text = getString(R.string.dzikir_source_combined)
        binding.source.setOnClickListener {
            requireContext().openUrl(Constants.URL_DZIKIR_ALMANHAJ)
        }
        binding.source.setOnLongClickListener {
            requireContext().openUrl(Constants.URL_DZIKIR_AFTER_PRAYER)
            true
        }
        binding.btnBack.setOnClickListener { closeCenter() }
        binding.btnBack.addPressScale(0.9f)
        binding.location.setOnClickListener { openSettings() }
        binding.prayerCard.setOnClickListener { openSettings() }
        binding.morningDhikrCard.setOnClickListener { openDhikr(DhikrPeriod.MORNING) }
        binding.eveningDhikrCard.setOnClickListener { openDhikr(DhikrPeriod.EVENING) }
        binding.afterPrayerDhikrCard.setOnClickListener { openDhikr(DhikrPeriod.AFTER_PRAYER) }
        binding.prayerCard.addPressScale()
        binding.morningDhikrCard.addPressScale(0.95f)
        binding.eveningDhikrCard.addPressScale(0.95f)
        binding.afterPrayerDhikrCard.addPressScale(0.95f)
        startPrayerTicker()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SOURCE_DIRECTION, sourceDirection)
        super.onSaveInstanceState(outState)
    }

    private fun bindCachedContent() {
        val cached = repository.cachedSchedule()
        if (cached == null) {
            binding.nextPrayer.text = "--:--"
            binding.nextPrayerName.text = getString(R.string.prayer_times)
            binding.nextPrayerCountdown.text = getString(R.string.loading_prayer_times)
            clearPrayerChips()
            binding.prayerSource.text = getString(R.string.prayer_source)
        } else {
            renderSchedule(cached, getString(R.string.cached_schedule))
        }
        renderDhikrSummary()
    }

    private fun refreshPrayerTimes() {
        refreshPrayerJob?.cancel()
        refreshPrayerJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (prayerStore.autoDetectLocation && hasLocationPermission()) {
                    tryAutoDetectLocation()
                }
                when (val result = repository.getOrFetchToday(forceRefresh = false)) {
                    is PrayerScheduleResult.Fresh -> renderSchedule(result.schedule)
                    is PrayerScheduleResult.Cached -> renderSchedule(
                        result.schedule,
                        getString(R.string.cached_schedule)
                    )
                    is PrayerScheduleResult.Error -> renderPrayerError(result.message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                renderPrayerError(error.message ?: getString(R.string.unable_to_load_prayer_times))
            }
        }
    }

    private suspend fun tryAutoDetectLocation() {
        val detected = PrayerLocationHelper.detectLocation(requireContext()) ?: return
        // Coordinate-first: this works with no data connection at all. The old path
        // needed the Kemenag search API just to turn a city name into an id, so a
        // fresh install offline was left on the Jakarta default.
        repository.adoptDetectedLocation(
            label = detected.cityQuery,
            country = detected.country,
            latitude = detected.latitude,
            longitude = detected.longitude,
            timeZoneId = detected.timeZoneId,
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun renderSchedule(schedule: PrayerSchedule, badge: String? = null) {
        currentSchedule = schedule
        val nextPrayer = schedule.nextPrayer()
        binding.nextPrayerName.text = prayerNameLabel(nextPrayer.name)
        binding.nextPrayer.text = nextPrayer.time
        binding.nextPrayerCountdown.text = countdownLabel(nextPrayer.time)
        binding.location.text = listOf(schedule.city, schedule.province, schedule.dateLabel)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        renderPrayerChips(schedule, nextPrayer.name)
        binding.prayerSource.text = badge?.let {
            getString(R.string.prayer_source_cached, schedule.source, it, schedule.updatedLabel())
        } ?: getString(R.string.prayer_source_live, schedule.source, schedule.updatedLabel())
    }

    private fun renderPrayerError(message: String) {
        currentSchedule = null
        binding.nextPrayer.text = "--:--"
        binding.nextPrayerName.text = getString(R.string.prayer_times)
        binding.nextPrayerCountdown.text = getString(R.string.prayer_need_location)
        binding.location.text = message
        clearPrayerChips()
        binding.prayerSource.text = getString(R.string.prayer_source)
    }

    private fun renderPrayerChips(schedule: PrayerSchedule, activeName: PrayerName) {
        prayerChipViews().forEach { (name, chip) ->
            val prayerTime = schedule.times.firstOrNull { it.name == name }
            chip.name.text = prayerNameLabel(name)
            chip.time.text = prayerTime?.time ?: "--:--"
            styleChip(chip, active = name == activeName)
        }
    }

    private fun clearPrayerChips() {
        prayerChipViews().forEach { (name, chip) ->
            chip.name.text = prayerNameLabel(name)
            chip.time.text = "--:--"
            styleChip(chip, active = false)
            chip.row.alpha = 0.55f
        }
    }

    /**
     * The active chip is filled with inverted ink, so its label must flip colour and
     * drop the legibility halo — a halo over a solid fill is what reads as "embossed".
     */
    private fun styleChip(chip: PrayerChip, active: Boolean) {
        chip.row.setBackgroundResource(
            if (active) R.drawable.bg_prayer_row_active else R.drawable.bg_prayer_row
        )
        chip.name.applyGlassInk(active, inactiveAlpha = 0.82f)
        chip.time.applyGlassInk(active, inactiveAlpha = 0.82f)
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(requireContext(), value.resourceId)
        } else {
            value.data
        }
    }

    /** One prayer row: the tappable container + the two labels inside it. */
    private data class PrayerChip(
        val row: View,
        val name: TextView,
        val time: TextView,
    )

    private fun prayerChipViews(): Map<PrayerName, PrayerChip> {
        return mapOf(
            PrayerName.FAJR to PrayerChip(binding.subuhRow, binding.subuhName, binding.subuhTime),
            PrayerName.DHUHR to PrayerChip(binding.dzuhurRow, binding.dzuhurName, binding.dzuhurTime),
            PrayerName.ASR to PrayerChip(binding.asharRow, binding.asharName, binding.asharTime),
            PrayerName.MAGHRIB to PrayerChip(binding.maghribRow, binding.maghribName, binding.maghribTime),
            PrayerName.ISHA to PrayerChip(binding.isyaRow, binding.isyaName, binding.isyaTime),
        )
    }

    private fun prayerNameLabel(name: PrayerName): String {
        return getString(
            when (name) {
                PrayerName.FAJR -> R.string.prayer_fajr
                PrayerName.DHUHR -> R.string.prayer_dhuhr
                PrayerName.ASR -> R.string.prayer_asr
                PrayerName.MAGHRIB -> R.string.prayer_maghrib
                PrayerName.ISHA -> R.string.prayer_isha
            }
        )
    }

    /**
     * Minutes until [time] ("HH:mm"), rendered as a human countdown. Wraps past
     * midnight so the label stays correct after Isha.
     */
    private fun countdownLabel(time: String): String {
        val parts = time.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val minute = parts.getOrNull(1)?.trim()?.take(2)?.toIntOrNull()
        if (hour == null || minute == null) return getString(R.string.next_prayer)
        val now = java.util.Calendar.getInstance()
        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        var delta = (hour * 60 + minute) - nowMinutes
        if (delta < 0) delta += 24 * 60
        val hours = delta / 60
        val minutes = delta % 60
        val duration = if (hours > 0) {
            getString(R.string.duration_h_m, hours, minutes)
        } else {
            getString(R.string.duration_m, minutes)
        }
        return getString(R.string.next_prayer_in, duration)
    }

    private fun startPrayerTicker() {
        prayerTickerJob?.cancel()
        prayerTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle(STARTED), not a bare launch: lifecycleScope only cancels
            // at DESTROY, so with the screen off or another app on top this kept waking
            // every 60s to re-render invisible views — and could reach the network.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(60_000L)
                    val schedule = currentSchedule ?: continue
                    if (schedule.isFetchedToday()) {
                        renderSchedule(schedule)
                    } else {
                        val cachedToday = repository.loadCachedToday()
                        if (cachedToday != null) {
                            renderSchedule(cachedToday, getString(R.string.cached_schedule))
                        } else {
                            refreshPrayerTimes()
                        }
                    }
                }
            }
        }
    }

    private fun renderDhikrSummary() {
        binding.morningDhikrCount.text = DhikrContent.countFor(DhikrPeriod.MORNING).toString()
        binding.eveningDhikrCount.text = DhikrContent.countFor(DhikrPeriod.EVENING).toString()
        binding.afterPrayerDhikrCount.text = DhikrContent.countFor(DhikrPeriod.AFTER_PRAYER).toString()
    }

    private fun openSettings() {
        findNavController().navigate(R.id.settingsFragment)
    }

    private fun openDhikr(period: DhikrPeriod) {
        findNavController().navigate(
            R.id.dhikrPagerFragment,
            bundleOf(Constants.Key.DHIKR_PERIOD to period.name)
        )
    }

    private fun initSwipe() {
        binding.scrollView.onHorizontalSwipeLeft = {
            handleHorizontalSwipe(Constants.SwipeDirection.LEFT)
        }
        binding.scrollView.onHorizontalSwipeRight = {
            handleHorizontalSwipe(Constants.SwipeDirection.RIGHT)
        }
    }

    private fun handleHorizontalSwipe(direction: String) {
        if (direction == sourceDirection) {
            launchSwipeApp(
                context = requireContext(),
                viewModel = viewModel,
                prefs = prefs,
                isLeft = direction == Constants.SwipeDirection.LEFT
            )
        } else {
            closeCenter()
        }
    }

    private fun closeCenter() {
        runCatching {
            if (findNavController().popBackStack().not()) {
                findNavController().navigate(R.id.mainFragment)
            }
        }
    }

    override fun onDestroyView() {
        refreshPrayerJob?.cancel()
        refreshPrayerJob = null
        prayerTickerJob?.cancel()
        prayerTickerJob = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_SOURCE_DIRECTION = "muslim_center_source_direction"
    }
}

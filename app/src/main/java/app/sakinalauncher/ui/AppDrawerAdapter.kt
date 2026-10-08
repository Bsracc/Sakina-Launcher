package app.sakinalauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.sakinalauncher.R
import app.sakinalauncher.data.AppModel
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.databinding.AdapterAppDrawerBinding
import app.sakinalauncher.databinding.AdapterAppDrawerMenuBinding
import app.sakinalauncher.databinding.AdapterAppDrawerRenameBinding
import app.sakinalauncher.databinding.AdapterPrivateSpaceHeaderBinding
import app.sakinalauncher.helper.getColorFromAttr
import app.sakinalauncher.helper.hideKeyboard
import app.sakinalauncher.helper.isEinkDisplay
import app.sakinalauncher.helper.isSystemApp
import app.sakinalauncher.helper.showKeyboard
import java.text.Normalizer

class AppDrawerAdapter(
    private var flag: Int,
    private val appLabelGravity: Int,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val appMindfulListener: ((AppModel) -> Unit)? = null,
    private val privateSpaceToggleListener: () -> Unit = {},
    private val privateSpaceSettingsListener: () -> Unit = {},
) : ListAdapter<AppModel, RecyclerView.ViewHolder>(DIFF_CALLBACK), Filterable {

    companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_PRIVATE_HEADER = 1

        /** Compiled once. These were rebuilt per app label, per keystroke. */
        private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val SEPARATORS = Regex("[-_+,. ]")

        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppModel>() {
            override fun areItemsTheSame(oldItem: AppModel, newItem: AppModel): Boolean = when {
                oldItem is AppModel.App && newItem is AppModel.App ->
                    oldItem.appPackage == newItem.appPackage && oldItem.user == newItem.user

                oldItem is AppModel.PinnedShortcut && newItem is AppModel.PinnedShortcut ->
                    oldItem.shortcutId == newItem.shortcutId && oldItem.user == newItem.user

                oldItem is AppModel.PrivateSpaceHeader && newItem is AppModel.PrivateSpaceHeader -> true

                else -> false
            }

            override fun areContentsTheSame(oldItem: AppModel, newItem: AppModel): Boolean =
                oldItem == newItem
        }
    }

    private var autoLaunch = true
    private var isBangSearch = false
    private val appFilter = createAppFilter()
    private val myUserHandle = android.os.Process.myUserHandle()

    /**
     * Per-label subtitle shown only when two or more entries share the same label.
     *
     * Built once in [setAppList] (O(n) over the list), not per bind: the drawer
     * renders ~250 rows and every bind used to re-scan the whole list for each row,
     * which is the O(n²) this map avoids. Only the *duplicated* labels are stored, so
     * a single "Messages" row stays untouched. Pinned shortcuts key off `shortcutId`,
     * which is what rename writes, matching [AppDrawerAdapter.DIFF_CALLBACK].
     */
    private var labelCounts: Map<String, Int> = emptyMap()

    /**
     * Accent/separator-stripped labels, computed once per label instead of once per
     * keystroke. Filtering used to build two [Regex] objects and run [Normalizer] for
     * every app on every character typed — on a 200-app device that was 400 regex
     * compilations per keystroke, which is what made drawer search feel sticky.
     * Written only from the [Filter] worker thread; concurrent for safety.
     */
    private val normalizedLabels = java.util.concurrent.ConcurrentHashMap<String, String>()

    var appsList: List<AppModel> = emptyList()
    var appFilteredList: List<AppModel> = emptyList()

    override fun getItemViewType(position: Int): Int {
        return when (appFilteredList.getOrNull(position)) {
            is AppModel.PrivateSpaceHeader -> VIEW_TYPE_PRIVATE_HEADER
            else -> VIEW_TYPE_APP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PRIVATE_HEADER -> PrivateSpaceHeaderViewHolder(
                AdapterPrivateSpaceHeaderBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> ViewHolder(
                AdapterAppDrawerBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        try {
            if (appFilteredList.isEmpty() || position == RecyclerView.NO_POSITION) return
            val appModel = appFilteredList[holder.bindingAdapterPosition]
            when (holder) {
                is PrivateSpaceHeaderViewHolder -> {
                    holder.bind(
                        appLabelGravity,
                        privateSpaceToggleListener,
                        privateSpaceSettingsListener,
                    )
                }

                is ViewHolder -> holder.bind(
                    flag,
                    appLabelGravity,
                    myUserHandle,
                    appModel,
                    labelCounts,
                    appClickListener,
                    appDeleteListener,
                    appInfoListener,
                    appHideListener,
                    appRenameListener,
                    appMindfulListener
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getFilter(): Filter = this.appFilter

    private fun createAppFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(charSearch: CharSequence?): FilterResults {
                isBangSearch = charSearch?.startsWith("!") ?: false
                autoLaunch = charSearch?.startsWith(" ")?.not() ?: true

                // Always a fresh list, never the live `appsList` reference: submitList
                // short-circuits on reference equality, so re-publishing the same list
                // instance would silently skip the diff.
                val filtered: List<AppModel> = if (charSearch.isNullOrBlank()) {
                    ArrayList(appsList)
                } else {
                    appsList.filter { app ->
                        app !is AppModel.PrivateSpaceHeader && appLabelMatches(app.appLabel, charSearch)
                    }
                }

                val filterResults = FilterResults()
                filterResults.values = filtered
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                results?.values?.let {
                    appFilteredList = it as List<AppModel>
                    submitList(appFilteredList) {
                        autoLaunch()
                    }
                }
            }
        }
    }

    private fun autoLaunch() {
        try {
            if (itemCount == 1
                && autoLaunch
                && isBangSearch.not()
                && flag == Constants.FLAG_LAUNCH_APP
                && appFilteredList.isNotEmpty()
                && appFilteredList[0] !is AppModel.PrivateSpaceHeader
            ) appClickListener(appFilteredList[0])
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun appLabelMatches(appLabel: String, charSearch: CharSequence): Boolean {
        if (appLabel.contains(charSearch.trim(), true)) return true
        val stripped = normalizedLabels.getOrPut(appLabel) {
            DIACRITICS.replace(Normalizer.normalize(appLabel, Normalizer.Form.NFD), "")
                .let { SEPARATORS.replace(it, "") }
        }
        return stripped.contains(charSearch, true)
    }

    /**
     * Replace the backing list.
     *
     * The copy is load-bearing, not defensive hygiene: [ListAdapter.submitList] returns
     * early when the new list is the *same reference* as the current one. Handing it the
     * caller's list and then mutating that list in place (which the hide flow did) meant
     * the next submit compared a list against itself and DiffUtil emitted nothing. The
     * padding row is appended to our copy so the caller's list is left alone too.
     */
    fun setAppList(appsList: List<AppModel>) {
        val next = ArrayList<AppModel>(appsList.size + 1)
        next.addAll(appsList)
        // Empty app for bottom padding in the recyclerview.
        next.add(
            AppModel.App(
                appLabel = "",
                key = null,
                appPackage = "",
                activityClassName = "",
                isNew = false,
                user = myUserHandle
            )
        )
        this.appsList = next
        this.appFilteredList = next
        // Count labels once per list build (O(n)); binds only look up the map.
        labelCounts = next.asSequence()
            .filterNot { it is AppModel.PrivateSpaceHeader || it.appLabel.isBlank() }
            .groupingBy { it.appLabel }
            .eachCount()
        submitList(next)
    }

    /**
     * Drop one row immediately, ahead of the reload that follows a hide.
     *
     * Goes through [submitList] with a fresh list rather than mutating in place and
     * calling `notifyItemRemoved`: a ListAdapter owns its list, and mutating it behind
     * its back desynchronises the adapter from the diff it is computing.
     */
    fun removeItem(appModel: AppModel) {
        appsList = ArrayList(appsList).apply { remove(appModel) }
        appFilteredList = ArrayList(appFilteredList).apply { remove(appModel) }
        // Keep the duplicate map in step: hiding one of two same-labelled apps must
        // drop the sublabel on the survivor, not wait for the reload to rebuild it.
        labelCounts = appsList.asSequence()
            .filterNot { it is AppModel.PrivateSpaceHeader || it.appLabel.isBlank() }
            .groupingBy { it.appLabel }
            .eachCount()
        submitList(appFilteredList)
    }

    fun launchFirstInList() {
        val first = appFilteredList.firstOrNull { it !is AppModel.PrivateSpaceHeader }
        if (first != null) appClickListener(first)
    }

    class PrivateSpaceHeaderViewHolder(private val binding: AdapterPrivateSpaceHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            appLabelGravity: Int,
            toggleListener: () -> Unit,
            settingsListener: () -> Unit,
        ) = with(binding) {
            privateSpaceTitle.gravity = appLabelGravity
            privateSpaceTitle.setOnClickListener { toggleListener() }
            privateSpaceTitle.setOnLongClickListener {
                settingsListener()
                true
            }
        }
    }

    class ViewHolder(private val binding: AdapterAppDrawerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        /** Null until this row is long-pressed; see [bind]. */
        private var menu: AdapterAppDrawerMenuBinding? = null

        /** Null until rename is opened on this row; see [bind]. */
        private var rename: AdapterAppDrawerRenameBinding? = null
        private var renameWatcher: TextWatcher? = null
        private var originalAppName = ""

        private fun menu(): AdapterAppDrawerMenuBinding =
            menu ?: AdapterAppDrawerMenuBinding.bind(binding.appHideStub.inflate()).also { menu = it }

        private fun rename(): AdapterAppDrawerRenameBinding =
            rename ?: AdapterAppDrawerRenameBinding.bind(binding.renameStub.inflate()).also { rename = it }

        /**
         * Per-bind work is deliberately minimal: label, gravity, profile dot, two
         * listeners. The long-press menu and the rename row are behind ViewStubs and
         * their listeners are wired on first use — binding them on every row allocated
         * a TextWatcher, a focus listener, an editor-action listener and eight lambdas
         * per row, which the drawer then had to collect mid-fling.
         */
        fun bind(
            flag: Int,
            appLabelGravity: Int,
            myUserHandle: UserHandle,
            appModel: AppModel,
            labelCounts: Map<String, Int>,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appMindfulListener: ((AppModel) -> Unit)? = null,
        ) = with(binding) {
            menu?.root?.visibility = View.GONE
            rename?.root?.visibility = View.GONE
            appTitle.visibility = View.VISIBLE
            originalAppName = ""
            renameWatcher?.let { watcher ->
                rename?.etAppRename?.removeTextChangedListener(watcher)
                renameWatcher = null
            }

            // Show indicators in title based on app type and state
            appTitle.text = appModel.appLabel
            appTitle.gravity = appLabelGravity
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            // Two apps sharing one label ("Settings", "Files", …) are indistinguishable;
            // disambiguate only those rows with the package/identifier as a sublabel.
            // The list is sorted by label, so same-labelled rows are adjacent and a
            // stable sublabel keeps the row height identical.
            val isDuplicate = labelCounts[appModel.appLabel]?.let { it > 1 } == true
            val sublabel = if (isDuplicate) {
                when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.shortcutId
                    is AppModel.App -> appModel.appPackage
                    else -> ""
                }
            } else {
                ""
            }
            appTitleSub.isVisible = sublabel.isNotEmpty()
            if (sublabel.isNotEmpty()) {
                appTitleSub.text = sublabel
                appTitleSub.gravity = appLabelGravity
                appTitleSub.setTextColor(root.context.getColorFromAttr(R.attr.primaryColorTrans50))
                if (root.context.isEinkDisplay()) {
                    appTitleSub.setTextColor(
                        ContextCompat.getColor(root.context, R.color.eink_text)
                    )
                }
            }

            // Mindful launch indicator (B1): a checkbox in the mindful picker
            // (ring = not opted in, filled + inverse check = opted in) and a quiet
            // check glyph in the normal drawer. Deliberately keyed to the opted-in
            // list only (not the master toggle): in the picker the user must see
            // which apps are REGISTERED, even when mindful launch is currently off.
            // Reads prefs per bind (cheap: a few SharedPreferences reads).
            val mindfulApps = Prefs(root.context).mindfulApps
            val isMindfulApp = appModel.appPackage.isNotBlank() && appModel.appPackage in mindfulApps
            val inMindfulPicker = flag == Constants.FLAG_SET_MINDFUL_APP
            mindfulCheck.isVisible = isMindfulApp || inMindfulPicker
            mindfulCheckBg.isVisible = inMindfulPicker
            mindfulCheckBg.isSelected = isMindfulApp
            mindfulCheckGlyph.isVisible = isMindfulApp
            mindfulCheck.contentDescription = when {
                isMindfulApp -> root.context.getString(R.string.mindful_badge_desc)
                inMindfulPicker -> root.context.getString(R.string.mindful_not_selected_desc)
                else -> null
            }
            // In the mindful picker the checkbox IS the toggle target — tapping the
            // app name is too easy to hit by accident while scrolling. Outside the
            // picker the check is informative only (the row still launches the app).
            mindfulCheck.setOnClickListener {
                if (inMindfulPicker) {
                    appMindfulListener?.invoke(appModel)
                }
            }
            mindfulCheck.isClickable = inMindfulPicker

            // In the mindful picker the app label is deliberately not tappable —
            // selection happens ONLY on the checkbox. The click listener is skipped
            // entirely (no no-op lambda that still swallows the tap).
            if (inMindfulPicker) {
                appTitle.setOnClickListener(null)
            } else {
                appTitle.setOnClickListener { clickListener(appModel) }
            }

            appTitle.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    val chrome = menu()
                    wireHideChrome(
                        chrome,
                        appModel,
                        appDeleteListener,
                        appInfoListener,
                        appHideListener,
                        appRenameListener,
                        appMindfulListener,
                    )
                    chrome.appDelete.alpha = when (
                        appModel is AppModel.PinnedShortcut || !root.context.isSystemApp(appModel.appPackage, appModel.user)
                    ) {
                        true -> 1.0f
                        false -> 0.5f
                    }
                    chrome.appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    chrome.appHide.alpha = when (appModel is AppModel.PinnedShortcut) {
                        true -> 0.5f
                        false -> 1.0f
                    }
                    chrome.root.visibility = View.VISIBLE
                    // Only allow renaming non hidden apps
                    chrome.appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                    // Mindful opt-in is only offered in the launch picker (FLAG_LAUNCH_APP)
                    chrome.appMindful.isVisible = flag == Constants.FLAG_LAUNCH_APP && appMindfulListener != null
                }
                true
            }
        }

        /** Wired on first long-press of this row — see [bind]. */
        private fun wireHideChrome(
            chrome: AdapterAppDrawerMenuBinding,
            appModel: AppModel,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appMindfulListener: ((AppModel) -> Unit)? = null,
        ) {
            chrome.appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    val editor = rename()
                    wireRenameChrome(editor, appModel, appRenameListener)
                    originalAppName = getAppName(editor.etAppRename.context, appModel.appPackage, appModel.user)
                    editor.etAppRename.hint = originalAppName
                    editor.etAppRename.setText(appModel.appLabel)
                    editor.etAppRename.setSelectAllOnFocus(true)
                    editor.root.visibility = View.VISIBLE
                    chrome.root.visibility = View.GONE
                    editor.etAppRename.showKeyboard()
                    editor.etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            chrome.appInfo.setOnClickListener { appInfoListener(appModel) }
            chrome.appDelete.setOnClickListener { appDeleteListener(appModel) }
            chrome.appMenuClose.setOnClickListener {
                chrome.root.visibility = View.GONE
                binding.appTitle.visibility = View.VISIBLE
            }
            chrome.appHide.setOnClickListener { appHideListener(appModel) }
            appMindfulListener?.let { listener ->
                chrome.appMindful.setOnClickListener {
                    listener(appModel)
                    chrome.root.visibility = View.GONE
                    binding.appTitle.visibility = View.VISIBLE
                }
            }
        }

        /** Wired when the rename row is first opened on this row — see [bind]. */
        private fun wireRenameChrome(
            editor: AdapterAppDrawerRenameBinding,
            appModel: AppModel,
            appRenameListener: (AppModel, String) -> Unit,
        ) = with(editor) {
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                binding.appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            renameWatcher?.let { etAppRename.removeTextChangedListener(it) }
            renameWatcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = originalAppName
                }

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            }
            etAppRename.addTextChangedListener(renameWatcher)
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        root.visibility = View.GONE
                    }
                    true
                }
                false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    root.visibility = View.GONE
                } else {
                    val fallbackName = originalAppName.ifBlank {
                        getAppName(etAppRename.context, appModel.appPackage, appModel.user)
                    }
                    appRenameListener(
                        appModel,
                        fallbackName
                    )
                    root.visibility = View.GONE
                }
            }
            appRenameClose.setOnClickListener {
                root.visibility = View.GONE
                binding.appTitle.visibility = View.VISIBLE
            }
        }

        private fun getAppName(context: Context, appPackage: String, user: UserHandle): String {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            return try {
                val activityList = launcherApps.getActivityList(appPackage, user)
                if (activityList.isNotEmpty()) {
                    activityList.first().label.toString()
                } else {
                    val packageManager = context.packageManager
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(appPackage, 0)
                    ).toString()
                }
            } catch (_: Exception) {
                "" // As a fallback, display an empty string.
            }
        }
    }
}

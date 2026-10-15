package app.sakinalauncher.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.sakinalauncher.MainActivity
import app.sakinalauncher.MainViewModel
import app.sakinalauncher.R
import app.sakinalauncher.data.Constants
import app.sakinalauncher.data.NoteMessage
import app.sakinalauncher.data.NotePanelMode
import app.sakinalauncher.data.NotePanelRows
import app.sakinalauncher.data.NotePanelStore
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.data.ProductiveWidgetStore
import app.sakinalauncher.data.TimerKeys
import app.sakinalauncher.data.TimerSession
import app.sakinalauncher.data.TodoItem
import app.sakinalauncher.databinding.FragmentNotePanelBinding
import app.sakinalauncher.helper.AppDialog
import app.sakinalauncher.helper.ProductiveWidgetHostHelper
import app.sakinalauncher.helper.SoundEffects
import app.sakinalauncher.helper.applyGlassInk
import app.sakinalauncher.helper.hideKeyboard
import app.sakinalauncher.helper.launchSwipeApp
import app.sakinalauncher.helper.showKeyboard
import app.sakinalauncher.helper.showToast
import app.sakinalauncher.helper.frostWallpaperWhileResumed
import app.sakinalauncher.listener.OnSwipeTouchListener
import app.sakinalauncher.timer.TimerService
import java.util.Locale
import kotlin.math.abs

class NotePanelFragment : Fragment() {

    private lateinit var store: NotePanelStore
    private lateinit var widgetStore: ProductiveWidgetStore
    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: NotePanelAdapter
    private var widgetHost: ProductiveWidgetHostHelper? = null
    private var mode: NotePanelMode = NotePanelMode.NOTES
    private var noteDraft: String = ""
    private var todoDraft: String = ""
    private var sourceDirection: String? = null
    private lateinit var timerSession: TimerSession
    private var selectedNoteIds: MutableSet<String> = mutableSetOf()
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private val smoothInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var _binding: FragmentNotePanelBinding? = null
    private val binding get() = _binding!!

    private val pickWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        clearSuppressHome()
        ensureWidgetHost()
        val host = widgetHost ?: return@registerForActivityResult
        val id = result.data?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            pendingWidgetId,
        ) ?: pendingWidgetId
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return@registerForActivityResult
        }
        pendingWidgetId = id
        // Prefer provider from the pick result when info is not ready yet (OEM race).
        val providerFromIntent = readProviderExtra(result.data)
        // ACTION_APPWIDGET_PICK often binds the id for our host already. If info is
        // present, skip bind permission UI — launching it again can delete the id.
        val info = host.providerInfo(id)
        if (result.resultCode != Activity.RESULT_OK && info == null && providerFromIntent == null) {
            host.deleteId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return@registerForActivityResult
        }
        if (info != null) {
            finishWidgetSetup(id, info.provider)
            return@registerForActivityResult
        }
        val provider = providerFromIntent
        if (provider == null) {
            host.deleteId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            requireContext().showToast(getString(R.string.widget_add_failed))
            return@registerForActivityResult
        }
        if (host.tryBind(id, provider)) {
            finishWidgetSetup(id, provider)
            return@registerForActivityResult
        }
        setSuppressHome()
        bindWidgetLauncher.launch(host.createBindIntent(id, provider))
    }

    private val bindWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        clearSuppressHome()
        ensureWidgetHost()
        val host = widgetHost ?: return@registerForActivityResult
        val id = pendingWidgetId
        // If the system already bound the id (pick race) treat as success even when
        // the bind activity returns a non-OK code.
        val alreadyBound = id != AppWidgetManager.INVALID_APPWIDGET_ID &&
            host.providerInfo(id) != null
        if (result.resultCode != Activity.RESULT_OK && !alreadyBound) {
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            requireContext().showToast(getString(R.string.widget_bind_denied))
            return@registerForActivityResult
        }
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return@registerForActivityResult
        }
        finishWidgetSetup(id)
    }

    private val configureWidgetLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        clearSuppressHome()
        ensureWidgetHost()
        val host = widgetHost ?: return@registerForActivityResult
        val id = pendingWidgetId
        if (result.resultCode != Activity.RESULT_OK || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            return@registerForActivityResult
        }
        host.persistBound(id)
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (mode != NotePanelMode.WIDGETS) mode = NotePanelMode.WIDGETS
        render(animateIndicator = false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentNotePanelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Frost the wallpaper behind the panel. The alpha tokens already carry
        // legibility, so this is pure depth: it is what separates "translucent panel"
        // from "actual glass" when the wallpaper has no detail to show through.
        frostWallpaperWhileResumed()
        store = NotePanelStore(requireContext())
        widgetStore = ProductiveWidgetStore(requireContext())
        prefs = Prefs(requireContext())
        widgetHost = ProductiveWidgetHostHelper(requireContext(), widgetStore)
        timerSession = TimerSession(prefs).apply {
            listener = object : TimerSession.Listener {
                override fun onTick(remainingMillis: Long) = renderTimer()
                override fun onFinish() {
                    renderTimer()
                    // The panel is showing its own dialog — stop the service so
                    // it doesn't also show the overlay popup.
                    TimerService.stop(requireContext())
                    showTimerDoneDialog()
                }
            }
        }
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        sourceDirection = savedInstanceState?.getString(KEY_SOURCE_DIRECTION)
            ?: arguments?.getString(Constants.Key.SWIPE_DIRECTION)
        val requested = readMode(savedInstanceState?.getString(KEY_MODE))
            ?: readMode(arguments?.getString(Constants.Key.NOTE_PANEL_MODE))
        mode = prefs.resolveProductiveOpenMode(requested)
        noteDraft = savedInstanceState?.getString(KEY_NOTE_DRAFT).orEmpty()
        todoDraft = savedInstanceState?.getString(KEY_TODO_DRAFT).orEmpty()
        timerSession.restore()

        applyPanelSize()
        initAdapter()
        initSwipeFlow()
        initClickListeners()
        initKeyboardInsets()
        binding.input.setText(draftForMode())
        render()
        binding.modeSwitch.post { positionSegmentIndicator(animate = false) }
    }

    private fun applyPanelSize() {
        val topWeight = Constants.ProductivePanelSize.topSpacerWeight(prefs.productivePanelSize)
        val lp = binding.topSpacer.layoutParams as LinearLayout.LayoutParams
        // Keep spacer tiny / gone so title sits like Muslim Center (top-leading).
        lp.weight = topWeight.coerceAtLeast(0f)
        lp.height = 0
        binding.topSpacer.layoutParams = lp
        binding.topSpacer.visibility =
            if (topWeight <= 0f) View.GONE else View.VISIBLE
        val margin = resources.getDimensionPixelSize(R.dimen.productive_edge_margin)
        (binding.headerRow.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            marginStart = margin
            marginEnd = margin
            // 36dp matches Muslim Center root paddingTop (no double status padding).
            topMargin = resources.getDimensionPixelSize(R.dimen.productive_header_top)
        }
        (binding.bottomChrome.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            marginStart = margin
            marginEnd = margin
        }
        binding.widgetsLayout.setPadding(margin, 0, margin, 0)
    }

    private fun initKeyboardInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            // Muslim Center does not pad status bars on the root — only IME/nav so
            // the title shares the same top inset look (paddingTop 36dp on header).
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(top = 0, bottom = maxOf(ime, nav))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        storeCurrentDraft()
        outState.putString(KEY_MODE, mode.name)
        outState.putString(KEY_NOTE_DRAFT, noteDraft)
        outState.putString(KEY_TODO_DRAFT, todoDraft)
        outState.putString(KEY_SOURCE_DIRECTION, sourceDirection)
        timerSession.snapshot(outState, TimerKeys())
        super.onSaveInstanceState(outState)
    }

    private fun initAdapter() {
        adapter = NotePanelAdapter(
            onNoteClick = ::selectNote,
            onTodoClick = { toggleSelection(it.id) },
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
    }

    private fun initClickListeners() {
        binding.notesTab.setOnClickListener { switchMode(NotePanelMode.NOTES) }
        binding.todoTab.setOnClickListener { switchMode(NotePanelMode.TODO) }
        binding.timerTab.setOnClickListener { switchMode(NotePanelMode.TIMER) }
        binding.widgetsTab.setOnClickListener { switchMode(NotePanelMode.WIDGETS) }
        binding.timerValue.setOnClickListener { showDurationDialog() }
        binding.timerStart.setOnClickListener { startTimer() }
        binding.timerPause.setOnClickListener { togglePauseResume() }
        binding.timerReset.setOnClickListener { resetTimer() }
        binding.send.setOnClickListener { submitInput() }
        binding.addWidgetButton.setOnClickListener { startAddWidget() }
        binding.noteActionDelete.setOnClickListener {
            if (selectedNoteIds.size == 1) deleteSingleSelected() else deleteSelectedNotes()
        }
        binding.noteActionEdit.setOnClickListener {
            if (selectedNoteIds.size == 1) showEditNoteById(selectedNoteIds.first())
        }
        binding.noteActionCopy.setOnClickListener {
            if (selectedNoteIds.size == 1) copySingleSelected()
        }
        binding.noteActionDone.setOnClickListener {
            if (selectedNoteIds.size == 1) {
                if (mode == NotePanelMode.TODO) {
                    store.toggleTodo(selectedNoteIds.first())
                } else {
                    store.toggleNoteDone(selectedNoteIds.first())
                }
                render()
            } else {
                doneSelectedNotes()
            }
        }
        binding.noteActionClose.setOnClickListener { clearNoteSelection() }
        binding.noteActionSelectAll.setOnClickListener {
            val total = if (mode == NotePanelMode.NOTES) store.getNotes().size else store.getTodos().size
            if (total > 0 && selectedNoteIds.size >= total) {
                clearNoteSelection()
            } else {
                selectAllNotes()
            }
        }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitInput()
                true
            } else {
                false
            }
        }
        binding.input.setOnKeyListener { _, keyCode, event ->
            val isSingleLineEnter = keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.isShiftPressed.not() &&
                binding.input.text?.contains('\n') != true
            if (isSingleLineEnter) {
                submitInput()
                true
            } else {
                false
            }
        }
    }

    private fun initSwipeFlow() {
        binding.root.setOnTouchListener(object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                handleHorizontalSwipe(Constants.SwipeDirection.LEFT)
            }

            override fun onSwipeRight() {
                handleHorizontalSwipe(Constants.SwipeDirection.RIGHT)
            }
        })

        val detector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = e1 ?: return false
                val diffX = e2.x - start.x
                val diffY = e2.y - start.y
                if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    handleHorizontalSwipe(
                        if (diffX < 0) Constants.SwipeDirection.LEFT else Constants.SwipeDirection.RIGHT,
                    )
                    return true
                }
                return false
            }
        })

        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
        })
    }

    private fun handleHorizontalSwipe(direction: String) {
        if (direction == sourceDirection) {
            launchSwipeApp(
                context = requireContext(),
                viewModel = viewModel,
                prefs = prefs,
                isLeft = direction == Constants.SwipeDirection.LEFT,
            )
        } else {
            closePanel()
        }
    }

    private fun submitInput() {
        if (mode == NotePanelMode.TIMER || mode == NotePanelMode.WIDGETS) return
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            requireContext().showToast(
                getString(
                    if (mode == NotePanelMode.NOTES) R.string.note_empty_message
                    else R.string.todo_empty_message,
                ),
            )
            return
        }

        if (mode == NotePanelMode.NOTES) {
            store.addNote(text)
            noteDraft = ""
        } else {
            store.addTodo(text)
            todoDraft = ""
        }
        binding.input.text?.clear()
        render(scrollToBottom = true)
    }

    private fun render(scrollToBottom: Boolean = false, animateIndicator: Boolean = false) {
        if (_binding == null) return
        if (!prefs.isProductiveModeEnabled(mode)) {
            mode = prefs.firstEnabledProductiveMode()
        }
        prefs.productiveLastMode = mode.name

        updateModuleTabsVisibility()

        val notes = if (mode == NotePanelMode.NOTES) store.getNotes() else emptyList()
        val todos = if (mode == NotePanelMode.TODO) store.getTodos() else emptyList()
        // Selection is list-mode state only; never carry ids across modules.
        if (mode != NotePanelMode.NOTES && mode != NotePanelMode.TODO && selectedNoteIds.isNotEmpty()) {
            selectedNoteIds.clear()
        }
        val labels = notePanelLabels()
        val rows = when (mode) {
            NotePanelMode.NOTES -> NotePanelRows.noteRows(notes, labels = labels)
            NotePanelMode.TODO -> NotePanelRows.todoRows(todos, labels = labels)
            NotePanelMode.TIMER, NotePanelMode.WIDGETS -> emptyList()
        }
        val hasSelection = selectedNoteIds.isNotEmpty()

        binding.title.text = getString(
            when (mode) {
                NotePanelMode.NOTES -> R.string.notes
                NotePanelMode.TODO -> R.string.todo
                NotePanelMode.TIMER -> R.string.timer
                NotePanelMode.WIDGETS -> R.string.widgets
            },
        )
        binding.input.hint = getString(if (mode == NotePanelMode.NOTES) R.string.write_note else R.string.write_todo)
        binding.send.contentDescription = getString(if (mode == NotePanelMode.NOTES) R.string.send else R.string.add)

        // The segment indicator is a near-opaque fill of the same ink as the labels,
        // so the active tab must flip to inverse ink or it disappears into the pill.
        binding.notesTab.applyGlassInk(mode == NotePanelMode.NOTES)
        binding.todoTab.applyGlassInk(mode == NotePanelMode.TODO)
        binding.timerTab.applyGlassInk(mode == NotePanelMode.TIMER)
        binding.widgetsTab.applyGlassInk(mode == NotePanelMode.WIDGETS)

        val showList = mode == NotePanelMode.NOTES || mode == NotePanelMode.TODO
        binding.recyclerView.isVisible = showList
        // Collapse bottom chrome on Timer/Widgets so minHeight does not leave an empty strip.
        binding.bottomChrome.isVisible = showList
        binding.composer.isVisible = showList && !hasSelection
        binding.noteSelectionCount.text = selectedNoteIds.size.toString()
        val total = if (mode == NotePanelMode.NOTES) store.getNotes().size else store.getTodos().size
        val allSelected = total > 0 && selectedNoteIds.size >= total
        binding.noteActionSelectAll.alpha = if (allSelected) 1.0f else 0.7f
        renderNoteActionBar(hasSelection, selectedNoteIds.size > 1)
        binding.timerLayout.isVisible = mode == NotePanelMode.TIMER
        binding.widgetsLayout.isVisible = mode == NotePanelMode.WIDGETS

        binding.emptyState.text = getString(
            when (mode) {
                NotePanelMode.NOTES -> R.string.no_notes_yet
                NotePanelMode.TODO -> R.string.no_todos_yet
                else -> R.string.no_notes_yet
            },
        )
        binding.emptyState.isVisible = rows.isEmpty() && showList
        adapter.setRows(rows, selectedNoteIds)
        renderTimer()

        if (mode == NotePanelMode.WIDGETS) {
            widgetHost?.startListening()
            renderWidgets()
        } else {
            widgetHost?.stopListening()
            binding.widgetsContainer.removeAllViews()
        }

        binding.modeSwitch.post { positionSegmentIndicator(animate = animateIndicator) }

        if (scrollToBottom && rows.isNotEmpty()) {
            binding.recyclerView.post { binding.recyclerView.scrollToPosition(rows.lastIndex) }
        }
    }

    private fun updateModuleTabsVisibility() {
        binding.notesTab.isVisible = prefs.productiveWidgetNotes
        binding.todoTab.isVisible = prefs.productiveWidgetTodo
        binding.timerTab.isVisible = prefs.productiveWidgetTimer
        binding.widgetsTab.isVisible = prefs.productiveWidgetWidgets
    }

    private fun renderWidgets(retryPending: Boolean = false) {
        ensureWidgetHost()
        val host = widgetHost ?: return
        // Re-adopt system-bound widgets that never made it into the store (e.g. after
        // older builds popped this fragment during the pick flow).
        host.reconcileStoreFromBoundProviders()
        val hasWidgets = widgetStore.getWidgets().isNotEmpty()
        binding.widgetsEmpty.isVisible = !hasWidgets
        binding.widgetsScroll.isVisible = hasWidgets
        binding.widgetsHint.isVisible = hasWidgets
        binding.widgetsContainer.post {
            if (_binding == null || mode != NotePanelMode.WIDGETS) return@post
            host.inflateInto(binding.widgetsContainer) { id -> confirmRemoveWidget(id) }
            val stillHas = widgetStore.getWidgets().isNotEmpty()
            binding.widgetsEmpty.isVisible = !stillHas
            binding.widgetsScroll.isVisible = stillHas
            binding.widgetsHint.isVisible = stillHas
            if (retryPending && stillHas) {
                val needsRetry = widgetStore.getWidgets().any { bound ->
                    host.providerInfo(bound.appWidgetId) == null
                }
                if (needsRetry) {
                    binding.widgetsContainer.postDelayed({
                        if (_binding != null && mode == NotePanelMode.WIDGETS) {
                            renderWidgets(retryPending = false)
                        }
                    }, 350L)
                }
            }
        }
    }

    private fun startAddWidget() {
        ensureWidgetHost()
        val host = widgetHost ?: return
        val id = host.allocateId()
        pendingWidgetId = id
        setSuppressHome()
        runCatching {
            pickWidgetLauncher.launch(host.createPickIntent(id))
        }.onFailure {
            clearSuppressHome()
            host.deleteId(id)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            requireContext().showToast(getString(R.string.widget_add_failed))
        }
    }

    private fun finishWidgetSetup(appWidgetId: Int, knownProvider: ComponentName? = null) {
        ensureWidgetHost()
        val host = widgetHost ?: return
        var info = host.providerInfo(appWidgetId)
        if (info == null && knownProvider != null) {
            // Bind may have succeeded; info can lag a frame on some OEMs.
            host.tryBind(appWidgetId, knownProvider)
            info = host.providerInfo(appWidgetId)
        }
        if (info == null) {
            // Persist provider string if we know it so restore can rebind later.
            if (knownProvider != null && host.persistBound(appWidgetId, knownProvider)) {
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                if (mode != NotePanelMode.WIDGETS) mode = NotePanelMode.WIDGETS
                render(animateIndicator = false)
                return
            }
            host.deleteId(appWidgetId)
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            requireContext().showToast(getString(R.string.widget_add_failed))
            return
        }
        if (host.needsConfigure(info)) {
            pendingWidgetId = appWidgetId
            setSuppressHome()
            runCatching {
                configureWidgetLauncher.launch(host.createConfigureIntent(appWidgetId, info))
            }.onFailure {
                clearSuppressHome()
                host.persistBound(appWidgetId, knownProvider ?: info.provider)
                pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
                if (mode != NotePanelMode.WIDGETS) mode = NotePanelMode.WIDGETS
                render(animateIndicator = false)
            }
            return
        }
        host.persistBound(appWidgetId, knownProvider ?: info.provider)
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (mode != NotePanelMode.WIDGETS) mode = NotePanelMode.WIDGETS
        render(animateIndicator = false)
    }

    private fun ensureWidgetHost() {
        if (widgetHost == null && _binding != null) {
            if (!::widgetStore.isInitialized) {
                widgetStore = ProductiveWidgetStore(requireContext())
            }
            widgetHost = ProductiveWidgetHostHelper(requireContext(), widgetStore)
        }
    }

    private fun setSuppressHome() {
        (activity as? MainActivity)?.suppressHomeOnBackground = true
    }

    private fun clearSuppressHome() {
        (activity as? MainActivity)?.suppressHomeOnBackground = false
    }

    @Suppress("DEPRECATION")
    private fun readProviderExtra(data: android.content.Intent?): ComponentName? {
        if (data == null) return null
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, ComponentName::class.java)
        } else {
            data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER) as? ComponentName
        }
    }

    private fun confirmRemoveWidget(appWidgetId: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_app_input, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = getString(R.string.remove_widget)
        // No text field for confirm-only dialogs — hide empty input container.
        view.findViewById<LinearLayout>(R.id.dialogInputContainer).isVisible = false
        val positive = view.findViewById<TextView>(R.id.dialogPositive)
        val negative = view.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.remove)
        negative.setText(R.string.close)
        val dialog = AppDialog.create(requireContext(), view, widthScale = prefs.productiveDialogWidthScale)
        negative.setOnClickListener { dialog.dismiss() }
        positive.setOnClickListener {
            widgetHost?.deleteId(appWidgetId)
            requireContext().showToast(getString(R.string.widget_removed))
            renderWidgets()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun renderNoteActionBar(isSelected: Boolean, isMultiSelect: Boolean) {
        val bar = binding.noteActionBarScroll
        if (isSelected) {
            binding.noteActionDelete.isVisible = true
            binding.noteActionEdit.isVisible = !isMultiSelect
            binding.noteActionCopy.isVisible = !isMultiSelect
            binding.noteActionDone.isVisible = true
            if (bar.isVisible.not()) {
                bar.alpha = 0f
                bar.translationY = dp(18).toFloat()
                bar.scaleX = 0.96f
                bar.scaleY = 0.96f
                bar.isVisible = true
            }
            bar.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(190L)
                .setInterpolator(smoothInterpolator)
                .start()
        } else if (bar.isVisible) {
            bar.animate()
                .alpha(0f)
                .translationY(dp(18).toFloat())
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(150L)
                .setInterpolator(smoothInterpolator)
                .withEndAction {
                    if (selectedNoteIds.isEmpty()) bar.isVisible = false
                }
                .start()
        }
    }

    private fun toggleSelection(id: String) {
        if (id in selectedNoteIds) selectedNoteIds.remove(id) else selectedNoteIds.add(id)
        binding.input.hideKeyboard()
        render()
    }

    private fun selectNote(note: NoteMessage) {
        toggleSelection(note.id)
    }

    private fun clearNoteSelection() {
        selectedNoteIds.clear()
        render()
    }

    private fun deleteSingleSelected() {
        val id = selectedNoteIds.first()
        if (mode == NotePanelMode.TODO) store.deleteTodo(id) else store.deleteNote(id)
        selectedNoteIds.remove(id)
        render()
    }

    private fun showEditNoteById(id: String) {
        if (mode == NotePanelMode.TODO) {
            val todo = store.getTodos().firstOrNull { it.id == id } ?: return
            showEditDialog(
                title = getString(R.string.edit_note),
                initialText = todo.text,
                emptyMessage = getString(R.string.todo_empty_message),
            ) { text ->
                store.updateTodo(id, text)
                selectedNoteIds.remove(id)
                render()
            }
            return
        }
        val note = store.getNotes().firstOrNull { it.id == id } ?: return
        showEditDialog(
            title = getString(R.string.edit_note),
            initialText = note.text,
            emptyMessage = getString(R.string.note_empty_message),
        ) { text ->
            store.updateNote(id, text)
            selectedNoteIds.remove(id)
            render()
        }
    }

    private fun copySingleSelected() {
        val id = selectedNoteIds.first()
        val text = if (mode == NotePanelMode.TODO) {
            store.getTodos().firstOrNull { it.id == id }?.text
        } else {
            store.getNotes().firstOrNull { it.id == id }?.text
        } ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.notes), text))
        requireContext().showToast(getString(R.string.copied))
    }

    private fun selectAllNotes() {
        val allIds = if (mode == NotePanelMode.NOTES) {
            store.getNotes().sortedByDescending { it.createdAtMillis }.map { it.id }
        } else {
            store.getTodos().sortedByDescending { it.createdAtMillis }.map { it.id }
        }
        selectedNoteIds.clear()
        selectedNoteIds.addAll(allIds)
        binding.input.hideKeyboard()
        render()
    }

    private fun deleteSelectedNotes() {
        if (mode == NotePanelMode.TODO) {
            store.deleteTodos(selectedNoteIds)
        } else {
            store.deleteNotes(selectedNoteIds)
        }
        selectedNoteIds.clear()
        render()
    }

    private fun doneSelectedNotes() {
        if (mode == NotePanelMode.TODO) {
            store.toggleTodosDone(selectedNoteIds)
        } else {
            store.toggleNotesDone(selectedNoteIds)
        }
        selectedNoteIds.clear()
        render()
    }

    private fun switchMode(nextMode: NotePanelMode) {
        if (!prefs.isProductiveModeEnabled(nextMode)) return
        if (mode == nextMode) return
        storeCurrentDraft()
        mode = nextMode
        selectedNoteIds.clear()
        if (mode == NotePanelMode.TIMER || mode == NotePanelMode.WIDGETS) binding.input.hideKeyboard()
        binding.input.setText(draftForMode())
        binding.input.setSelection(binding.input.text?.length ?: 0)

        // Move the indicator and the ink IMMEDIATELY, then cross-fade the body under it.
        // The previous version fully faded the content out (90ms), re-rendered, and faded
        // back in (150ms) — so the indicator did not start moving until 90ms after the
        // tap, and the whole switch took 240ms of dead time. The chrome should respond on
        // the same frame as the touch; only the content needs to cross-fade.
        renderChromeForModeChange()

        val content = binding.contentFrame
        content.animate().cancel()
        content.animate()
            .alpha(0f)
            .translationY(dp(6).toFloat())
            .setDuration(80L)
            .setInterpolator(smoothInterpolator)
            .withEndAction {
                if (_binding == null) return@withEndAction
                render(animateIndicator = false)
                content.alpha = 0f
                content.translationY = dp(-6).toFloat()
                content.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(160L)
                    .setInterpolator(smoothInterpolator)
                    .start()
            }
            .start()
    }

    /**
     * The parts of [render] that must land on the touch frame: the segment indicator and
     * the tab ink. Everything else can wait for the content cross-fade.
     */
    private fun renderChromeForModeChange() {
        binding.notesTab.applyGlassInk(mode == NotePanelMode.NOTES)
        binding.todoTab.applyGlassInk(mode == NotePanelMode.TODO)
        binding.timerTab.applyGlassInk(mode == NotePanelMode.TIMER)
        binding.widgetsTab.applyGlassInk(mode == NotePanelMode.WIDGETS)
        positionSegmentIndicator(animate = true)
    }

    private fun positionSegmentIndicator(animate: Boolean) {
        if (_binding == null) return
        val active = when (mode) {
            NotePanelMode.NOTES -> binding.notesTab
            NotePanelMode.TODO -> binding.todoTab
            NotePanelMode.TIMER -> binding.timerTab
            NotePanelMode.WIDGETS -> binding.widgetsTab
        }
        if (!active.isVisible) return

        fun applyPosition() {
            if (_binding == null || !active.isVisible) return
            val tabWidth = active.width.takeIf { it > 0 }
                ?: resources.getDimensionPixelSize(R.dimen.productive_segment_tab_width)
            binding.segmentIndicator.layoutParams = binding.segmentIndicator.layoutParams.apply {
                width = tabWidth
            }
            // translationX is a delta from the view's OWN laid-out position, not an
            // absolute coordinate in the parent. The indicator and segmentTabs are both
            // direct children of modeSwitch, so they already share its 4dp padding —
            // adding segmentTabs.left on top of that pushed the capsule 4dp to the
            // right of its label on every tab (measured: thumb at x=805 for a tab at
            // x=794), which also clipped it against the track's right edge.
            val target = (binding.segmentTabs.left + active.left - binding.segmentIndicator.left)
                .toFloat()
            binding.segmentIndicator.animate().cancel()
            if (animate) {
                // Squash along the travel axis and recover: the capsule reads as being
                // carried across rather than teleported, which is what makes a slide
                // feel like one gesture instead of two states. Kept subtle (4%) — this
                // is a 200ms move over ~60dp, so anything larger looks like a wobble.
                val distance = kotlin.math.abs(target - binding.segmentIndicator.translationX)
                val stretch = if (distance > 1f) 1.04f else 1f
                binding.segmentIndicator.animate()
                    .translationX(target)
                    .scaleX(stretch)
                    .setDuration(180L)
                    .setInterpolator(smoothInterpolator)
                    .withEndAction {
                        if (_binding == null) return@withEndAction
                        binding.segmentIndicator.animate()
                            .scaleX(1f)
                            .setDuration(120L)
                            .setInterpolator(smoothInterpolator)
                            .start()
                    }
                    .start()
            } else {
                binding.segmentIndicator.translationX = target
                binding.segmentIndicator.scaleX = 1f
            }
        }

        if (active.width <= 0 || binding.segmentTabs.width <= 0) {
            binding.modeSwitch.post { applyPosition() }
        } else {
            applyPosition()
        }
    }

    private fun storeCurrentDraft() {
        val text = binding.input.text?.toString().orEmpty()
        when (mode) {
            NotePanelMode.NOTES -> noteDraft = text
            NotePanelMode.TODO -> todoDraft = text
            NotePanelMode.TIMER, NotePanelMode.WIDGETS -> Unit
        }
    }

    private fun draftForMode(): String {
        return when (mode) {
            NotePanelMode.NOTES -> noteDraft
            NotePanelMode.TODO -> todoDraft
            NotePanelMode.TIMER, NotePanelMode.WIDGETS -> ""
        }
    }

    private fun closePanel() {
        binding.input.hideKeyboard()
        runCatching {
            if (findNavController().popBackStack().not()) {
                findNavController().navigate(R.id.mainFragment)
            }
        }
    }

    private fun startTimer() {
        timerSession.start()
        TimerService.start(requireContext())
        renderTimer()
    }

    private fun togglePauseResume() {
        if (timerSession.isRunning) {
            timerSession.pause()
            TimerService.stop(requireContext())
        } else {
            timerSession.resume()
            TimerService.start(requireContext())
        }
        renderTimer()
    }

    private fun resetTimer() {
        timerSession.reset()
        TimerService.stop(requireContext())
        renderTimer()
    }

    private fun showTimerDoneDialog() {
        if (_binding == null) return
        val view = layoutInflater.inflate(R.layout.dialog_timer_done, null)
        view.findViewById<TextView>(R.id.dialogTitle).setText(R.string.timer_done_title)
        view.findViewById<TextView>(R.id.dialogMessage).setText(R.string.timer_done_message)
        val positive = view.findViewById<TextView>(R.id.dialogPositive)
        val negative = view.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.timer_add_time)
        negative.setText(R.string.close)

        val dialog = AppDialog.create(
            requireContext(),
            view,
            widthScale = prefs.productiveDialogWidthScale,
        )
        // Gentle chime so the finished timer is noticeable even if the panel is not in view.
        SoundEffects.mindfulDone(requireContext())
        negative.setOnClickListener {
            dialog.dismiss()
            // Timer already reset in onFinish; nothing else to do.
        }
        positive.setOnClickListener {
            dialog.dismiss()
            showDurationDialog()
        }
        dialog.setOnDismissListener { timerSession.release() }
        dialog.show()
    }

    private fun renderTimer() {
        if (_binding == null) return
        val total = timerSession.totalMillis.coerceAtLeast(1L)
        val completed = 1f - (timerSession.remainingMillis.toFloat() / total.toFloat())
        binding.timerProgress.progress = completed
        binding.timerValue.text = formatTimer(timerSession.remainingMillis)
        binding.timerStart.isVisible = timerSession.isRunning.not()
        binding.timerControls.isVisible = timerSession.isRunning || timerSession.remainingMillis != timerSession.totalMillis
        binding.timerPause.text = getString(if (timerSession.isRunning) R.string.pause else R.string.resume)
    }

    private fun showDurationDialog() {
        val context = requireContext()
        val currentSeconds = timerSession.remainingSeconds
        val minutesInput = AppCompatEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.minutes)
            setText((currentSeconds / 60L).toString())
            setSelectAllOnFocus(true)
            setBackgroundResource(R.drawable.bg_note_composer)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        val secondsInput = AppCompatEditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.seconds)
            setText((currentSeconds % 60L).toString())
            setSelectAllOnFocus(true)
            setBackgroundResource(R.drawable.bg_note_composer)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }

        val view = layoutInflater.inflate(R.layout.dialog_app_input, null)
        view.findViewById<TextView>(R.id.dialogTitle).setText(R.string.duration)
        val container = view.findViewById<LinearLayout>(R.id.dialogInputContainer)
        container.addView(minutesInput)
        container.addView(secondsInput)
        val positive = view.findViewById<TextView>(R.id.dialogPositive)
        val negative = view.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.save)
        negative.setText(R.string.close)

        val dialog = AppDialog.create(
            context,
            view,
            widthScale = prefs.productiveDialogWidthScale,
            onShow = { minutesInput.showKeyboard() },
        )
        negative.setOnClickListener { dialog.dismiss() }
        positive.setOnClickListener {
            val minutes = minutesInput.text?.toString()?.toIntOrNull()?.coerceIn(0, 999) ?: 0
            val seconds = secondsInput.text?.toString()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            if (minutes == 0 && seconds == 0) {
                context.showToast(getString(R.string.timer_duration_empty))
                return@setOnClickListener
            }
            timerSession.setDuration(minutes, seconds)
            // A freshly-set duration isn't running — stop any foreground countdown.
            TimerService.stop(requireContext())
            renderTimer()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatTimer(millis: Long): String {
        val totalSeconds = ((millis.coerceAtLeast(0L) + 999L) / 1000L)
        return String.format(Locale.getDefault(), "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun notePanelLabels(): NotePanelRows.Labels = NotePanelRows.Labels(
        pinned = getString(R.string.notes_section_pinned),
        active = getString(R.string.todo_section_active),
        done = getString(R.string.todo_section_done),
        today = getString(R.string.today),
        yesterday = getString(R.string.yesterday),
        lastFormat = getString(R.string.note_last_format),
    )

    private fun readMode(value: String?): NotePanelMode? {
        return value?.let { runCatching { NotePanelMode.valueOf(it) }.getOrNull() }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun showEditDialog(
        title: String,
        initialText: String,
        emptyMessage: String,
        onSave: (String) -> Unit,
    ) {
        val input = AppCompatEditText(requireContext()).apply {
            setText(initialText)
            setSelectAllOnFocus(true)
            maxLines = 4
            minLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setBackgroundResource(R.drawable.bg_note_composer)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }

        val view = layoutInflater.inflate(R.layout.dialog_app_input, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<LinearLayout>(R.id.dialogInputContainer).addView(input)
        val positive = view.findViewById<TextView>(R.id.dialogPositive)
        val negative = view.findViewById<TextView>(R.id.dialogNegative)
        positive.setText(R.string.save)
        negative.setText(R.string.close)

        val dialog = AppDialog.create(
            requireContext(),
            view,
            widthScale = prefs.productiveDialogWidthScale,
            onShow = { input.showKeyboard() },
        )
        negative.setOnClickListener { dialog.dismiss() }
        positive.setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) {
                requireContext().showToast(emptyMessage)
                return@setOnClickListener
            }
            onSave(text)
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onStop() {
        // Persist a paused/idle session so it survives leaving the panel; a running
        // timer keeps its end-time in prefs (set at start) and continues.
        if (!timerSession.isRunning) timerSession.pause()
        binding.input.hideKeyboard()
        widgetHost?.stopListening()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        applyPanelSize()
        if (mode == NotePanelMode.WIDGETS) {
            ensureWidgetHost()
            widgetHost?.startListening()
            renderWidgets(retryPending = true)
        }
    }

    override fun onDestroyView() {
        timerSession.release()
        clearSuppressHome()
        widgetHost?.destroy()
        widgetHost = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_MODE = "note_panel_mode"
        private const val KEY_NOTE_DRAFT = "note_panel_note_draft"
        private const val KEY_TODO_DRAFT = "note_panel_todo_draft"
        private const val KEY_SOURCE_DIRECTION = "note_panel_source_direction"
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 100
    }
}

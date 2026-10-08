package app.sakinalauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import app.sakinalauncher.R
import app.sakinalauncher.data.BoundWidget
import app.sakinalauncher.data.ProductiveWidgetStore
import app.sakinalauncher.data.WidgetSizeMath
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lifecycle wrapper around [AppWidgetHost] for the Productive Widgets tab.
 *
 * Layout rules:
 * - Default size follows the provider's minWidth × minHeight (pixels).
 * - Wide providers (search bars) expand to full panel width, height keeps aspect.
 * - Compact/square providers keep native size so content is not clipped/stretched.
 * - Side-by-side packing when sizes fit; otherwise stack.
 * - Resize updates layout **live while dragging**.
 * - Remove / resize chrome only after long-press.
 */
class ProductiveWidgetHostHelper(
    private val context: Context,
    private val store: ProductiveWidgetStore,
) {
    private val appContext = context.applicationContext
    private val appWidgetManager = AppWidgetManager.getInstance(appContext)
    val host = AppWidgetHost(appContext, HOST_ID)

    private var listening = false
    private var activeEditFrame: ResizableWidgetFrame? = null
    private var flowLayout: WidgetFlowLayout? = null
    private var observedContainer: ViewGroup? = null
    private var containerLayoutListener: View.OnLayoutChangeListener? = null
    private var lastContainerWidthPx: Int = 0
    private var storeReconciled = false

    /**
     * Fingerprint of the currently inflated flow: container width plus every card's
     * id and resolved size. [inflateInto] is called from every panel render, and
     * rebuilding meant tearing down live [AppWidgetHostView]s (each one re-inflates a
     * RemoteViews tree from the provider process) to produce an identical result.
     */
    private var renderedSignature: String? = null

    fun startListening() {
        if (listening) return
        runCatching {
            host.startListening()
            listening = true
        }
    }

    fun stopListening() {
        if (!listening) return
        runCatching { host.stopListening() }
        listening = false
        clearEditMode()
    }

    fun destroy() {
        stopListening()
        containerLayoutListener?.let { listener ->
            observedContainer?.removeOnLayoutChangeListener(listener)
        }
        containerLayoutListener = null
        observedContainer = null
        flowLayout = null
        lastContainerWidthPx = 0
        renderedSignature = null
    }

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun deleteId(appWidgetId: Int) {
        runCatching { host.deleteAppWidgetId(appWidgetId) }
        store.removeWidget(appWidgetId)
        renderedSignature = null
    }

    fun installedProviders(): List<AppWidgetProviderInfo> {
        // Load each label once, then sort the pairs. loadLabel() is a PackageManager
        // call; inside the comparator it ran O(n log n) times instead of O(n).
        val packageManager = appContext.packageManager
        return appWidgetManager.installedProviders.orEmpty()
            .map { it to it.loadLabel(packageManager)?.toString().orEmpty() }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun createPickIntent(appWidgetId: Int): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, arrayListOf<AppWidgetProviderInfo>())
        }
    }

    fun createBindIntent(appWidgetId: Int, provider: ComponentName): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
        }
    }

    fun tryBind(appWidgetId: Int, provider: ComponentName): Boolean {
        return runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
        }.getOrDefault(false)
    }

    fun providerInfo(appWidgetId: Int): AppWidgetProviderInfo? =
        appWidgetManager.getAppWidgetInfo(appWidgetId)

    fun needsConfigure(info: AppWidgetProviderInfo?): Boolean {
        return info?.configure != null
    }

    fun createConfigureIntent(appWidgetId: Int, info: AppWidgetProviderInfo): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = info.configure
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
    }

    fun persistBound(
        appWidgetId: Int,
        provider: ComponentName? = null,
        heightDp: Int = 0,
        widthDp: Int = 0,
    ): Boolean {
        val info = providerInfo(appWidgetId)
        val flattened = info?.provider?.flattenToString()
            ?: provider?.flattenToString()
            ?: return false
        // widthDp 0 = full panel width (stacked by default).
        store.addWidget(BoundWidget(appWidgetId, flattened, heightDp, widthDp))
        return true
    }

    fun reconcileStoreFromBoundProviders() {
        if (storeReconciled) return
        storeReconciled = true
        val known = store.getWidgets().map { it.appWidgetId }.toMutableSet()
        val additions = mutableListOf<BoundWidget>()

        // API 26+: the host tells us exactly which ids it owns. One call, no probing.
        val ownedIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { host.appWidgetIds }.getOrNull()
        } else {
            null
        }

        if (ownedIds != null) {
            for (id in ownedIds) {
                if (id == AppWidgetManager.INVALID_APPWIDGET_ID || id in known) continue
                val boundInfo = appWidgetManager.getAppWidgetInfo(id) ?: continue
                val flat = boundInfo.provider?.flattenToString() ?: continue
                additions.add(BoundWidget(id, flat, 0, 0))
                known.add(id)
            }
        } else {
            // API 24-25 fallback: probe by creating a host view. Expensive, so it is
            // confined to the two API levels that have no getAppWidgetIds().
            for (info in installedProviders()) {
                val provider = info.provider ?: continue
                val ids = runCatching { appWidgetManager.getAppWidgetIds(provider) }.getOrNull() ?: continue
                for (id in ids) {
                    if (id == AppWidgetManager.INVALID_APPWIDGET_ID || id in known) continue
                    val boundInfo = appWidgetManager.getAppWidgetInfo(id) ?: continue
                    val ownsId = runCatching {
                        val view = host.createView(appContext, id, boundInfo)
                        (view.parent as? ViewGroup)?.removeView(view)
                        true
                    }.getOrDefault(false)
                    if (!ownsId) continue
                    val flat = boundInfo.provider?.flattenToString() ?: provider.flattenToString()
                    additions.add(BoundWidget(id, flat, 0, 0))
                    known.add(id)
                }
            }
        }

        if (additions.isNotEmpty()) {
            store.setWidgets(store.getWidgets() + additions)
        }
    }

    fun clearEditMode() {
        activeEditFrame?.setEditMode(false)
        activeEditFrame = null
    }

    /**
     * Rebuild host views into a flow that packs left→right and wraps when needed.
     * Full-width cards always take their own row; narrower ones share a row when they fit.
     */
    fun inflateInto(container: ViewGroup, onRemove: (Int) -> Unit) {
        startListening()

        val density = appContext.resources.displayMetrics.density
        val gapPx = appContext.resources.getDimensionPixelSize(R.dimen.productive_widget_gap)
        // Resolve the width BEFORE tearing down existing views: bailing out after
        // removeAllViews() used to leave the panel empty until the next re-render.
        val containerWidthPx = container.width.takeIf { it > 0 }
            ?: container.measuredWidth.takeIf { it > 0 }
        if (containerWidthPx == null) {
            // Not measured yet — keep current views and retry once layout settles.
            observeContainerWidth(container, onRemove)
            container.post {
                if (container.isAttachedToWindow && container.width > 0) {
                    inflateInto(container, onRemove)
                }
            }
            return
        }
        val maxWidthDp = (containerWidthPx / density).roundToInt().coerceAtLeast(1)

        // Pass 1: resolve what the flow should contain. Pure lookups and math — no
        // views are created, so an unchanged panel costs nothing but this pass.
        val kept = mutableListOf<BoundWidget>()
        val plan = mutableListOf<PlannedCard>()
        val stored = store.getWidgets()
        for (bound in stored) {
            val info = appWidgetManager.getAppWidgetInfo(bound.appWidgetId)
            if (info == null) {
                val pkg = bound.providerComponent()?.packageName
                val stillInstalled = pkg != null && runCatching {
                    appContext.packageManager.getApplicationInfo(pkg, 0)
                    true
                }.getOrDefault(false)
                if (!stillInstalled) {
                    runCatching { host.deleteAppWidgetId(bound.appWidgetId) }
                } else {
                    kept.add(bound)
                }
                continue
            }
            kept.add(bound)
            val (widthPx, heightPx) = resolveHostSizePx(
                info = info,
                bound = bound,
                containerWidthPx = containerWidthPx,
                density = density,
            )
            plan.add(PlannedCard(bound, info, widthPx, heightPx))
        }

        if (kept.size != stored.size) {
            store.setWidgets(kept)
        }

        // Nothing about the panel changed — keep the live host views. Rebuilding here
        // is what made every note/todo/timer render re-inflate the whole widget tab.
        val signature = buildSignature(containerWidthPx, plan)
        val existingFlow = flowLayout
        if (signature == renderedSignature &&
            existingFlow != null &&
            existingFlow.parent === container &&
            existingFlow.childCount == plan.size
        ) {
            observeContainerWidth(container, onRemove)
            lastContainerWidthPx = containerWidthPx
            return
        }

        clearEditMode()
        container.removeAllViews()
        observeContainerWidth(container, onRemove)
        lastContainerWidthPx = containerWidthPx

        val flow = WidgetFlowLayout(appContext).apply {
            this.gapPx = gapPx
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        flowLayout = flow

        for (planned in plan) {
            val widthDp = (planned.widthPx / density).roundToInt().coerceAtLeast(1)
            val heightDp = (planned.heightPx / density).roundToInt().coerceAtLeast(1)
            val card = createCard(
                bound = planned.bound,
                info = planned.info,
                widthPx = planned.widthPx,
                heightPx = planned.heightPx,
                widthDp = widthDp,
                heightDp = heightDp,
                density = density,
                containerWidthPx = containerWidthPx,
                maxWidthDp = maxWidthDp,
                onRemove = onRemove,
            )
            flow.addView(card, WidgetFlowLayout.LayoutParams(planned.widthPx, planned.heightPx))
        }
        container.addView(flow)
        renderedSignature = signature
    }

    private class PlannedCard(
        val bound: BoundWidget,
        val info: AppWidgetProviderInfo,
        val widthPx: Int,
        val heightPx: Int,
    )

    private fun buildSignature(containerWidthPx: Int, plan: List<PlannedCard>): String {
        return buildString {
            append(containerWidthPx)
            for (card in plan) {
                append('|').append(card.bound.appWidgetId)
                append(':').append(card.widthPx)
                append('x').append(card.heightPx)
            }
        }
    }

    /**
     * Re-derive the signature after a reorder or a committed resize, so the next render
     * recognises the live flow as already-correct instead of rebuilding it. The live
     * views and the store agree at this point; only the cached fingerprint is stale.
     */
    private fun syncSignatureToStore() {
        val width = lastContainerWidthPx
        val flow = flowLayout
        if (width <= 0 || flow == null) {
            renderedSignature = null
            return
        }
        val density = appContext.resources.displayMetrics.density
        val plan = mutableListOf<PlannedCard>()
        for (bound in store.getWidgets()) {
            val info = appWidgetManager.getAppWidgetInfo(bound.appWidgetId) ?: continue
            val (w, h) = resolveHostSizePx(info, bound, width, density)
            plan.add(PlannedCard(bound, info, w, h))
        }
        renderedSignature = if (plan.size == flow.childCount) buildSignature(width, plan) else null
    }

    private fun observeContainerWidth(container: ViewGroup, onRemove: (Int) -> Unit) {
        if (observedContainer === container && containerLayoutListener != null) return
        containerLayoutListener?.let { observedContainer?.removeOnLayoutChangeListener(it) }
        val listener = View.OnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            val width = right - left
            val oldWidth = oldRight - oldLeft
            // Height-only changes (what a resize produces) must NOT rebuild the cards.
            if (oldWidth > 0 && WidgetSizeMath.shouldRebuildForWidth(width, lastContainerWidthPx) &&
                width != oldWidth
            ) {
                view.post {
                    if (view.isAttachedToWindow && view.width == width) {
                        inflateInto(container, onRemove)
                    }
                }
            }
        }
        observedContainer = container
        containerLayoutListener = listener
        container.addOnLayoutChangeListener(listener)
    }

    private fun moveCardBy(frame: ResizableWidgetFrame, offset: Int) {
        val layout = flowLayout ?: return
        val from = layout.indexOfChild(frame)
        val target = (from + offset).coerceIn(0, layout.childCount - 1)
        if (from < 0 || target == from) return
        layout.removeViewAt(from)
        layout.addView(frame, target)
        val current = store.getWidgets().associateBy { it.appWidgetId }
        store.setWidgets((0 until layout.childCount).mapNotNull {
            (layout.getChildAt(it).tag as? Int)?.let(current::get)
        })
        // The live flow already reflects the new order; keep the fingerprint in step so
        // the next render does not rebuild every host view just to reproduce it.
        syncSignatureToStore()
    }

    private fun createCard(
        bound: BoundWidget,
        info: AppWidgetProviderInfo,
        widthPx: Int,
        heightPx: Int,
        widthDp: Int,
        heightDp: Int,
        density: Float,
        containerWidthPx: Int,
        maxWidthDp: Int,
        onRemove: (Int) -> Unit,
    ): ResizableWidgetFrame {
        val hostView = host.createView(appContext, bound.appWidgetId, info)
        hostView.setAppWidget(bound.appWidgetId, info)
        applyWidgetOptions(bound.appWidgetId, widthDp, heightDp, maxWidthDp)

        val pad = (7 * density).roundToInt()
        val removeSize = (32 * density).roundToInt()
        val handleWidth = (52 * density).roundToInt()
        val handleHeight = (24 * density).roundToInt()
        val chromeColor = themedColor(R.attr.primaryColorInverseTrans80, Color.argb(190, 20, 20, 24))
        val chromeInk = themedColor(R.attr.primaryColor, Color.WHITE)
        val (resizeMinW, resizeMinH) = providerResizeMinSizePx(info)
        val maxHeightPxValue = (MAX_HEIGHT_DP * density).roundToInt()

        val wrap = ResizableWidgetFrame(appContext).apply {
            tag = bound.appWidgetId
            clipChildren = true
            clipToPadding = true
            this.hostView = hostView
            minWidthPx = resizeMinW.coerceAtLeast((MIN_WIDTH_DP * density).roundToInt())
                .coerceAtMost(containerWidthPx)
            maxWidthPx = containerWidthPx
            minHeightPx = resizeMinH.coerceAtLeast((MIN_HEIGHT_DP * density).roundToInt())
                .coerceAtMost(maxHeightPxValue)
            maxHeightPx = maxHeightPxValue
            onEnterEdit = {
                if (activeEditFrame !== this) {
                    activeEditFrame?.setEditMode(false)
                    activeEditFrame = this
                }
                setEditMode(true)
            }
            onExitEdit = {
                if (activeEditFrame === this) activeEditFrame = null
            }
            // Keep drag feedback local. Provider/Binder updates are sent once on commit.
            onSizeLive = null
            onSizeCommitted = { _, hPx ->
                val wDp = maxWidthDp
                val hDp = WidgetSizeMath.commitHeightDp(hPx, density)
                store.updateSize(bound.appWidgetId, wDp, hDp)
                applyWidgetOptions(bound.appWidgetId, wDp, hDp, maxWidthDp)
                // The card is already at the dragged size; refresh the fingerprint so
                // the next render keeps it instead of rebuilding the whole flow.
                syncSignatureToStore()
            }
            addView(
                hostView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val removeBtn = ImageButton(appContext).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = appContext.getString(R.string.remove_widget)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(chromeColor)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            elevation = 6 * density
            visibility = View.GONE
            // Keep clicks on the button; never fall through to the host.
            isClickable = true
            isFocusable = true
            setOnClickListener {
                clearEditMode()
                onRemove(bound.appWidgetId)
            }
        }
        wrap.removeButton = removeBtn
        wrap.addView(removeBtn, FrameLayout.LayoutParams(removeSize, removeSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (6 * density).roundToInt()
            marginEnd = (6 * density).roundToInt()
        })

        fun moveButton(iconRes: Int, description: String, offset: Int): ImageButton = ImageButton(appContext).apply {
            setImageResource(iconRes)
            contentDescription = description
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(chromeColor)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            elevation = 4 * density
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { moveCardBy(wrap, offset) }
        }
        val upButton = moveButton(R.drawable.ic_arrow_up, "Move widget up", -1)
        val downButton = moveButton(R.drawable.ic_arrow_down, "Move widget down", 1)
        wrap.addView(upButton, FrameLayout.LayoutParams(removeSize, removeSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (6 * density).roundToInt()
            marginEnd = (44 * density).roundToInt()
        })
        wrap.addView(downButton, FrameLayout.LayoutParams(removeSize, removeSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (6 * density).roundToInt()
            marginEnd = (82 * density).roundToInt()
        })
        wrap.editButtons = listOf(removeBtn, upButton, downButton)

        val resizeHandle = ResizeHandleView(appContext).apply {
            contentDescription = appContext.getString(R.string.resize_widget)
            elevation = 6 * density
            visibility = View.GONE
            isClickable = true
        }
        wrap.attachResizeHandle(resizeHandle)
        wrap.addView(
            resizeHandle,
            FrameLayout.LayoutParams(handleWidth, handleHeight).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (6 * density).roundToInt()
            },
        )

        return wrap
    }

    private fun themedColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun applyWidgetOptions(
        appWidgetId: Int,
        widthDp: Int,
        heightDp: Int,
        maxWidthDp: Int,
    ) {
        // OPTION_* values must be in **dp**, not px.
        val w = widthDp.coerceIn(1, maxWidthDp)
        val h = heightDp.coerceIn(1, MAX_HEIGHT_DP)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    arrayListOf(SizeF(w.toFloat(), h.toFloat())),
                )
            }
        }
        runCatching { appWidgetManager.updateAppWidgetOptions(appWidgetId, options) }
    }

    /**
     * [AppWidgetProviderInfo.minWidth]/[minHeight] are **pixels** (loaded via
     * getDimensionPixelSize). Dumpsys may print raw complex TypedValues; handle both.
     */
    private fun providerMinSizePx(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val metrics = appContext.resources.displayMetrics
        fun toPx(raw: Int): Int {
            if (raw <= 0) return 0
            // Unconverted complex dimension (dumpsys-style, typically > 10_000).
            if (raw > 10_000) {
                return runCatching {
                    TypedValue.complexToDimensionPixelSize(raw, metrics)
                }.getOrDefault(0)
            }
            return raw
        }
        var w = toPx(info.minWidth)
        var h = toPx(info.minHeight)
        // API 31+: target cells ≈ 70dp each when min is missing/zero.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (w <= 0 && info.targetCellWidth > 0) {
                w = (info.targetCellWidth * 70 * metrics.density).roundToInt()
            }
            if (h <= 0 && info.targetCellHeight > 0) {
                h = (info.targetCellHeight * 70 * metrics.density).roundToInt()
            }
        }
        return w to h
    }

    private fun providerResizeMinSizePx(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val (defaultWidth, defaultHeight) = providerMinSizePx(info)
        val resizeWidth = info.minResizeWidth.takeIf { it > 0 } ?: defaultWidth
        val resizeHeight = info.minResizeHeight.takeIf { it > 0 } ?: defaultHeight
        return resizeWidth to resizeHeight
    }

    /**
     * Host frame size in **pixels**.
     * - User override: stored widthDp/heightDp.
     * - Default: provider min size; wide widgets fill panel width (aspect kept).
     */
    private fun resolveHostSizePx(
        info: AppWidgetProviderInfo,
        bound: BoundWidget,
        containerWidthPx: Int,
        density: Float,
    ): Pair<Int, Int> {
        val maxHpx = (MAX_HEIGHT_DP * density).roundToInt()
        val minWpx = (MIN_WIDTH_DP * density).roundToInt()
        val minHpx = (MIN_HEIGHT_DP * density).roundToInt()

        // Explicit user resize wins.
        WidgetSizeMath.resolveHeightPx(bound.heightDp, density)?.let { stored ->
            return containerWidthPx to stored
        }

        val (provW, provH) = providerMinSizePx(info)
        val pW = provW.coerceAtLeast(minWpx)
        val pH = provH.coerceAtLeast(minHpx)

        // No provider data — sensible fallback: full width × default height.
        if (provW <= 0 || provH <= 0) {
            return containerWidthPx to (DEFAULT_HEIGHT_DP * density).roundToInt()
        }

        val aspect = pH.toFloat() / pW.toFloat()
        val isWide = (pW.toFloat() / pH >= 2.0f) || (pW >= containerWidthPx * 0.55f)

        // A single full-width column makes widgets easier to scan and reorder.
        val h = if (isWide) {
            (containerWidthPx * aspect).roundToInt()
        } else {
            pH
        }.coerceIn(minHpx, maxHpx)
        return containerWidthPx to h
    }

    /**
     * Flow: pack children left→right; if the next card does not fit the remaining
     * width of the row, wrap to a new line. Sizes come from each child's LayoutParams.
     */
    private class WidgetFlowLayout(context: Context) : ViewGroup(context) {
        var gapPx: Int = 0
        private val defaultHeightPx =
            (DEFAULT_HEIGHT_DP * resources.displayMetrics.density).roundToInt()

        class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height)

        override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams =
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DEFAULT_HEIGHT_DP)

        override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams {
            return LayoutParams(
                p?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                p?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(0)
            var x = 0
            var y = 0
            var rowH = 0
            val count = childCount
            var any = false
            for (i in 0 until count) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                any = true
                val lp = child.layoutParams
                val cw = when {
                    lp.width > 0 -> lp.width.coerceAtMost(width)
                    else -> width
                }.coerceAtLeast(1)
                val ch = when {
                    lp.height > 0 -> lp.height
                    else -> defaultHeightPx
                }.coerceAtLeast(1)
                child.measure(
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY),
                )
                if (x > 0 && x + gapPx + cw > width) {
                    x = 0
                    y += rowH + gapPx
                    rowH = 0
                }
                x += cw + gapPx
                rowH = max(rowH, ch)
            }
            val height = if (!any) 0 else y + rowH
            setMeasuredDimension(
                resolveSize(width, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility == View.GONE) continue
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                if (x > 0 && x + gapPx + cw > width) {
                    x = 0
                    y += rowH + gapPx
                    rowH = 0
                }
                child.layout(x, y, x + cw, y + ch)
                x += cw + gapPx
                rowH = max(rowH, ch)
            }
        }

    }

    /**
     * Long-press shows remove + resize **without opening the widget**.
     * While editing, the AppWidgetHostView is blocked so taps go only to chrome.
     */
    private class ResizableWidgetFrame(context: Context) : FrameLayout(context) {
        var onEnterEdit: (() -> Unit)? = null
        var onExitEdit: (() -> Unit)? = null
        var onSizeLive: ((widthPx: Int, heightPx: Int) -> Unit)? = null
        var onSizeCommitted: ((widthPx: Int, heightPx: Int) -> Unit)? = null
        var minWidthPx: Int = 200
        var maxWidthPx: Int = 1000
        var minHeightPx: Int = 200
        var maxHeightPx: Int = 2000
        var removeButton: View? = null
        var editButtons: List<View> = emptyList()
        var hostView: View? = null

        private var resizeHandle: View? = null
        private var editing = false
        private var resizing = false
        /**
         * Chrome view (✕ / ↑ / ↓ / resize handle) that captured the current gesture.
         * Android delivers the whole gesture to the child that received ACTION_DOWN, so
         * we must keep routing to it even after the finger leaves its bounds — otherwise
         * the handle gets ACTION_CANCEL mid-drag and the resize is reverted.
         */
        private var chromeCapture: View? = null
        /** After long-press, swallow the rest of the gesture so the host never clicks. */
        private var blockHostUntilUp = false
        private var startRawX = 0f
        private var startRawY = 0f
        private var startW = 0
        private var startH = 0

        /** Frame-coalesced resize state — see [applyLiveSize]. */
        private var pendingWidthPx = 0
        private var pendingHeightPx = 0
        private var sizeFlushScheduled = false
        private val sizeFlush = Runnable { flushPendingSize() }

        private val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onLongPress(e: MotionEvent) {
                    if (resizing) return
                    blockHostUntilUp = true
                    // Cancel any in-progress press inside the widget (prevents open-on-release).
                    cancelHostTouch()
                    onEnterEdit?.invoke()
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            },
        ).apply {
            setIsLongpressEnabled(true)
        }

        fun setEditMode(enabled: Boolean) {
            editing = enabled
            editButtons.forEach { it.visibility = if (enabled) View.VISIBLE else View.GONE }
            resizeHandle?.visibility = if (enabled) View.VISIBLE else View.GONE
            // Hard-block widget interaction while chrome is visible.
            hostView?.let { host ->
                host.isEnabled = !enabled
                host.isClickable = !enabled
                host.isLongClickable = false
            }
            if (!enabled) {
                blockHostUntilUp = false
                onExitEdit?.invoke()
            }
        }

        fun attachResizeHandle(handle: View) {
            resizeHandle = handle
            handle.setOnTouchListener { _, event -> handleResizeTouch(event) }
        }

        private fun cancelHostTouch() {
            val host = hostView ?: return
            host.isPressed = false
            host.cancelLongPress()
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            runCatching { host.dispatchTouchEvent(cancel) }
            cancel.recycle()
        }

        private fun chromeAt(ev: MotionEvent): View? {
            val x = ev.x
            val y = ev.y
            for (chrome in editButtons) {
                if (chrome.visibility == View.VISIBLE &&
                    x >= chrome.left && x < chrome.right && y >= chrome.top && y < chrome.bottom
                ) return chrome
            }
            val handle = resizeHandle
            if (handle != null && handle.visibility == View.VISIBLE &&
                x >= handle.left && x < handle.right && y >= handle.top && y < handle.bottom
            ) return handle
            return null
        }

        /**
         * Chrome that owns this gesture. Once a chrome child captured ACTION_DOWN it keeps
         * the gesture until UP/CANCEL, even when the finger drags outside its bounds.
         */
        private fun chromeForGesture(ev: MotionEvent): View? {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> chromeCapture = chromeAt(ev)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val captured = chromeCapture
                    chromeCapture = null
                    return captured ?: chromeAt(ev)
                }
            }
            return chromeCapture ?: if (resizing) resizeHandle else null
        }

        private fun isOnEditChrome(ev: MotionEvent): Boolean = chromeForGesture(ev) != null

        private fun applyLiveSize(newW: Int, newH: Int) {
            // Coalesce to one layout pass per frame. A 120Hz digitizer delivers up to 120
            // ACTION_MOVEs per second, and each requestLayout() here re-measures the flow
            // *and every AppWidgetHostView in it* — RemoteViews trees this app does not
            // control. Multiple moves inside one frame now collapse into a single pass.
            pendingWidthPx = newW
            pendingHeightPx = newH
            if (sizeFlushScheduled) return
            sizeFlushScheduled = true
            postOnAnimation(sizeFlush)
        }

        private fun flushPendingSize() {
            sizeFlushScheduled = false
            val newW = pendingWidthPx
            val newH = pendingHeightPx
            if (newW <= 0 || newH <= 0) return
            val lp = layoutParams ?: return
            if (lp.width != newW || lp.height != newH) {
                lp.width = newW
                lp.height = newH
                layoutParams = lp
                (parent as? View)?.requestLayout()
                onSizeLive?.invoke(newW, newH)
            }
        }

        /** Applies any move that arrived after the last frame flush. */
        private fun commitPendingSizeNow() {
            if (sizeFlushScheduled) {
                removeCallbacks(sizeFlush)
                flushPendingSize()
            }
        }

        /** Stop every scrolling ancestor (ScrollView, RecyclerView, pager) from stealing the drag. */
        private fun lockAncestors(disallow: Boolean) {
            var p = parent
            while (p != null) {
                (p as? ViewGroup)?.requestDisallowInterceptTouchEvent(disallow)
                p = p.parent
            }
        }

        private fun handleResizeTouch(event: MotionEvent): Boolean {
            if (!editing) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizing = true
                    blockHostUntilUp = true
                    lockAncestors(true)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startW = width.takeIf { it > 0 } ?: (layoutParams?.width ?: minWidthPx)
                    startH = height.takeIf { it > 0 } ?: (layoutParams?.height ?: minHeightPx)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!resizing) return false
                    val dh = (event.rawY - startRawY).roundToInt()
                    val newW = maxWidthPx
                    val newH = (startH + dh).coerceIn(minHeightPx, maxHeightPx)
                    applyLiveSize(newW, newH)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizing) {
                        resizing = false
                        lockAncestors(false)
                        // Land the last move before reading layoutParams — with the
                        // per-frame coalescing the final ACTION_MOVE may still be pending,
                        // and committing without it would drop up to one frame of drag.
                        commitPendingSizeNow()
                        // Commit on UP **and** on CANCEL: a CANCEL here means an ancestor took
                        // over the gesture, not that the user abandoned the resize. Reverting
                        // on CANCEL is what made every resize snap back to its original size.
                        val lp = layoutParams
                        if (lp != null && lp.width > 0 && lp.height > 0) {
                            onSizeCommitted?.invoke(lp.width, lp.height)
                        } else {
                            applyLiveSize(startW, startH)
                        }
                    }
                    return true
                }
            }
            return false
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked

            // Edit chrome (✕ / resize) always receives touches first when visible.
            if (editing && isOnEditChrome(ev)) {
                detector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }

            // Feed long-press detector without letting host open on hold.
            if (!resizing) {
                detector.onTouchEvent(ev)
            }

            // After long-press (or while editing the body): never deliver to AppWidgetHostView.
            if (editing || blockHostUntilUp) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    blockHostUntilUp = false
                    // Tap empty body while editing → exit edit mode (optional, clear chrome).
                    if (editing && action == MotionEvent.ACTION_UP && !resizing) {
                        // Keep edit mode so user can still hit ✕ / resize; only exit if they
                        // tap outside this card (handled by next long-press on another card).
                    }
                }
                // Consume — host does not see this gesture.
                return true
            }

            // Normal short taps: allow widget interaction.
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // Once we are editing or blocked the host, take the stream (except chrome).
            if ((editing || blockHostUntilUp) && !isOnEditChrome(ev)) {
                return true
            }
            return super.onInterceptTouchEvent(ev)
        }
    }

    private class ResizeHandleView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            strokeWidth = 3f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val halfWidth = 12f * density
        private val lineGap = 3f * density

        init {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.argb(180, 20, 20, 24))
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val centerX = width / 2f
            val centerY = height / 2f
            canvas.drawLine(centerX - halfWidth, centerY - lineGap, centerX + halfWidth, centerY - lineGap, paint)
            canvas.drawLine(centerX - halfWidth, centerY + lineGap, centerX + halfWidth, centerY + lineGap, paint)
        }
    }

    companion object {
        const val HOST_ID = 1024
        const val MIN_WIDTH_DP = 120
        const val MIN_HEIGHT_DP = 80
        const val MAX_HEIGHT_DP = 600
        const val DEFAULT_HEIGHT_DP = 160
    }
}

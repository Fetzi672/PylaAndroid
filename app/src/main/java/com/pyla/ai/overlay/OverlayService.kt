package com.pyla.ai.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pyla.ai.engine.BotEngine
import com.pyla.ai.engine.BotStatus
import com.pyla.ai.engine.HudTelemetry
import com.pyla.ai.engine.RuntimeVerification
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Floating control surface, drawn entirely with platform Canvas (no Compose, no assets).
 *
 * Window model (each independently bounded so empty space passes touches through to the app below):
 *  1. main bubble      — 48dp circular window showing the packaged Pyla icon, draggable; tap opens
 *                        the two-stage gesture
 *  2. three controls   — one 48dp window per action, created only while the menu is open
 *  3. HUD pill         — compact dark rounded pill, top-centre inside the safe viewport, drawn in a
 *                        NOT_TOUCHABLE window that is never removed while the service lives
 *
 * There is deliberately NO transparent enclosing menu window. The HUD is orchestrated by the same
 * service but is independent of the bubble: it is never hidden/removed when the menu closes, docks,
 * retracts or the engine stops. Its optional detail row appears only while the bubble controls are
 * open, in the same NOT_TOUCHABLE window, so it can never intercept a touch.
 */
class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var density = 1f
    private var metrics = OverlayMetrics.forDensity(1f)
    private var hudSpec = HudPillSpec.forDensity(1f)
    private var geometry = ScreenGeometry(1080, 1920)

    private var side = DockSide.RIGHT
    private var relativeY = OverlayGeometry.DEFAULT_RELATIVE_Y

    private val controller = OverlayController()
    private var lastStage = controller.stage

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var hudView: HudView? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private val controlViews = ArrayList<ControlView>()
    private val controlParams = ArrayList<WindowManager.LayoutParams>()

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startParamX = 0
    private var startParamY = 0

    // Live status values mirrored from the existing BotStatus / RuntimeVerification sources.
    private var running = false
    private var paused = false
    private var engineLabel = "Stopped"

    private var hudStatus = HudStatus(HudStatusResolver.STOPPED, HudTone.MUTED)
    private var hudCompact = HudCompact()
    private var hudDetail: HudDetail? = null
    private var hudRaw = HudRawValues()
    private var hudError: String? = null
    private var detailOpen = false

    private val tick = object : Runnable {
        override fun run() {
            if (!OverlayPreferences.isEnabled(this@OverlayService) ||
                !OverlayPreferences.canDrawOverlays(this@OverlayService)
            ) {
                stopSelf(); return
            }
            refreshStatus()
            if (bubbleView != null || hudView != null) handler.postDelayed(this, STATUS_TICK_MS)
        }
    }

    private val frame = object : Runnable {
        override fun run() {
            controller.onTick(SystemClock.uptimeMillis())
            syncWindows()
            if (controller.animating) handler.postDelayed(this, FRAME_MS)
        }
    }

    private val hideCheck = Runnable {
        controller.onTick(SystemClock.uptimeMillis())
        if (controller.animating) startFrames() else syncWindows()
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) { relayoutForConfiguration() }
    }

    override fun onCreate() {
        super.onCreate()
        if (!OverlayPreferences.isEnabled(this) || !OverlayPreferences.canDrawOverlays(this)) {
            stopSelf(); return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        readConfiguration()
        side = OverlayPreferences.side(this)
        relativeY = OverlayPreferences.relativeY(this)
        try {
            installHud()
            installBubble()
            registerDisplayListener()
            handler.post(tick)
        } catch (t: Throwable) {
            Log.w(TAG, "overlay install failed: ${t.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        relayoutForConfiguration()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager).unregisterDisplayListener(displayListener) }
        removeControls()
        bubbleView?.release()
        bubbleView?.let { removeWindow(it) }
        hudView?.let { removeWindow(it) }
        bubbleView = null; bubbleParams = null
        hudView = null; hudParams = null
        controller.cancelToClosed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- configuration / geometry

    private fun readConfiguration() {
        density = resources.displayMetrics.density
        metrics = OverlayMetrics.forDensity(density)
        hudSpec = HudPillSpec.forDensity(density)
        geometry = OverlayInsets.compute(this, windowManager)
    }

    private fun relayoutForConfiguration() {
        if (bubbleView == null && hudView == null) return
        try {
            readConfiguration()
            hudView?.apply { reconfigure(hudSpec) }
            bubbleView?.apply { reconfigure() }
            applyHudLayout()
            applyBubbleLayout()
            if (controlViews.isNotEmpty()) applyControlLayout()
            refreshStatus()
        } catch (t: Throwable) {
            Log.w(TAG, "overlay relayout failed: ${t.message}")
        }
    }

    private fun registerDisplayListener() {
        runCatching {
            (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .registerDisplayListener(displayListener, handler)
        }
    }

    // ---------------------------------------------------------------- window installation

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    /**
     * Alpha for the NOT_TOUCHABLE HUD window. Android 12+ blocks touches that pass through an
     * overlay whose window alpha exceeds the "maximum obscuring opacity for touch"; keeping the
     * HUD window at a safe <= 0.7 (rather than relying on a translucent background paint) keeps
     * taps in that band flowing to the game while remaining legible.
     */
    private fun hudWindowAlpha(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) HUD_ALPHA_API31 else 1.0f

    private fun installHud() {
        val p = baseParams(1, metrics.hudHeightPx, touchable = false)
        p.alpha = hudWindowAlpha()
        hudParams = p
        val v = HudView(this, hudSpec)
        hudView = v
        addWindow(v, p)
        refreshStatus()
        applyHudLayout()
    }

    private fun installBubble() {
        val rect = OverlayGeometry.bubbleRect(side, geometry, metrics, relativeY, controller.revealProgress)
        val p = baseParams(rect.width, rect.height, touchable = true)
        p.x = rect.left; p.y = rect.top
        bubbleParams = p
        val v = BubbleView(this)
        v.updateDescription(stageDescription(controller.stage))
        bubbleView = v
        addWindow(v, p)
    }

    /**
     * Re-measures the pill for the current text and re-centres its window. The window is resized
     * only when its size actually changes, so a stable HUD causes no layout churn.
     */
    private fun applyHudLayout() {
        val v = hudView ?: return
        val p = hudParams ?: return
        val safeWidth = (geometry.widthPx - geometry.insetLeftPx - geometry.insetRightPx -
            2 * metrics.edgeMarginPx).coerceAtLeast(1)
        val frame = v.update(hudCompact, hudDetail, hudError, hudStatus, hudRaw, safeWidth.toFloat())
        val rect = OverlayGeometry.pillRect(
            geometry, metrics,
            frame.width.roundToInt().coerceAtLeast(1),
            frame.height.roundToInt().coerceAtLeast(1),
        )
        val changed = p.width != rect.width || p.height != rect.height || p.x != rect.left || p.y != rect.top
        p.width = rect.width; p.height = rect.height
        p.x = rect.left; p.y = rect.top
        if (changed) updateWindow(v, p)
    }

    private fun applyBubbleLayout() {
        val v = bubbleView ?: return
        val p = bubbleParams ?: return
        val rect = OverlayGeometry.bubbleRect(side, geometry, metrics, relativeY, controller.revealProgress)
        p.width = rect.width; p.height = rect.height
        p.x = rect.left; p.y = rect.top
        updateWindow(v, p)
    }

    private fun applyControlLayout() {
        if (controlViews.isEmpty()) return
        val rects = OverlayGeometry.controlRects(side, geometry, metrics, relativeY)
        val alpha = controller.menuProgress.coerceIn(0f, 1f)
        controlViews.forEachIndexed { i, v ->
            val p = controlParams[i]
            if (i < rects.size) {
                val r = rects[i]
                p.width = r.width; p.height = r.height
                p.x = r.left; p.y = r.top
            }
            p.alpha = alpha
            updateWindow(v, p)
        }
    }

    private fun ensureControls(visible: Boolean) {
        if (visible && controlViews.isEmpty()) {
            for (i in 0..2) {
                val p = baseParams(metrics.controlSizePx, metrics.controlSizePx, touchable = true)
                val v = ControlView(this, i)
                v.setEngine(running, paused)
                controlViews.add(v); controlParams.add(p)
                try { windowManager.addView(v, p) } catch (t: Throwable) {
                    Log.w(TAG, "add control failed: ${t.message}")
                    removeControls()
                    stopSelf()
                    return
                }
            }
            applyControlLayout()
        } else if (!visible && controlViews.isNotEmpty()) {
            removeControls()
        }
    }

    private fun removeControls() {
        controlViews.forEach { removeWindow(it) }
        controlViews.clear(); controlParams.clear()
    }

    private fun addWindow(v: View, p: WindowManager.LayoutParams) {
        // Installation failures reach onCreate's existing stopSelf cleanup.
        windowManager.addView(v, p)
    }

    private fun updateWindow(v: View, p: WindowManager.LayoutParams) {
        try { windowManager.updateViewLayout(v, p) } catch (t: Throwable) {
            Log.w(TAG, "updateViewLayout failed: ${t.message}")
            stopSelf()
        }
    }

    private fun removeWindow(v: View) {
        try { windowManager.removeView(v) } catch (t: Throwable) { Log.w(TAG, "removeView failed: ${t.message}") }
    }

    // ---------------------------------------------------------------- status tick + engine state

    private fun refreshStatus() {
        running = BotStatus.engineRunning
        paused = running && BotStatus.enginePaused
        engineLabel = OverlayStatusText.engineState(running, paused)
        val verification = if (running) BotStatus.verification else RuntimeVerification()
        val error = BotStatus.lastError.ifBlank { null }

        hudStatus = HudStatusResolver.resolve(
            running = running,
            paused = paused,
            verified = verification.verified,
            stateLabel = verification.state.label,
            hasError = error != null,
        )

        val snapshot = HudTelemetry.snapshot(SystemClock.elapsedRealtime())
        hudRaw = HudRawValues(
            loopMs = snapshot.loopMs,
            detectMs = snapshot.detectMs, styleMs = snapshot.styleMs,
            inputMs = snapshot.inputMs, frameAgeMs = snapshot.frameAgeMs,
        )
        hudCompact = HudCompact(
            status = hudStatus.label,
            style = HudMetrics.style(snapshot.playstyle),
            loop = HudMetrics.ms(snapshot.loopMs),
            ips = HudMetrics.ips(snapshot.ips),
        )
        hudDetail = if (detailOpen) {
            HudDetail(
                detect = HudMetrics.ms(snapshot.detectMs),
                style = HudMetrics.ms(snapshot.styleMs),
                input = HudMetrics.ms(snapshot.inputMs),
                frame = HudMetrics.frameAge(snapshot.frameAgeMs),
                backend = HudMetrics.backend(snapshot.backend),
            )
        } else {
            null
        }
        hudError = if (detailOpen && running) error else null

        hudView?.apply { setAccessibilityText(hudStatus, hudCompact) }
        bubbleView?.apply { setEngine(running, paused) }
        controlViews.forEach { it.setEngine(running, paused) }
        applyHudLayout()
    }

    /** The detail row only exists while the bubble's control menu is open. */
    private fun syncDetailRow() {
        val open = controller.controlsVisible
        if (open == detailOpen) return
        detailOpen = open
        refreshStatus()
    }

    // ---------------------------------------------------------------- animation orchestration

    private fun startFrames() {
        handler.removeCallbacks(frame)
        handler.post(frame)
    }

    private fun syncWindows() {
        applyBubbleLayout()
        if (controlViews.isNotEmpty()) applyControlLayout()
        bubbleView?.invalidate()

        val stage = controller.stage
        if (stage != lastStage) {
            onStageTransition(lastStage, stage)
            lastStage = stage
        }
        // Controls exist whenever any menu progress is visible (including the fade in/out frames).
        if (controller.controlsVisible) ensureControls(true) else ensureControls(false)
        syncDetailRow()
    }

    private fun onStageTransition(prev: OverlayStage, next: OverlayStage) {
        if (prev == OverlayStage.REVEALED && next != OverlayStage.REVEALED) {
            handler.removeCallbacks(hideCheck)
        }
        when (next) {
            OverlayStage.MENU_OPEN -> { handler.removeCallbacks(hideCheck); ensureControls(true) }
            OverlayStage.REVEALED -> { ensureControls(false); scheduleHide() }
            OverlayStage.CLOSED -> ensureControls(false)
        }
        bubbleView?.updateDescription(stageDescription(next))
    }

    private fun scheduleHide() {
        val delay = (controller.autoHideAtMs - SystemClock.uptimeMillis()).coerceAtLeast(1L)
        handler.removeCallbacks(hideCheck)
        handler.postDelayed(hideCheck, delay)
    }

    private fun stageDescription(stage: OverlayStage): String = when (stage) {
        OverlayStage.CLOSED -> "closed"
        OverlayStage.REVEALED -> "revealed"
        OverlayStage.MENU_OPEN -> "menu open"
    }

    // ---------------------------------------------------------------- gestures

    private fun onBubbleDown(rawX: Float, rawY: Float) {
        handler.removeCallbacks(hideCheck)
        val p = bubbleParams ?: return
        downRawX = rawX; downRawY = rawY
        startParamX = p.x; startParamY = p.y
        dragging = false
    }

    private fun onBubbleMove(rawX: Float, rawY: Float, slopPx: Int): Boolean {
        val p = bubbleParams ?: return false
        val dx = rawX - downRawX
        val dy = rawY - downRawY
        if (!dragging && !OverlayGeometry.exceedsSlop(dx, dy, slopPx)) return true
        if (!dragging) {
            dragging = true
            handler.removeCallbacks(hideCheck)
            controller.onDragStart()
            ensureControls(false)
            bubbleView?.updateDescription("dragging")
        }
        val band = OverlayGeometry.usableVerticalRange(geometry, metrics)
        p.x = (startParamX + dx.roundToInt()).coerceIn(-metrics.bubbleSizePx / 2, geometry.widthPx - metrics.bubbleSizePx / 2)
        p.y = (startParamY + dy.roundToInt()).coerceIn(band.top, (band.bottom - metrics.bubbleSizePx).coerceAtLeast(band.top))
        bubbleView?.let { updateWindow(it, p) }
        return true
    }

    private fun onBubbleUp() {
        val p = bubbleParams ?: return
        if (dragging) {
            dragging = false
            val rect = OverlayRect(p.x, p.y, p.x + metrics.bubbleSizePx, p.y + metrics.bubbleSizePx)
            val newSide = OverlayGeometry.snapSide(rect.centerX, geometry)
            val newRelY = OverlayGeometry.relativeYForTop(p.y, geometry, metrics, metrics.bubbleSizePx)
            side = newSide
            relativeY = OverlayGeometry.clampRelativeY(newRelY)
            // Persist only when the drag docks (never per frame).
            OverlayPreferences.setSide(this, side)
            OverlayPreferences.setRelativeY(this, relativeY)
            controller.onDragEnd(SystemClock.uptimeMillis())
            bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            applyBubbleLayout()
            syncWindows()
            scheduleHide()
            startFrames()
        } else {
            if (controller.onTap(SystemClock.uptimeMillis())) {
                bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                startFrames()
            }
        }
    }

    private fun startBot() {
        if (running) return
        bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        startActivity(Intent(this, com.pyla.ai.ui.MainActivity::class.java).apply {
            action = com.pyla.ai.ui.MainActivity.ACTION_REQUEST_START
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun pauseResume() {
        bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        BotEngine.instance?.let { it.setPaused(!it.isPaused()) }
    }

    private fun stopBot() {
        bubbleView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        BotEngine.instance?.stop()
        com.pyla.ai.capture.CaptureService.stop(this)
    }

    private fun onControl(index: Int) {
        when (index) {
            0 -> startBot()
            1 -> pauseResume()
            2 -> stopBot()
        }
        refreshStatus()
    }

    // ---------------------------------------------------------------- views

    /** Raw telemetry used only to colour detail values by their millisecond budget. */
    private data class HudRawValues(
        val loopMs: Double? = null,
        val detectMs: Double? = null,
        val styleMs: Double? = null,
        val inputMs: Double? = null,
        val frameAgeMs: Long? = null,
    )

    private inner class HudView(context: Context, initialSpec: HudPillSpec) : View(context) {
        init { setWillNotDraw(false) }

        private var spec = initialSpec
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudTheme.pillFill }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * density; color = HudTheme.pillBorder
        }
        private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f * density; color = HudTheme.divider
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var paints = buildPaints()

        private fun buildPaints(): HashMap<HudTextStyle, Paint> = HashMap<HudTextStyle, Paint>().apply {
            put(HudTextStyle.BRAND, textPaint(11.5f, Typeface.DEFAULT_BOLD, HudTone.BRAND.argb))
            put(HudTextStyle.STATUS, textPaint(10.5f, Typeface.DEFAULT_BOLD, HudTone.MUTED.argb))
            put(HudTextStyle.STYLE, textPaint(10.5f, Typeface.DEFAULT, HudTheme.primaryText))
            put(HudTextStyle.LABEL, textPaint(8f, Typeface.DEFAULT_BOLD, HudTheme.labelText, spacing = 0.08f))
            put(HudTextStyle.VALUE, textPaint(10.5f, Typeface.DEFAULT_BOLD, HudTheme.primaryText, tabular = true))
            put(HudTextStyle.DETAIL_LABEL, textPaint(7f, Typeface.DEFAULT_BOLD, HudTheme.labelText, spacing = 0.1f))
            put(HudTextStyle.DETAIL_VALUE, textPaint(9.5f, Typeface.DEFAULT_BOLD, HudTheme.primaryText, tabular = true))
            put(HudTextStyle.ERROR, textPaint(8f, Typeface.DEFAULT, HudTone.BAD.argb))
        }

        private var frame: HudPillFrame? = null
        private var statusTone = HudTone.MUTED
        private var raw = HudRawValues()

        private fun textPaint(
            sizeSp: Float,
            face: Typeface,
            color: Int,
            spacing: Float = 0f,
            tabular: Boolean = false,
        ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = sizeSp * density
            this.color = color
            letterSpacing = spacing
            if (tabular) fontFeatureSettings = "tnum"
        }

        fun reconfigure(spec: HudPillSpec) {
            this.spec = spec
            border.strokeWidth = 1f * density
            rule.strokeWidth = 1f * density
            paints = buildPaints()
        }

        fun update(
            compact: HudCompact,
            detail: HudDetail?,
            error: String?,
            status: HudStatus,
            rawValues: HudRawValues,
            maxWidth: Float,
        ): HudPillFrame {
            statusTone = status.tone
            raw = rawValues
            paints.getValue(HudTextStyle.STATUS).color = status.tone.argb
            val measured = HudPillLayout.layout(compact, detail, error, maxWidth, spec) { text, style ->
                paints.getValue(style).measureText(text)
            }
            frame = measured
            invalidate()
            return measured
        }

        fun setAccessibilityText(status: HudStatus, compact: HudCompact) {
            contentDescription = HudStatusResolver.description(status, compact.loop, compact.ips)
        }

        /** Baseline that vertically centres [paint] inside a row of [centerY]. */
        private fun baseline(paint: Paint, centerY: Float): Float =
            centerY - (paint.ascent() + paint.descent()) / 2f

        override fun onDraw(canvas: Canvas) {
            val f = frame ?: return
            if (f.width <= 0f || f.height <= 0f) return
            canvas.drawRoundRect(0f, 0f, f.width, f.height, spec.radius, spec.radius, fill)
            canvas.drawRoundRect(
                0.5f, 0.5f, f.width - 0.5f, f.height - 0.5f, spec.radius, spec.radius, border,
            )

            val rowCenter = spec.rowHeight / 2f
            f.segments.forEach { segment ->
                when (segment.kind) {
                    HudSegmentKind.BRAND ->
                        canvas.drawText(segment.text, segment.left, baseline(paints.getValue(HudTextStyle.BRAND), rowCenter), paints.getValue(HudTextStyle.BRAND))
                    HudSegmentKind.STATUS -> {
                        dotPaint.color = statusTone.argb
                        canvas.drawCircle(
                            segment.left + spec.dotDiameter / 2f, rowCenter, spec.dotDiameter / 2f, dotPaint,
                        )
                        val paint = paints.getValue(HudTextStyle.STATUS)
                        canvas.drawText(segment.text, segment.valueLeft, baseline(paint, rowCenter), paint)
                    }
                    HudSegmentKind.STYLE -> {
                        val paint = paints.getValue(HudTextStyle.STYLE)
                        canvas.drawText(segment.text, segment.left, baseline(paint, rowCenter), paint)
                    }
                    HudSegmentKind.LOOP, HudSegmentKind.IPS -> {
                        val labelPaint = paints.getValue(HudTextStyle.LABEL)
                        segment.label?.let { label ->
                            canvas.drawText(label, segment.labelLeft, baseline(labelPaint, rowCenter), labelPaint)
                        }
                        val valuePaint = paints.getValue(HudTextStyle.VALUE)
                        valuePaint.color = if (segment.kind == HudSegmentKind.LOOP) {
                            HudMetrics.msTone(raw.loopMs, 30.0, 60.0).argb
                        } else if (segment.value == HudMetrics.EM_DASH) {
                            HudTone.MUTED.argb
                        } else HudTone.INFO.argb
                        canvas.drawText(segment.value, segment.valueLeft, baseline(valuePaint, rowCenter), valuePaint)
                    }
                }
            }
            f.dividers.forEach { x ->
                canvas.drawLine(x, rowCenter - spec.rowHeight * 0.22f, x, rowCenter + spec.rowHeight * 0.22f, rule)
            }

            if (f.columns.isNotEmpty()) {
                val top = f.detailTop
                canvas.drawLine(spec.padH * 0.6f, top, f.width - spec.padH * 0.6f, top, rule)
                f.columns.forEach { column ->
                    val centerX = column.left + column.width / 2f
                    val labelPaint = paints.getValue(HudTextStyle.DETAIL_LABEL).apply { textAlign = Paint.Align.CENTER }
                    val valuePaint = paints.getValue(HudTextStyle.DETAIL_VALUE).apply { textAlign = Paint.Align.CENTER }
                    valuePaint.color = detailColor(column.label)
                    canvas.drawText(column.label, centerX, top + spec.detailHeight * 0.36f, labelPaint)
                    canvas.drawText(column.value, centerX, top + spec.detailHeight * 0.76f, valuePaint)
                }
            }

            f.errorLine?.let { line ->
                val paint = paints.getValue(HudTextStyle.ERROR)
                canvas.drawText(line.text, line.left, line.top + spec.errorHeight * 0.7f, paint)
            }
        }

        /**
         * Identity colour per detail metric, overridden by its millisecond budget. IPS has no
         * threshold because it is a rate, not a latency.
         */
        private fun detailColor(label: String): Int = when (label) {
            "DETECT" -> HudMetrics.msTone(raw.detectMs, DETECT_BUDGET_MS, DETECT_WARN_MS).decorate(HudTone.INFO)
            "STYLE" -> HudMetrics.msTone(raw.styleMs, STYLE_BUDGET_MS, STYLE_WARN_MS).decorate(HudTone.BRAND)
            "INPUT" -> HudMetrics.msTone(raw.inputMs, INPUT_BUDGET_MS, INPUT_WARN_MS).decorate(HudTone.GOOD)
            "FRAME" -> HudMetrics.msTone(raw.frameAgeMs?.toDouble(), FRAME_BUDGET_MS, FRAME_WARN_MS).decorate(HudTone.WARN)
            else -> HudTheme.primaryText
        }

        private fun HudTone.decorate(identity: HudTone): Int = when (this) {
            HudTone.WARN -> HudTone.WARN.argb
            HudTone.BAD -> HudTone.BAD.argb
            else -> identity.argb
        }
    }

    private inner class BubbleView(context: Context) : View(context) {
        init { setWillNotDraw(false); isClickable = true }
        private val icon = BubbleIcon(context)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HudTheme.bubbleFill }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private var color = 0xFF7A8699.toInt()

        fun setEngine(running: Boolean, paused: Boolean) {
            color = OverlayStatusText.stateColor(running, paused)
            invalidate()
        }

        fun reconfigure() {
            icon.invalidate()
            invalidate()
        }

        fun release() = icon.invalidate()

        fun updateDescription(stage: String) {
            contentDescription = "PYLA bubble $stage, $engineLabel"
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onBubbleDown(event.rawX, event.rawY)
                MotionEvent.ACTION_MOVE -> onBubbleMove(event.rawX, event.rawY, metrics.dragSlopPx)
                MotionEvent.ACTION_UP -> if (dragging) onBubbleUp() else performClick()
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    controller.cancelToClosed()
                    handler.removeCallbacks(hideCheck)
                    syncWindows()
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            onBubbleUp()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val r = (min(width, height) / 2).toFloat()
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, r - 1f, fill)
            // The bubble shows the packaged Pyla icon, never a drawn letter mark.
            icon.draw(canvas, cx, cy, (r - 3f * density).coerceAtLeast(1f))
            ring.color = color
            canvas.drawCircle(cx, cy, r - 2f, ring)
        }
    }

    private inner class ControlView(context: Context, private val index: Int) : View(context) {
        init { setWillNotDraw(false); isClickable = true }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        private var enabled = false
        private val accent: Int get() = when (index) {
            0 -> GREEN
            1 -> ACCENT
            else -> RED
        }
        private val labelText: String get() = when (index) {
            0 -> "Start"
            1 -> if (paused) "Resume" else "Pause"
            else -> "Stop"
        }

        fun setEngine(running: Boolean, paused: Boolean) {
            enabled = when (index) {
                0 -> !running
                1 -> running
                else -> running
            }
            contentDescription = "$labelText, ${if (enabled) "enabled" else "disabled"}"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = (width.coerceAtMost(height) / 2).toFloat()
            val cx = width / 2f; val cy = height / 2f
            val a = if (enabled) 1f else 0.35f
            // A dark disk keeps controls legible over bright/moving game scenes.
            fill.color = Color.argb(248, 20, 24, 32)
            canvas.drawCircle(cx, cy, r - 1f, fill)
            fill.color = withAlpha(accent, if (enabled) 28 else 8)
            canvas.drawCircle(cx, cy, r - 1f, fill)
            ring.color = withAlpha(if (enabled) accent else MUTED, (170 * a).roundToInt())
            canvas.drawCircle(cx, cy, r - 2f, ring)
            icon.color = withAlpha(if (enabled) accent else MUTED, (255 * a).roundToInt())
            drawIcon(canvas, cx, cy - 3f * density, r * 0.34f)
            label.textSize = 8f * density
            label.color = withAlpha(if (enabled) accent else MUTED, (255 * a).roundToInt())
            canvas.drawText(labelText, cx, cy + r * 0.62f, label)
        }

        private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val p = Path()
            when (index) {
                0 -> { // play triangle
                    p.moveTo(cx - r * 0.6f, cy - r); p.lineTo(cx + r * 0.9f, cy); p.lineTo(cx - r * 0.6f, cy + r); p.close()
                    canvas.drawPath(p, icon)
                }
                1 -> {
                    if (paused) { // play
                        p.moveTo(cx - r * 0.6f, cy - r); p.lineTo(cx + r * 0.9f, cy); p.lineTo(cx - r * 0.6f, cy + r); p.close()
                        canvas.drawPath(p, icon)
                    } else { // pause bars
                        canvas.drawRect(cx - r * 0.75f, cy - r, cx - r * 0.2f, cy + r, icon)
                        canvas.drawRect(cx + r * 0.2f, cy - r, cx + r * 0.75f, cy + r, icon)
                    }
                }
                else -> { // stop square
                    canvas.drawRect(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, icon)
                }
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = enabled
                    if (enabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                MotionEvent.ACTION_MOVE -> isPressed = enabled && e.x >= 0f && e.x < width && e.y >= 0f && e.y < height
                MotionEvent.ACTION_UP -> {
                    if (isPressed && e.x >= 0f && e.x < width && e.y >= 0f && e.y < height) performClick()
                    isPressed = false
                }
                MotionEvent.ACTION_CANCEL -> isPressed = false
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (enabled) onControl(index)
            return true
        }
    }

    companion object {
        const val ACTION_STOP = "com.pyla.ai.overlay.STOP"

        fun start(context: Context): Boolean {
            if (!OverlayPreferences.isEnabled(context) || !OverlayPreferences.canDrawOverlays(context)) return false
            return try { context.startService(Intent(context, OverlayService::class.java)); true }
            catch (t: Throwable) { Log.w(TAG, "overlay start failed: ${t.message}"); false }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}

private const val TAG = "PylaOverlay"
private const val STATUS_TICK_MS = 500L
private const val FRAME_MS = 16L
private const val HUD_ALPHA_API31 = 0.7f
private const val ACCENT = 0xFFFFC400.toInt()
private const val GREEN = 0xFF4CD964.toInt()
private const val RED = 0xFFFF5A5A.toInt()
private const val MUTED = 0xFF7A8699.toInt()

/** Millisecond budgets for the detail row; IPS is a rate and deliberately has none. */
private const val DETECT_BUDGET_MS = 25.0
private const val DETECT_WARN_MS = 50.0
private const val STYLE_BUDGET_MS = 8.0
private const val STYLE_WARN_MS = 16.0
private const val INPUT_BUDGET_MS = 8.0
private const val INPUT_WARN_MS = 16.0
private const val FRAME_BUDGET_MS = 33.0
private const val FRAME_WARN_MS = 66.0

private fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

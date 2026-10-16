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
import com.pyla.ai.engine.RuntimeVerification
import kotlin.math.roundToInt

/**
 * Floating control surface, drawn entirely with platform Canvas (no Compose, no assets).
 *
 * Window model (each independently bounded so empty space passes touches through to the app below):
 *  1. main bubble      — 48dp circular window, draggable, tap opens/joins the two-stage gesture
 *  2. three controls   — one 48dp window per action, created only while the menu is open
 *  3. status strip     — 28dp full-width NOT_TOUCHABLE window, always present
 *
 * There is deliberately NO transparent enclosing menu window. The status strip is orchestrated by
 * the same service but is independent of the bubble: it is never hidden/removed when the menu
 * closes, docks, retracts or the engine stops.
 */
class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var density = 1f
    private var metrics = OverlayMetrics.forDensity(1f)
    private var geometry = ScreenGeometry(1080, 1920)

    private var side = DockSide.RIGHT
    private var relativeY = OverlayGeometry.DEFAULT_RELATIVE_Y

    private val controller = OverlayController()
    private var lastStage = controller.stage

    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var statusView: StatusView? = null
    private var statusParams: WindowManager.LayoutParams? = null
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
    private var stateLine = "State: Unknown"
    private var checkLine = "Check: Stopped"
    private var lastAction = ""
    private var statusText = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!OverlayPreferences.isEnabled(this@OverlayService) ||
                !OverlayPreferences.canDrawOverlays(this@OverlayService)
            ) {
                stopSelf(); return
            }
            refreshStatus()
            if (bubbleView != null || statusView != null) handler.postDelayed(this, STATUS_TICK_MS)
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
            installStatus()
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
        bubbleView?.let { removeWindow(it) }
        statusView?.let { removeWindow(it) }
        bubbleView = null; bubbleParams = null
        statusView = null; statusParams = null
        controller.cancelToClosed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- configuration / geometry

    private fun readConfiguration() {
        density = resources.displayMetrics.density
        metrics = OverlayMetrics.forDensity(density)
        geometry = OverlayInsets.compute(this, windowManager)
    }

    private fun relayoutForConfiguration() {
        if (bubbleView == null && statusView == null) return
        try {
            readConfiguration()
            applyStatusLayout()
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
     * Alpha for the NOT_TOUCHABLE status window. Android 12+ blocks touches that pass through an
     * overlay whose window alpha exceeds the "maximum obscuring opacity for touch"; keeping the
     * status window at a safe <= 0.7 (rather than relying on a translucent background paint) keeps
     * taps in that band flowing to the game while remaining legible.
     */
    private fun statusWindowAlpha(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) STATUS_ALPHA_API31 else 1.0f

    private fun installStatus() {
        val rect = OverlayGeometry.statusRect(geometry, metrics)
        val p = baseParams(rect.width, rect.height, touchable = false)
        p.x = rect.left; p.y = rect.top
        p.alpha = statusWindowAlpha()
        statusParams = p
        val v = StatusView(this)
        statusView = v
        addWindow(v, p)
        applyStatusLayout()
        refreshStatus()
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

    private fun applyStatusLayout() {
        val v = statusView ?: return
        val p = statusParams ?: return
        val rect = OverlayGeometry.statusRect(geometry, metrics)
        if (p.width != rect.width) p.width = rect.width
        if (p.height != rect.height) p.height = rect.height
        p.x = rect.left; p.y = rect.top
        updateWindow(v, p)
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
        stateLine = verification.stateLine
        checkLine = verification.phaseLine
        lastAction = BotStatus.lastAction.ifBlank { BotStatus.currentState }
        statusText = OverlayStatusText.compose(engineLabel, stateLine, checkLine, lastAction)

        statusView?.apply {
            setStatus(statusText, OverlayStatusText.stateColor(running, paused))
        }
        bubbleView?.apply { setEngine(running, paused) }
        controlViews.forEach { it.setEngine(running, paused) }
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

    private inner class BubbleView(context: Context) : View(context) {
        init { setWillNotDraw(false); isClickable = true }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(244, 20, 24, 32) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; isFakeBoldText = true; textAlign = Paint.Align.CENTER
        }
        private var color = 0xFF7A8699.toInt()

        fun setEngine(running: Boolean, paused: Boolean) { color = OverlayStatusText.stateColor(running, paused); invalidate() }
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
            val r = (width.coerceAtMost(height) / 2).toFloat()
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, r - 1f, fill)
            ring.color = color
            canvas.drawCircle(cx, cy, r - 2f, ring)
            text.textSize = 13f * density
            val label = if (text.measureText("PYLA") <= r * 1.7f) "PYLA" else "P"
            text.color = color
            val baseline = cy - (text.descent() + text.ascent()) / 2f
            canvas.drawText(label, cx, baseline, text)
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

    private inner class StatusView(context: Context) : View(context) {
        init { setWillNotDraw(false) }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(14, 17, 23) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private var color = 0xFF7A8699.toInt()
        private var line = ""

        fun setStatus(value: String, stateColor: Int) {
            line = value; color = stateColor; contentDescription = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
            text.textSize = 12f * density
            val cy = height / 2f
            val pad = 12f * density
            dot.color = color
            canvas.drawCircle(pad + 4f * density, cy, 4f * density, dot)
            val textLeft = pad + 14f * density
            val available = width - textLeft - pad
            while (text.textSize > 10f * density && text.measureText(line) > available) {
                text.textSize = (text.textSize - 0.5f * density).coerceAtLeast(10f * density)
            }
            val baseline = cy - (text.descent() + text.ascent()) / 2f
            val fitted = OverlayStatusText.fitFields(
                OverlayStatusText.parts(engineLabel, stateLine, checkLine, lastAction),
                available, { text.measureText(it) },
            )
            canvas.drawText(fitted, textLeft, baseline, text)
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
private const val STATUS_ALPHA_API31 = 0.7f
private const val ACCENT = 0xFFFFC400.toInt()
private const val GREEN = 0xFF4CD964.toInt()
private const val RED = 0xFFFF5A5A.toInt()
private const val MUTED = 0xFF7A8699.toInt()

private fun withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

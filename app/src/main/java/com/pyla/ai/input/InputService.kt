package com.pyla.ai.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.engine.BotStatus
import com.pyla.ai.engine.PylaLog

class InputService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** One optional request authority. Once denied, retries cannot revive it. */
    private class Authority(private val guard: () -> Boolean) {
        private var denied = false
        fun allows(op: String = "dispatchGate"): Boolean {
            if (!denied) {
                denied = try { !guard() } catch (_: Throwable) { true }
                if (denied) DiagnosticLog.event("input.gesture.denied", "op=$op reason=authority")
            }
            return !denied
        }
    }

    private class Channel(val name: String) {
        var authority: Authority? = null
        fun snapshot() = Snapshot(lastStroke, curX, curY, isDown, wantDown)
        fun restore(s: Snapshot) {
            lastStroke = s.stroke; curX = s.x; curY = s.y; isDown = s.down; wantDown = s.wanted
        }
        data class Snapshot(val stroke: GestureDescription.StrokeDescription?, val x: Float,
            val y: Float, val down: Boolean, val wanted: Boolean)
        var lastStroke: GestureDescription.StrokeDescription? = null
        var curX = 0f; var curY = 0f
        var downX = 0f; var downY = 0f
        var targetX = 0f; var targetY = 0f
        var wantDown = false
        var isDown = false

        // A continuable accessibility stroke must be followed by another continuation or Android
        // eventually lifts the pointer. Keep pumping while held, independently of whether the bot's
        // logical target changed. The logical re_apply_movement setting is handled by
        // WindowController, just like the PC version.
        fun needsUpdate(): Boolean = wantDown != isDown || (isDown && wantDown)
        fun reset() { lastStroke = null; isDown = false }
    }

    private val joystickCh = Channel("joystick")
    private val attackCh = Channel("attack")
    private class Pending(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long, val authority: Authority? = null, val replayable: Boolean = true)
    private val strokeQueue = ArrayDeque<Pending>()
    private var gestureInFlight = false
    private var pressSeq = 0
    private var lastQueueWarningMs = 0L
    private var diagnosticDispatchSeq = 0L

    override fun onServiceConnected() {
        instance = this
        BotStatus.inputConnected = true
        PylaLog.p(TAG, "InputService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        BotStatus.inputConnected = false
        handler.post {
            joystickCh.reset(); joystickCh.wantDown = false
            attackCh.reset(); attackCh.wantDown = false
            strokeQueue.clear()
            gestureInFlight = false
        }
        PylaLog.w(TAG, "InputService unbound")
        return super.onUnbind(intent)
    }

    private val launchableCache = HashMap<String, Boolean>()

    private fun isLaunchableApp(pkg: String): Boolean =
        launchableCache.getOrPut(pkg) {
            try {
                packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (t: Throwable) {
                false
            }
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg.isEmpty() || pkg == "com.android.systemui") return
            if (isLaunchableApp(pkg)) {
                if (foregroundPkg != pkg) PylaLog.p(TAG, "foreground app: $pkg")
                foregroundPkg = pkg
            }
        }
    }

    override fun onInterrupt() {}

    fun tap(key: String, holdMs: Long = 40) {
        val (x, y) = InputCoordinates.press(key)
        if (x < 0) return
        tapAt(x.toFloat(), y.toFloat(), holdMs)
    }

    fun tapAt(x: Float, y: Float, holdMs: Long = 40) {
        handler.post {
            enqueueAndPump(Pending(x, y, x, y, holdMs.coerceIn(1, 10_000)))
        }
    }

    fun swipeAt(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500) {
        handler.post {
            enqueueAndPump(Pending(x1, y1, x2, y2, durationMs.coerceIn(1, 10_000)))
        }
    }

    private fun enqueueAndPump(pending: Pending) {
        enqueueStroke(pending)
        pump()
    }

    private fun enqueueStroke(pending: Pending) {
        if (strokeQueue.size >= MAX_QUEUED_STROKES) {
            // A stale touch is more dangerous than dropping it; retain the most recent intent.
            strokeQueue.removeFirst()
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastQueueWarningMs >= 5_000L) {
                lastQueueWarningMs = now
                PylaLog.w(TAG, "gesture queue saturated; discarded oldest pending touch")
            }
        }
        strokeQueue.addLast(pending)
    }

    private fun restorePending(items: List<Pending>) {
        for (item in items.asReversed()) {
            // Navigation consumes its request even on failure: only a fresh gate ticket may retry.
            if (!item.replayable) continue
            if (item.authority != null) {
                if (!item.authority.allows()) continue
                // Keep at most one waiting basic attack, including after cancellation/rejection.
                if (strokeQueue.any { it.authority != null && it.replayable }) continue
            }
            if (strokeQueue.size >= MAX_QUEUED_STROKES) strokeQueue.removeLast()
            strokeQueue.addFirst(item)
        }
    }

    fun joystickMove(dx: Float, dy: Float) = queueJoystickMove(dx, dy, null)

    fun joystickMoveGuarded(dx: Float, dy: Float, guard: () -> Boolean) =
        queueJoystickMove(dx, dy, Authority(guard))

    private fun queueJoystickMove(dx: Float, dy: Float, authority: Authority?) {
        val admitted = dx.isFinite() && dy.isFinite() && authority?.allows("joystickMove") != false
        val (cx, cy) = InputCoordinates.joystick()
        handler.post {
            joystickCh.authority = authority
            if (!admitted || authority?.allows() == false) {
                joystickCh.wantDown = false
                pump()
                return@post
            }
            if (!joystickCh.wantDown) { joystickCh.downX = cx; joystickCh.downY = cy }
            joystickCh.wantDown = true
            joystickCh.targetX = cx + dx
            joystickCh.targetY = cy + dy
            pump()
        }
    }

    fun releaseJoystick() {
        handler.post { joystickCh.wantDown = false; pump() }
    }

    fun holdKey(key: String) {
        if (key != "attack") { tap(key); return }
        val (x, y) = InputCoordinates.press(key)
        if (x < 0) return
        holdAttackAt(x.toFloat(), y.toFloat())
    }

    @JvmOverloads
    fun holdAttackAt(x: Float, y: Float, guard: (() -> Boolean)? = null) {
        val authority = guard?.let { Authority(it) }
        val admitted = authority?.allows("holdAttackAt") != false
        handler.post {
            pressSeq++
            attackCh.authority = authority
            if (!admitted || authority?.allows() == false) {
                attackCh.wantDown = false
                pump() // If the old contact was held, its UP remains required.
                return@post
            }
            if (!attackCh.wantDown) { attackCh.downX = x; attackCh.downY = y }
            attackCh.wantDown = true
            attackCh.targetX = x
            attackCh.targetY = y
            pump()
        }
    }

    @JvmOverloads
    fun pressAndRelease(x: Float, y: Float, holdMs: Long, guard: (() -> Boolean)? = null) {
        val authority = guard?.let { Authority(it) }
        if (authority?.allows() == false) return
        handler.post {
            if (authority?.allows() == false) return@post
            if (authority != null) {
                // Freshest request replaces a waiting guarded tap, never an unguarded UI action.
                if (strokeQueue.removeAll { it.authority != null && it.replayable })
                    DiagnosticLog.sample("input.gesture.coalesced", "op=basic_attack")
            }
            // Normal combat presses are discrete PC-style taps. Keeping them on the
            // continuous attack channel caused repeated attack() calls to cancel each
            // other's release and turn into one long hold, which is not equivalent to
            // the PC click/touch_down/touch_up sequence.
            enqueueAndPump(Pending(x, y, x, y, holdMs.coerceIn(1, 10_000), authority))
        }
    }

    /** One navigation request, at most one dispatch attempt; never restored by combat recovery. */
    fun navigationTapOnce(x: Float, y: Float, holdMs: Long, guard: () -> Boolean) {
        val authority = Authority(guard)
        if (!authority.allows()) return
        handler.post {
            if (!authority.allows()) return@post
            enqueueAndPump(Pending(x, y, x, y, holdMs.coerceIn(1, 10_000), authority, replayable = false))
        }
    }

    fun releaseKey(key: String) {
        if (key != "attack") return
        handler.post {
            pressSeq++
            attackCh.wantDown = false
            pump()
        }
    }

    private fun pump() {
        if (gestureInFlight) return
        val channels = listOf(joystickCh, attackCh)
        // At most one held attack and one waiting guarded tap can lose authority. Each denial is
        // sticky; three passes suffice to rebuild without either, retaining unguarded work and UP.
        for (attempt in 0..2) {
            channels.forEach { if (it.wantDown && it.authority?.allows() == false) it.wantDown = false }
            if (!channels.any { it.needsUpdate() } && strokeQueue.isEmpty()) return
            val before = channels.map { it.snapshot() }
            // A gesture that continues an already-open pointer must not introduce a new pointer in
            // its first step: AOSP MotionEventInjector.prepareToContinueOldGesture counts every
            // first-step point (a fresh DOWN included) and subtracts only the old open strokes, so a
            // new tap sharing the continuation's start time makes it return false and the dispatch is
            // cancelled. Offset every new stroke when a continuation is present.
            // See InputGestureComposition.
            val willContinue = channels.any { it.isDown && it.lastStroke != null }
            val willEmitNew = channels.any { it.wantDown && !it.isDown } || strokeQueue.isNotEmpty()
            val mixed = willContinue && willEmitNew
            val builder = GestureDescription.Builder()
            var strokeCount = 0
            val queuedForGesture = ArrayList<Pending>()
            // AOSP appends a new pointer after all open pointers but uses its gesture-step
            // index for ACTION_POINTER_DOWN. Keep continued channels first, even when attack
            // was held before the joystick, so that action index addresses the new pointer.
            for (ch in channels.sortedBy { if (it.isDown && it.lastStroke != null) 0 else 1 }) {
                val stroke = strokeFor(ch, mixed) ?: continue
                builder.addStroke(stroke); strokeCount++
            }
            while (strokeQueue.isNotEmpty() && strokeCount < MAX_STROKES_PER_GESTURE) {
                val p = strokeQueue.removeFirst()
                if (p.authority?.allows("pendingStroke") == false) continue
                queuedForGesture.add(p)
                val path = Path().apply { moveTo(sx(p.x1), sy(p.y1)); lineTo(sx(p.x2), sy(p.y2)) }
                val startTimeMs = InputGestureComposition.startTimeFor(isContinuation = false, mixed = mixed)
                val durationMs = InputGestureComposition.durationFor(p.durationMs, isContinuation = false, mixed = mixed)
                builder.addStroke(GestureDescription.StrokeDescription(path, startTimeMs, durationMs, false))
                strokeCount++
            }
            if (strokeCount == 0) return
            val gesture = try { builder.build() } catch (t: Throwable) {
                PylaLog.w(TAG, "gesture build failed: ${t.message}")
                channels.forEach { it.reset() }
                restorePending(queuedForGesture)
                handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
                return
            }
            val deniedChannels = channels.mapIndexed { i, ch -> before[i].wanted && ch.authority?.allows() == false }
            val deniedTap = queuedForGesture.any { it.authority?.allows() == false }
            if (deniedChannels.any { it } || deniedTap) {
                // Nothing was sent. Undo ALL speculative channel mutations, not just the denied
                // channel: otherwise an unaffected joystick could continue an unsent predecessor.
                channels.forEachIndexed { i, ch ->
                    ch.restore(before[i])
                    if (deniedChannels[i]) ch.wantDown = false
                }
                restorePending(queuedForGesture)
                continue
            }
        gestureInFlight = true
        val diagnosticDispatchId = ++diagnosticDispatchSeq
        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    DiagnosticLog.sample("input.gesture.completed", "dispatch=$diagnosticDispatchId strokes=$strokeCount (Android callback; not game-effect proof)")
                    gestureInFlight = false
                    pump()
                }

                override fun onCancelled(g: GestureDescription?) {
                    DiagnosticLog.event("input.gesture.cancelled", "dispatch=$diagnosticDispatchId strokes=$strokeCount restored=${queuedForGesture.size}")
                    // Android has already lifted every pointer of the cancelled gesture. Drop the
                    // established chains so the next dispatch presses fresh, but preserve each logical
                    // want: WindowController suppresses an unchanged movement target, so a dropped
                    // joystick want would stop movement permanently. See InputCancellationRecovery.
                    for (ch in listOf(joystickCh, attackCh)) {
                        val recovered = InputCancellationRecovery.afterCancel(ch.wantDown)
                        ch.reset()
                        ch.wantDown = recovered.wantDown
                    }
                    restorePending(queuedForGesture)
                    gestureInFlight = false
                    handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
                }
            }, handler)
        } catch (t: Throwable) {
            PylaLog.w(TAG, "dispatch failed: ${t.message}")
            false
        }
        DiagnosticLog.sample("input.gesture.dispatch", "dispatch=$diagnosticDispatchId accepted=$accepted strokes=$strokeCount (API acknowledgement only)")
        if (!accepted) {
            DiagnosticLog.event("input.gesture.rejected", "dispatch=$diagnosticDispatchId strokes=$strokeCount restored=${queuedForGesture.size}")
            gestureInFlight = false
            joystickCh.reset()
            attackCh.reset()
            restorePending(queuedForGesture)
            handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
        }
            return
        }
        handler.postDelayed({ pump() }, DISPATCH_RETRY_MS)
    }

    private fun sx(v: Float): Float = InputCoordinates.toScreenX(v)
    private fun sy(v: Float): Float = InputCoordinates.toScreenY(v)

    private fun strokeFor(ch: Channel, mixed: Boolean): GestureDescription.StrokeDescription? {
        val prev = ch.lastStroke
        return when {
            ch.wantDown && !ch.isDown -> {
                val path = transitionPath(ch.downX, ch.downY, ch.targetX, ch.targetY)
                // New pointer in this gesture: offset it when the gesture also continues an open one.
                val startTimeMs = InputGestureComposition.startTimeFor(isContinuation = false, mixed = mixed)
                val durationMs = InputGestureComposition.durationFor(MOVE_DURATION_MS, isContinuation = false, mixed = mixed)
                val s = GestureDescription.StrokeDescription(path, startTimeMs, durationMs, true)
                ch.isDown = true
                ch.curX = ch.targetX; ch.curY = ch.targetY
                ch.lastStroke = s
                s
            }
            !ch.wantDown && ch.isDown && prev != null -> {
                val path = stationaryPath(ch.curX, ch.curY)
                val s = prev.continueStroke(path, 0L, RELEASE_DURATION_MS, false)
                ch.reset()
                s
            }
            ch.isDown && prev != null -> {
                val targetChanged = !samePoint(ch.curX, ch.curY, ch.targetX, ch.targetY)
                val path = if (targetChanged) {
                    transitionPath(ch.curX, ch.curY, ch.targetX, ch.targetY)
                } else {
                    keepAlivePath(ch)
                }
                val duration = if (targetChanged) MOVE_DURATION_MS else HOLD_DURATION_MS
                val s = prev.continueStroke(path, 0L, duration, true)
                // Both paths end at the nominal target. Keeping this exact is essential:
                // continueStroke rejects/cancels a chain if the next path does not start where the
                // previous one really ended.
                ch.curX = ch.targetX; ch.curY = ch.targetY
                ch.lastStroke = s
                s
            }
            else -> null
        }
    }

    private fun samePoint(x1: Float, y1: Float, x2: Float, y2: Float): Boolean =
        kotlin.math.abs(x1 - x2) < 0.01f && kotlin.math.abs(y1 - y2) < 0.01f

    private fun transitionPath(fromX: Float, fromY: Float, toX: Float, toY: Float): Path =
        Path().apply {
            moveTo(sx(fromX), sy(fromY))
            lineTo(sx(toX), sy(toY))
        }

    private fun stationaryPath(x: Float, y: Float): Path = Path().apply {
        val px = sx(x); val py = sy(y)
        moveTo(px, py); lineTo(px, py)
    }

    /**
     * Keeps a stationary pointer alive without changing its final position.
     *
     * Some Android builds treat a completely zero-length continuation as a completed tap. A tiny
     * radial out-and-back path is non-degenerate, but ends at exactly the same point where it began.
     * Therefore the next continueStroke starts at the required endpoint and the OS never cancels or
     * re-presses the joystick. The excursion is one screen pixel along the current joystick radius,
     * so it cannot introduce sideways direction wobble.
     */
    private fun keepAlivePath(ch: Channel): Path {
        val px = sx(ch.curX); val py = sy(ch.curY)
        val centerX = sx(ch.downX); val centerY = sy(ch.downY)
        val dx = px - centerX; val dy = py - centerY
        val magnitude = kotlin.math.hypot(dx, dy)
        val ux = if (magnitude >= 0.5f) dx / magnitude else 1f
        val uy = if (magnitude >= 0.5f) dy / magnitude else 0f
        return Path().apply {
            moveTo(px, py)
            lineTo(px + ux * KEEP_ALIVE_PX, py + uy * KEEP_ALIVE_PX)
            lineTo(px, py)
        }
    }

    companion object {
        private const val TAG = "PylaInput"
        // Direction changes complete quickly; steady holds use longer closed continuations to reduce
        // main-thread dispatch handoffs while preserving a continuously-down pointer.
        private const val MOVE_DURATION_MS = 32L
        private const val HOLD_DURATION_MS = 96L
        private const val KEEP_ALIVE_PX = 1f
        private const val RELEASE_DURATION_MS = 16L
        private const val MAX_STROKES_PER_GESTURE = 8
        private const val MAX_QUEUED_STROKES = 16
        private const val DISPATCH_RETRY_MS = 24L

        @Volatile private var instance: InputService? = null
        @Volatile private var foregroundPkg: String? = null

        fun get(): InputService? = instance
        fun isConnected(): Boolean = instance != null
        fun foregroundPackage(): String? = foregroundPkg
    }
}

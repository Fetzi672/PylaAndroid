package com.pyla.ai.engine

import android.os.SystemClock
import com.pyla.ai.capture.CaptureService
import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.input.InputService

class FrameSnapshot(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val capturedAtMs: Long,
) {
    fun ageMs(): Long =
        if (capturedAtMs <= 0L) Long.MAX_VALUE
        else (SystemClock.elapsedRealtime() - capturedAtMs).coerceAtLeast(0L)
}

class WindowController(
    val captureService: CaptureService,
    private val captureWidth: Int = InputCoordinates.captureWidth.coerceAtLeast(1),
    private val captureHeight: Int = InputCoordinates.captureHeight.coerceAtLeast(1),
) {
    init {
        InputCoordinates.setCaptureSize(captureWidth, captureHeight)
    }

    var width: Int = 0
        private set
    var height: Int = 0
        private set
    val widthRatio: Float get() = width.toFloat() / 1920f
    val heightRatio: Float get() = height.toFloat() / 1080f
    val scaleFactor: Float get() = minOf(widthRatio, heightRatio)

    private var areWeMoving = false
    private var lastJoystickX: Float? = null
    private var lastJoystickY: Float? = null
    private val reApplyMovement: Boolean
        get() = PylaUtils.configBool(
            com.pyla.ai.config.PylaConfig.load("cfg/debug_settings.toml").opt("re_apply_movement"),
            false,
        )

    fun screenshot(): FrameSnapshot {
        var snap = latestFrameNow()
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (snap == null || snap.ageMs() > FRAME_STALE_TIMEOUT_MS) {
            if (SystemClock.elapsedRealtime() > deadline) {
                throw IllegalStateException("No fresh frame from MediaProjection")
            }
            try { Thread.sleep(50) } catch (_: InterruptedException) {
                throw IllegalStateException("Interrupted while waiting for a capture frame")
            }
            snap = latestFrameNow()
        }
        if (width != snap.width || height != snap.height) {
            width = snap.width
            height = snap.height
            InputCoordinates.setCaptureSize(width, height)
            PylaLog.p(TAG, "capture size now ${width}x${height} (rotation)")
        }
        return snap
    }

    fun getLatestFrame(): Pair<FrameSnapshot?, Long> {
        val frame = latestFrameNow()
        return frame to (frame?.capturedAtMs ?: 0L)
    }

    private var copyBuffer: IntArray? = null

    private fun latestFrameNow(): FrameSnapshot? {
        val timestamp = captureService.latestTimestampMs()
        if (timestamp <= 0L || SystemClock.elapsedRealtime() - timestamp > FRAME_STALE_TIMEOUT_MS) return null
        val copy = captureService.copyLatestFrame(copyBuffer) ?: return null
        copyBuffer = copy.rgbBuffer
        return FrameSnapshot(copy.width, copy.height, copy.rgbBuffer, copy.capturedAtMs)
    }

    private fun inputOrWarn(operation: String): InputService? {
        val input = InputService.get()
        if (input == null) PylaLog.w(TAG, "$operation ignored: InputService not connected")
        return input
    }

    fun move(x: Float, y: Float) {
        val input = inputOrWarn("move") ?: return

        // Match PC WindowController.move: press once, then only send a new logical MOVE when the
        // target changes unless debug_settings.re_apply_movement asks for repeated MOVE events.
        // InputService independently keeps the already-down accessibility pointer alive.
        val unchanged = areWeMoving && lastJoystickX == x && lastJoystickY == y
        if (unchanged && !reApplyMovement) return

        DiagnosticLog.sample("window.joystick.requested", "x=$x y=$y")
        input.joystickMove(x, y)
        areWeMoving = true
        lastJoystickX = x
        lastJoystickY = y
    }

    /** Live gameplay authority also guards renewals, not just the initial joystick DOWN. */
    fun moveGuarded(dx: Float, dy: Float, guard: () -> Boolean) {
        val input = inputOrWarn("moveGuarded") ?: return
        val allowed = try { guard() } catch (_: Exception) { false }
        if (!allowed || !dx.isFinite() || !dy.isFinite()) {
            releaseMovement()
            return
        }
        val limit = 75f
        val m = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val sx = if (m > limit && m > 0f) dx * limit / m else dx
        val sy = if (m > limit && m > 0f) dy * limit / m else dy
        // Do not dedupe: even an unchanged direction must carry the current guard to the queue.
        lastJoystickX = sx; lastJoystickY = sy; areWeMoving = true
        DiagnosticLog.sample("window.joystick.requested", "x=$sx y=$sy guarded=true")
        input.joystickMoveGuarded(sx, sy, guard)
    }

    fun releaseMovement() {
        val input = InputService.get() ?: return
        if (areWeMoving) {
            DiagnosticLog.event("window.joystick.release_requested", "last=($lastJoystickX, $lastJoystickY)")
            input.releaseJoystick()
            areWeMoving = false
            lastJoystickX = null
            lastJoystickY = null
        }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 500) {
        val input = inputOrWarn("swipe") ?: return
        PylaLog.p(TAG, "swipe ($x1,$y1) -> ($x2,$y2) ${durationMs}ms")
        input.swipeAt(x1, y1, x2, y2, durationMs)
    }

    fun releaseAndTap(x: Float, y: Float, holdMs: Long = 40) {
        val input = inputOrWarn("releaseAndTap") ?: return
        PylaLog.p(TAG, "releaseAndTap at ($x,$y) hold=${holdMs}ms")
        input.releaseJoystick()
        input.tapAt(x, y, holdMs)
    }

    /** New navigation actions must retain their capture/lifecycle authority through the queue. */
    fun guardedNavigationTap(x: Float, y: Float, guard: () -> Boolean) {
        val input = inputOrWarn("guardedNavigationTap") ?: return
        if (!guard()) return
        releaseMovement()
        input.releaseJoystick()
        input.navigationTapOnce(x, y, 40L, guard)
    }

    /**
     * Mapped navigation press with a live lifecycle guard.
     *
     * Resolves [key] through [InputCoordinates.press] exactly like [press] does for non-combat
     * keys, then pends it on the non-replayable navigation queue ([InputService.navigationTapOnce]):
     * one admission attempt at issue and one at dispatch, and it is never restored by combat
     * recovery. Retains the non-combat [press] hold semantics (`delayMs + 20`).
     *
     * The caller passes the [guard] bound to the request's lifecycle epoch (see
     * [NavigationAuthority]); a false or throwing guard fails closed and stays denied. This is the
     * guarded path for menu navigation. The generic [press] intentionally drops [guard] for
     * non-combat keys and routes combat keys through the combat composition instead.
     */
    fun guardedNavigationPress(key: String, delayMs: Long = 20, guard: () -> Boolean) {
        val (bx, by) = InputCoordinates.press(key)
        if (bx < 0) { PylaLog.w(TAG, "guardedNavigationPress ignored: unknown key '$key'"); return }
        val input = inputOrWarn("guardedNavigationPress") ?: return
        if (!guard()) return
        PylaLog.p(TAG, "guarded navigation press '$key' at ($bx,$by) hold=${delayMs + 20}ms")
        BotStatus.action("guarded press '$key' at ($bx,$by)")
        BotStatus.inputConnected = com.pyla.ai.input.InputService.isConnected()
        releaseMovement()
        input.releaseJoystick()
        input.navigationTapOnce(bx.toFloat(), by.toFloat(), delayMs + 20, guard)
    }

    /**
 * Raw click at a screen point. [guard] applies only to the held-attack (`touchDown` without
 * `touchUp`) branch; the plain tap and the release-only branch are never gated.
 */
    @JvmOverloads
    fun click(
        x: Float,
        y: Float,
        delayMs: Long = 20,
        touchUp: Boolean = true,
        touchDown: Boolean = true,
        guard: (() -> Boolean)? = null,
    ) {
        val input = inputOrWarn("click") ?: return
        if (touchDown) {
            if (touchUp) {
                PylaLog.p(TAG, "click at ($x,$y) hold=${delayMs + 20}ms")
                input.tapAt(x, y, (delayMs + 20))
            } else {
                PylaLog.p(TAG, "hold attack at ($x,$y)")
                input.holdAttackAt(x, y, guard)
            }
        } else if (touchUp) {
            PylaLog.p(TAG, "release attack key")
            input.releaseKey("attack")
        }
    }

    /**
 * Presses a mapped screen point.
 *
 * [guard] is optional screen authority for the combat DOWN this call produces and defaults to
 * `null`, which preserves the exact historical behaviour for every existing caller (lobby taps,
 * swipes, movement, abilities). When supplied it is handed to the input service and re-evaluated
 * there at admission, dispatch and every held continuation; a false or throwing guard fails closed.
 * Release-only presses (`touchUp` without `touchDown`) are never gated, so a pointer can always be
 * lifted even with no positive screen authority.
 */
    @JvmOverloads
    fun press(
        key: String,
        delayMs: Long = 20,
        touchUp: Boolean = true,
        touchDown: Boolean = true,
        guard: (() -> Boolean)? = null,
    ) {
        val (bx, by) = InputCoordinates.press(key)
        if (bx < 0) { PylaLog.w(TAG, "press ignored: unknown key '$key'"); return }
        PylaLog.p(TAG, "press '$key' at ($bx,$by) hold=${delayMs}ms up=$touchUp down=$touchDown")
        BotStatus.action("press '$key' at ($bx,$by)")
        BotStatus.inputConnected = com.pyla.ai.input.InputService.isConnected()
        if (key in COMBAT_KEYS) {
            val input = inputOrWarn("press") ?: return
            if (touchDown && touchUp) {
                input.pressAndRelease(bx.toFloat(), by.toFloat(), delayMs + 40, guard)
            } else {
                click(bx.toFloat(), by.toFloat(), delayMs, touchUp, touchDown, guard)
            }
            return
        }
        releaseAndTap(bx.toFloat(), by.toFloat(), delayMs + 20)
    }

    /** A visually located combat control, with the same queued combat guard as mapped presses. */
    fun pressCombatAt(x: Float, y: Float, guard: () -> Boolean) {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return
        if (!guard()) return
        inputOrWarn("pressCombatAt")?.pressAndRelease(x, y, 60L, guard)
    }

    /** A stationary charge at a visually verified control; never a drag toward an enemy. */
    fun holdCombatAt(x: Float, y: Float, guard: () -> Boolean) {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return
        if (!guard()) return
        inputOrWarn("holdCombatAt")?.holdAttackAt(x, y, guard)
    }

    val joystickX: Float get() = InputCoordinates.joystick().first
    val joystickY: Float get() = InputCoordinates.joystick().second

    companion object {
        private const val TAG = "PylaWindowController"
        const val FRAME_STALE_TIMEOUT_MS = 5_000L
        private val COMBAT_KEYS = setOf("attack", "super", "gadget", "hypercharge")
    }
}
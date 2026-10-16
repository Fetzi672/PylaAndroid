package com.pyla.ai.overlay

/** Discrete, user-visible overlay stages. */
enum class OverlayStage { CLOSED, REVEALED, MENU_OPEN }

/**
 * Deterministic timing/transition model for the bubble + action menu. It owns no Android objects
 * and reads no clock directly: the caller supplies `nowMs`, which makes every transition a pure
 * function of the input timeline and therefore exactly testable on the JVM.
 *
 * Two independent progress values drive the renderer:
 *  - [revealProgress]: 0 = half hidden at the docked edge, 1 = fully visible.
 *  - [menuProgress]:   0 = controls hidden, 1 = controls fully expanded.
 *
 * Two-stage tap contract: while any animation is in flight, taps are ignored so a fast double tap
 * during the reveal slide can never accidentally open the menu.
 */
class OverlayController(
    private val revealDurationMs: Long = 180L,
    private val menuDurationMs: Long = 160L,
    private val autoHideDelayMs: Long = 3_000L,
) {
    var stage: OverlayStage = OverlayStage.CLOSED
        private set
    var revealProgress: Float = 0f
        private set
    var menuProgress: Float = 0f
        private set
    var animating: Boolean = false
        private set

    private var revealFrom = 0f
    private var revealTo = 0f
    private var menuFrom = 0f
    private var menuTo = 0f
    private var animStartMs = 0L
    private var animDurationMs = 0L
    private var pendingStage: OverlayStage? = null

    /** Absolute uptime at which an idle, fully-revealed bubble should retract; -1 disables. */
    var autoHideAtMs: Long = -1L
        private set

    /** True while the action controls should be present and drawn. */
    val controlsVisible: Boolean get() = menuProgress > 0.0001f || stage == OverlayStage.MENU_OPEN

    /** A tap may only be acted on when no animation is running. */
    val acceptsTap: Boolean get() = !animating

    private fun ease(t: Float): Float = 1f - (1f - t) * (1f - t)

    private fun beginAnimation(nowMs: Long, durationMs: Long, revealTarget: Float, menuTarget: Float, endStage: OverlayStage) {
        revealFrom = revealProgress
        revealTo = revealTarget.coerceIn(0f, 1f)
        menuFrom = menuProgress
        menuTo = menuTarget.coerceIn(0f, 1f)
        animStartMs = nowMs
        animDurationMs = durationMs
        pendingStage = endStage
        if (durationMs <= 0L) {
            revealProgress = revealTo
            menuProgress = menuTo
            animating = false
            enterStage(nowMs, endStage)
        } else {
            animating = true
        }
    }

    private fun enterStage(nowMs: Long, next: OverlayStage) {
        stage = next
        autoHideAtMs = if (next == OverlayStage.REVEALED) nowMs + autoHideDelayMs else -1L
    }

    /** Advance the timeline. Returns true when any visible value changed. */
    fun onTick(nowMs: Long): Boolean {
        var changed = false
        if (animating) {
            val raw = ((nowMs - animStartMs).toFloat() / animDurationMs.toFloat()).coerceIn(0f, 1f)
            val e = ease(raw)
            val nr = revealFrom + (revealTo - revealFrom) * e
            val nm = menuFrom + (menuTo - menuFrom) * e
            changed = nr != revealProgress || nm != menuProgress
            revealProgress = nr
            menuProgress = nm
            if (raw >= 1f) {
                animating = false
                revealProgress = revealTo
                menuProgress = menuTo
                val end = pendingStage ?: stage
                pendingStage = null
                enterStage(nowMs, end)
                changed = true
            }
        }
        if (!animating && stage == OverlayStage.REVEALED && autoHideAtMs in 0..nowMs) {
            beginAnimation(nowMs, revealDurationMs, 0f, 0f, OverlayStage.CLOSED)
            changed = true
        }
        return changed
    }

    /**
     * A tap on the main bubble. Returns true when the tap was consumed. Taps during an animation
     * are dropped (never queued) so the reveal completion is deterministic.
     */
    fun onTap(nowMs: Long): Boolean {
        if (animating) return false
        when (stage) {
            OverlayStage.CLOSED ->
                beginAnimation(nowMs, revealDurationMs, 1f, 0f, OverlayStage.REVEALED)
            OverlayStage.REVEALED -> {
                autoHideAtMs = -1L
                beginAnimation(nowMs, menuDurationMs, 1f, 1f, OverlayStage.MENU_OPEN)
            }
            OverlayStage.MENU_OPEN ->
                beginAnimation(nowMs, menuDurationMs, 1f, 0f, OverlayStage.REVEALED)
        }
        return true
    }

    /** Dragging begins: close any menu instantly and reveal the bubble fully for the gesture. */
    fun onDragStart() {
        animating = false
        pendingStage = null
        menuProgress = 0f
        revealProgress = 1f
        stage = OverlayStage.REVEALED
        autoHideAtMs = -1L
    }

    /** Dragging ends after a snap/persist decision: bubble stays revealed, then auto-hides. */
    fun onDragEnd(nowMs: Long) {
        animating = false
        pendingStage = null
        revealProgress = 1f
        menuProgress = 0f
        stage = OverlayStage.REVEALED
        autoHideAtMs = nowMs + autoHideDelayMs
    }

    /** Immediate, non-animated retract used when the menu must collapse (e.g. teardown). */
    fun cancelToClosed() {
        animating = false
        pendingStage = null
        revealProgress = 0f
        menuProgress = 0f
        stage = OverlayStage.CLOSED
        autoHideAtMs = -1L
    }
}

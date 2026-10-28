package com.pyla.ai.diagnostics

import java.util.Arrays

/**
 * Bounded, RAM-only aggregation for pipeline timing diagnostics.
 *
 * Pure Kotlin: the clock and the sink are injected, so unit tests need no
 * Android runtime. [PipelineTimingTelemetry] wires the real `SystemClock` and
 * `DiagnosticLog`.
 *
 * Semantics:
 * - Every stage keeps a ring of the last [SAMPLE_CAPACITY] accepted samples plus
 *   a monotonic `n_total`. Nothing else is retained per sample.
 * - Samples are aggregated once per [WINDOW_MS]; no sorting happens on the
 *   recording path. Each flush emits at most one short line per stage that
 *   received samples in that window (~17 lines per 5 s), plus optional model /
 *   interruption metadata lines. Detail length is far below the log cap.
 * - `p50`/`p95` are exact nearest-rank values of the *retained* samples
 *   (`p50` = lower median for even counts); `sample=last128` states that
 *   explicitly. `max` covers the whole window, including samples evicted from
 *   the ring.
 * - Rejected input (negative or overflowed durations, `Long.MIN_VALUE` starts)
 *   is ignored, never clamped into a misleading value.
 * - The sink is called outside the internal lock; a throwing sink or clock can
 *   never propagate to the caller or corrupt accumulated state.
 * - [reset] discards the pending partial window, so a new run never emits
 *   samples or metadata observed before the reset.
 */
class PipelineTimingAccumulator(
    private val clockMs: () -> Long,
    private val sink: (kind: String, detail: String) -> Unit,
) {
    companion object {
        const val SAMPLE_CAPACITY = 128
        const val WINDOW_MS = 5_000L
        const val MAX_THREADS = 1_024

        private val ROLES = setOf("entity", "tile", "tile_centered")
        private val PROVIDERS = setOf("cpu", "gpu", "auto", "NNAPI", "XNNPACK", "CPU", "UNKNOWN")

        /** Fixed vocabulary; anything else is reported as OTHER. */
        val INTERRUPTION_REASONS = listOf(
            "NO_STRATEGY", "STRATEGY_FAILED", "PLAYER_MISSING", "PLAYER_AMBIGUOUS",
            "PLAYER_BOX_INVALID", "INPUT_DISABLED", "FRAME_STALE", "STOP", "PAUSED",
            "FOREGROUND", "GEOMETRY_CHANGED", "AUTHORITY_DENIED", "NONFINITE",
            "CONTRACT_REJECTED", "STILL", "RELEASE_REQUEST", "APPLY_REVOKED",
            "APPLY_EXCEPTION", "ANDROID_CANCELLED", "ANDROID_REJECTED",
            "ANDROID_DISPATCH_EXCEPTION", "STATE_MENU_REVOKED", "STALE_FRAME",
            "FUTURE_FRAME", "SAME_FRAME_REPEATED", "AWAITING_SECOND_OBSERVATION",
            "CLOSED", "ISSUING_THREAD_INTERRUPTED", "GENERATION_CHANGED", "OTHER",
        )

        /** Observed-gap stage: interval between accepted requests, not a physical hold. */
        private val GAP = PipelineTimingTelemetry.Stage.MOVEMENT_REQUEST_GAP

        private fun isFrameAge(stage: PipelineTimingTelemetry.Stage): Boolean = when (stage) {
            PipelineTimingTelemetry.Stage.FRAME_AGE_START,
            PipelineTimingTelemetry.Stage.FRAME_AGE_DECISION,
            PipelineTimingTelemetry.Stage.FRAME_AGE_GUARD,
            PipelineTimingTelemetry.Stage.FRAME_AGE_DISPATCH -> true
            else -> false
        }
    }

    private class StageState {
        val samples = LongArray(SAMPLE_CAPACITY)
        var cursor = 0
        var nSample = 0
        var nWindow = 0
        var nTotal = 0L
        var maxWindow = Long.MIN_VALUE
    }

    private class Emission(val kind: String, val detail: String)

    private val lock = Any()
    private val stages = Array(PipelineTimingTelemetry.Stage.entries.size) { StageState() }
    private var anchorMs: Long? = null
    // Only the three fixed roles plus UNKNOWN can be keys; no user-defined cardinality.
    private val models = LinkedHashMap<String, String>()
    private var modelDirty = false
    private val pendingInterruptions = IntArray(INTERRUPTION_REASONS.size)
    private var lastRequestMs: Long? = null

    /** Monotonic start of the current run; older starts are not attributed to it. */
    @Volatile
    private var resetEpochMs: Long? = null

    fun record(stage: PipelineTimingTelemetry.Stage, valueMs: Long) {
        if (valueMs < 0L) return
        val now = nowOrNull()
        var lines: List<Emission>? = null
        synchronized(lock) {
            addSample(stage, valueMs)
            if (now != null) lines = advanceLocked(now)
        }
        lines?.let(::emitAll)
    }

    /**
     * Records the duration since an observed start and returns it, or -1 when the
     * interval is negative, starts in the future or overflows. The single call is
     * deliberate: callers must not add their own [record] for the same interval.
     */
    fun elapsed(stage: PipelineTimingTelemetry.Stage, startedAtMs: Long): Long {
        val epoch = resetEpochMs
        if (epoch != null && startedAtMs < epoch) return -1L
        val now = nowOrNull() ?: return -1L
        val gap = subtractOrNull(now, startedAtMs) ?: return -1L
        if (gap < 0L) return -1L
        record(stage, gap)
        return gap
    }

    /** Original age of an already-captured frame; the capture timestamp is never restamped. */
    fun age(stage: PipelineTimingTelemetry.Stage, capturedAtMs: Long) {
        if (!isFrameAge(stage) || capturedAtMs <= 0L) return
        val epoch = resetEpochMs
        if (epoch != null && capturedAtMs < epoch) return
        val now = nowOrNull() ?: return
        if (capturedAtMs > now) return
        val gap = subtractOrNull(now, capturedAtMs) ?: return
        if (gap < 0L) return
        record(stage, gap)
    }

    /** Gap between observed accepted requests (not a physical hold). First request opens the interval. */
    fun movementRequest() {
        val now = nowOrNull() ?: return
        var lines: List<Emission>? = null
        synchronized(lock) {
            val last = lastRequestMs
            lastRequestMs = now
            if (last != null && last <= now) {
                val gap = subtractOrNull(now, last)
                if (gap != null && gap >= 0L) {
                    addSample(GAP, gap)
                    lines = advanceLocked(now)
                }
            }
        }
        lines?.let(::emitAll)
    }

    fun model(role: String, requested: String, effective: String, threads: Int) {
        val safeRole = if (role in ROLES) role else "UNKNOWN"
        val safeRequested = if (requested in PROVIDERS) requested else "UNKNOWN"
        val safeEffective = if (effective in PROVIDERS) effective else "UNKNOWN"
        val safeThreads = threads.coerceIn(0, MAX_THREADS)
        val line = "role=$safeRole requested=$safeRequested effective=$safeEffective " +
            "threads=$safeThreads hardware=UNVERIFIED"
        synchronized(lock) {
            if (models[safeRole] != line) modelDirty = true
            models[safeRole] = line
        }
    }

    fun interruption(reason: String) {
        val index = INTERRUPTION_REASONS.indexOf(reason).let { if (it >= 0) it else INTERRUPTION_REASONS.lastIndex }
        synchronized(lock) {
            if (pendingInterruptions[index] < Int.MAX_VALUE) pendingInterruptions[index]++
            // A stop/denial ends the acceptance streak: do not report the pause as a request gap.
            lastRequestMs = null
        }
    }

    /** Force a partial window out (e.g. shutdown). No-op when nothing is pending. */
    fun flush() {
        val now = nowOrNull() ?: return
        var lines: List<Emission>? = null
        synchronized(lock) { lines = flushLocked(now) }
        lines?.let(::emitAll)
    }

    fun reset() {
        synchronized(lock) {
            for (state in stages) {
                Arrays.fill(state.samples, 0L)
                state.cursor = 0
                state.nSample = 0
                state.nWindow = 0
                state.nTotal = 0L
                state.maxWindow = Long.MIN_VALUE
            }
            Arrays.fill(pendingInterruptions, 0)
            anchorMs = null
            models.clear()
            modelDirty = false
            lastRequestMs = null
            // If the reset clock fails, omit interval/age observations until a valid reset.
            // Never silently disable the epoch check and attribute an old run to this one.
            resetEpochMs = nowOrNull() ?: Long.MAX_VALUE
        }
    }

    internal fun sampleCount(stage: PipelineTimingTelemetry.Stage): Int = synchronized(lock) { stages[stage.ordinal].nSample }

    internal fun totalCount(stage: PipelineTimingTelemetry.Stage): Long = synchronized(lock) { stages[stage.ordinal].nTotal }

    private fun addSample(stage: PipelineTimingTelemetry.Stage, valueMs: Long) {
        val state = stages[stage.ordinal]
        state.samples[state.cursor] = valueMs
        state.cursor = (state.cursor + 1) % SAMPLE_CAPACITY
        if (state.nSample < SAMPLE_CAPACITY) state.nSample++
        if (state.nWindow < Int.MAX_VALUE) state.nWindow++
        if (state.nTotal < Long.MAX_VALUE) state.nTotal++
        if (valueMs > state.maxWindow) state.maxWindow = valueMs
    }

    /** Window bookkeeping only; never sorts, never touches the ring beyond advancing the anchor. */
    private fun advanceLocked(now: Long): List<Emission>? {
        val anchor = anchorMs
        if (anchor == null) {
            anchorMs = now
            return null
        }
        if (now < anchor) {
            // Clock rollback: re-anchor, keep samples, do not emit a bogus window.
            anchorMs = now
            return null
        }
        if (now - anchor < WINDOW_MS) return null
        return flushLocked(now)
    }

    private fun flushLocked(now: Long): List<Emission>? {
        anchorMs = now
        val out = ArrayList<Emission>(PipelineTimingTelemetry.Stage.entries.size + 2)

        if (!modelDirty && stages.none { it.nWindow > 0 } && pendingInterruptions.none { it > 0 }) return null
        // Repeat role metadata alongside populated windows so bounded-log eviction is harmless.
        for (line in models.values) out.add(Emission("timing.model", line))
        modelDirty = false

        var interruptionLine: String? = null
        for (index in pendingInterruptions.indices) {
            val count = pendingInterruptions[index]
            if (count <= 0) continue
            pendingInterruptions[index] = 0
            val token = INTERRUPTION_REASONS[index]
            interruptionLine = if (interruptionLine == null) "interruptions $token=$count"
            else "$interruptionLine $token=$count"
        }
        interruptionLine?.let { out.add(Emission("timing.interruptions", it)) }

        for (stage in PipelineTimingTelemetry.Stage.entries) {
            val state = stages[stage.ordinal]
            if (state.nWindow <= 0) continue
            val sorted = LongArray(state.nSample)
            System.arraycopy(state.samples, 0, sorted, 0, state.nSample)
            sorted.sort()
            val p50 = sorted[(sorted.size - 1) / 2]
            val p95 = sorted[rank95(sorted.size)]
            val boundary = if (stage == PipelineTimingTelemetry.Stage.FRAME_AGE_DISPATCH) {
                " boundary=request_before_queue"
            } else if (stage == PipelineTimingTelemetry.Stage.FRAME_AGE_GUARD) {
                " boundary=authority_check"
            } else ""
            out.add(
                Emission(
                    "timing." + stage.name.lowercase(),
                    "p50=${p50}ms p95=${p95}ms max=${state.maxWindow}ms n=${state.nWindow} " +
                        "n_total=${state.nTotal} n_sample=${sorted.size} window=${WINDOW_MS}ms " +
                        "sample=last128$boundary",
                ),
            )
            state.nWindow = 0
            state.maxWindow = Long.MIN_VALUE
            // Quantiles and max must describe the same window, not mix old and new frames.
            state.cursor = 0
            state.nSample = 0
        }
        return if (out.isEmpty()) null else out
    }

    /** Nearest-rank p95 index without floating point: ceil(0.95 * n) - 1. */
    private fun rank95(n: Int): Int = (95 * n + 99) / 100 - 1

    private fun nowOrNull(): Long? = try {
        clockMs()
    } catch (_: Throwable) {
        null
    }

    private fun subtractOrNull(a: Long, b: Long): Long? = try {
        Math.subtractExact(a, b)
    } catch (_: ArithmeticException) {
        null
    }

    private fun emitAll(lines: List<Emission>) {
        for (line in lines) {
            try {
                sink(line.kind, line.detail)
            } catch (_: Throwable) {
                // Diagnostics must never affect control flow.
            }
        }
    }
}

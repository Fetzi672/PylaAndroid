package com.pyla.ai.engine

/**
 * Immutable, allocation-bounded performance view for the top-center HUD.
 *
 * Every value is nullable: `null` means "no honest measurement is currently available" and the UI
 * must render it as an unavailable placeholder. A metric is never fabricated, never carried over
 * from a previous run, and never substituted with `0.0` for a stage that did not execute.
 *
 * Units: [loopMs], [detectMs], [styleMs], [inputMs] are milliseconds of work, [ips] is completed
 * gameplay-processing cycles per measured wall-clock second, [frameAgeMs] is the age of the capture
 * that the frame was processed from at the moment processing completed.
 */
data class HudTelemetrySnapshot(
    /** Duration of one main-loop iteration body, i.e. processing work without the throttle sleep. */
    val loopMs: Double? = null,
    /** Completed gameplay-processing calls per measured wall second (not an action or detection rate). */
    val ips: Double? = null,
    /** Time inside the entity-detection inference call for the most recent processed frame. */
    val detectMs: Double? = null,
    /** Time inside the `.pyla` playstyle call; null when no script ran for that frame. */
    val styleMs: Double? = null,
    /** Time spent handing that frame's input to the dispatch path (submission, not Android completion). */
    val inputMs: Double? = null,
    /** Age of the capture the frame was processed from, at processing completion. */
    val frameAgeMs: Long? = null,
    /** Actual ONNX Runtime execution provider of the successful detection session (NNAPI/XNNPACK/CPU). */
    val backend: String? = null,
    /** Display name of the playstyle metadata that was actually loaded, not the requested one. */
    val playstyle: String? = null,
) {
    companion object {
        /** Empty snapshot: every metric unavailable. */
        val EMPTY = HudTelemetrySnapshot()
    }
}

/**
 * Bounded, lifecycle-aware recorder behind [HudTelemetry].
 *
 * Design constraints (HUD performance revision):
 *  * **Explicit monotonic time.** Nothing here reads a clock. Every writer supplies the timestamp of
 *    the sample on the same monotonic base the reader later passes to [snapshot]. This keeps the
 *    aggregator deterministic and free of Android clock classes, so it is unit-testable on the JVM.
 *  * **Refuse rather than guess.** Non-finite, negative, future-dated, zero-timestamp and
 *    implausibly long samples are rejected outright instead of being clamped into a plausible-looking
 *    number. A rejected loop sample leaves the prior stale-checked loop value in place. Per-frame
 *    stages are published together: a missing or rejected stage clears its preceding value, so a
 *    denied frame cannot inherit an earlier successful frame's processing costs.
 *  * **Staleness.** Per-frame metrics expire after [sampleMaxAgeMs]; a HUD refresh that happens long
 *    after the last processed frame shows `null` instead of a frozen number. Session facts (backend,
 *    playstyle) describe the loaded configuration, so they stay until the next lifecycle reset.
 *  * **Bounded storage.** The rate window is a fixed-size ring of completion timestamps; no list,
 *    map or log grows with runtime. Snapshot copies scalars and two string references only.
 *  * **Cheap publication.** The critical section is a handful of field writes under one private
 *    uncontended monitor, never held across gameplay work and never doing I/O. Writers run on the
 *    engine thread once per loop iteration; readers run at HUD refresh cadence.
 *  * **Generation binding.** [reset] advances the generation. Writers capture [generation] before
 *    doing their work and pass it back; a sample produced by a run that has since been paused,
 *    stopped or replaced is discarded, so an in-flight old iteration can never publish into the new
 *    generation's HUD.
 */
class HudTelemetryRecorder(
    val sampleMaxAgeMs: Long = DEFAULT_SAMPLE_MAX_AGE_MS,
    val rateWindowMs: Long = DEFAULT_RATE_WINDOW_MS,
    rateCapacity: Int = DEFAULT_RATE_CAPACITY,
) {
    companion object {
        /**
         * A per-frame metric older than this is not shown. Two seconds covers a slow capture cadence
         * with margin while still dropping values quickly once processing stops.
         */
        const val DEFAULT_SAMPLE_MAX_AGE_MS = 2_000L

        /** Trailing window used for the completed-cycles rate. */
        const val DEFAULT_RATE_WINDOW_MS = 3_000L

        /** Ring capacity: enough for 3 s at ~40 completed cycles/s without ever growing. */
        const val DEFAULT_RATE_CAPACITY = 128

        /**
         * No single loop/processing stage legitimately takes a minute. Larger values are treated as a
         * clock or accounting fault and refused rather than published.
         */
        const val MAX_PLAUSIBLE_STAGE_MS = 60_000.0

        /** Maximum length of an accepted backend/playstyle label; longer input is refused. */
        const val MAX_LABEL_LENGTH = 64
    }

    private val lock = Any()
    private val ring = LongArray(rateCapacity.coerceAtLeast(2))
    private var ringHead = 0
    private var ringSize = 0

    /**
     * Lifecycle generation. Volatile so hot writers can reject a superseded cycle without taking the
     * monitor, and so a stale iteration observes a reset even if it never re-reads under the lock.
     */
    @Volatile
    private var cycleGeneration: Long = 0L

    private var loopMs: Double = Double.NaN
    private var loopAtMs: Long = 0L
    private var detectMs: Double = Double.NaN
    private var detectAtMs: Long = 0L
    private var styleMs: Double = Double.NaN
    private var styleAtMs: Long = 0L
    private var inputMs: Double = Double.NaN
    private var inputAtMs: Long = 0L
    private var frameAgeMs: Long = -1L
    private var frameAgeAtMs: Long = 0L
    private var backendLabel: String? = null
    private var playstyleLabel: String? = null

    /** Current lifecycle generation; capture before work and pass it back to every write. */
    fun generation(): Long = cycleGeneration

    /**
     * Start a new telemetry generation. Called by the engine on start, stop, pause and unpause.
     * Clears every sample and the rate window; any writer still holding the previous generation is
     * rejected afterwards.
     */
    fun reset() {
        synchronized(lock) {
            cycleGeneration += 1L
            loopMs = Double.NaN; loopAtMs = 0L
            detectMs = Double.NaN; detectAtMs = 0L
            styleMs = Double.NaN; styleAtMs = 0L
            inputMs = Double.NaN; inputAtMs = 0L
            frameAgeMs = -1L; frameAgeAtMs = 0L
            backendLabel = null
            playstyleLabel = null
            ringHead = 0
            ringSize = 0
        }
    }

    /**
     * Record the processing duration of one main-loop iteration.
     *
     * @param cycle generation captured at the start of the iteration ([generation]).
     * @param nowMs monotonic timestamp of completion.
     * @param durationMs measured processing time of the iteration body, excluding the throttle sleep.
     */
    fun recordLoop(cycle: Long, nowMs: Long, durationMs: Double) {
        if (!validTime(nowMs) || !validStage(durationMs)) return
        synchronized(lock) {
            if (cycle != cycleGeneration) return
            loopMs = durationMs
            loopAtMs = nowMs
        }
    }

    /**
     * Record one completed gameplay-processing call (one `Play.main` return) together with the
     * per-stage costs of that call.
     *
     * Completion is recorded even when every stage is `null`: the call did complete and did cost wall
     * time, so it counts towards the rate. Stage values stay `null` when the stage did not run, which
     * is what keeps an early-denied or script-less frame from claiming a zero-cost, healthy frame.
     *
     * @param cycle generation captured when the call started.
     * @param completedAtMs monotonic timestamp of completion.
     * @param detectMs entity-detection inference time, or null if detection did not complete.
     * @param styleMs playstyle execution time, or null if no script ran.
     * @param inputMs input dispatch time, or null if the frame never reached dispatch.
     * @param frameAgeMs capture age at completion, or null when the capture timestamp was unusable.
     * @param backend actual execution provider reported by the detection session, if known.
     */
    fun recordGameplayFrame(
        cycle: Long,
        completedAtMs: Long,
        detectMs: Double? = null,
        styleMs: Double? = null,
        inputMs: Double? = null,
        frameAgeMs: Long? = null,
        backend: String? = null,
    ) {
        if (!validTime(completedAtMs)) return
        val detect = detectMs?.takeIf { validStage(it) }
        val style = styleMs?.takeIf { validStage(it) }
        val input = inputMs?.takeIf { validStage(it) }
        val age = frameAgeMs?.takeIf { it >= 0L && it <= MAX_PLAUSIBLE_STAGE_MS.toLong() }
        val backendValue = backend?.let { sanitizeLabel(it) }
        synchronized(lock) {
            if (cycle != cycleGeneration) return
            // One coherent completed-frame snapshot. A skipped/invalid stage must clear the
            // preceding frame's value rather than making this frame appear to have executed it.
            this.detectMs = detect ?: Double.NaN; this.detectAtMs = completedAtMs
            this.styleMs = style ?: Double.NaN; this.styleAtMs = completedAtMs
            this.inputMs = input ?: Double.NaN; this.inputAtMs = completedAtMs
            this.frameAgeMs = age ?: -1L; this.frameAgeAtMs = completedAtMs
            if (backendValue != null) backendLabel = backendValue
            appendCompletion(completedAtMs)
        }
    }

    /**
     * Record the playstyle that was actually loaded. Session-level, so it survives per-frame
     * staleness and is cleared only by [reset].
     */
    fun recordPlaystyle(cycle: Long, playstyle: String?) {
        val value = playstyle?.let { sanitizeLabel(it) } ?: return
        synchronized(lock) {
            if (cycle != cycleGeneration) return
            playstyleLabel = value
        }
    }

    /** Record the actual detection backend (session-level, like [recordPlaystyle]). */
    fun recordBackend(cycle: Long, backend: String?) {
        val value = backend?.let { sanitizeLabel(it) } ?: return
        synchronized(lock) {
            if (cycle != cycleGeneration) return
            backendLabel = value
        }
    }

    /**
     * Immutable view for the given monotonic timestamp. Values whose sample is missing, future-dated
     * or older than [sampleMaxAgeMs] are reported as `null`.
     */
    fun snapshot(nowMs: Long): HudTelemetrySnapshot {
        if (!validTime(nowMs)) return HudTelemetrySnapshot.EMPTY
        synchronized(lock) {
            prune(nowMs)
            return HudTelemetrySnapshot(
                loopMs = freshDouble(loopMs, loopAtMs, nowMs),
                ips = ipsLocked(nowMs),
                detectMs = freshDouble(detectMs, detectAtMs, nowMs),
                styleMs = freshDouble(styleMs, styleAtMs, nowMs),
                inputMs = freshDouble(inputMs, inputAtMs, nowMs),
                frameAgeMs = freshLong(frameAgeMs, frameAgeAtMs, nowMs),
                backend = backendLabel,
                playstyle = playstyleLabel,
            )
        }
    }

    // ---------- internals ----------

    private fun appendCompletion(atMs: Long) {
        ring[ringHead] = atMs
        ringHead = (ringHead + 1) % ring.size
        if (ringSize < ring.size) ringSize += 1
        prune(atMs)
    }

    /** Drop completions that fell out of the trailing window; keeps the scan bounded. */
    private fun prune(nowMs: Long) {
        val windowStart = nowMs - rateWindowMs
        while (ringSize > 0) {
            val oldestIndex = (ringHead - ringSize + ring.size) % ring.size
            val oldest = ring[oldestIndex]
            // Future-dated entries are also dropped: they cannot be a completed cycle of this clock.
            if (oldest >= windowStart && oldest <= nowMs) return
            ringSize -= 1
        }
    }

    /**
     * Completed cycles per measured wall second over the trailing window.
     *
     * Honest denominator: the interval between the earliest and the latest completion in the window,
     * not the nominal window length and not a forced `1000 / loopMs`. That keeps the rate truthful at
     * startup (0.4 s of gameplay cannot claim a 3 s average) and keeps latency separable from
     * cadence. Fewer than two completions is not a rate, so it reports `null`.
     */
    private fun ipsLocked(nowMs: Long): Double? {
        if (ringSize < 2) return null
        var count = 0
        var earliest = Long.MAX_VALUE
        var latest = Long.MIN_VALUE
        for (i in 0 until ringSize) {
            val index = (ringHead - 1 - i + ring.size * 2) % ring.size
            val at = ring[index]
            if (at < nowMs - rateWindowMs || at > nowMs) continue
            count += 1
            if (at < earliest) earliest = at
            if (at > latest) latest = at
        }
        if (count < 2 || !isFresh(latest, nowMs)) return null
        val spanMs = latest - earliest
        if (spanMs <= 0L) return null
        return (count - 1) * 1000.0 / spanMs
    }

    private fun freshDouble(value: Double, atMs: Long, nowMs: Long): Double? =
        if (value.isNaN()) null else if (isFresh(atMs, nowMs)) value else null

    private fun freshLong(value: Long, atMs: Long, nowMs: Long): Long? =
        if (value < 0L) null else if (isFresh(atMs, nowMs)) value else null

    private fun isFresh(atMs: Long, nowMs: Long): Boolean =
        atMs > 0L && atMs <= nowMs && nowMs - atMs <= sampleMaxAgeMs

    private fun validTime(value: Long): Boolean = value > 0L

    private fun validStage(value: Double): Boolean =
        value.isFinite() && value >= 0.0 && value <= MAX_PLAUSIBLE_STAGE_MS

    private fun sanitizeLabel(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LABEL_LENGTH) return null
        // Labels are shown verbatim in the HUD: refuse control characters rather than draw garbage.
        if (trimmed.any { it < ' ' || it == '\u007f' }) return null
        return trimmed
    }
}

/**
 * Process-wide telemetry facade. This is the stable cross-worker contract: the HUD reads
 * [snapshot] only and never calls into the engine or grants input authority.
 *
 * Writers are the engine/Play instrumentation: they call [generation] once per unit of work and pass
 * it to the matching `record*` call.
 */
object HudTelemetry {
    private val recorder = HudTelemetryRecorder()

    /** Immutable, stale-checked view for the caller's monotonic timestamp. */
    fun snapshot(nowMs: Long): HudTelemetrySnapshot = recorder.snapshot(nowMs)

    /** Lifecycle reset (start, stop, pause, unpause). */
    fun reset() = recorder.reset()

    /** Current lifecycle generation to bind recorded samples to. */
    fun generation(): Long = recorder.generation()

    fun recordLoop(cycle: Long, nowMs: Long, durationMs: Double) =
        recorder.recordLoop(cycle, nowMs, durationMs)

    fun recordGameplayFrame(
        cycle: Long,
        completedAtMs: Long,
        detectMs: Double? = null,
        styleMs: Double? = null,
        inputMs: Double? = null,
        frameAgeMs: Long? = null,
        backend: String? = null,
    ) = recorder.recordGameplayFrame(cycle, completedAtMs, detectMs, styleMs, inputMs, frameAgeMs, backend)

    fun recordPlaystyle(cycle: Long, playstyle: String?) = recorder.recordPlaystyle(cycle, playstyle)

    fun recordBackend(cycle: Long, backend: String?) = recorder.recordBackend(cycle, backend)
}

package com.pyla.ai.engine

import com.pyla.ai.diagnostics.DiagnosticLog

/**
 * Bounded, privacy-safe observation of the perception pipeline's *configured* execution shape.
 *
 * What this reports:
 *  - the execution provider the runtime actually built for each model role (NNAPI / XNNPACK / CPU),
 *  - the intra-op thread count the session was configured with,
 *  - a fixed non-secret build tag so an APK can be told apart from earlier generations,
 *  - attack recognition phase costs in microseconds, beside the existing millisecond stage cost.
 *
 * What this deliberately does NOT report or claim:
 *  - no hardware offload claim: a successful NNAPI session does not prove GPU/NPU use,
 *  - no CPU utilization, temperature, clock or benchmark claim,
 *  - no device/model/channel identifiers, model paths, config text, OCR, raw pixels or exceptions.
 *
 * Everything here is observation only. Formatting is total: malformed, negative, oversized or
 * absent input degrades to a bounded token instead of throwing, and every emit is wrapped so a
 * diagnostics failure can never cancel, delay or enable a gameplay decision.
 */
object PerceptionPerformanceTelemetry {

    /** Fixed, non-secret build label: identifies this performance generation of the APK. */
    const val BUILD_TAG: String = "perception-perf-v1"

    /** Fixed schema version for the emitted detail payloads. */
    const val SCHEMA: Int = 1

    /** Upper bound for any reported duration; real frame/phase costs are far below it. */
    const val MAX_REPORTED_NANOS: Long = 600_000_000_000L

    /** Upper bound for a configured thread count (the runtime clamps far lower). */
    const val MAX_REPORTED_THREADS: Int = 256

    /** Upper bound for candidate/seed counters. */
    const val MAX_REPORTED_COUNT: Int = 1_000_000

    private const val ABSENT_TRACE = "attack_trace=absent"

    /** Provider vocabulary is closed: anything unexpected reads as [Backend.UNKNOWN]. */
    enum class Backend { NNAPI, XNNPACK, CPU, UNKNOWN }

    /** Model roles known to the pipeline; anything else reads as [ROLE_OTHER]. */
    const val ROLE_ENTITY = "entity"
    const val ROLE_TILE = "tile"
    const val ROLE_TILE_CENTERED = "tile_centered"
    const val ROLE_OTHER = "other"

    private val ROLE_TOKENS = setOf(ROLE_ENTITY, ROLE_TILE, ROLE_TILE_CENTERED, ROLE_OTHER)

    /** Map an arbitrary provider string onto the closed vocabulary. Never echoes the input. */
    fun backendOf(raw: String?): Backend = when (raw?.trim()?.uppercase()) {
        "NNAPI" -> Backend.NNAPI
        "XNNPACK" -> Backend.XNNPACK
        "CPU" -> Backend.CPU
        else -> Backend.UNKNOWN
    }

    /** Clamp a configured thread count into a bounded positive range. */
    fun threadsOf(raw: Int): Int = raw.coerceIn(1, MAX_REPORTED_THREADS)

    /** Map an arbitrary role string onto the closed vocabulary. Never echoes the input. */
    fun roleOf(raw: String?): String {
        val token = raw?.trim()?.lowercase() ?: return ROLE_OTHER
        return if (token in ROLE_TOKENS) token else ROLE_OTHER
    }

    /** Nanoseconds to bounded non-negative microseconds. Negative and oversized input is saturated. */
    fun nanosToMicros(nanos: Long): Long = when {
        nanos <= 0L -> 0L
        nanos >= MAX_REPORTED_NANOS -> MAX_REPORTED_NANOS / 1_000L
        else -> nanos / 1_000L
    }

    /** Clamp a candidate/seed counter into a bounded non-negative range. */
    fun countOf(raw: Int): Int = raw.coerceIn(0, MAX_REPORTED_COUNT)

    /**
     * Immutable attack-phase snapshot. Values are the detector's monotonic per-stage nanoseconds
     * plus its coarse/refine work counters; [exhausted] means a search budget was exceeded:
     * the result is unknown, not an exhaustive negative and never permission to dispatch input.
     */
    data class AttackPhases(
        val opaqueNanos: Long,
        val translucentNanos: Long,
        val coarseNanos: Long,
        val refineNanos: Long,
        val coarseCandidates: Int,
        val refinementSeeds: Int,
        val exhausted: Boolean,
    )

    /** Fixed one-line primitive payload for a model session's effective execution shape. */
    fun modelDetail(role: String?, backend: Backend, threads: Int): String =
        "role=${roleOf(role)} backend=${backend.name} threads=${threadsOf(threads)} build=$BUILD_TAG"

    /** Model shape repeated in retained frame samples: startup rows may already have left the ring. */
    fun frameModelsDetail(
        entityBackendRaw: String?, entityThreads: Int,
        wallRoleRaw: String?, wallBackendRaw: String?, wallThreads: Int?,
    ): String = buildString(160) {
        append("build=").append(BUILD_TAG)
        append(" entity_backend=").append(backendOf(entityBackendRaw).name)
        append(" entity_threads=").append(threadsOf(entityThreads))
        val present = wallThreads != null
        append(" wall_role=").append(if (present) roleOf(wallRoleRaw) else "none")
        append(" wall_backend=").append(if (present) backendOf(wallBackendRaw).name else "NONE")
        append(" wall_threads=").append(wallThreads?.let(::threadsOf) ?: 0)
    }

    /** Fixed one-line primitive payload for a provider transition (e.g. NNAPI -> CPU fallback). */
    fun fallbackDetail(role: String?, previous: Backend, current: Backend, threads: Int): String =
        "role=${roleOf(role)} previous=${previous.name} current=${current.name} " +
            "threads=${threadsOf(threads)} build=$BUILD_TAG"

    /**
     * Fixed schema attack-phase fragment, appended to the existing frame-timing observation.
     * A null snapshot renders only the absence token: no numeric field is ever fabricated or
     * carried over from a previous call.
     */
    fun attackDetail(phases: AttackPhases?): String {
        if (phases == null) return ABSENT_TRACE
        return buildString(192) {
            append("attack_trace=present")
            append(" attack_op_us=").append(nanosToMicros(phases.opaqueNanos))
            append(" attack_tl_us=").append(nanosToMicros(phases.translucentNanos))
            append(" attack_coarse_us=").append(nanosToMicros(phases.coarseNanos))
            append(" attack_refine_us=").append(nanosToMicros(phases.refineNanos))
            append(" attack_candidates=").append(countOf(phases.coarseCandidates))
            append(" attack_seeds=").append(countOf(phases.refinementSeeds))
            append(" attack_exhausted=").append(if (phases.exhausted) "true" else "false")
        }
    }

    private val buildTagged = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Emit the fixed build label once per process so an APK generation is identifiable. */
    fun recordBuild() {
        if (!buildTagged.compareAndSet(false, true)) return
        try { DiagnosticLog.event("perception.build", "build=$BUILD_TAG schema=$SCHEMA") } catch (_: Throwable) { }
    }

    /** Report the provider/thread shape that was actually built for [role]. */
    fun recordModel(role: String?, backendRaw: String?, threads: Int) {
        try {
            DiagnosticLog.event("perception.model", modelDetail(role, backendOf(backendRaw), threads))
        } catch (_: Throwable) { }
    }

    /** Report a provider transition only when it actually changed. */
    fun recordFallback(role: String?, previousRaw: String?, currentRaw: String?, threads: Int) {
        try {
            val previous = backendOf(previousRaw)
            val current = backendOf(currentRaw)
            if (previous == current) return
            DiagnosticLog.event("perception.model_fallback", fallbackDetail(role, previous, current, threads))
        } catch (_: Throwable) { }
    }

    /** Rate-limited attack-phase sample. Identical repeats fold inside the diagnostics budget. */
    fun recordAttack(phases: AttackPhases?) {
        try { DiagnosticLog.sample("combat.attack_phases", attackDetail(phases), 1_000) } catch (_: Throwable) { }
    }
}

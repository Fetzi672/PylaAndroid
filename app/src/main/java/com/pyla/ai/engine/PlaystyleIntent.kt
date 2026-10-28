package com.pyla.ai.engine

import com.pyla.ai.pyla.PyDict
import com.pyla.ai.pyla.PyList
import com.pyla.ai.pyla.PyNone
import com.pyla.ai.pyla.PyTuple
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pure, side-effect-free model of what one `.pyla` interpretation asks for.
 *
 * A playstyle run is executed outside any input authority. Everything the script requests while it
 * runs is *buffered* here and only ever reaches the guarded dispatch layer through
 * [PlaystyleDecisionResult.Accepted] after the run completed with a whole, valid output. Nothing in
 * this file touches Android, the window controller, timers or the joystick.
 */
internal sealed interface PlaystyleIntent {
    /** `attack()` / `attack(touch_up=True, touch_down=True)`: one complete tap. */
    object Tap : PlaystyleIntent

    /** `attack(touch_up=False, touch_down=True)`: keep the charge DOWN. */
    object Hold : PlaystyleIntent

    /** `attack(touch_up=True, touch_down=False)`: lift the charge. */
    object Release : PlaystyleIntent

    /** `use_super()` / `use_gadget()` / `use_hypercharge()`. */
    data class Ability(val key: String) : PlaystyleIntent
}

/** Fixed rejection reasons. Never derived from script text, so they are safe to log. */
internal object PlaystyleRejection {
    const val ATTACK_ARGUMENT_UNSUPPORTED = "ATTACK_ARGUMENT_UNSUPPORTED"
    const val ATTACK_ARGUMENT_CONFLICT = "ATTACK_ARGUMENT_CONFLICT"
    const val ATTACK_NOTHING_REQUESTED = "ATTACK_NOTHING_REQUESTED"
    const val ATTACK_INTENT_CONFLICT = "ATTACK_INTENT_CONFLICT"
    const val ABILITY_UNSUPPORTED = "ABILITY_UNSUPPORTED"
    const val INTENT_BUDGET = "INTENT_BUDGET"
    const val MOVEMENT_MISSING = "MOVEMENT_MISSING"
    const val MOVEMENT_SHAPE = "MOVEMENT_SHAPE_INVALID"
    const val MOVEMENT_NONFINITE = "MOVEMENT_NONFINITE"
}

/** Collected requests of one tick, plus the first fatal argument problem if there was one. */
internal data class PlaystyleIntentBatch(
    val intents: List<PlaystyleIntent>,
    val rejection: String?,
) {
    val isWhole: Boolean get() = rejection == null

    companion object {
        val EMPTY = PlaystyleIntentBatch(emptyList(), null)
    }
}

/**
 * Bounded per-frame intent collector.
 *
 * Contract:
 *  - basic attack is a single channel: identical repeats coalesce; a tap versus hold conflicts.
 *    A release dominates a same-tick new DOWN: shipped charged strategies release an old charge
 *    then ask to rehold, so preserve its UP boundary and allow a new DOWN on a later fresh tick;
 *  - the three abilities are distinct channels: a repeated request coalesces, an unknown name is
 *    rejected rather than silently ignored;
 *  - `touch_up`/`touch_down` accept only booleans, only as the two documented keyword arguments or
 *    as the first two positional arguments, never both for the same flag, and never unknown keyword
 *    names. `(False, False)` requests nothing and is rejected instead of being guessed;
 *  - a tick that overflows [maxIntents] is rejected as a whole, so no partial set ever runs.
 */
internal class PlaystyleIntentCollector(private val maxIntents: Int = MAX_INTENTS) {

    companion object {
        /** Basic attack + three abilities + a little slack for merged repeats. */
        const val MAX_INTENTS = 8
        val ABILITY_KEYS: Set<String> = linkedSetOf("super", "gadget", "hypercharge")
    }

    private val buffered = ArrayList<PlaystyleIntent>(4)
    private var rejection: String? = null

    val size: Int get() = buffered.size
    val rejected: String? get() = rejection

    fun reset() {
        buffered.clear()
        rejection = null
    }

    /** Records one `attack(...)` call. Returns false when the tick is already/now rejected. */
    fun requestAttack(args: List<Any?>, kwargs: Map<String, Any?>): Boolean {
        if (rejection != null) return false
        if (args.size > 2) return reject(PlaystyleRejection.ATTACK_ARGUMENT_UNSUPPORTED)
        var up: Boolean? = null
        var down: Boolean? = null
        for (index in args.indices) {
            val flag = args[index] as? Boolean ?: return reject(PlaystyleRejection.ATTACK_ARGUMENT_UNSUPPORTED)
            if (index == 0) up = flag else down = flag
        }
        for ((name, value) in kwargs) {
            val flag = value as? Boolean ?: return reject(PlaystyleRejection.ATTACK_ARGUMENT_UNSUPPORTED)
            when (name) {
                "touch_up" -> {
                    if (up != null) return reject(PlaystyleRejection.ATTACK_ARGUMENT_CONFLICT)
                    up = flag
                }
                "touch_down" -> {
                    if (down != null) return reject(PlaystyleRejection.ATTACK_ARGUMENT_CONFLICT)
                    down = flag
                }
                else -> return reject(PlaystyleRejection.ATTACK_ARGUMENT_UNSUPPORTED)
            }
        }
        val touchUp = up ?: true
        val touchDown = down ?: true
        val intent = when {
            touchUp && touchDown -> PlaystyleIntent.Tap
            !touchUp && touchDown -> PlaystyleIntent.Hold
            touchUp && !touchDown -> PlaystyleIntent.Release
            else -> return reject(PlaystyleRejection.ATTACK_NOTHING_REQUESTED)
        }
        val existing = buffered.firstOrNull { it is PlaystyleIntent.Tap || it is PlaystyleIntent.Hold || it is PlaystyleIntent.Release }
        if (existing != null) {
            if (existing == intent) return true
            if (existing === PlaystyleIntent.Release) return true
            if (intent === PlaystyleIntent.Release) {
                buffered[buffered.indexOf(existing)] = PlaystyleIntent.Release
                return true
            }
            return reject(PlaystyleRejection.ATTACK_INTENT_CONFLICT)
        }
        return add(intent)
    }

    /** Records one ability request. Returns false when the tick is rejected. */
    fun requestAbility(key: String): Boolean {
        if (rejection != null) return false
        if (key !in ABILITY_KEYS) return reject(PlaystyleRejection.ABILITY_UNSUPPORTED)
        if (buffered.any { it is PlaystyleIntent.Ability && it.key == key }) return true
        return add(PlaystyleIntent.Ability(key))
    }

    fun finish(): PlaystyleIntentBatch = PlaystyleIntentBatch(buffered.toList(), rejection)

    private fun add(intent: PlaystyleIntent): Boolean {
        if (buffered.size >= maxIntents) return reject(PlaystyleRejection.INTENT_BUDGET)
        buffered.add(intent)
        return true
    }

    private fun reject(reason: String): Boolean {
        rejection = reason
        buffered.clear()
        return false
    }
}

/** What the script's `movement` global looked like, before validation. */
internal sealed interface PlaystyleMovementOutput {
    /** The global was absent: a failed/missing decision, not an intentional stand-still. */
    object Missing : PlaystyleMovementOutput

    /** Explicit `None`: the strategy deliberately wants no joystick deflection. */
    object None : PlaystyleMovementOutput

    /** Present but not a two-number vector: an invalid output, never a silent stand-still. */
    object Invalid : PlaystyleMovementOutput

    data class Vector(val dx: Double, val dy: Double) : PlaystyleMovementOutput
}

/** Validated movement of one tick. */
internal sealed interface PlaystyleMovement {
    data class Vector(val dx: Double, val dy: Double) : PlaystyleMovement

    /** Intentional still: release the joystick, never reuse a previous vector. */
    object Still : PlaystyleMovement
}

internal sealed interface PlaystyleDecisionResult {
    data class Accepted(val movement: PlaystyleMovement, val intents: List<PlaystyleIntent>) : PlaystyleDecisionResult
    data class Rejected(val reason: String) : PlaystyleDecisionResult
}

/** Turns a finished run into a whole decision or a deterministic rejection. */
internal object PlaystyleDecision {
    /** Below this magnitude a vector is indistinguishable from standing still. */
    const val STILL_EPSILON = 1e-9

    fun decide(output: PlaystyleMovementOutput, batch: PlaystyleIntentBatch): PlaystyleDecisionResult {
        batch.rejection?.let { return PlaystyleDecisionResult.Rejected(it) }
        return when (output) {
            PlaystyleMovementOutput.Missing ->
                PlaystyleDecisionResult.Rejected(PlaystyleRejection.MOVEMENT_MISSING)
            PlaystyleMovementOutput.Invalid ->
                PlaystyleDecisionResult.Rejected(PlaystyleRejection.MOVEMENT_SHAPE)
            PlaystyleMovementOutput.None ->
                PlaystyleDecisionResult.Accepted(PlaystyleMovement.Still, batch.intents)
            is PlaystyleMovementOutput.Vector -> {
                val dx = output.dx
                val dy = output.dy
                if (!dx.isFinite() || !dy.isFinite() || !dx.toFloat().isFinite() || !dy.toFloat().isFinite()) {
                    PlaystyleDecisionResult.Rejected(PlaystyleRejection.MOVEMENT_NONFINITE)
                } else if (hypot(dx, dy) < STILL_EPSILON) {
                    PlaystyleDecisionResult.Accepted(PlaystyleMovement.Still, batch.intents)
                } else {
                    PlaystyleDecisionResult.Accepted(PlaystyleMovement.Vector(dx, dy), batch.intents)
                }
            }
        }
    }
}

/**
 * `get_random_movement()` support: a finite candidate from a fixed 16-direction table, produced only
 * when a script actually calls the helper. It never advances the native planner and never dispatches.
 */
internal object PlaystyleRandomMovement {
    private const val MAX_MAGNITUDE = 75.0
    private val DIRECTIONS: Array<Pair<Double, Double>> = Array(16) { index ->
        val angle = index * (2.0 * PI / 16.0)
        cos(angle) to sin(angle)
    }

    fun candidate(index: Int, magnitude: Double): Pair<Double, Double> {
        val base = if (magnitude.isFinite() && magnitude > 0.0) magnitude else MAX_MAGNITUDE
        val length = (base * 0.6).coerceIn(1.0, MAX_MAGNITUDE)
        val (dx, dy) = DIRECTIONS[Math.floorMod(index, DIRECTIONS.size)]
        return (dx * length) to (dy * length)
    }
}

/**
 * Deep copy of a staged `persistent_data` value. Every mutable container the interpreter can see is
 * rebuilt, so a failing tick cannot leak changes into the next tick's state; scalars are immutable
 * and shared.
 */
internal fun deepCopyPlaystyleValue(value: Any?): Any {
    val active = java.util.IdentityHashMap<Any, Boolean>()
    var visited = 0
    fun copy(item: Any?, depth: Int): Any {
        require(depth <= 64 && ++visited <= 16_384) { "PLAYSTYLE_SNAPSHOT_INVALID" }
        if (item == null || item === PyNone) return PyNone
        when (item) {
            is Boolean, is Number -> return item
            is String -> {
                require(item.length <= 4_096) { "PLAYSTYLE_SNAPSHOT_INVALID" }
                return item
            }
        }
        require(!active.containsKey(item)) { "PLAYSTYLE_SNAPSHOT_INVALID" }
        active[item] = true
        try {
            return when (item) {
                is PyDict -> {
                    require(item.map.size <= 4_096) { "PLAYSTYLE_SNAPSHOT_INVALID" }
                    PyDict(LinkedHashMap<Any, Any>().apply {
                        for ((key, entry) in item.map) {
                            require(key !is PyDict && key !is PyList && key !is PyTuple) { "PLAYSTYLE_SNAPSHOT_INVALID" }
                            put(copy(key, depth + 1), copy(entry, depth + 1))
                        }
                    })
                }
                is PyList -> {
                    require(item.items.size <= 4_096) { "PLAYSTYLE_SNAPSHOT_INVALID" }
                    PyList(item.items.mapTo(ArrayList(item.items.size)) { copy(it, depth + 1) })
                }
                is PyTuple -> {
                    require(item.items.size <= 4_096) { "PLAYSTYLE_SNAPSHOT_INVALID" }
                    PyTuple(item.items.map { copy(it, depth + 1) })
                }
                // Persist data, never closures/native callables retaining an older frame or collector.
                else -> throw IllegalArgumentException("PLAYSTYLE_SNAPSHOT_INVALID")
            }
        } finally {
            active.remove(item)
        }
    }
    return copy(value, 0)
}

/** Fixed telemetry vocabulary of the `playstyle.tick` channel. */
internal object PlaystyleTelemetry {
    const val CHANNEL = "playstyle.tick"
    const val SUCCESS = "SUCCESS"
    const val RUNTIME_ERROR = "RUNTIME_ERROR"
    const val BUDGET_EXCEEDED = "BUDGET_EXCEEDED"
    const val CANCELLED = "CANCELLED"
    const val INVALID_OUTPUT = "INVALID_OUTPUT"

    private const val MAX_MODULE_CHARS = 64

    /** Basename only, printable ASCII word characters; never a path, never script content. */
    fun sanitizeModule(raw: String?): String {
        if (raw.isNullOrBlank()) return "unknown"
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = buildString {
            for (ch in base) {
                if (ch.isLetterOrDigit() && ch.code < 128 || ch == '.' || ch == '_' || ch == '-') append(ch)
            }
        }
        return clean.take(MAX_MODULE_CHARS).ifBlank { "unknown" }
    }

    fun detail(status: String, module: String?, elapsedMs: Long, steps: Long, reason: String? = null): String =
        buildString {
            append("status=").append(status)
            append(" module=").append(sanitizeModule(module))
            append(" elapsed_ms=").append(elapsedMs.coerceAtLeast(0L))
            append(" steps=").append(steps.coerceAtLeast(0L))
            if (reason != null) append(" reason=").append(sanitizeReason(reason))
        }

    private fun sanitizeReason(raw: String): String =
        raw.take(48).filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }.ifBlank { "UNKNOWN" }
}

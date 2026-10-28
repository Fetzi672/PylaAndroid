package com.pyla.ai.pyla

/**
 * Why a bounded execution stopped early. Fixed enum only — never carries script-produced text.
 */
internal enum class PyBudgetKind { STEPS, DEADLINE, CANCELLED, COLLECTION, RECURSION }

/**
 * Raised when the deterministic execution budget stops a script. The message contains only the
 * fixed kind name so no arbitrary script value/message can leak into logs or caller state.
 */
class PyBudgetExceededException internal constructor(
    internal val kind: PyBudgetKind,
) : RuntimeException("pyla execution budget exceeded: ${kind.name}")

/**
 * Deterministic, per-run execution budget for the .pyla interpreter.
 *
 * Boundaries are intentionally finite so a hostile or broken playstyle cannot stall the frame
 * thread or allocate without limit:
 *  - [maxSteps]   evaluated statements / expressions / loop iterations / calls
 *  - [maxNanos]   monotonic wall-clock deadline (default 25 ms)
 *  - [maxCollection] size of any single list/tuple/dict/string created by the runtime
 *  - [maxTotalElements] cumulative element churn (catches slow growth via repeated concat/append)
 *  - [maxRecursion] nested script-function frames (stack overflow would otherwise be swallowed)
 *
 * The clock and the cancellation probe are injected so unit tests can drive both deterministically.
 * Deadline arithmetic uses a monotonic [clock] only; system time changes cannot extend a budget.
 */
internal class PyExecutionBudget(
    val maxSteps: Long = DEFAULT_MAX_STEPS,
    val maxNanos: Long = DEFAULT_MAX_NANOS,
    val maxCollection: Int = DEFAULT_MAX_COLLECTION,
    val maxRecursion: Int = DEFAULT_MAX_RECURSION,
    val maxTotalElements: Long = DEFAULT_MAX_TOTAL_ELEMENTS,
    private val cancelled: () -> Boolean = { false },
    private val clock: () -> Long = { System.nanoTime() },
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 50_000L
        const val DEFAULT_MAX_NANOS = 25_000_000L
        const val DEFAULT_MAX_COLLECTION = 4_096
        const val DEFAULT_MAX_RECURSION = 64
        /** maxCollection * 1024: cumulative churn far above any single container. */
        const val DEFAULT_MAX_TOTAL_ELEMENTS = 4_194_304L
    }

    var steps: Long = 0L
        private set

    private var allocated: Long = 0L
    private var startedAt: Long = 0L
    private var started = false
    private var depth = 0

    /** Arms the budget for one run: resets counters and takes the single monotonic start reading. */
    fun start() {
        steps = 0L
        allocated = 0L
        depth = 0
        if (!started) {
            startedAt = clock()
            started = true
        }
        checkNow()
    }

    fun elapsedNanos(): Long = if (!started) 0L else (clock() - startedAt).coerceAtLeast(0L)

    /** Per-statement/expression/iteration checkpoint. Cheap: clock/cancel sampled every 32 ticks. */
    fun tick() {
        steps++
        if (steps > maxSteps) throw PyBudgetExceededException(PyBudgetKind.STEPS)
        if (steps and 0x1FL == 0L) checkNow()
    }

    /** Full deadline + cancellation + interruption check. Used at entry, around calls, at exit. */
    fun checkNow() {
        if (isCancelled()) throw PyBudgetExceededException(PyBudgetKind.CANCELLED)
        if (elapsedNanos() > maxNanos) throw PyBudgetExceededException(PyBudgetKind.DEADLINE)
    }

    private fun isCancelled(): Boolean {
        val requested = try {
            cancelled()
        } catch (t: Throwable) {
            // A failing cancellation probe must stop the run, never let it continue unchecked.
            true
        }
        if (requested) return true
        // Cancellation must remain visible to existing queued dispatch guards on the issuer.
        // Only the owner of a pooled thread may clear its flag after settling all work.
        return Thread.currentThread().isInterrupted
    }

    fun beforeCall() {
        steps++
        if (steps > maxSteps) throw PyBudgetExceededException(PyBudgetKind.STEPS)
        checkNow()
    }

    fun afterCall() {
        checkNow()
    }

    fun completionCheck() {
        steps++
        checkNow()
    }

    fun enterFrame() {
        if (depth + 1 > maxRecursion) throw PyBudgetExceededException(PyBudgetKind.RECURSION)
        depth++
    }

    fun exitFrame() {
        if (depth > 0) depth--
    }

    /** Rejects any single container/string that would exceed the per-collection cap. */
    fun guardSize(size: Long) {
        if (size > maxCollection.toLong()) throw PyBudgetExceededException(PyBudgetKind.COLLECTION)
    }

    /** Charges one allocation against both the single-allocation and cumulative caps. */
    fun charge(elements: Long) {
        if (elements <= 0L) return
        guardSize(elements)
        allocated += elements
        if (allocated > maxTotalElements) throw PyBudgetExceededException(PyBudgetKind.COLLECTION)
    }
}

/**
 * Binds the active run's budget to the current thread for the duration of one execution so the
 * pure-runtime helpers in [Py] can enforce allocation bounds without changing their signatures.
 *
 * Save/restore discipline: a nested run sees its own budget and the outer one is reinstated on
 * both normal and exceptional exit; the slot is removed (not just nulled) when no outer run exists,
 * so nothing leaks between runs, exceptions or nested runs.
 */
internal object PyBudgetContext {

    private val active = ThreadLocal<PyExecutionBudget?>()

    fun current(): PyExecutionBudget? = active.get()

    fun <T> withBudget(budget: PyExecutionBudget, body: () -> T): T {
        val previous = active.get()
        active.set(budget)
        try {
            return body()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}

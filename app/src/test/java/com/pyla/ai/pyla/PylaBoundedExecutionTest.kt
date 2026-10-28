package com.pyla.ai.pyla

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

/**
 * Focused, real-interpreter tests for bounded .pyla execution.
 *
 * Every case below parses and executes a real script with the production interpreter — there are no
 * mocks of the evaluator and no fabricated statuses. The bundled-playstyle compatibility case is the
 * only asset-dependent one: standalone harnesses provide `-Dpyla.playstylesDir`; Gradle uses its
 * app-project asset directory. Missing inputs are a failure, never skipped compatibility coverage.
 */
class PylaBoundedExecutionTest {

    // ---------- helpers ----------

    private fun script(body: String): PylaScript = PylaScript.parseContent("bounded_test.pyla", body)

    /** Movement pair of a PyTuple/PyList of two numbers, or null. */
    private fun pairOf(v: Any?): Pair<Double, Double>? {
        val items = when (v) {
            is PyTuple -> v.items
            is PyList -> v.items
            else -> return null
        }
        if (items.size != 2 || !Py.isNumber(items[0]) || !Py.isNumber(items[1])) return null
        return Py.toDouble(items[0]) to Py.toDouble(items[1])
    }

    private fun num(d: Double): Any = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong() else d

    private fun dictOf(vararg kv: Pair<String, Any>): PyDict =
        PyDict(LinkedHashMap<Any, Any>().apply { for ((k, v) in kv) put(k, v) })

    private fun listOfNums(vararg xs: Double): PyList =
        PyList(xs.map { num(it) as Any }.toMutableList())

    /**
     * Context mirroring Play.kt#buildContext: same keys, same arities, same return shapes as the
     * real bridge. Geometry predicates are intentionally trivial; this measures interpreter
     * contract and budget behaviour, not tactics.
     */
    private fun buildContext(counter: () -> Unit = {}): Map<String, Any> {
        val joystick = 75L
        val brawler = "shelly"
        // Same shape as assets/pyla/cfg/brawlers_info.json for "shelly".
        val info = PyDict(LinkedHashMap<Any, Any>().apply {
            put("shelly", dictOf(
                "safe_range" to 301L, "attack_range" to 490L, "super_type" to "damage",
                "super_range" to 490L, "ignore_walls_for_attacks" to false,
                "ignore_walls_for_supers" to true, "hold_attack" to 0L,
            ))
        })
        val persistent = PyDict(LinkedHashMap<Any, Any>().apply {
            put("time_since_holding_attack", PyNone)
            put("last_gadget_use", 0.0)
        })
        val ctx = HashMap<String, Any>()
        ctx["player_data"] = listOfNums(100.0, 100.0, 150.0, 150.0)
        ctx["enemy_data"] = PyList(mutableListOf<Any>(listOfNums(300.0, 90.0, 350.0, 140.0)))
        ctx["teammate_data"] = PyList(mutableListOf<Any>(listOfNums(80.0, 140.0, 120.0, 180.0)))
        ctx["walls"] = PyList(mutableListOf<Any>(listOfNums(0.0, 0.0, 1920.0, 10.0)))
        ctx["bushes"] = PyList()
        ctx["brawler"] = brawler
        ctx["current_brawler"] = brawler
        ctx["brawlers_info"] = info
        ctx["is_gadget_ready"] = true
        ctx["is_hypercharge_ready"] = false
        ctx["is_super_ready"] = true
        ctx["TILE_SIZE"] = 60L
        ctx["JOYSTICK_RADIUS"] = joystick
        ctx["width"] = 1920L
        ctx["height"] = 1080L
        ctx["debug"] = false
        ctx["seconds_to_hold_attack_after_reaching_max"] = 0.0
        ctx["persistent_data"] = persistent
        ctx["last_movement"] = PyNone
        ctx["last_movement_change_time"] = 0.0

        fun box(a: Any?): List<Double> = when (a) {
            is PyList -> a.items.map { Py.toDouble(it) }
            is PyTuple -> a.items.map { Py.toDouble(it) }
            else -> emptyList()
        }
        fun posOf(b: List<Double>): Pair<Double, Double> =
            if (b.size >= 4) ((b[0] + b[2]) / 2.0) to ((b[1] + b[3]) / 2.0) else 0.0 to 0.0
        fun pairArg(a: Any?): Pair<Double, Double>? = pairOf(a)
        fun tup(p: Pair<Double, Double>) = PyTuple(listOf<Any>(num(p.first), num(p.second)))

        ctx["get_entity_pos"] = NativeFn("get_entity_pos") { a, _ -> tup(posOf(box(a[0]))) }
        ctx["get_distance"] = NativeFn("get_distance") { a, _ ->
            val p = pairArg(a[0]) ?: (0.0 to 0.0)
            val q = pairArg(a[1]) ?: (0.0 to 0.0)
            hypot(p.first - q.first, p.second - q.second)
        }
        ctx["get_actual_player_box"] = NativeFn("get_actual_player_box") { a, _ ->
            PyList(box(a[0]).map { num(it) as Any }.toMutableList())
        }
        ctx["get_brawler_range"] = NativeFn("get_brawler_range") { _, _ ->
            PyList(mutableListOf<Any>(300L, 490L, 500L))
        }
        ctx["is_there_enemy"] = NativeFn("is_there_enemy") { a, _ ->
            when (val v = a.getOrNull(0)) {
                is PyList -> v.items.isNotEmpty()
                is PyTuple -> v.items.isNotEmpty()
                else -> false
            }
        }
        ctx["attack"] = NativeFn("attack") { _, _ -> counter(); PyNone }
        ctx["use_hypercharge"] = NativeFn("use_hypercharge") { _, _ -> PyNone }
        ctx["use_super"] = NativeFn("use_super") { _, _ -> PyNone }
        ctx["use_gadget"] = NativeFn("use_gadget") { _, _ -> PyNone }
        ctx["get_random_movement"] = NativeFn("get_random_movement") { _, _ -> tup(0.0 to -joystick.toDouble()) }
        ctx["must_brawler_hold_attack"] = NativeFn("must_brawler_hold_attack") { _, _ -> false }
        ctx["find_closest_enemy"] = NativeFn("find_closest_enemy") { a, _ ->
            val es = when (val v = a.getOrNull(0)) {
                is PyList -> v.items.map { box(it) }
                is PyTuple -> v.items.map { box(it) }
                else -> emptyList()
            }
            var best: Pair<Double, Double>? = null
            var bd: Double? = null
            for (b in es) {
                val p = posOf(b)
                val d = hypot(p.first - 125.0, p.second - 125.0)
                if (bd == null || d < bd!!) { best = p; bd = d }
            }
            PyTuple(listOf<Any>(best?.let { tup(it) } ?: PyNone, bd ?: PyNone))
        }
        ctx["find_closest_teammate"] = ctx.getValue("find_closest_enemy")
        ctx["is_there_poison_gas"] = NativeFn("is_there_poison_gas") { _, _ ->
            dictOf("up" to 0L, "down" to 0L, "left" to 0L, "right" to 0L)
        }
        ctx["is_path_blocked"] = NativeFn("is_path_blocked") { _, _ -> false }
        ctx["is_enemy_hittable"] = NativeFn("is_enemy_hittable") { _, _ -> true }
        ctx["rotate_movement"] = NativeFn("rotate_movement") { a, _ ->
            val m = pairArg(a[0]) ?: (0.0 to 0.0)
            val r = Math.toRadians(Py.toDouble(a[1]))
            tup((m.first * Math.cos(r) - m.second * Math.sin(r)) to (m.first * Math.sin(r) + m.second * Math.cos(r)))
        }
        return ctx
    }

    // ---------- positive controls: legitimate execution still succeeds ----------

    @Test
    fun legitimateMovementAndStillProduceSuccess() {
        val moving = script("movement = (0, -JOYSTICK_RADIUS)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.SUCCESS, moving.status)
        assertEquals(0.0 to -75.0, pairOf(moving.movement))
        assertTrue("steps counted", moving.steps > 0)
        assertTrue("elapsed non-negative", moving.elapsedNanos >= 0)

        val still = script("movement = (0, 0)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.SUCCESS, still.status)
        assertEquals(0.0 to 0.0, pairOf(still.movement))
    }

    @Test
    fun noOutputAndLegitimateNoneAreSuccessAndDistinct() {
        val absent = script("x = 1 + 1\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.SUCCESS, absent.status)
        assertNull("movement key absent", absent.movement)

        val explicitNone = script("movement = None\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.SUCCESS, explicitNone.status)
        assertSame(PyNone, explicitNone.movement)

        // The legacy wrapper keeps its old contract: value on success, null on failure.
        assertNotNull(script("movement = (1, 2)\n").run(buildContext()))
        assertNull(script("raise ValueError('x')\n").run(buildContext()))
    }

    @Test
    fun finiteWorkInsideBudgetSucceeds() {
        val body = """
            total = 0
            i = 0
            while i < 200:
                total = total + i
                i = i + 1
            xs = [n for n in range(50) if n > 10]
            movement = (0, total + len(xs))
        """.trimIndent() + "\n"
        val r = script(body).runBounded(buildContext())
        assertEquals(PylaRunStatus.SUCCESS, r.status)
        assertEquals(0.0 to (199 * 200 / 2 + 39).toDouble(), pairOf(r.movement))
        assertTrue("well under step budget", r.steps < 50_000)
    }

    // ---------- budget stops ----------

    @Test
    fun infiniteWhileIsBudgetExceededAndBounded() {
        val r = script("i = 0\nwhile True:\n    i = i + 1\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, r.status)
        assertNull(r.movement)
        assertTrue(r.steps > 0)
        assertTrue("must stop fast, took ${r.elapsedNanos}ns", r.elapsedNanos < 5_000_000_000L)
    }

    @Test
    fun giantRangeIsRejectedBeforeAllocation() {
        val r = script("items = range(10000000)\nmovement = (1, 2)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, r.status)
        assertNull(r.movement)
        // The allocation guard fires long before the step budget: no giant PyList was built.
        assertTrue("steps=${r.steps} must be far below 50000", r.steps < 100)
        assertTrue("no multi-second allocation", r.elapsedNanos < 2_000_000_000L)
    }

    @Test
    fun giantRepeatAndComprehensionAreRejected() {
        val str = script("s = 'ab' * 100000000\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, str.status)

        val list = script("xs = [1, 2] * 100000000\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, list.status)

        val comp = script("xs = [i for i in range(10000000)]\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, comp.status)
    }

    @Test
    fun runawayAccumulationHitsAllocationCapBeforeStepCap() {
        val concat = script("s = ''\nwhile True:\n    s = s + 'ab'\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, concat.status)
        assertTrue("collection cap, not steps: ${concat.steps}", concat.steps < 50_000)

        val append = script("xs = []\nwhile True:\n    xs.append(1)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, append.status)
        assertTrue("collection cap, not steps: ${append.steps}", append.steps < 50_000)
    }

    @Test
    fun finiteButCostlyLoopIsBudgetExceeded() {
        val r = script("i = 0\nwhile i < 100000000:\n    i = i + 1\nmovement = (1, 2)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, r.status)
        assertNull(r.movement)
    }

    @Test
    fun recursionIsBoundedBeforeStackOverflow() {
        val body = """
            def f(n):
                return f(n + 1)
            movement = f(0)
        """.trimIndent() + "\n"
        val r = script(body).runBounded(buildContext())
        // A StackOverflowError would be swallowed as RUNTIME_ERROR; the depth bound must win first.
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, r.status)
        assertNull(r.movement)
        assertTrue("depth bound fired before step budget: ${r.steps}", r.steps < 50_000)
    }

    @Test
    fun deadlineUsesMonotonicClockSeam() {
        val ticks = AtomicLong(0)
        val budget = PyExecutionBudget(
            maxSteps = 10_000_000L,
            maxNanos = 5_000_000L,
            clock = { ticks.addAndGet(1_000_000L) }, // +1 ms per reading
        )
        val r = script("while True:\n    x = 1\n").runBoundedWith(buildContext(), budget)
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, r.status)
        assertTrue("fake clock advanced", ticks.get() > 5_000_000L)
    }

    // ---------- cancellation / interruption ----------

    @Test
    fun cancelledBeforeStartIsCancelledEvenForValidScript() {
        val r = script("movement = (0, -75)\n").runBounded(buildContext(), cancelled = { true })
        assertEquals(PylaRunStatus.CANCELLED, r.status)
        assertNull("cancelled run exposes no movement", r.movement)
        assertTrue(r.elapsedNanos >= 0)
    }

    @Test
    fun cancelledDuringNativeCallbackStopsMidRun() {
        val calls = AtomicLong(0)
        val ctx = buildContext().toMutableMap()
        ctx["probe"] = NativeFn("probe") { _, _ -> calls.incrementAndGet(); PyNone }
        val body = """
            i = 0
            while i < 1000:
                probe()
                i = i + 1
            movement = (1, 2)
        """.trimIndent() + "\n"
        val r = script(body).runBounded(ctx, cancelled = { calls.get() >= 5L })
        assertEquals(PylaRunStatus.CANCELLED, r.status)
        assertNull(r.movement)
        assertTrue("stopped after the callback, not at 1000: ${calls.get()}", calls.get() in 5L..8L)
    }

    @Test
    fun threadInterruptionCancelsWithoutRevivingTheIssuingThread() {
        var interrupted = false
        val body = "while True:\n    x = 1\n"
        try {
            val r = script(body).runBounded(buildContext(), cancelled = {
                if (!interrupted) { interrupted = true; Thread.currentThread().interrupt() }
                false
            })
            assertEquals(PylaRunStatus.CANCELLED, r.status)
            assertTrue("dispatch authority must still see the issuing thread interrupted", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted() // Test-owned cleanup only, never a production cancellation policy.
        }
    }

    // ---------- failures, isolation, compat ----------

    @Test
    fun throwingAndUndefinedScriptsAreRuntimeErrors() {
        val raised = script("raise ValueError('boom')\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.RUNTIME_ERROR, raised.status)
        assertNull(raised.movement)

        val undefined = script("movement = not_a_name\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.RUNTIME_ERROR, undefined.status)

        val divideByZero = script("movement = (1 // 0, 0)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.RUNTIME_ERROR, divideByZero.status)
    }

    @Test
    fun noBudgetLeaksBetweenRunsOrAfterFailures() {
        val failing = script("def f(n):\n    return f(n + 1)\nmovement = f(0)\n")
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED, failing.runBounded(buildContext()).status)
        assertNull("active budget must be removed after a budget stop", PyBudgetContext.current())

        val second = script("movement = (0, -75)\n").runBounded(buildContext())
        assertEquals(PylaRunStatus.SUCCESS, second.status)
        assertEquals(0.0 to -75.0, pairOf(second.movement))
        assertTrue("fresh counters for the second run: ${second.steps}", second.steps < 100)

        assertEquals(PylaRunStatus.RUNTIME_ERROR, script("raise ValueError('x')\n").runBounded(buildContext()).status)
        assertNull("active budget must be removed after a script error", PyBudgetContext.current())
        assertEquals(PylaRunStatus.CANCELLED, script("movement = (0, 0)\n").runBounded(buildContext(), cancelled = { true }).status)
        assertNull("active budget must be removed after cancellation", PyBudgetContext.current())
    }

    @Test
    fun nestedRunGetsItsOwnBudgetAndRestoresOuterOne() {
        val inner = script("movement = (0, -75)\n")
        val ctx = buildContext().toMutableMap()
        ctx["run_inner"] = NativeFn("run_inner") { _, _ ->
            val r = inner.runBounded(ctx, cancelled = { false })
            assertEquals(PylaRunStatus.SUCCESS, r.status)
            PyNone
        }
        val outer = script("run_inner()\nmovement = (0, 0)\n").runBounded(ctx)
        assertEquals(PylaRunStatus.SUCCESS, outer.status)
        assertEquals(0.0 to 0.0, pairOf(outer.movement))
        assertNull("outer binding removed after the nested run", PyBudgetContext.current())
    }

    /**
     * Parses and executes every bundled playstyle with the real interpreter, using the explicit
     * standalone harness path or the ordinary Gradle app-project asset directory.
     */
    @Test
    fun bundledPlaystylesStillRunWithinDefaultBudget() {
        val dir = File(System.getProperty("pyla.playstylesDir") ?: "src/main/assets/pyla/playstyles")
        assertTrue("bundled asset directory must exist: $dir", dir.isDirectory)
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".pyla") } ?: emptyArray())
            .sortedBy { it.name }
        assertEquals("six bundled playstyles", 6, files.size)

        val report = StringBuilder()
        for (f in files) {
            val parsed = PylaScript.parseContent(f.name, f.readText())
            val results = (1..3).map { parsed.runBounded(buildContext()) }
            for (r in results) {
                assertEquals("${f.name} status (steps=${r.steps}, ns=${r.elapsedNanos})",
                    PylaRunStatus.SUCCESS, r.status)
                assertNotNull("${f.name} produced a movement", pairOf(r.movement))
                assertTrue("${f.name} steps=${r.steps} should fit the default budget", r.steps < 50_000)
            }
            report.append("${f.name}: steps=${results.map { it.steps }}\n")
        }
        println("BUNDLED-COMPAT\n$report")
    }
}

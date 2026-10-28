package com.pyla.ai.input

import org.junit.Assert.*
import org.junit.Test

/** Real private Channel state only. Android pointer/dispatch behavior is covered by the SDK fixture. */
class InputServiceReleaseBoundaryTest {
    private val type = InputService::class.java.declaredClasses.single { it.simpleName == "Channel" }
    private fun channel(name: String = "attack"): Any = type.getDeclaredConstructor(String::class.java).apply {
        isAccessible = true
    }.newInstance(name)
    private fun set(c: Any, name: String, value: Boolean) = type.getDeclaredField(name).apply {
        isAccessible = true
    }.setBoolean(c, value)
    private fun get(c: Any, name: String): Boolean = type.getDeclaredField(name).apply {
        isAccessible = true
    }.getBoolean(c)
    private fun invoke(c: Any, name: String, vararg args: Any): Any? = type.declaredMethods.single {
        it.name == name && it.parameterCount == args.size
    }.apply { isAccessible = true }.invoke(c, *args)

    @Test fun releaseCannotBeOverwrittenByLatestHoldOnEitherChannel() {
        for (name in listOf("attack", "joystick")) {
            val c = channel(name)
            set(c, "isDown", true)
            set(c, "wantDown", true)
            invoke(c, "requestRelease")
            assertFalse(get(c, "wantDown"))
            assertTrue(get(c, "releasePending"))
            // Same latest-demand write used by accepted fresh HOLD/MOVE, while old dispatch is busy.
            set(c, "wantDown", true)
            assertTrue("$name must retain the intervening UP", get(c, "releasePending"))
            assertEquals(true, invoke(c, "needsUpdate"))
        }
    }

    @Test fun speculativeReleaseRollbackRestoresPendingEdgeAndLatestDemand() {
        val c = channel()
        set(c, "isDown", true)
        invoke(c, "requestRelease")
        set(c, "wantDown", true)
        val snapshot = invoke(c, "snapshot")!!
        invoke(c, "reset")
        assertFalse(get(c, "releasePending"))
        invoke(c, "restore", snapshot)
        assertTrue(get(c, "releasePending"))
        assertTrue(get(c, "isDown"))
        assertTrue(get(c, "wantDown"))
    }

    @Test fun terminalOrCancelledChainClearsOldEdgeButKeepsFreshDemand() {
        val c = channel()
        set(c, "isDown", true)
        invoke(c, "requestRelease")
        set(c, "wantDown", true)
        invoke(c, "reset")
        assertFalse(get(c, "releasePending"))
        assertFalse(get(c, "isDown"))
        assertTrue(get(c, "wantDown"))
        assertEquals(true, invoke(c, "needsUpdate"))
    }

    @Test fun releaseBeforeAnyPointerDoesNotCreatePhantomEdge() {
        val c = channel()
        set(c, "wantDown", true)
        invoke(c, "requestRelease")
        assertFalse(get(c, "wantDown"))
        assertFalse(get(c, "releasePending"))
        assertEquals(false, invoke(c, "needsUpdate"))
    }

    @Test fun repeatedReleaseIsIdempotentAndDoesNotReviveDemand() {
        val c = channel()
        set(c, "isDown", true)
        repeat(10) { invoke(c, "requestRelease") }
        assertTrue(get(c, "releasePending"))
        assertFalse(get(c, "wantDown"))
        invoke(c, "reset")
        assertEquals(false, invoke(c, "needsUpdate"))
    }

    @Test fun snapshotRestoresReleaseEvenWhenLatestDemandIsFalse() {
        val c = channel("joystick")
        set(c, "isDown", true)
        invoke(c, "requestRelease")
        val snapshot = invoke(c, "snapshot")!!
        invoke(c, "reset")
        set(c, "wantDown", true)
        invoke(c, "restore", snapshot)
        assertTrue(get(c, "releasePending"))
        assertTrue(get(c, "isDown"))
        assertFalse(get(c, "wantDown"))
    }
}

package com.pyla.ai.ui

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaystyleSelectionLoadingTest {
    @Test fun notReadyNeverCallsTheResolver() = runBlocking {
        var calls = 0
        assertNull(loadPlaystyleSelection(false) { calls++; error("must not access assets") })
        assertEquals(0, calls)
    }

    @Test fun readinessTransitionLoadsTheExistingSelectionOnce() = runBlocking {
        var calls = 0
        val resolve = { calls++; "custom.pyla" }
        assertNull(loadPlaystyleSelection(false, resolve))
        assertEquals("custom.pyla", loadPlaystyleSelection(true, resolve))
        assertNull(loadPlaystyleSelection(false, resolve))
        assertEquals(1, calls)
    }

    @Test fun resolutionDoesNotRunOnTheCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        var worker: Thread? = null
        assertEquals("chosen.pyla", loadPlaystyleSelection(true) {
            worker = Thread.currentThread()
            "chosen.pyla"
        })
        assertNotSame(caller, worker)
    }

    @Test fun existingResolutionFailureIsNotConvertedToAPersistedDefault() = runBlocking {
        val expected = IOException("synthetic read failure")
        try {
            loadPlaystyleSelection(true) { throw expected }
            throw AssertionError("failure must propagate")
        } catch (actual: IOException) {
            // Coroutine stacktrace recovery may copy the exception across dispatchers.
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
        }
    }

    @Test fun cancelledLoadDoesNotPublishALateSelection() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            loadPlaystyleSelection(true) {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    "old-selection.pyla"
                } finally {
                    exited.countDown()
                }
            }
            published.set(true)
        }
        try {
            assertTrue("resolver entered IO worker", entered.await(5, TimeUnit.SECONDS))
            assertFalse(published.get())
            job.cancel()
        } finally {
            release.countDown()
            job.join()
        }
        assertTrue("physical resolver settled", exited.await(5, TimeUnit.SECONDS))
        assertTrue(job.isCancelled)
        assertFalse("disposed composition must not receive an old result", published.get())
    }
}

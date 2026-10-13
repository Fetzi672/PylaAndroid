package com.pyla.ai.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Focused unit tests for the bounded, private, process-session diagnostics log.
 *
 * These run on the JVM against the real DiagnosticLog source (no Android framework needed):
 * every public entry point is total (never throws) and every bound is enforced in-process.
 */
class DiagnosticLogTest {

    @Before
    fun setUp() {
        DiagnosticLog.resetForTesting()
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun `entry cap keeps newest entries and stays within max entries`() {
        repeat(3_000) { DiagnosticLog.event("evt", "e$it") }
        val lines = DiagnosticLog.recentLines(limit = 5_000)
        assertTrue("entry cap exceeded: ${lines.size}", lines.size <= DiagnosticLog.MAX_ENTRIES)
        assertTrue("newest entry missing", lines.last().contains("e2999"))
        assertFalse("oldest entry should have been evicted", lines.any { it.contains("e0 ") || it.endsWith("e0") })
    }

    @Test
    fun `utf8 byte budget is enforced and never exceeded`() {
        val big = "x".repeat(4_000) + "é".repeat(4_000)
        repeat(200) { DiagnosticLog.record("INFO", "Tags", "$it $big") }
        val snapshot = DiagnosticLog.snapshot()
        assertTrue(
            "byte budget exceeded: ${snapshot.byteCount}",
            snapshot.byteCount <= DiagnosticLog.MAX_BYTES,
        )
    }

    @Test
    fun `byte count equals real utf8 length of the snapshot text`() {
        DiagnosticLog.record("INFO", "Tags", "naïve café ünïcode ✅ \uD83D\uDE80")
        val snapshot = DiagnosticLog.snapshot()
        assertEquals(snapshot.text.toByteArray(Charsets.UTF_8).size, snapshot.byteCount)
        assertEquals(64, snapshot.sha256.length)
        assertTrue(snapshot.sha256.all { it in "0123456789abcdef" })
    }

    // ------------------------------------------------------------ sanitizing

    @Test
    fun `composed secrets are removed from composed credential shapes`() {
        val cases = listOf(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def to https://api.example.com/v1",
            "authorization=Bearer%20sk-live-1234567890abcdef",
            "Cookie: session=deadbeefcafe; theme=dark",
            "password=hunter2secret passwd: 'hunter3' pwd=abcdef123456",
            "token=ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 api_key=AIzaSyABCDEFGHIJKLMNOPQRSTUV",
            "client_secret=shhhhh-very-secret clientSecret=\"abc123\"",
            "device_key: ZXCVBNMASDFGHJKL9876543210",
        )
        for (secret in cases) {
            DiagnosticLog.event("auth", secret)
        }
        val text = DiagnosticLog.snapshot().text
        for (leak in listOf(
            "eyJhbGciOiJIUzI1NiJ9", "sk-live-1234567890abcdef", "ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789",
            "AIzaSyABCDEFGHIJKLMNOPQRSTUV", "shhhhh-very-secret", "deadbeefcafe",
            "ZXCVBNMASDFGHJKL9876543210", "hunter2secret", "hunter3", "abcdef123456",
            "abc123",
        )) {
            assertFalse("secret leaked: $leak", text.contains(leak))
        }
        assertFalse("url leaked", text.contains("api.example.com"))
        assertTrue("redaction markers missing:\n$text", text.contains("[redacted"))
    }

    @Test
    fun `identity data is removed - emails, ipv4, paths and package names`() {
        DiagnosticLog.event(
            "ident",
            "user player.one+tag@example.com from 192.168.1.77 at /data/data/com.supercell.brawlstars/files/x.cfg pkg=com.supercell.brawlstars com.pyla.ai",
        )
        val text = DiagnosticLog.snapshot().text
        for (leak in listOf(
            "player.one+tag@example.com", "192.168.1.77", "/data/data/", "com.supercell.brawlstars", "com.pyla.ai",
        )) {
            assertFalse("identity leaked: $leak -> $text", text.contains(leak))
        }
    }

    @Test
    fun `throwable cause chains are sanitized and depth bounded`() {
        val deep = generateSequence(0) { it + 1 }.take(64).fold(
            RuntimeException("root cause token=abc123secret"),
        ) { prev, _ -> IllegalStateException("level cause password=hunter2", prev) }
        DiagnosticLog.record("ERROR", "Tags", "failure", deep)
        val text = DiagnosticLog.snapshot().text
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("abc123secret"))
        val causeLines = text.lines().count { it.contains("Caused by") || it.contains("at com.pyla") }
        assertTrue("cause depth not bounded: $causeLines", causeLines <= DiagnosticLog.MAX_CAUSE_DEPTH + 8)
    }

    @Test
    fun `control and log-forging characters are stripped`() {
        DiagnosticLog.record(
            "INFO", "Tags",
            "line1\nline2\r\revent=engine.start \u0000 \u0007 \u001B[31m \u202E forged \u2028 end",
        )
        val text = DiagnosticLog.snapshot().text
        assertFalse("raw newline survived", text.contains('\n') && text.lines().any { it.isEmpty() })
        for (bad in listOf("\u0000", "\u0007", "\u001B", "\u202E", "\u2028", "\r")) {
            assertFalse("control char survived: " + bad.toCharArray().joinToString(), text.contains(bad))
        }
        assertTrue(text.contains("event=engine.start"))
        assertTrue(text.contains("forged"))
    }

    @Test
    fun `unicode and multi-byte text survives intact`() {
        DiagnosticLog.event("unicode", "éà中文 \uD83D\uDE80 \uD83D\uDD25 ✅ done")
        assertTrue(DiagnosticLog.snapshot().text.contains("éà中文"))
        assertTrue(DiagnosticLog.snapshot().text.contains("\uD83D\uDE80"))
    }

    @Test
    fun `oversized single message is truncated to a bounded length`() {
        DiagnosticLog.record("INFO", "Tags", "m".repeat(DiagnosticLog.MAX_MESSAGE_CHARS * 3))
        val text = DiagnosticLog.snapshot().text
        assertTrue("single entry not bounded", text.length < DiagnosticLog.MAX_MESSAGE_CHARS + 400)
    }

    // -------------------------------------------------------------- contract

    @Test
    fun `record and event never throw`() {
        try {
            DiagnosticLog.record("INFO", "Tags", "ok")
            DiagnosticLog.record("ERROR", "Tags", "null-ish", null)
            DiagnosticLog.record("INFO", "Tags", "x".repeat(200_000), RuntimeException("boom"))
            DiagnosticLog.event("kind", "null-ish detail")
            DiagnosticLog.recentLines(limit = -5)
            DiagnosticLog.recentLines(limit = Int.MAX_VALUE)
        } catch (t: Throwable) {
            throw AssertionError("DiagnosticLog must never throw, got $t")
        }
    }

    @Test
    fun `snapshot is immutable and frozen per call`() {
        DiagnosticLog.event("first", "a")
        val snap1 = DiagnosticLog.snapshot()
        DiagnosticLog.event("second", "b")
        val snap2 = DiagnosticLog.snapshot()
        assertNotEquals(snap1.text, snap2.text)
        assertTrue("earlier snapshot mutated", !snap1.text.contains("second"))
        assertTrue(snap2.text.contains("second"))
        assertEquals(snap1.byteCount, snap1.text.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `snapshot text has a report id line and bounded entry count`() {
        repeat(50) { DiagnosticLog.event("e$it", "d$it") }
        val snapshot = DiagnosticLog.snapshot()
        assertTrue(snapshot.reportId.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
        assertTrue(snapshot.text.lines().count { it.contains("|E|") } in 1..DiagnosticLog.MAX_ENTRIES)
        assertTrue(snapshot.text.contains("# report_id=${snapshot.reportId}"))
    }

    @Test
    fun `recentLines returns a defensive copy in newest-last order`() {
        DiagnosticLog.event("a", "1")
        val first = DiagnosticLog.recentLines(limit = 10)
        (first as MutableList<String>).clear()
        val second = DiagnosticLog.recentLines(limit = 10)
        assertTrue("recentLines returned an aliased list", second.any { it.contains("a") })
    }

    @Test
    fun `entries carry sequence and monotonic relative time`() {
        val before = System.nanoTime()
        repeat(5) { DiagnosticLog.event("seq", "n$it") }
        val after = System.nanoTime()
        val lines = DiagnosticLog.recentLines(limit = 10)
        val seqs = lines.map { it.substringBefore('|').trim().toLong() }
        assertEquals("sequence must be strictly increasing", seqs.size, seqs.toSet().size)
        assertEquals(seqs.sorted(), seqs)
        val times = lines.map { it.substringAfter('|').substringBefore('|').trim().toLong() }
        assertEquals("relative time must be nondecreasing; milliseconds can tie", times.sorted(), times)
        assertTrue(times.first() >= 0)
        assertTrue(times.last() < TimeUnit.SECONDS.toNanos(60))
        assertTrue(before <= after)
    }

    @Test
    fun `log survives on failure without throwing`() {
        DiagnosticLog.record("ERROR", "Tags", "inner", IllegalStateException("nested secret"))
        val text = DiagnosticLog.snapshot().text
        assertTrue(text.contains("ERROR"))
        assertFalse(text.contains("nested secret"))
    }

    @Test
    fun `secrets are removed before RAM retention and recent display`() {
        val secret = listOf("synthetic", "private", "value").joinToString("-")
        DiagnosticLog.record("INFO", "Tags", "Authorization: Bearer $secret Cookie: session=$secret; theme=$secret")
        DiagnosticLog.event("auth", "{\"clientSecret\":\"$secret\"} password='$secret'")
        val shown = DiagnosticLog.recentLines(100).joinToString("\n")
        assertFalse("RAM/UI secret leak", shown.contains(secret))
        assertTrue(shown.contains("[redacted]"))
    }

    @Test
    fun `unchanged events are folded but state transitions are retained`() {
        repeat(1000) { DiagnosticLog.event("state", "match") }
        DiagnosticLog.event("state", "lobby")
        assertEquals(2, DiagnosticLog.recentLines(1000).size)
        assertTrue(DiagnosticLog.recentLines().last().contains("lobby"))
    }

    @Test
    fun `tag and level are sanitized before retention`() {
        val secret = listOf("synthetic", "header").joinToString("-")
        DiagnosticLog.record("token=$secret", "password=$secret", "ordinary")
        assertFalse(DiagnosticLog.recentLines().joinToString().contains(secret))
    }

    @Test
    fun `numeric samples are rate bounded even when details change`() {
        repeat(1000) { DiagnosticLog.sample("input.dispatch", "dispatch=$it", 60_000) }
        assertEquals(1, DiagnosticLog.recentLines().size)
    }

    @Test
    fun `throttle metadata remains bounded with unique channels`() {
        repeat(1000) { DiagnosticLog.event("channel$it", "value$it") }
        val f = DiagnosticLog::class.java.getDeclaredField("channels").apply { isAccessible = true }
        assertTrue((f.get(DiagnosticLog) as Map<*, *>).size <= 128)
        assertTrue(DiagnosticLog.snapshot().byteCount <= DiagnosticLog.MAX_BYTES)
    }

    // ----------------------------------------------------------- concurrency

    @Test
    fun `concurrent writers stay within both bounds`() {
        val threads = 8
        val perThread = 400
        val latch = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val workers = (0 until threads).map { t ->
            Thread {
                latch.await()
                repeat(perThread) { i ->
                    DiagnosticLog.record("INFO", "T$t", "thread=$t i=$i payload=${"y".repeat(200)}")
                    DiagnosticLog.event("telemetry", "tick t=$t i=$i")
                }
                done.countDown()
            }.apply { start() }
        }
        latch.countDown()
        assertTrue("workers did not finish", done.await(60, TimeUnit.SECONDS))
        workers.forEach { it.join(5_000) }

        val snapshot = DiagnosticLog.snapshot()
        assertTrue("entry cap exceeded under concurrency: ${snapshot.byteCount} bytes", snapshot.byteCount <= DiagnosticLog.MAX_BYTES)
        val lines = DiagnosticLog.recentLines(limit = DiagnosticLog.MAX_ENTRIES)
        assertTrue(lines.size <= DiagnosticLog.MAX_ENTRIES)
        assertTrue(lines.any { it.contains("telemetry") })
    }
}
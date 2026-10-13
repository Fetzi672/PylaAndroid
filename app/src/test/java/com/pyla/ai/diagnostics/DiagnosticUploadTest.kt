package com.pyla.ai.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Uploader contract suite: exact request serialization, strict receipt
 * verification and every fixed failure class. A fake transport is used
 * throughout, so no test performs real network I/O.
 *
 * The receipt shape mirrors the live backend exactly:
 *   {"reportId":"<uuid>","sha256":"<hex>","bytes":<int>,"expiresAt":"<ISO-8601 UTC>"}
 */
class DiagnosticUploadTest {

    private val nowSeconds = 1_700_000_000L // 2023-11-14T22:13:20Z


    private class FakeTransport(
        var status: Int = 201,
        var sawRedirect: Boolean = false,
        var transportFailed: Boolean = false,
        var throwOnCall: Boolean = false,
        var body: (DiagnosticSnapshot) -> ByteArray = { snap -> ack(snap) },
    ) : DiagnosticTransport {
        val calls = AtomicInteger()
        var lastBody: ByteArray? = null
        var lastEndpoint: String? = null
        var lastToken: String? = null
        var lastConnectTimeout = -1
        var lastReadTimeout = -1

        override fun postJson(
            endpoint: String,
            bearerToken: String,
            jsonBody: ByteArray,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            deadlineElapsedMillis: Long,
        ): RawResponse {
            calls.incrementAndGet()
            lastBody = jsonBody.copyOf()
            lastEndpoint = endpoint
            lastToken = bearerToken
            lastConnectTimeout = connectTimeoutMillis
            lastReadTimeout = readTimeoutMillis
            if (throwOnCall) throw java.io.IOException("connect to https://reports.example.com failed: token=secret")
            return RawResponse(status, body(LAST_SNAPSHOT), sawRedirect, transportFailed)
        }
    }


    private fun uploader(
        transport: DiagnosticTransport,
        endpoint: String = "https://reports.example.com/v1/reports",
        token: String = "upload-only-token",
        now: Long = nowSeconds,
    ) = DiagnosticUploader(
        config = DiagnosticUploadConfig(
            endpoint = endpoint,
            uploadToken = token,
            appVersion = "1.2.0",
            appVersionCode = 3,
            androidApi = 33,
        ),
        transport = transport,
        nowEpochSeconds = { now },
    )

    private fun snap(text: String = "x", id: String = "0f9c1f7e-1c2b-4d3e-8a90-0123456789ab") =
        DiagnosticSnapshot(id, text).also { LAST_SNAPSHOT = it }

    // =====================================================================
    // Request serialization.
    // =====================================================================

    @Test
    fun requestBodyMatchesTheFrozenContract() {
        val s = snap("line one\nline two")
        val transport = FakeTransport()
        val outcome = uploader(transport).upload(s)
        assertTrue("a matching receipt must verify: $outcome", outcome is UploadOutcome.Verified)

        val sent = String(transport.lastBody!!, Charsets.UTF_8)
        assertTrue(sent.contains("\"schema\":1"))
        assertTrue(sent.contains("\"reportId\":\"${s.reportId}\""))
        assertTrue(sent.contains("\"appVersion\":\"1.2.0\""))
        assertTrue(sent.contains("\"appVersionCode\":3"))
        assertTrue(sent.contains("\"androidApi\":33"))
        assertTrue("newlines must be escaped, not raw", sent.contains("\"log\":\"line one\\nline two\""))
    }

    @Test
    fun bearerTokenAndExactEndpointAreUsed() {
        val s = snap()
        val transport = FakeTransport()
        uploader(transport).upload(s)
        assertEquals("https://reports.example.com/v1/reports", transport.lastEndpoint)
        assertEquals("upload-only-token", transport.lastToken)
    }

    @Test
    fun budgetsAreFiniteAndBoundedByTheAbsoluteDeadline() {
        val s = snap()
        val transport = FakeTransport()
        uploader(transport).upload(s)
        assertTrue(transport.lastConnectTimeout in 1..DiagnosticUploadLimits.CONNECT_TIMEOUT_MILLIS)
        assertTrue(transport.lastReadTimeout in 1..DiagnosticUploadLimits.READ_TIMEOUT_MILLIS)
        // connect + read may never sum past the one absolute budget.
        assertTrue(
            "per-phase budgets must stay under the absolute deadline",
            transport.lastConnectTimeout.toLong() + transport.lastReadTimeout.toLong() <=
                DiagnosticUploadLimits.ATTEMPT_DEADLINE_MILLIS,
        )
    }

    // =====================================================================
    // Idempotent 200 and 201.
    // =====================================================================

    @Test
    fun idempotentTwoHundredIsAccepted() {
        val s = snap()
        assertTrue(uploader(FakeTransport(status = 200)).upload(s) is UploadOutcome.Verified)
    }

    @Test
    fun createdIsAccepted() {
        val s = snap()
        assertTrue(uploader(FakeTransport(status = 201)).upload(s) is UploadOutcome.Verified)
    }

    @Test
    fun otherStatusesAreRejectedWithAFixedReason() {
        val s = snap()
        for (code in listOf(202, 204, 301, 400, 401, 403, 429, 500, 502, 503)) {
            val outcome = uploader(FakeTransport(status = code)).upload(s)
            assertEquals(
                "status $code must not verify",
                UploadFailure.SERVER_REJECTED,
                (outcome as UploadOutcome.Rejected).failure,
            )
        }
    }

    // =====================================================================
    // Receipt verification: wrong id / hash / size / expiry.
    // =====================================================================

    @Test
    fun wrongReportIdIsRejected() {
        val s = snap()
        val outcome = uploader(FakeTransport { receiptFor("11111111-2222-3333-4444-555555555555", s.sha256, s.byteCount, FUTURE_ISO) })
            .upload(s)
        assertEquals(UploadFailure.RECEIPT_ID_MISMATCH, (outcome as UploadOutcome.Rejected).failure)
    }

    @Test
    fun wrongSha256IsRejected() {
        val s = snap()
        val outcome = uploader(FakeTransport { receiptFor(s.reportId, "0".repeat(64), s.byteCount, FUTURE_ISO) })
            .upload(s)
        assertEquals(UploadFailure.RECEIPT_HASH_MISMATCH, (outcome as UploadOutcome.Rejected).failure)
    }

    @Test
    fun wrongByteCountIsRejected() {
        val s = snap()
        val outcome = uploader(FakeTransport { receiptFor(s.reportId, s.sha256, s.byteCount + 1, FUTURE_ISO) })
            .upload(s)
        assertEquals(UploadFailure.RECEIPT_SIZE_MISMATCH, (outcome as UploadOutcome.Rejected).failure)
    }

    @Test
    fun expiredOrUnparsableExpiryIsRejected() {
        val s = snap()
        // An epoch integer (the old wrong assumption), a past instant, exactly
        // now, a non-UTC offset, a missing zone and plain nonsense.
        val badExpiry = listOf(
            "4102444800",                      // NOT an ISO string: epoch integer is refused
            "2020-01-01T00:00:00.000Z",        // in the past
            "2023-11-14T22:13:20.000Z",        // exactly now, not in the future
            "2023-11-14T22:13:20.001Z",        // one millisecond in the past
            "2026-10-17T18:35:00.000+02:00",   // non-UTC offset
            "2026-10-17 16:35:00Z",            // no T separator
            "2026-10-17T16:35:00",             // no zone
            "not-a-date",
            "",
        )
        for (expiry in badExpiry) {
            val outcome = uploader(FakeTransport { receiptFor(s.reportId, s.sha256, s.byteCount, expiry) })
                .upload(s)
            assertEquals(
                "expiry '$expiry' must be rejected",
                UploadFailure.RECEIPT_EXPIRY_INVALID,
                (outcome as UploadOutcome.Rejected).failure,
            )
        }
    }

    @Test
    fun validIsoExpiryIsVerifiedAndPreservedVerbatim() {
        val s = snap()
        for (iso in listOf("2026-10-17T16:35:00.000Z", "2026-10-17T16:35:00Z", "2026-10-17T16:35:00.123456789Z")) {
            val outcome = uploader(FakeTransport { receiptFor(s.reportId, s.sha256, s.byteCount, iso) })
                .upload(s)
            assertTrue("ISO expiry $iso must verify: $outcome", outcome is UploadOutcome.Verified)
            val r = (outcome as UploadOutcome.Verified).receipt
            assertEquals("the server's ISO string is preserved verbatim", iso, r.expiresAt)
            assertEquals(s.reportId, r.reportId)
            assertEquals(s.sha256, r.sha256)
            assertEquals(s.byteCount, r.bytes)
        }
    }

    @Test
    fun epochExpiryIsNeverAcceptedAsAFallback() {
        // Guards the exact regression: a far-future epoch integer used to be
        // the parsed shape and must now be refused outright.
        assertEquals(null, DiagnosticUploader.parseIsoUtcToEpochSeconds("4102444800"))
        assertEquals(1792254900L, DiagnosticUploader.parseIsoUtcToEpochSeconds("2026-10-17T16:35:00.000Z"))
    }

    @Test
    fun malformedReceiptsAreRejected() {
        val s = snap()
        val bodies = listOf(
            "not json",
            "[]",
            "{",
            "{\"reportId\":\"${s.reportId}\"",
            "{\"sha256\":\"${s.sha256}\"}",
            "{\"reportId\":\"${s.reportId}\",\"sha256\":\"${s.sha256}\",\"bytes\":${s.byteCount}}",
            "{\"reportId\":123,\"sha256\":\"a\",\"bytes\":1,\"expiresAt\":\"$FUTURE_ISO\"}",
            "{\"reportId\":{\"a\":1},\"sha256\":\"a\",\"bytes\":1,\"expiresAt\":\"$FUTURE_ISO\"}",
            "{\"reportId\":[\"a\"],\"sha256\":\"a\",\"bytes\":1,\"expiresAt\":\"$FUTURE_ISO\"}",
            "{\"reportId\":\"a\",\"reportId\":\"b\"}",
            "{\"reportId\":\"a\",,\"sha256\":\"b\"}",
            "{\"reportId\":\"a\" \"sha256\":\"b\"}",
            "{\"reportId\":\"a\"} trailing",
            "",
        )
        for (body in bodies) {
            val outcome = uploader(FakeTransport { body.toByteArray(Charsets.UTF_8) }).upload(s)
            assertTrue("must reject: '$body'", outcome is UploadOutcome.Rejected)
        }
    }

    @Test
    fun duplicateKeyIsDeniedEvenWhenBothValuesAgree() {
        val s = snap()
        val text = String(ack(s), Charsets.UTF_8).replaceFirst("}", ",\"reportId\":\"${s.reportId}\"}")
        val outcome = uploader(FakeTransport { text.toByteArray(Charsets.UTF_8) }).upload(s)
        assertEquals(
            "a duplicated key must be denied, not silently collapsed",
            UploadFailure.RESPONSE_MALFORMED,
            (outcome as UploadOutcome.Rejected).failure,
        )
    }

    // =====================================================================
    // Strict field types.
    // =====================================================================

    @Test
    fun quotedBytesIsATypeViolationNotCoerced() {
        val s = snap()
        val quoted = String(ack(s), Charsets.UTF_8)
            .replace("\"bytes\":${s.byteCount}", "\"bytes\":\"${s.byteCount}\"")
            .toByteArray(Charsets.UTF_8)
        val outcome = uploader(FakeTransport { quoted }).upload(s)
        assertEquals(
            "a quoted bytes value must be refused, not coerced to an Int",
            UploadFailure.RESPONSE_MALFORMED,
            (outcome as UploadOutcome.Rejected).failure,
        )
    }

    @Test
    fun nonIntegerBytesIsRejected() {
        val s = snap()
        for (bytes in listOf("${s.byteCount}.0", "1e3", "true", "null", "0x10", "017", "+1", "")) {
            val text = String(ack(s), Charsets.UTF_8)
                .replace("\"bytes\":${s.byteCount}", "\"bytes\":$bytes")
                .toByteArray(Charsets.UTF_8)
            val outcome = uploader(FakeTransport { text }).upload(s)
            assertTrue("bytes='$bytes' must be refused", outcome is UploadOutcome.Rejected)
        }
    }

    @Test
    fun negativeBytesIsRejected() {
        val s = snap()
        val text = String(ack(s), Charsets.UTF_8)
            .replace("\"bytes\":${s.byteCount}", "\"bytes\":-1")
            .toByteArray(Charsets.UTF_8)
        val outcome = uploader(FakeTransport { text }).upload(s)
        assertEquals(UploadFailure.RESPONSE_MALFORMED, (outcome as UploadOutcome.Rejected).failure)
    }

    // =====================================================================
    // Size ceilings.
    // =====================================================================

    @Test
    fun oversizedResponseKeepsItsTruthfulCategory() {
        val s = snap()

        // Case A: the transport cut the read off at the ceiling and says so.
        val truncated = uploader(
            object : DiagnosticTransport {
                override fun postJson(
                    endpoint: String, bearerToken: String, jsonBody: ByteArray,
                    connectTimeoutMillis: Int, readTimeoutMillis: Int, deadlineElapsedMillis: Long,
                ) = RawResponse(201, ByteArray(0), sawRedirect = false, transportFailed = false, bodyTooLarge = true)
            }
        ).upload(s)
        assertEquals(
            "an over-ceiling body must stay RESPONSE_TOO_LARGE, not become MALFORMED",
            UploadFailure.RESPONSE_TOO_LARGE,
            (truncated as UploadOutcome.Rejected).failure,
        )

        // Case B: an over-ceiling body arriving through an ordinary transport.
        val padding = "A".repeat(DiagnosticUploadLimits.MAX_RESPONSE_BYTES + 64)
        val big = String(ack(s), Charsets.UTF_8).dropLast(1) + ",\"pad\":\"" + padding + "\"}"
        assertTrue(big.toByteArray(Charsets.UTF_8).size > DiagnosticUploadLimits.MAX_RESPONSE_BYTES)
        val outcome = uploader(FakeTransport { big.toByteArray(Charsets.UTF_8) }).upload(s)
        assertEquals(UploadFailure.RESPONSE_TOO_LARGE, (outcome as UploadOutcome.Rejected).failure)
    }

    @Test
    fun responseExactlyAtTheCeilingIsParsed() {
        val s = snap()
        val head = "{\"reportId\":\"" + s.reportId + "\",\"sha256\":\"" + s.sha256 +
            "\",\"bytes\":" + s.byteCount + ",\"pad\":\""
        val tail = "\",\"expiresAt\":\"" + FUTURE_ISO + "\"}"
        val padLen = DiagnosticUploadLimits.MAX_RESPONSE_BYTES -
            head.toByteArray(Charsets.UTF_8).size - tail.toByteArray(Charsets.UTF_8).size
        val atCeiling = (head + "A".repeat(padLen) + tail).toByteArray(Charsets.UTF_8)
        assertEquals(DiagnosticUploadLimits.MAX_RESPONSE_BYTES, atCeiling.size)
        assertTrue(
            "a body exactly at the ceiling must still verify",
            uploader(FakeTransport { atCeiling }).upload(s) is UploadOutcome.Verified,
        )
    }

    @Test
    fun oversizedLogIsRejectedWithoutSendingAnything() {
        val transport = FakeTransport()
        val s = snap("x".repeat(DiagnosticUploadLimits.MAX_LOG_BYTES + 1))
        val outcome = uploader(transport).upload(s)
        assertEquals(UploadFailure.LOG_TOO_LARGE, (outcome as UploadOutcome.Rejected).failure)
        assertEquals("an oversize log must never reach the wire", 0, transport.calls.get())
    }

    @Test
    fun logAtExactlyTheCeilingIsAccepted() {
        val s = snap("y".repeat(DiagnosticUploadLimits.MAX_LOG_BYTES))
        val outcome = uploader(FakeTransport()).upload(s)
        assertTrue("a 256 KiB log is within contract: $outcome", outcome is UploadOutcome.Verified)
    }

    @Test
    fun multiByteLogIsMeasuredInUtf8BytesNotChars() {
        // 3 UTF-8 bytes per char: the byte count, not the char count, is the cap.
        val chars = DiagnosticUploadLimits.MAX_LOG_BYTES / 3 + 10
        val transport = FakeTransport()
        val s = snap("\u20ac".repeat(chars))
        assertEquals(UploadFailure.LOG_TOO_LARGE, (uploader(transport).upload(s) as UploadOutcome.Rejected).failure)
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun bodyCeilingIsEnforced() {
        // A log near the log cap plus escaping overhead must exceed the body cap.
        val text = "\"\\\n".repeat(60_000)
        val s = snap(text)
        val logBytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue("fixture must be within the log cap: $logBytes", logBytes <= DiagnosticUploadLimits.MAX_LOG_BYTES)
        val transport = FakeTransport()
        val outcome = uploader(transport).upload(s)
        assertEquals(UploadFailure.BODY_TOO_LARGE, (outcome as UploadOutcome.Rejected).failure)
        assertEquals("an oversize body must never reach the wire", 0, transport.calls.get())
    }

    // =====================================================================
    // Redirects, transport failures and the absolute budget.
    // =====================================================================

    @Test
    fun redirectIsRefusedEvenWhenTheStatusLooksSuccessful() {
        val s = snap()
        val outcome = uploader(FakeTransport(status = 200, sawRedirect = true)).upload(s)
        assertEquals(UploadFailure.REDIRECT_REFUSED, (outcome as UploadOutcome.Rejected).failure)
    }

    @Test
    fun transportFailureIsRejected() {
        val s = snap()
        val outcome = uploader(FakeTransport(transportFailed = true)).upload(s)
        assertEquals(UploadFailure.NETWORK_UNAVAILABLE, (outcome as UploadOutcome.Rejected).failure)
    }

    @Test
    fun thrownTransportExceptionNeverLeaksDetail() {
        val s = snap()
        val outcome = uploader(FakeTransport(throwOnCall = true)).upload(s)
        assertEquals(UploadFailure.NETWORK_UNAVAILABLE, (outcome as UploadOutcome.Rejected).failure)
        // The outcome carries only an enum: no message, URL or token can escape.
        assertFalse(outcome.toString().contains("secret"))
        assertFalse(outcome.toString().contains("https"))
    }

    @Test
    fun aLateReturnPastTheAbsoluteBudgetIsTimedOutEvenIfItLooksSuccessful() {
        val s = snap()
        val slow = object : DiagnosticTransport {
            override fun postJson(
                endpoint: String, bearerToken: String, jsonBody: ByteArray,
                connectTimeoutMillis: Int, readTimeoutMillis: Int, deadlineElapsedMillis: Long,
            ): RawResponse {
                Thread.sleep(DiagnosticUploadLimits.ATTEMPT_DEADLINE_MILLIS + 400)
                return RawResponse(201, ack(s), sawRedirect = false, transportFailed = false)
            }
        }
        val outcome = uploader(slow).upload(s)
        assertEquals(
            "a late return must not be able to claim success",
            UploadFailure.TIMED_OUT,
            (outcome as UploadOutcome.Rejected).failure,
        )
    }

    // =====================================================================
    // Configuration is fail-closed.
    // =====================================================================

    @Test
    fun onlyHttpsExactReportsPathEndpointsAreAccepted() {
        fun build(endpoint: String) = DiagnosticUploadConfig(
            endpoint = endpoint,
            uploadToken = "t",
            appVersion = "1.0",
            appVersionCode = 1,
            androidApi = 33,
        )
        listOf(
            "http://reports.example.com/v1/reports",       // cleartext
            "ftp://reports.example.com/v1/reports",
            "not a url",
            "",
            "https://reports.example.com/v1/upload",        // wrong path
            "https://reports.example.com/",                 // missing path
            "https://reports.example.com/v1/reports/",      // trailing slash
            "https://reports.example.com/v1/reports/extra", // path suffix
            "https://reports.example.com/api/v1/reports",   // path prefix
            "https://reports.example.com/v1/reports?x=1",   // query string
            "https://reports.example.com/v1/reports#frag",  // fragment
            "https://reports.example.com:8443/v1/reports",  // explicit port
            "https://reports.example.com:443/v1/reports",   // even the default port
            "https://user:pass@reports.example.com/v1/reports", // userinfo
            "//reports.example.com/v1/reports",             // scheme-relative
            "https://reports.example.com/v1/repo rts",      // embedded space
        ).forEach { bad ->
            var threw = false
            try {
                build(bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("must reject endpoint: '$bad'", threw)
        }
        val good = build("https://reports.example.com/v1/reports")
        assertEquals("https://reports.example.com", good.origin)
        assertTrue(good.isTlsOnly)
    }

    @Test
    fun invalidBuildMetadataIsRejected() {
        fun build(version: String, code: Int, api: Int) = DiagnosticUploadConfig(
            endpoint = "https://reports.example.com/v1/reports",
            uploadToken = "t",
            appVersion = version,
            appVersionCode = code,
            androidApi = api,
        )
        listOf(
            Triple("x".repeat(41), 1, 33),   // version too long
            Triple("1.0", 0, 33),           // versionCode must be positive
            Triple("1.0", -1, 33),
            Triple("1.0", 1, 25),           // below the declared minSdk
            Triple("1.0", 1, 101),          // above the contract ceiling
            Triple("", 1, 33),              // empty version
            Triple("1.0\n", 1, 33),         // control character
        ).forEach { (v, c, a) ->
            var threw = false
            try {
                build(v, c, a)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("must reject version='$v' code=$c api=$a", threw)
        }
    }

    @Test
    fun blankOrMalformedTokenIsRejected() {
        for (bad in listOf("", "   ", "tok en", "tok\nen")) {
            var threw = false
            try {
                DiagnosticUploadConfig(
                    endpoint = "https://reports.example.com/v1/reports",
                    uploadToken = bad,
                    appVersion = "1.0",
                    appVersionCode = 1,
                    androidApi = 33,
                )
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("a malformed upload token must fail closed: '$bad'", threw)
        }
    }

    @Test
    fun canonicalReportIdIsEnforcedByTheHelper() {
        assertTrue(DiagnosticUploader.isCanonicalReportId("0f9c1f7e-1c2b-4d3e-8a90-0123456789ab"))
        assertFalse(DiagnosticUploader.isCanonicalReportId("0F9C1F7E-1C2B-4D3E-8A90-0123456789AB"))
        assertFalse(DiagnosticUploader.isCanonicalReportId("not-a-uuid"))
        assertFalse(DiagnosticUploader.isCanonicalReportId(""))
    }

    @Test
    fun controlCharactersInTheLogAreEscapedNotDropped() {
        val s = snap("a\u0007b\u000Cc\u001Bd")
        val body = String(uploader(FakeTransport()).buildBody(s)!!, Charsets.UTF_8)
        assertTrue("control chars must be \\u escaped: $body", body.contains("a\\u0007b\\fc\\u001bd"))
    }

    @Test
    fun buildBodyIsPureAndRepeatable() {
        val s = snap("stable content\nwith a newline")
        val u = uploader(FakeTransport())
        assertEquals(u.buildBody(s)!!.toList(), u.buildBody(s)!!.toList())
    }

    companion object {
        @Volatile
        var LAST_SNAPSHOT: DiagnosticSnapshot = DiagnosticSnapshot(
            "0f9c1f7e-1c2b-4d3e-8a90-0123456789ab",
            "log",
        )
    }
}

/** Well after the suites' fixed now (2023-11-14T22:13:20Z). */
private const val FUTURE_ISO = "2026-10-17T16:35:00.000Z"

/**
 * The backend's exact ack body. `expiresAt` is an ISO-8601 UTC *string*, and
 * `bytes` is a JSON integer - never a quoted string, never an epoch integer.
 */
private fun receiptFor(id: String, sha: String, bytes: Int, expiresAtIso: String): ByteArray {
    val sb = StringBuilder()
    sb.append('{').append("\"reportId\":\"").append(id).append("\",")
    sb.append("\"sha256\":\"").append(sha).append("\",")
    sb.append("\"bytes\":").append(bytes).append(',')
    sb.append("\"expiresAt\":\"").append(expiresAtIso).append("\"}")
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun ack(snap: DiagnosticSnapshot): ByteArray =
    receiptFor(snap.reportId, snap.sha256, snap.byteCount, FUTURE_ISO)

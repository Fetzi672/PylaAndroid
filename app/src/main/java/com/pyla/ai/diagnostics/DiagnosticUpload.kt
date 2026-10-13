package com.pyla.ai.diagnostics

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Contract + verification layer for the confirmed private diagnostic upload.
 *
 * The upload is only ever reached through [DiagnosticConsentMachine.confirm].
 * There is no retry anywhere in this file: a failed attempt is terminal and is
 * reported with a fixed, safe reason. No URL, token, raw exception message or
 * server body can reach the UI through [UploadOutcome].
 */

object DiagnosticUploadLimits {
    /** Maximum UTF-8 bytes of the `log` field. */
    const val MAX_LOG_BYTES = 256 * 1024
    /** Maximum UTF-8 bytes of the whole request body. */
    const val MAX_BODY_BYTES = 300 * 1024
    /** Maximum UTF-8 bytes of a decoded response body. */
    const val MAX_RESPONSE_BYTES = 4 * 1024
    /** Absolute wall-clock budget for one attempt, connect through body read. */
    const val ATTEMPT_DEADLINE_MILLIS = 20_000L
    /** Per-phase connect / read budgets, always also bounded by the absolute deadline. */
    const val CONNECT_TIMEOUT_MILLIS = 10_000
    const val READ_TIMEOUT_MILLIS = 10_000
    /** The exact request path the backend serves. No prefix, suffix, query or fragment. */
    const val REQUIRED_PATH = "/v1/reports"
    /** schema value of the request contract. */
    const val SCHEMA = 1
}

/** Immutable endpoint + build identity. Fails closed when misconfigured. */
data class DiagnosticUploadConfig(
    val endpoint: String,
    val uploadToken: String,
    val appVersion: String,
    val appVersionCode: Int,
    val androidApi: Int,
) {
    val origin: String
    val isTlsOnly: Boolean

    init {
        require(
            endpoint.isNotBlank() &&
                !endpoint.any { it.isWhitespace() || it.isISOControl() },
        ) { "endpoint must not contain whitespace or control characters" }

        val parsed = runCatching { URI(endpoint) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        val host = parsed?.host
        val path = parsed?.path
        isTlsOnly = scheme == "https" && !host.isNullOrEmpty() && host.none { it.isWhitespace() }
        origin = if (isTlsOnly) "https://$host" else ""
        require(isTlsOnly) { "endpoint must be an absolute https URL" }
        require(parsed!!.rawAuthority == host) { "endpoint must carry no port, no userinfo and no percent-encoding tricks" }
        require(parsed.userInfo == null) { "endpoint must not carry userinfo" }
        require(parsed.port == -1) { "endpoint must not carry an explicit port" }
        require(parsed.query == null && parsed.rawQuery == null) { "endpoint must not carry a query string" }
        require(parsed.fragment == null && parsed.rawFragment == null) { "endpoint must not carry a fragment" }
        require(path == DiagnosticUploadLimits.REQUIRED_PATH) {
            "endpoint path must be exactly ${DiagnosticUploadLimits.REQUIRED_PATH}"
        }
        require(uploadToken.isNotBlank() && uploadToken.none { it.isWhitespace() || it.isISOControl() }) {
            "upload token must be a single non-blank token"
        }
        require(appVersion.isNotEmpty() && appVersion.length <= 40) { "appVersion must be 1..40 chars" }
        require(appVersion.none { it.isISOControl() }) { "appVersion must not contain control characters" }
        require(appVersionCode > 0) { "appVersionCode must be positive" }
        require(androidApi in 26..100) { "androidApi outside the contract range" }
    }
}

/** Fixed, secret-free failure vocabulary. Nothing else is ever surfaced. */
enum class UploadFailure {
    NOT_CONFIGURED,
    LOG_TOO_LARGE,
    BODY_TOO_LARGE,
    NETWORK_UNAVAILABLE,
    TIMED_OUT,
    REDIRECT_REFUSED,
    TLS_OR_ORIGIN_REJECTED,
    SERVER_REJECTED,
    RESPONSE_TOO_LARGE,
    RESPONSE_MALFORMED,
    RECEIPT_ID_MISMATCH,
    RECEIPT_HASH_MISMATCH,
    RECEIPT_SIZE_MISMATCH,
    RECEIPT_EXPIRY_INVALID,
}

/** Terminal result of one attempt. Exactly one of [receipt]/[failure] is set. */
sealed class UploadOutcome {
    data class Verified(val receipt: DiagnosticReceipt) : UploadOutcome()
    data class Rejected(val failure: UploadFailure) : UploadOutcome()
}

/**
 * Server-confirmed receipt. Every field is type- and value-checked by the
 * client before it is allowed to exist.
 *
 * [expiresAt] is the server's ISO-8601 UTC instant exactly as returned, e.g.
 * `2026-10-17T16:35:00.000Z`. The backend does **not** send an epoch integer;
 * anything else is rejected before a receipt is constructed.
 */
data class DiagnosticReceipt(
    val reportId: String,
    val sha256: String,
    val bytes: Int,
    val expiresAt: String,
)

/** One HTTP attempt as observed by [DiagnosticUploader]. Fakeable in unit tests. */
interface DiagnosticTransport {
    /**
     * @param connectTimeoutMillis per-phase budget already clamped to the absolute deadline
     * @param readTimeoutMillis per-phase budget already clamped to the absolute deadline
     * @param deadlineElapsedMillis millis already spent before this call
     * @return status code, response body bytes (already size-capped by the transport),
     *         whether the server tried to redirect and whether the body was cut off
     *         for exceeding the decoded ceiling.
     */
    fun postJson(
        endpoint: String,
        bearerToken: String,
        jsonBody: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        deadlineElapsedMillis: Long,
    ): RawResponse
}

data class RawResponse(
    val statusCode: Int,
    val bodyBytes: ByteArray,
    val sawRedirect: Boolean,
    val transportFailed: Boolean,
    /** True when the body was abandoned because it exceeded [DiagnosticUploadLimits.MAX_RESPONSE_BYTES]. */
    val bodyTooLarge: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is RawResponse && statusCode == other.statusCode && sawRedirect == other.sawRedirect &&
            transportFailed == other.transportFailed && bodyTooLarge == other.bodyTooLarge &&
            bodyBytes.contentEquals(other.bodyBytes)

    override fun hashCode(): Int = ((statusCode * 31 + sawRedirect.hashCode()) * 31 +
        transportFailed.hashCode()) * 31 + bodyTooLarge.hashCode() * 7 + bodyBytes.contentHashCode()
}

/**
 * Serializes the frozen snapshot into the request body, performs exactly one
 * attempt through the injected transport and verifies the receipt.
 *
 * Stateless with respect to retries: [upload] performs exactly one transport
 * call for one call of [upload]. The caller owns the single-worker fencing.
 */
class DiagnosticUploader(
    private val config: DiagnosticUploadConfig,
    private val transport: DiagnosticTransport,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {

    /** Serialize the frozen snapshot. Pure; used by tests to assert byte exactness. */
    fun buildBody(snapshot: DiagnosticSnapshot): ByteArray? {
        val logBytes = utf8Size(snapshot.text)
        if (logBytes > DiagnosticUploadLimits.MAX_LOG_BYTES) return null
        val body = (
            "{" +
                "\"schema\":" + DiagnosticUploadLimits.SCHEMA + "," +
                "\"reportId\":\"" + jsonEscape(snapshot.reportId) + "\"," +
                "\"appVersion\":\"" + jsonEscape(config.appVersion) + "\"," +
                "\"appVersionCode\":" + config.appVersionCode + "," +
                "\"androidApi\":" + config.androidApi + "," +
                "\"log\":\"" + jsonEscape(snapshot.text) + "\"" +
                "}"
            ).toByteArray(Charsets.UTF_8)
        if (body.size > DiagnosticUploadLimits.MAX_BODY_BYTES) return null
        return body
    }

    /** Exactly one attempt. Never retries; every rejection is terminal. */
    fun upload(snapshot: DiagnosticSnapshot): UploadOutcome {
        val logBytes = utf8Size(snapshot.text)
        if (logBytes > DiagnosticUploadLimits.MAX_LOG_BYTES) {
            return UploadOutcome.Rejected(UploadFailure.LOG_TOO_LARGE)
        }
        val body = buildBody(snapshot)
            ?: return UploadOutcome.Rejected(UploadFailure.BODY_TOO_LARGE)

        val start = System.nanoTime()
        // Every per-phase budget is clamped to whatever is left of the one
        // absolute budget, so connect + write + read can never sum past it.
        val remaining = DiagnosticUploadLimits.ATTEMPT_DEADLINE_MILLIS - elapsedMillis(start)
        val connectBudget = DiagnosticUploadLimits.CONNECT_TIMEOUT_MILLIS.coerceAtMost(remaining.toInt()).coerceAtLeast(1)
        val readBudget = DiagnosticUploadLimits.READ_TIMEOUT_MILLIS.coerceAtMost(remaining.toInt()).coerceAtLeast(1)

        val raw = try {
            transport.postJson(
                endpoint = config.endpoint,
                bearerToken = config.uploadToken,
                jsonBody = body,
                connectTimeoutMillis = connectBudget,
                readTimeoutMillis = readBudget,
                deadlineElapsedMillis = elapsedMillis(start),
            )
        } catch (t: Throwable) {
            // Throwable text may embed the URL, the token or a proxy string: never surfaced.
            return UploadOutcome.Rejected(UploadFailure.NETWORK_UNAVAILABLE)
        }

        // Late-return check: a transport that ignored its timeouts must not be
        // allowed to report success just because it eventually came back.
        if (elapsedMillis(start) > DiagnosticUploadLimits.ATTEMPT_DEADLINE_MILLIS) {
            return UploadOutcome.Rejected(UploadFailure.TIMED_OUT)
        }

        if (raw.transportFailed) return UploadOutcome.Rejected(UploadFailure.NETWORK_UNAVAILABLE)
        if (raw.sawRedirect) return UploadOutcome.Rejected(UploadFailure.REDIRECT_REFUSED)
        // Truthful category: an over-ceiling body is reported as oversize, never
        // degraded into "malformed" by handing back an empty byte array.
        if (raw.bodyTooLarge || raw.bodyBytes.size > DiagnosticUploadLimits.MAX_RESPONSE_BYTES) {
            return UploadOutcome.Rejected(UploadFailure.RESPONSE_TOO_LARGE)
        }
        if (raw.statusCode !in ACCEPTED_STATUS) return UploadOutcome.Rejected(UploadFailure.SERVER_REJECTED)

        val fields = parseFlatJsonObject(String(raw.bodyBytes, Charsets.UTF_8))
            ?: return UploadOutcome.Rejected(UploadFailure.RESPONSE_MALFORMED)

        val reportIdField = fields["reportId"]
        if (reportIdField !is JsonScalar.StringValue) {
            return UploadOutcome.Rejected(UploadFailure.RESPONSE_MALFORMED)
        }
        val reportId = reportIdField.value
        if (reportId.isEmpty() || reportId != snapshot.reportId) {
            return UploadOutcome.Rejected(UploadFailure.RECEIPT_ID_MISMATCH)
        }

        val shaField = fields["sha256"]
        if (shaField !is JsonScalar.StringValue) {
            return UploadOutcome.Rejected(UploadFailure.RESPONSE_MALFORMED)
        }
        val sha = shaField.value
        if (sha != snapshot.sha256) {
            return UploadOutcome.Rejected(UploadFailure.RECEIPT_HASH_MISMATCH)
        }

        // `bytes` must be a JSON integer. A quoted "123" is a type violation,
        // not something to coerce into an Int.
        val bytesField = fields["bytes"]
        if (bytesField !is JsonScalar.IntegerValue || bytesField.value < 0L || bytesField.value > Int.MAX_VALUE) {
            return UploadOutcome.Rejected(UploadFailure.RESPONSE_MALFORMED)
        }
        val bytes = bytesField.value.toInt()
        if (bytes != snapshot.byteCount) return UploadOutcome.Rejected(UploadFailure.RECEIPT_SIZE_MISMATCH)

        // `expiresAt` is an ISO-8601 UTC instant string, never an epoch integer.
        val expiryField = fields["expiresAt"]
        if (expiryField !is JsonScalar.StringValue) {
            return UploadOutcome.Rejected(UploadFailure.RECEIPT_EXPIRY_INVALID)
        }
        val expiresAt = expiryField.value
        val expiresAtEpoch = parseIsoUtcToEpochSeconds(expiresAt)
        if (expiresAtEpoch == null || expiresAtEpoch <= nowEpochSeconds()) {
            return UploadOutcome.Rejected(UploadFailure.RECEIPT_EXPIRY_INVALID)
        }

        return UploadOutcome.Verified(
            DiagnosticReceipt(
                reportId = reportId,
                sha256 = sha,
                bytes = bytes,
                expiresAt = expiresAt,
            )
        )
    }

    companion object {
        /** 201 Created, or 200 OK for an idempotent replay of the same report id. */
        val ACCEPTED_STATUS = setOf(200, 201)
        private val CANONICAL_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** Strict ISO-8601 UTC: `YYYY-MM-DDTHH:MM:SS[.fff…]Z`. No offsets, no missing zone. */
        private val ISO_UTC = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$")

        fun isCanonicalReportId(id: String): Boolean = CANONICAL_UUID.matches(id)

        /** Parses the contract's ISO-8601 UTC instant; null for anything else. */
        fun parseIsoUtcToEpochSeconds(text: String): Long? {
            if (!ISO_UTC.matches(text)) return null
            return runCatching { Instant.parse(text).epochSecond }.getOrNull()
        }

        private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

        private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size
    }
}

/**
 * Default [DiagnosticTransport]: platform default CA + hostname verification,
 * redirects disabled, ambient proxies bypassed, bounded phases under one
 * absolute deadline, and a hard decoded-response ceiling that is reported as
 * such instead of being silently truncated.
 */
class HttpUrlDiagnosticTransport(
    private val watchdog: ScheduledExecutorService = newWatchdog(),
) : DiagnosticTransport {

    override fun postJson(
        endpoint: String,
        bearerToken: String,
        jsonBody: ByteArray,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        deadlineElapsedMillis: Long,
    ): RawResponse {
        var connection: HttpURLConnection? = null
        try {
            val url: URL = URL(endpoint)
            if (!url.protocol.equals("https", ignoreCase = true)) {
                return RawResponse(0, ByteArray(0), sawRedirect = false, transportFailed = true)
            }
            // Explicitly NO_PROXY: an ambient system proxy must never be handed
            // the bearer token, and no proxy may rewrite the destination.
            connection = (url.openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                useCaches = false
                doOutput = true
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                // Platform default TrustManager + HostnameVerifier stay in place;
                // nothing here relaxes TLS.
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + bearerToken)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
                setFixedLengthStreamingMode(jsonBody.size)
            }
            // Absolute deadline: disconnect the socket even if a phase budget is stuck.
            val watchdogMillis = (DiagnosticUploadLimits.ATTEMPT_DEADLINE_MILLIS - deadlineElapsedMillis)
                .coerceAtLeast(1L)
            val watchdogTask = watchdog.schedule(
                { runCatching { connection.disconnect() } },
                watchdogMillis,
                TimeUnit.MILLISECONDS,
            )
            try {
                connection.outputStream.use { it.write(jsonBody) }
                val status = connection.responseCode
                val sawRedirect = status in 300..399
                if (status !in 200..299) {
                    return RawResponse(status, ByteArray(0), sawRedirect, transportFailed = false)
                }
                val stream = connection.inputStream
                val tooLarge = readCapped(stream)
                if (tooLarge == null) {
                    // Report the real category instead of an empty body.
                    return RawResponse(status, ByteArray(0), sawRedirect, transportFailed = false, bodyTooLarge = true)
                }
                return RawResponse(status, tooLarge, sawRedirect, transportFailed = false)
            } finally {
                watchdogTask.cancel(false)
            }
        } catch (t: Throwable) {
            return RawResponse(0, ByteArray(0), sawRedirect = false, transportFailed = true)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Returns null when the decoded body exceeds the ceiling. */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            total += read
            if (total > DiagnosticUploadLimits.MAX_RESPONSE_BYTES) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private fun newWatchdog(): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(
                ThreadFactory { r -> Thread(r, "pyla-diag-deadline").apply { isDaemon = true } }
            )
    }
}

/** JSON string escaping for the request body. Control characters are escaped, never dropped. */
internal fun jsonEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 16)
    for (ch in raw) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else ->
                if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.toString()
}

/**
 * A parsed JSON scalar, keeping the *declared type* so a quoted integer can
 * never be coerced into a number later on.
 */
sealed class JsonScalar {
    data class StringValue(val value: String) : JsonScalar()
    data class IntegerValue(val value: Long) : JsonScalar()
}

/**
 * Strict flat-object JSON reader for the receipt: one object, only string and
 * (optionally signed, non-negative) integer scalars at the top level, no
 * nesting, no floats, no keywords, no duplicate keys and no trailing content.
 */
internal fun parseFlatJsonObject(text: String): Map<String, JsonScalar>? {
    var i = 0
    fun skipWs(): Unit {
        while (i < text.length && (text[i] == ' ' || text[i] == '\n' || text[i] == '\r' || text[i] == '\t')) i++
    }

    fun readString(): String? {
        // assumes text[i] == '"'
        i++
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i]
            if (c == '"') { i++; return sb.toString() }
            if (c == '\\') {
                i++
                if (i >= text.length) return null
                when (val e = text[i]) {
                    '"', '\\', '/' -> { sb.append(e); i++ }
                    'n' -> { sb.append('\n'); i++ }
                    'r' -> { sb.append('\r'); i++ }
                    't' -> { sb.append('\t'); i++ }
                    'b' -> { sb.append('\b'); i++ }
                    'f' -> { sb.append('\u000C'); i++ }
                    'u' -> {
                        if (i + 4 >= text.length) return null
                        val hex = text.substring(i + 1, i + 5)
                        val code = hex.toIntOrNull(16) ?: return null
                        sb.append(code.toChar()); i += 5
                    }
                    else -> return null
                }
            } else {
                if (c < ' ') return null // raw control characters are not legal JSON
                sb.append(c); i++
            }
        }
        return null
    }

    /** Only an optionally negative integer literal; floats, exponents and keywords are rejected. */
    fun readInteger(): JsonScalar.IntegerValue? {
        val start = i
        if (i < text.length && text[i] == '-') i++
        val digitsStart = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == digitsStart) return null
        val literal = text.substring(start, i)
        // A leading zero is not valid JSON ("0123"), and "+3" is not either.
        if (literal.startsWith("-0") && literal.length > 2) return null
        if (!literal.startsWith("-") && literal.length > 1 && literal[0] == '0') return null
        val value = literal.toLongOrNull() ?: return null
        return JsonScalar.IntegerValue(value)
    }

    skipWs()
    if (i >= text.length || text[i] != '{') return null
    i++
    val out = LinkedHashMap<String, JsonScalar>()
    skipWs()
    if (i < text.length && text[i] == '}') {
        i++
        skipWs()
        return if (i == text.length) out else null
    }
    while (true) {
        skipWs()
        if (i >= text.length || text[i] != '"') return null
        val key = readString() ?: return null
        skipWs()
        if (i >= text.length || text[i] != ':') return null
        i++
        skipWs()
        if (i >= text.length) return null
        val value = when (text[i]) {
            '"' -> JsonScalar.StringValue(readString() ?: return null)
            '{', '[', 't', 'f', 'n' -> return null // no nesting, no keywords
            else -> readInteger() ?: return null
        }
        if (out.put(key, value) != null) return null // duplicate key is denied
        skipWs()
        if (i < text.length && text[i] == ',') { i++; continue }
        if (i < text.length && text[i] == '}') { i++; break }
        return null
    }
    skipWs()
    return if (i == text.length) out else null
}
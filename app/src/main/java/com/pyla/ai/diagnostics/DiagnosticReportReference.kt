package com.pyla.ai.diagnostics

import java.math.BigInteger

/**
 * Exact random report reference. With the current public viewer, its holder may
 * read the report after human verification; treat the reference as a capability.
 * Encodes the ENTIRE existing random UUID losslessly; never truncates it or
 * creates a second upload identity. Only the verified-success UI exposes it.
 */
object DiagnosticReportReference {
    private const val PREFIX = "pyla-android-"
    private const val DIGITS = 25
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val referencePattern = Regex("pyla-android-[0-9a-z]{25}")

    fun fromReportId(reportId: String): String {
        require(uuidPattern.matches(reportId)) { "Invalid report ID" }
        val encoded = BigInteger(reportId.replace("-", ""), 16).toString(36)
        return PREFIX + encoded.padStart(DIGITS, '0')
    }

    /** Decoding preserves identity only; it does not authorize a read or bypass viewer verification. */
    fun toReportId(reference: String): String {
        require(referencePattern.matches(reference)) { "Invalid report reference" }
        val value = BigInteger(reference.substring(PREFIX.length), 36)
        require(value.bitLength() <= 128) { "Invalid report reference" }
        val hex = value.toString(16).padStart(32, '0')
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-" +
            "${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

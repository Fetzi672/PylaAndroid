package com.pyla.ai.diagnostics

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportReferenceTest {
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected strict rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun referenceHasRequestedPrefixAndFixedWidth() {
        val ref = DiagnosticReportReference.fromReportId("37427515-ff28-4680-b489-35d454ad6bf1")
        assertTrue(ref.matches(Regex("pyla-android-[0-9a-z]{25}")))
        assertEquals(38, ref.length)
    }
    @Test fun exactIdRoundTripsIncludingAllLeadingZeros() {
        for (id in listOf("00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-0000-000000000001",
            "37427515-ff28-4680-b489-35d454ad6bf1",
            "ffffffff-ffff-ffff-ffff-ffffffffffff")) {
            assertEquals(id, DiagnosticReportReference.toReportId(DiagnosticReportReference.fromReportId(id)))
        }
    }
    @Test fun independentlyRandomIdsRoundTripWithoutTruncation() {
        val ids = (1..1000).map { UUID.randomUUID().toString() }
        val refs = ids.map { DiagnosticReportReference.fromReportId(it) }
        assertEquals(ids, refs.map { DiagnosticReportReference.toReportId(it) })
        assertEquals(ids.toSet().size, refs.toSet().size)
    }
    @Test fun idsWithIdenticalPrefixesStillHaveDistinctReferences() {
        val a = DiagnosticReportReference.fromReportId("37427515-ff28-4680-b489-35d454ad6bf1")
        val b = DiagnosticReportReference.fromReportId("37427515-ff28-4680-b489-35d454ad6bf2")
        assertNotEquals(a, b)
    }
    @Test fun uuidInputIsNotRepairedOrNormalized() {
        for (id in listOf("1-1-1-1-1", "37427515-FF28-4680-b489-35d454ad6bf1",
            " 37427515-ff28-4680-b489-35d454ad6bf1", "37427515ff284680b48935d454ad6bf1",
            "37427515-ff28-4680-b489-35d454ad6bf1\n")) rejects { DiagnosticReportReference.fromReportId(id) }
    }
    @Test fun malformedOrOversizedReferencesAreRejected() {
        val valid = DiagnosticReportReference.fromReportId("37427515-ff28-4680-b489-35d454ad6bf1")
        for (ref in listOf(valid.uppercase(), " " + valid, valid + "\n", valid.dropLast(1),
            valid + "0", "pyla-android-" + "z".repeat(25), "pyla-android-" + "_".repeat(25))) {
            rejects { DiagnosticReportReference.toReportId(ref) }
        }
    }
}

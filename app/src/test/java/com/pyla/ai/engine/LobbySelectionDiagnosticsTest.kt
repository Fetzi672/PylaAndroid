package com.pyla.ai.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Source-level contract for the selection diagnostics. It pins the wiring that the Android
 * integration cannot exercise on the JVM: one terminal line per invocation, concrete rejection
 * codes instead of a generic channel, typed exception observation, and an unchanged result
 * vocabulary. Runtime behaviour of the selectors themselves stays with their own gates.
 */
class LobbySelectionDiagnosticsTest {
    private val root: File = System.getProperty("pyla.source.dir")?.let { File(it) } ?: File(".")

    private fun source(name: String): String = listOf(
        File(root, "app/src/main/java/com/pyla/ai/engine/$name.kt"),
        File(root, "src/main/java/com/pyla/ai/engine/$name.kt"),
    ).first { it.isFile }.readText()

    private fun region(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("missing anchor $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("missing anchor $end", to >= 0)
        return text.substring(from, to)
    }

    private fun selectBrawler() = region(source("LobbyAutomation"), "fun selectBrawler(", "fun close()")

    @Test fun exactlyOneTerminalPerInvocationAndItRunsInFinally() {
        val body = selectBrawler()
        assertEquals("one terminal call, never repeated per branch", 1,
            Regex("DiagnosticLog\\.terminal\\(").findAll(body).count())
        val finallyAt = body.indexOf("} finally {")
        val terminalAt = body.indexOf("DiagnosticLog.terminal(")
        assertTrue("the terminal line must be emitted in the finally block", finallyAt in 0 until terminalAt)
        assertTrue("the terminal line must never fold", body.contains(
            "DiagnosticLog.terminal(\"selection\", \"select\", terminalResult, terminalReason)"))
    }

    @Test fun everyResultStillTravelsThroughTheTrackedHelper() {
        val body = selectBrawler()
        assertFalse("a bare result return would bypass the terminal line",
            Regex("return \"(success|stuck|failed|error)\"").containsMatchIn(body))
        val results = Regex("(finish|phase|selectTerminal)\\(\"([a-z]+)\"")
            .findAll(body).map { it.groupValues[2] }.toSet()
        assertEquals(setOf("error", "stuck", "failed", "success"), results)
        assertEquals("success must have exactly one exit", 1,
            Regex("finish\\(\"success\"").findAll(body).count())
    }

    @Test fun terminalReasonInventoryIsFixed() {
        val reasons = Regex("(finish|phase|selectTerminal)\\(([^()]*)\\)")
            .findAll(selectBrawler())
            .mapNotNull { call ->
                // Only calls whose FINAL argument is a fixed literal name a terminal reason.
                val last = call.groupValues[2].substringAfterLast(',').trim()
                Regex("^\"([a-z_]+)\"$").find(last)?.groupValues?.get(1)
            }.toSet()
        assertEquals(setOf(
            "target_name_empty", "selection_not_allowed",
            "target_name_unresolved", "menu_entry_unconfirmed",
            "detail_denied", "selection_verified", "phase_timeout", "page_exhausted",
            "unexpected_exception", "detail_pair_unsettled", "detail_identity_incomplete",
            "lobby_return_unconfirmed",
        ), reasons)
    }

    @Test fun admissionAndLiveDenialsReuseTheirExistingFixedTokens() {
        val body = selectBrawler()
        assertTrue(body.contains("return finish(\"stuck\", admission.reason.token)"))
        assertTrue(body.contains("return finish(\"stuck\", denial.token)"))
        assertTrue(body.contains("return finish(\"stuck\", swipeDenial.token)"))
    }

    @Test fun genericExceptionCarriesOnlyItsType() {
        val body = selectBrawler()
        assertTrue(body.contains("} catch (t: Throwable) {"))
        assertTrue(body.contains("DiagnosticLog.failure(\"selection\", \"select\", \"unexpected_exception\", t)"))
        assertTrue("the pre-existing result must not change",
            body.contains("finish(\"error\", \"unexpected_exception\")"))
    }

    @Test fun listSessionReportsTheActualRejectionCode() {
        val body = selectBrawler()
        assertEquals("the single visual capture attempt must observe", 1,
            Regex("observer = sessionObserver").findAll(body).count())
        assertTrue(body.contains("var sessionRejection: BrawlerListSearchSession.Rejection? = null"))
        assertTrue("the generic event must survive", body.contains(
            "\"reason=list_session_started\" else \"reason=list_session_unavailable\""))
        assertTrue(body.contains("sessionRejection?.code ?: \"list_session_unobserved\""))
        val session = source("BrawlerListSearchSession")
        assertTrue(session.contains("enum class Rejection(val code: String)"))
        assertTrue(session.contains("fun interface CaptureObserver"))
        assertTrue("capture must stay optional and default-off",
            session.contains("observer: CaptureObserver? = null"))
        assertTrue("an observer failure may never change the null",
            session.contains("try { observer.rejected(reason) } catch (_: Throwable) { }"))
    }

    @Test fun everyDiagnosticArgumentInTheSelectorIsFixedText() {
        val calls = Regex("DiagnosticLog\\.(failure|terminal)\\([^)]*\\)").findAll(selectBrawler())
        assertTrue("expected observable failures", calls.count() > 0)
        for (call in calls) assertFalse("no OCR/name/pixel interpolation: ${call.value}",
            call.value.contains("$"))
    }

    @Test fun silentCatchesNowCarryTheirExceptionType() {
        val text = source("LobbyAutomation")
        for (site in listOf(
            "DiagnosticLog.failure(\"config\", \"names\", \"json_unreadable\", t)",
            "DiagnosticLog.failure(\"selection\", \"grid_ocr\", \"ocr_exception\", t)",
            "DiagnosticLog.failure(\"selection\", \"detail_ocr\", \"ocr_timeout\", t)",
            "DiagnosticLog.failure(\"entry\", \"brawler_menu\", \"menu_ocr_exception\", t)",
        )) assertTrue("missing typed observation: $site", text.contains(site))
        val silent = Regex("catch \\(_: Exception\\)").findAll(region(text, "fun selectBrawler(", "fun close()"))
        assertEquals("no silent exception catch may remain in the selector", 0, silent.count())
    }

    @Test fun entryRetryDenialUsesAOneWordLiteralVocabulary() {
        val entry = region(source("LobbyAutomation"), "private fun openBrawlerMenu(", "fun selectBrawler(")
        assertTrue(entry.contains("if (!more) DiagnosticLog.failure(\"entry\", \"brawler_menu\", \"retry_denied_\$reason\")"))
        val reasons = Regex("retry\\(\"([a-z_]+)\"\\)").findAll(entry).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("frame_unavailable", "lobby_unconfirmed", "menu_ocr_unavailable",
            "evidence_expired", "distinct_frame_unavailable", "lobby_changed",
            "menu_label_unconfirmed", "menu_control_moving", "live_control_unconfirmed"), reasons)
        assertEquals("declaration plus literal call sites only, never a computed reason",
            Regex("""retry\("[a-z_]+"\)""").findAll(entry).count() + 1,
            Regex("""retry\(""").findAll(entry).count())
        assertTrue(entry.contains("DiagnosticLog.failure(\"entry\", \"brawler_menu\", entryStop)"))
    }
}

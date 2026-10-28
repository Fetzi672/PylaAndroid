package com.pyla.ai.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-wiring checks for the observation-only pipeline timing seams in `engine/Detect.kt`.
 *
 * These read the production source and assert where the capture points actually sit relative to the
 * real execution calls (`session.run`, `preprocess`, `decoder.decode`, `finally` cleanup). They are
 * deliberately *not* runtime evidence: they cannot prove device timing, provider offload or native
 * behaviour, and they make no GPU/NPU claim. Comments are stripped before every assertion so prose
 * can never satisfy a wiring check.
 */
class DetectPipelineTimingWiringTest {

    private val detectSource: String by lazy { codeOnly(src("engine/Detect.kt")) }

    private fun src(rel: String): String =
        listOf(File("src/main/java/com/pyla/ai/$rel"), File("app/src/main/java/com/pyla/ai/$rel"))
            .first { it.isFile }.readText()

    /** Remove block and line comments so only executable/source structure remains. */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")

    private fun region(body: String, start: String, end: String): String {
        val from = body.indexOf(start)
        assertTrue("missing region start: $start", from >= 0)
        val to = body.indexOf(end, from + start.length)
        assertTrue("missing region end after $start: $end", to > from)
        return body.substring(from, to)
    }

    private fun indexOf(body: String, needle: String): Int {
        val at = body.indexOf(needle)
        assertTrue("missing: $needle", at >= 0)
        return at
    }

    /** Assert [needles] appear in [body] in exactly this order. */
    private fun assertOrder(body: String, vararg needles: String) {
        var previous = -1
        needles.forEach { needle ->
            val at = body.indexOf(needle, previous + 1)
            assertTrue("out of order or missing after index $previous: $needle", at > previous)
            previous = at
        }
    }

    private fun occurrences(body: String, needle: String): Int {
        var count = 0
        var at = body.indexOf(needle)
        while (at >= 0) {
            count++
            at = body.indexOf(needle, at + needle.length)
        }
        return count
    }

    private val legacyDetect get() = region(detectSource, "fun detectObjects(", "private fun runInference(")
    private val legacyRun get() = region(detectSource, "private fun runInference(", "private fun nms(")
    private val semanticDetect get() = region(detectSource, "private fun detectSemanticTerrain(", "private class SemanticOutputs")
    private val semanticRun get() = region(detectSource, "private fun runSemanticInference(", "private fun readSemanticOutputs(")
    private val semanticRead get() = region(detectSource, "private fun readSemanticOutputs(", "private fun validateSemanticSession(")

    /** Whitespace-insensitive view for multi-line call shapes. */
    private val flat: String get() = detectSource.replace(Regex("""\s+"""), " ")

    @Test
    fun `stage family is selected from the existing model telemetry role`() {
        assertTrue(detectSource.contains("private val timingStages: TimingStages = TimingStages.forRole(telemetryRole)"))
        val forRole = region(detectSource, "fun forRole(role: String): TimingStages", "private fun preprocess(")
        assertTrue("tile roles must report the terrain family", forRole.contains("PerceptionPerformanceTelemetry.ROLE_TILE"))
        assertTrue(forRole.contains("PerceptionPerformanceTelemetry.ROLE_TILE_CENTERED"))
        listOf("TERRAIN_PREPROCESS", "TERRAIN_INFERENCE", "TERRAIN_DECODE").forEach {
            assertTrue("missing $it", forRole.contains("Stage.$it"))
        }
        listOf("ENTITY_PREPROCESS", "ENTITY_INFERENCE", "ENTITY_DECODE").forEach {
            assertTrue("missing $it", forRole.contains("Stage.$it"))
        }
        assertTrue("no close-button model exists in this file",
            !detectSource.contains("Stage.CLOSE_"))
    }

    @Test
    fun `legacy path measures preprocess separately from inference`() {
        val body = legacyDetect
        assertOrder(
            body,
            "val preprocessStart = diagnosticStart()",
            "val (buf, nw, nh) = preprocess(img)",
            "recordStage(timingStages.preprocess, preprocessStart)",
            "OnnxTensor.createTensor(env, buf",
            "runInference(tensor)",
        )
        assertFalse("preprocess must not be folded into the inference span",
            legacyRun.contains("recordStage(timingStages.preprocess"))
        assertFalse("no timing inside the preprocess helper itself",
            region(detectSource, "private fun preprocess(img: Mat)", "private fun preprocessSemantic(")
                .contains("recordStage("))
    }

    @Test
    fun `legacy inference span covers only the measured session run`() {
        val body = legacyRun
        assertOrder(
            body,
            "val attemptStart = diagnosticStart()",
            "val outputs = session.run(mapOf(inputName to tensor))",
            "val runEndedAtMs = diagnosticStart()",
            "recordStage(timingStages.inference, attemptStart)",
            "TimedPrediction(normalizeOutput(outputs[0].value), runEndedAtMs)",
        )
        // The decode span starts where the run ended, so output materialisation is not counted twice.
        assertOrder(
            legacyDetect,
            "val timed = try {",
            "runInference(tensor)",
            "} finally {",
            "tensor.close()",
            "val decodeStart = timed.runEndedAtMs",
        )
        assertEquals("both successful attempt results must still be closed",
            2, occurrences(legacyRun, "} finally { outputs.close() }"))
        assertEquals("the input tensor must still be closed exactly once",
            1, occurrences(legacyDetect, "tensor.close()"))
    }

    @Test
    fun `legacy decode span ends at every exit that produced a prediction`() {
        val body = legacyDetect
        assertEquals("one decode sample per returning frame", 2, occurrences(body, "recordStage(timingStages.decode, decodeStart)"))
        assertOrder(
            body,
            "val decodeStart = timed.runEndedAtMs",
            "val nDet = prediction.shape[0]",
            "recordStage(timingStages.decode, decodeStart)",
        )
        assertOrder(
            body,
            "for ((cls, idxs) in clsGroups)",
            "recordStage(timingStages.decode, decodeStart)",
            "return results",
        )
        // The null-prediction exit performs no decode work and must stay unreported.
        assertOrder(body, "if (prediction == null) return emptyMap()", "val decodeStart = timed.runEndedAtMs")
    }

    @Test
    fun `legacy fallback keeps its original sequence and reports provider metadata`() {
        val body = legacyRun
        assertOrder(
            body,
            "if (providerName == \"CPU\") throw first",
            "try { session.close() } catch (_: Throwable) {}",
            "val loaded = createCpuSession(modelFile, configuredThreadCount)",
            "session = loaded.first",
            "providerName = loaded.second",
            "PerceptionPerformanceTelemetry.recordFallback(",
            "observe {",
            "PipelineTimingTelemetry.model(",
            "val retryStart = diagnosticStart()",
            "val outputs = session.run(mapOf(inputName to tensor))",
            "recordStage(timingStages.inference, retryStart)",
        )
        assertTrue("the failed attempt must still be measured before the retry",
            body.contains("if (!attemptRecorded) recordStage(timingStages.inference, attemptStart)"))
        assertTrue("original exception must be rethrown unchanged", body.contains("throw first"))
    }

    @Test
    fun `semantic path measures preprocess, run and decode without touching cleanup`() {
        val body = semanticDetect
        assertOrder(
            body,
            "val preprocessStart = diagnosticStart()",
            "val (buffer, geometry) = preprocessSemantic(img)",
            "recordStage(timingStages.preprocess, preprocessStart)",
            "OnnxTensor.createTensor(env, buffer",
            "runSemanticInference(tensor)",
            "} finally {",
            "tensor.close()",
            "val decodeStart = timed.runEndedAtMs",
            "runtime.decoder.decode(",
        )
        assertEquals("one decode sample per returning frame", 2,
            occurrences(body, "recordStage(timingStages.decode, decodeStart)"))
        assertOrder(
            body,
            "runtime.decoder.decode(",
            "if (boxes.isEmpty()) {",
            "recordStage(timingStages.decode, decodeStart)",
            "return emptyMap()",
        )
        assertOrder(
            body,
            "for (box in boxes)",
            "recordStage(timingStages.decode, decodeStart)",
            "return results",
        )
        assertOrder(body, "val decodeStart = timed.runEndedAtMs", "System.arraycopy(timed.classIds")
        assertTrue("the semantic run must not report the entity family",
            !body.contains("Stage.ENTITY_"))
    }

    @Test
    fun `semantic retry has its own span and copies rasters once per attempt`() {
        val body = semanticRun
        assertOrder(
            body,
            "val attemptStart = diagnosticStart()",
            "val result = session.run(mapOf(inputName to tensor))",
            "val runEndedAtMs = diagnosticStart()",
            "recordStage(timingStages.inference, attemptStart)",
            "val rasters = readSemanticOutputs(result)",
            "SemanticOutputs(rasters.first, rasters.second, runEndedAtMs)",
        )
        assertOrder(
            body,
            "if (!attemptRecorded) recordStage(timingStages.inference, attemptStart)",
            "if (providerName == \"CPU\") throw first",
            "val loaded = createCpuSession(modelFile, configuredThreadCount)",
            "validateSemanticSession(manifest)",
            "val retryStart = diagnosticStart()",
            "recordStage(timingStages.inference, retryStart)",
            "val rasters = readSemanticOutputs(result)",
        )
        assertEquals("one raster copy per attempt, never twice",
            2, occurrences(body, "readSemanticOutputs(result)"))
        assertEquals("the ORT result is still closed by the raster reader",
            1, occurrences(semanticRead, "try { result.close() } catch (_: Throwable) {}"))
        assertTrue("the reader still returns the copied arrays, not a live tensor",
            semanticRead.contains("return ids to confidence"))
    }

    @Test
    fun `no extra inference work and no direct clock reads were added`() {
        assertEquals("inference call count must stay 4 (two legacy, two semantic attempts)",
            4, occurrences(detectSource, "session.run(mapOf(inputName to tensor))"))
        assertEquals("createSession fallback chain must not gain a call",
            1, occurrences(detectSource, "private fun createCpuSession("))
        assertFalse("the guard clock is read only through the facade", detectSource.contains("SystemClock"))
        assertFalse(detectSource.contains("System.nanoTime"))
        assertEquals("one guarded clock read helper", 1, occurrences(detectSource, "PipelineTimingTelemetry.nowMs()"))
        assertEquals("requested-vs-effective metadata after constructor and both fallbacks",
            3, occurrences(detectSource, "PipelineTimingTelemetry.model("))
        assertTrue("the metadata carries requested and effective provider plus threads",
            flat.contains(
                "PipelineTimingTelemetry.model( telemetryRole, requestedProviderName, providerName, configuredThreadCount, )",
            ))
    }

    @Test
    fun `provider policy is untouched and requested provider is observation only`() {
        val factory = region(detectSource, "private fun createSessionWithFallback(", "private fun createXnnpackOrCpu(")
        assertOrder(
            factory,
            "val nnapiAttempted = preferredDevice != \"cpu\" && !isEmulator()",
            "requestedProviderName = if (nnapiAttempted) \"NNAPI\" else \"XNNPACK\"",
            "if (nnapiAttempted) {",
            "opts.addNnapi()",
        )
        assertTrue("NNAPI attempt order is unchanged",
            factory.indexOf("opts.addNnapi()") < factory.indexOf("return env.createSession(f.absolutePath, opts) to \"NNAPI\""))
        assertTrue(factory.contains("} else if (preferredDevice == \"cpu\") {"))
        assertTrue("the XNNPACK chain is still the fall-through",
            factory.contains("return createXnnpackOrCpu(f, threads)"))
        assertEquals("requested provider is written once, read only by the metadata observation",
            5, occurrences(detectSource, "requestedProviderName"))
        assertFalse("requested provider must never gate detection",
            Regex("""if\s*\(\s*requestedProviderName""").containsMatchIn(detectSource))
    }

    @Test
    fun `timing failures cannot escape or replace an original exception`() {
        assertTrue(detectSource.contains(
            "private fun diagnosticStart(): Long = try { PipelineTimingTelemetry.nowMs() } catch (_: Throwable) { -1L }"))
        val record = region(detectSource, "private fun recordStage(", "private class TimingStages")
        assertOrder(record, "if (startedAtMs < 0L) return", "PipelineTimingTelemetry.elapsed(stage, startedAtMs)")
        assertFalse("elapsed already records and rejects invalid durations; never double-record",
            record.contains("PipelineTimingTelemetry.record("))
        assertTrue("the observation body is total", record.contains("observe {"))
        assertEquals("one elapsed call site records exactly once", 1, occurrences(detectSource, "PipelineTimingTelemetry.elapsed("))
        assertEquals(0, occurrences(detectSource, "PipelineTimingTelemetry.record("))
        assertEquals("every model() observation is wrapped", 4, occurrences(detectSource, "observe {"))
        assertEquals("only the two original rethrow sites remain", 2, occurrences(detectSource, "throw first"))
        assertFalse("no timing call may run inside a finally closing a tensor or result",
            Regex("""finally\s*\{[^}]*PipelineTimingTelemetry""").containsMatchIn(detectSource))
        assertFalse("no timing call may replace an exception reason",
            Regex("""throw\s+\w*Exception\([^)]*(elapsed|nowMs|valueMs)""").containsMatchIn(detectSource))
    }
}

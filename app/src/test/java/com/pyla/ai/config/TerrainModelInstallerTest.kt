package com.pyla.ai.config

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class TerrainModelInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val model = "owned-model-one".toByteArray()
    private val meta = "owned-manifest-one".toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun root() = tmp.newFolder()
    private fun install(r: File, m: ByteArray = model, j: ByteArray = meta) =
        TerrainModelInstaller.synchronize(r, sha(m), sha(j), { m.inputStream() }, { j.inputStream() })
    private fun marker(r: File) = File(r, "models/.terrain-generations/active.txt")
    private fun denied(block: () -> Unit) { try { block(); fail("must deny") } catch (expected: Exception) { /* rejected */ } }

    @Test fun cleanPairIsVerifiedAndActivated() {
        val r = root(); val x = install(r)
        assertTrue(x.changed); assertArrayEquals(model, x.model.readBytes()); assertArrayEquals(meta, x.metadata.readBytes())
        assertEquals(x.generation + "\n", marker(r).readText())
        assertEquals(x.model, TerrainModelInstaller.active(r).model)
    }
    @Test fun retainedModelConfigAndScriptStayUntouched() {
        val r = root(); File(r, "models").mkdir(); File(r, "cfg").mkdir(); File(r, "playstyles").mkdir()
        val old = File(r, "models/tileDetector.onnx"); old.writeText("rollback-model")
        val cfg = File(r, "cfg/general_config.toml"); cfg.writeText("user-settings")
        val script = File(r, "playstyles/mine.pyla"); script.writeText("user-script")
        install(r); assertEquals("rollback-model", old.readText()); assertEquals("user-settings", cfg.readText()); assertEquals("user-script", script.readText())
    }
    @Test fun sameCommittedGenerationPreservesEditsWithoutReopeningBundle() {
        val r = root(); val x = install(r); x.model.writeText("custom-model"); x.metadata.writeText("custom-manifest")
        val next = TerrainModelInstaller.synchronize(r, sha(model), sha(meta), { error("must not reopen") }, { error("must not reopen") })
        assertFalse(next.changed); assertEquals("custom-model", next.model.readText()); assertEquals("custom-manifest", next.metadata.readText())
    }
    @Test fun generationChangeRetainsPriorCompletePair() {
        val r = root(); val first = install(r); val second = install(r, "owned-next-model".toByteArray())
        assertNotEquals(first.generation, second.generation); assertTrue(first.model.exists()); assertArrayEquals(model, first.model.readBytes())
        assertEquals(second.generation, TerrainModelInstaller.active(r).generation)
    }
    @Test fun metadataFailureNeverActivatesHalfPair() {
        val r = root()
        denied { TerrainModelInstaller.synchronize(r, sha(model), sha(meta), { model.inputStream() }, { throw IOException("owned") }) }
        assertFalse(marker(r).exists()); assertTrue(File(r, "models/.terrain-generations").listFiles()!!.isEmpty())
    }
    @Test fun failedNextGenerationRetainsOldActivation() {
        val r = root(); val old = install(r); val before = marker(r).readBytes()
        denied { TerrainModelInstaller.synchronize(r, sha("next".toByteArray()), sha(meta), { "wrong".byteInputStream() }, { meta.inputStream() }) }
        assertArrayEquals(before, marker(r).readBytes()); assertEquals(old.model, TerrainModelInstaller.active(r).model)
    }
    @Test fun incorrectDigestDoesNotPublish() {
        val r = root()
        denied { TerrainModelInstaller.synchronize(r, sha(model), sha(meta), { "different".byteInputStream() }, { meta.inputStream() }) }
        assertFalse(marker(r).exists())
    }
    @Test fun emptyModelDoesNotPublish() {
        val r = root(); denied { install(r, byteArrayOf()) }; assertFalse(marker(r).exists())
    }
    @Test fun invalidDigestRejectedBeforeFilesystemWrites() {
        val r = root(); denied { TerrainModelInstaller.synchronize(r, "INVALID", sha(meta), { model.inputStream() }, { meta.inputStream() }) }
        assertTrue(r.listFiles()!!.isEmpty())
    }
    @Test fun invalidMarkerIsPreserved() {
        val r = root(); install(r); marker(r).writeText("unknown-state")
        denied { install(r) }; assertEquals("unknown-state", marker(r).readText())
    }
    @Test fun missingManifestDeniesActiveGeneration() {
        val r = root(); val x = install(r); assertTrue(x.metadata.delete()); denied { TerrainModelInstaller.active(r) }
    }
    @Test fun symlinkModelsDirectoryIsNotFollowed() {
        val r = root(); val outside = root(); Files.createSymbolicLink(File(r, "models").toPath(), outside.toPath())
        denied { install(r) }; assertTrue(outside.listFiles()!!.isEmpty())
    }
    @Test fun symlinkMarkerIsNotReplaced() {
        val r = root(); install(r); assertTrue(marker(r).delete()); val outside = tmp.newFile(); outside.writeText("protected")
        Files.createSymbolicLink(marker(r).toPath(), outside.toPath()); denied { install(r) }; assertEquals("protected", outside.readText())
    }
    @Test fun symlinkPairMemberDeniedWithoutChangingTarget() {
        val r = root(); val x = install(r); assertTrue(x.model.delete()); val outside = tmp.newFile(); outside.writeText("protected")
        Files.createSymbolicLink(x.model.toPath(), outside.toPath()); denied { install(r) }; assertEquals("protected", outside.readText())
    }
    @Test fun publishedUnmarkedPairCanBeRecoveredWithoutCopy() {
        val r = root(); val x = install(r); assertTrue(marker(r).delete())
        val next = TerrainModelInstaller.synchronize(r, sha(model), sha(meta), { error("must adopt verified pair") }, { error("must adopt verified pair") })
        assertEquals(x.generation, next.generation); assertEquals(x.model, TerrainModelInstaller.active(r).model)
    }
    @Test fun corruptUnmarkedGenerationCannotBeAdopted() {
        val r = root(); val x = install(r); assertTrue(marker(r).delete()); x.model.writeText("unknown-collision")
        denied { install(r) }; assertFalse(marker(r).exists()); assertEquals("unknown-collision", x.model.readText())
    }
    @Test fun resolveRedirectsOnlyFactorySlot() {
        val r = root(); val x = install(r); val factory = File(r, "models/tileDetector.onnx"); val custom = File(r, "models/custom.onnx")
        assertEquals(x.model, TerrainModelInstaller.resolve(r, factory)); assertEquals(custom, TerrainModelInstaller.resolve(r, custom))
    }
}

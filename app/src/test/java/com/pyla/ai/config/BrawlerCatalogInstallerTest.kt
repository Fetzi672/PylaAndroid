package com.pyla.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * Focused JVM tests for the retained-install catalog migration. Pure filesystem: no
 * Android, no JSON parser, no Gradle-managed assets. Every case starts from a "retained"
 * files directory whose unrelated settings must survive untouched.
 */
class BrawlerCatalogInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val oldCatalog = """{"shelly":{"name":"Shelly"},"colt":{"name":"Colt"}}"""
    private val newCatalog = """{"shelly":{"name":"Shelly"},"nori":{"name":"Nori"},"cosmo":{"name":"Cosmo"},"wendy":{"name":"Wendy"}}"""

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun root(): File = tmp.newFolder("pyla")

    private fun catalog(root: File): File = File(root, BrawlerCatalogInstaller.MANAGED_RELATIVE_PATH)

    private fun marker(root: File): File = File(root, BrawlerCatalogInstaller.MARKER_FILE_NAME)

    /** Simulates a retained app install: version-8 marker, old catalog, user config+script. */
    private fun retainedRoot(): File {
        val root = root()
        File(root, "cfg").mkdirs()
        File(root, "playstyles").mkdirs()
        File(root, "cfg/bot_config.toml").writeText("maximum_ips = 42\ncurrent_playstyle = \"mine.pyla\"\n")
        File(root, "cfg/general_config.toml").writeText("window_width = 1280\n")
        File(root, "playstyles/mine.pyla").writeText("def on_tick(): pass\n")
        File(root, "pyla_assets_version.txt").writeText("8\n")
        catalog(root).writeText(oldCatalog)
        return root
    }

    private fun install(root: File, bundle: String = newCatalog) =
        BrawlerCatalogInstaller.synchronize(root, sha(bundle.toByteArray())) {
            ByteArrayInputStream(bundle.toByteArray())
        }

    @Test
    fun cleanInstallWritesBundleBytesAndMarker() {
        val root = root()
        File(root, "cfg").mkdirs()
        File(root, "cfg/general_config.toml").writeText("window_width = 1280\n")

        val result = install(root)

        assertTrue(result.ok)
        assertTrue(result.changed)
        assertEquals(newCatalog, catalog(root).readText())
        assertEquals(sha(newCatalog.toByteArray()), BrawlerCatalogInstaller.readMarker(marker(root)))
        assertNull(result.backup)
    }

    @Test
    fun generationChangeReplacesOnlyCatalogAndKeepsContentAddressedBackup() {
        val root = retainedRoot()

        val result = install(root)

        assertTrue(result.ok)
        assertTrue(result.changed)
        assertEquals(newCatalog, catalog(root).readText())
        assertEquals(oldCatalog, result.backup!!.readText())
        assertEquals("brawlers_info.json.${sha(oldCatalog.toByteArray())}.bak", result.backup!!.name)
        assertEquals("8\n", File(root, "pyla_assets_version.txt").readText())
        assertEquals("maximum_ips = 42\ncurrent_playstyle = \"mine.pyla\"\n",
            File(root, "cfg/bot_config.toml").readText())
        assertEquals("def on_tick(): pass\n", File(root, "playstyles/mine.pyla").readText())
    }

    @Test
    fun sameGenerationPreservesUserEditWithoutRewriting() {
        val root = retainedRoot()
        assertTrue(install(root).ok)
        val edited = """{"shelly":{"name":"Shelly"},"mine":{"name":"Private"}}"""
        catalog(root).writeText(edited)
        val stamp = catalog(root).lastModified()

        val second = install(root)

        assertTrue(second.ok)
        assertFalse(second.changed)
        assertEquals("preserved-user-edit", second.reason)
        assertEquals(edited, catalog(root).readText())
        assertEquals(stamp, catalog(root).lastModified())
    }

    @Test
    fun missingTargetIsRestoredForCurrentGeneration() {
        val root = retainedRoot()
        assertTrue(install(root).ok)
        assertTrue(catalog(root).delete())

        val second = install(root)

        assertTrue(second.ok)
        assertTrue(second.changed)
        assertEquals(newCatalog, catalog(root).readText())
    }

    @Test
    fun zeroLengthTargetIsRestored() {
        val root = retainedRoot()
        assertTrue(install(root).ok)
        catalog(root).writeText("")

        val second = install(root)

        assertTrue(second.ok)
        assertTrue(second.changed)
        assertEquals(newCatalog, catalog(root).readText())
    }

    @Test
    fun interruptedRunAfterMoveIsAdoptedWithoutRewrite() {
        val root = retainedRoot()
        catalog(root).writeText(newCatalog) // move happened, marker write did not
        val stamp = catalog(root).lastModified()

        val result = install(root)

        assertTrue(result.ok)
        assertFalse(result.changed)
        assertEquals("adopted-completed-move", result.reason)
        assertEquals(newCatalog, catalog(root).readText())
        assertEquals(stamp, catalog(root).lastModified())
        assertEquals(sha(newCatalog.toByteArray()), BrawlerCatalogInstaller.readMarker(marker(root)))
    }

    @Test
    fun secondRunIsIdempotent() {
        val root = retainedRoot()
        assertTrue(install(root).changed)
        val stamp = catalog(root).lastModified()

        val second = install(root)

        assertTrue(second.ok)
        assertFalse(second.changed)
        assertEquals("current", second.reason)
        assertEquals(stamp, catalog(root).lastModified())
        assertTrue(File(root, "cfg").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun digestMismatchLeavesEverythingUntouched() {
        val root = retainedRoot()
        val wrong = sha("something else".toByteArray())

        val result = BrawlerCatalogInstaller.synchronize(root, wrong) {
            ByteArrayInputStream(newCatalog.toByteArray())
        }

        assertFalse(result.ok)
        assertTrue(result.reason.startsWith("bundle-digest-mismatch"))
        assertEquals(oldCatalog, catalog(root).readText())
        assertNull(BrawlerCatalogInstaller.readMarker(marker(root)))
        assertTrue(File(root, "cfg").listFiles()!!.none { it.name.endsWith(".tmp") })
        assertFalse(File(root, BrawlerCatalogInstaller.BACKUP_DIR_RELATIVE_PATH).exists())
    }

    @Test
    fun unreadableBundleLeavesEverythingUntouched() {
        val root = retainedRoot()
        val digest = sha(newCatalog.toByteArray())

        val result = BrawlerCatalogInstaller.synchronize(root, digest) {
            throw java.io.IOException("asset stream closed")
        }

        assertFalse(result.ok)
        assertTrue(result.reason.startsWith("bundle-unreadable"))
        assertEquals(oldCatalog, catalog(root).readText())
        assertNull(BrawlerCatalogInstaller.readMarker(marker(root)))
    }

    @Test
    fun successiveGenerationsKeepOneBackupPerContent() {
        val root = retainedRoot()
        assertTrue(install(root).changed)

        val third = """{"shelly":{},"nori":{},"cosmo":{},"wendy":{},"meeple":{}}"""
        val result = install(root, third)

        assertTrue(result.changed)
        assertEquals(newCatalog, result.backup!!.readText())
        val backups = File(root, BrawlerCatalogInstaller.BACKUP_DIR_RELATIVE_PATH).listFiles()!!.map { it.name }.sorted()
        assertEquals(
            listOf(
                "brawlers_info.json.${sha(oldCatalog.toByteArray())}.bak",
                "brawlers_info.json.${sha(newCatalog.toByteArray())}.bak",
            ).sorted(),
            backups,
        )
        assertEquals(third, catalog(root).readText())
    }

    @Test
    fun symlinkedTargetIsRefused() {
        val root = retainedRoot()
        val outside = File(tmp.root, "outside.json")
        outside.writeText(oldCatalog)
        assertTrue(catalog(root).delete())
        Files.createSymbolicLink(Paths.get(catalog(root).path), Paths.get(outside.path))

        val result = install(root)

        assertFalse(result.ok)
        assertEquals("symlink-refused", result.reason)
        assertEquals(oldCatalog, outside.readText())
        assertNull(BrawlerCatalogInstaller.readMarker(marker(root)))
    }

    @Test
    fun invalidExpectedDigestIsRejected() {
        val root = retainedRoot()

        val result = BrawlerCatalogInstaller.synchronize(root, "not-a-digest") {
            ByteArrayInputStream(newCatalog.toByteArray())
        }

        assertFalse(result.ok)
        assertEquals("invalid-digest", result.reason)
        assertEquals(oldCatalog, catalog(root).readText())
        assertNotNull(catalog(root))
    }

    @Test fun backupDirectorySymlinkNeverWritesOutsideCatalog() {
        val root = retainedRoot()
        val outside = tmp.newFolder("outside-backups")
        Files.createSymbolicLink(File(root, BrawlerCatalogInstaller.BACKUP_DIR_RELATIVE_PATH).toPath(), outside.toPath())
        assertFalse(install(root).ok)
        assertTrue(outside.listFiles()!!.isEmpty())
        assertEquals(oldCatalog, catalog(root).readText())
    }
    @Test fun danglingTemporarySymlinkIsNotFollowed() {
        val root = retainedRoot()
        val outside = File(tmp.root, "must-not-be-created.json")
        val digest = sha(newCatalog.toByteArray())
        val temp = File(root, "cfg/.brawlers_info.json.${digest.take(16)}.tmp")
        Files.createSymbolicLink(temp.toPath(), outside.toPath())
        assertFalse(install(root).ok)
        assertFalse(outside.exists())
        assertEquals(oldCatalog, catalog(root).readText())
    }
    @Test fun existingDifferentBackupAtContentAddressIsNotOverwritten() {
        val root = retainedRoot()
        val backups = File(root, BrawlerCatalogInstaller.BACKUP_DIR_RELATIVE_PATH)
        assertTrue(backups.mkdirs())
        val backup = File(backups, "brawlers_info.json.${sha(oldCatalog.toByteArray())}.bak")
        backup.writeText("existing recovery evidence")
        assertFalse(install(root).ok)
        assertEquals("existing recovery evidence", backup.readText())
        assertEquals(oldCatalog, catalog(root).readText())
    }
    @Test fun adoptedMoveDoesNotClaimSuccessWhenMarkerCannotBeWritten() {
        val root = retainedRoot()
        catalog(root).writeText(newCatalog)
        assertTrue(marker(root).mkdir())
        assertFalse(install(root).ok)
        assertTrue(marker(root).isDirectory)
        assertEquals(newCatalog, catalog(root).readText())
    }
}

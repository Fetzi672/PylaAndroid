package com.pyla.ai.config

import java.io.File
import java.io.FileOutputStream
import com.pyla.ai.diagnostics.DiagnosticLog
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Retained-install migration for the ONE managed catalog file `cfg/brawlers_info.json`.
 *
 * Why this exists: [AssetBundler] intentionally keeps a global asset version (8) so an
 * in-place update never overwrites user configuration, imported playstyles or settings.
 * Bumping that version to ship a newer brawler catalog would re-copy every asset and
 * destroy those user changes. Instead the catalog alone is reconciled against the bytes
 * packaged in the APK, identified by their content digest.
 *
 * Policy (deliberate, documented):
 *  - A revision marker records which bundle generation's catalog was installed
 *    (`pyla_catalog_revision.txt`, `sha256=<hex>`).
 *  - Same generation + catalog present: nothing is written, so a user edit of the
 *    catalog survives every later startup. Managed bytes are not re-enforced per launch.
 *  - New generation (marker absent or different): the existing catalog is first backed
 *    up under a content-addressed name in `cfg/catalog-backups/`, then replaced
 *    atomically. Exactly one migration per bundle generation.
 *  - Missing (or zero-length, i.e. content-free) catalog: restored from the bundle.
 *  - The marker is written only after the new bytes are readable back with the expected
 *    digest, so an interrupted run simply retries on the next launch; if the file move
 *    completed but the marker did not, the next run adopts the already-correct bytes
 *    instead of rewriting them.
 *
 * No Android types, no JSON parsing, no network. Bounded memory (fixed 64 KiB copy
 * buffer, 4 MiB catalog cap), same-directory temp file with fd.sync before an atomic
 * rename, and no symlink traversal.
 */
object BrawlerCatalogInstaller {

    const val MANAGED_RELATIVE_PATH = "cfg/brawlers_info.json"
    const val MARKER_FILE_NAME = "pyla_catalog_revision.txt"
    const val BACKUP_DIR_RELATIVE_PATH = "cfg/catalog-backups"

    private const val MARKER_PREFIX = "sha256="
    private const val MAX_CATALOG_BYTES = 4L * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024
    private val HEX64 = Regex("[0-9a-f]{64}")
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * Outcome of one reconciliation. [ok] == false always means "nothing is worse than
     * before": the marker was not advanced, and replaced bytes have a verified backup.
     * The catalog may already be the new complete file if only its marker failed; retry
     * then adopts that file rather than reporting an unsuccessful marker as committed.
     */
    data class Installation(
        val ok: Boolean,
        val changed: Boolean,
        val digest: String,
        val reason: String,
        val backup: File? = null,
    )

    /**
     * Reconciles the retained catalog under [rootDir] with the catalog streamed by
     * [openBundle], whose SHA-256 the caller already knows as [expectedDigest].
     * Synchronized so two concurrent first-launch callers cannot race on the temp name.
     */
    @Synchronized
    fun synchronize(
        rootDir: File,
        expectedDigest: String,
        openBundle: () -> InputStream,
    ): Installation {
        val digest = expectedDigest
        if (!HEX64.matches(digest)) return Installation(false, false, digest, "invalid-digest")

        val cfgDir = File(rootDir, "cfg")
        val target = File(rootDir, MANAGED_RELATIVE_PATH)
        val marker = File(rootDir, MARKER_FILE_NAME)

        // Never write through a symlinked root/cfg/target: the app owns these paths.
        if (isSymlink(rootDir) || isSymlink(cfgDir) || isSymlink(target) || isSymlink(marker)) {
            return Installation(false, false, digest, "symlink-refused")
        }

        return try {
            val markerDigest = readMarker(marker)
            val targetDigest = sha256(target)
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && targetDigest == null) {
                return Installation(false, false, digest, "target-unreadable")
            }

            if (markerDigest == digest) {
                when {
                    targetDigest == digest -> Installation(true, false, digest, "current")
                    targetDigest == null -> install(rootDir, target, marker, digest, openBundle, "target-missing")
                    targetDigest == EMPTY_SHA256 -> install(rootDir, target, marker, digest, openBundle, "target-empty")
                    // Same bundle generation: a differing on-disk catalog is a user edit.
                    // Keep it; managed bytes are not silently re-enforced on every startup.
                    else -> Installation(true, false, digest, "preserved-user-edit")
                }
            } else if (targetDigest == digest) {
                // A previous run moved the file but did not reach the marker write.
                if (writeMarker(marker, digest)) Installation(true, false, digest, "adopted-completed-move")
                else Installation(false, false, digest, "marker-write-failed")
            } else {
                val reason = if (targetDigest == null) "target-missing" else "generation-change"
                install(rootDir, target, marker, digest, openBundle, reason)
            }
        } catch (e: Exception) {
            DiagnosticLog.failure("assets", "catalog_install", "install_exception", e)
            Installation(false, false, digest, "failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Lowercase hex SHA-256 of [file], or null when absent, not a regular file or unreadable. */
    fun sha256(file: File): String? {
        if (!file.isFile || isSymlink(file) || file.length() > MAX_CATALOG_BYTES) return null
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            hex(md.digest())
        } catch (_: Exception) {
            null
        }
    }

    /** Digest recorded by [MARKER_FILE_NAME], or null when absent/malformed. */
    fun readMarker(marker: File): String? {
        if (!marker.isFile || isSymlink(marker) || marker.length() > 128L) return null
        return try {
            val text = marker.readText().trim()
            val body = if (text.startsWith(MARKER_PREFIX)) text.substring(MARKER_PREFIX.length).trim() else text
            if (HEX64.matches(body.lowercase())) body.lowercase() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun install(
        rootDir: File,
        target: File,
        marker: File,
        digest: String,
        openBundle: () -> InputStream,
        reason: String,
    ): Installation {
        val cfgDir = File(rootDir, "cfg")
        if (!ensureDirectory(cfgDir)) return Installation(false, false, digest, "cfg-unavailable")

        val temp = File(cfgDir, ".${target.name}.${digest.take(16)}.tmp")
        if (isSymlink(temp)) return Installation(false, false, digest, "symlink-refused")
        if (temp.exists() && !temp.delete()) {
            return Installation(false, false, digest, "stale-temp")
        }

        val written = try {
            openBundle().use { input -> writeBounded(input, temp) }
        } catch (e: Exception) {
            temp.delete()
            return Installation(false, false, digest, "bundle-unreadable: ${e.message}")
        }
        if (written == null || written.first != digest) {
            temp.delete()
            val actual = written?.first ?: "none"
            return Installation(false, false, digest, "bundle-digest-mismatch: $actual")
        }

        var backup: File? = null
        val previousDigest = sha256(target)
        if (previousDigest != null) {
            backup = writeBackup(rootDir, target, previousDigest)
                ?: run {
                    temp.delete()
                    return Installation(false, false, digest, "backup-failed")
                }
        }

        if (!moveAtomically(temp, target)) {
            temp.delete()
            return Installation(false, false, digest, "move-failed")
        }
        if (sha256(target) != digest) {
            return Installation(false, false, digest, "readback-mismatch", backup)
        }
        if (!writeMarker(marker, digest)) {
            return Installation(false, false, digest, "marker-write-failed", backup)
        }
        return Installation(true, true, digest, reason, backup)
    }

    /** Copies [input] to [out] with fd.sync; returns (digest, bytes) or null when over the cap. */
    private fun writeBounded(input: InputStream, out: File): Pair<String, Long>? {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_BYTES)
        var total = 0L
        FileOutputStream(out).use { output ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_CATALOG_BYTES) return null
                output.write(buf, 0, n)
                md.update(buf, 0, n)
            }
            output.flush()
            output.fd.sync()
        }
        return hex(md.digest()) to total
    }

    /** Stores the replaced catalog under its own content digest; identical bytes reuse one name. */
    private fun writeBackup(rootDir: File, target: File, contentDigest: String): File? {
        val dir = File(rootDir, BACKUP_DIR_RELATIVE_PATH)
        if (!ensureDirectory(dir)) return null
        val backup = File(dir, "${target.name}.$contentDigest.bak")
        if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return if (!isSymlink(backup) && backup.isFile && sha256(backup) == contentDigest) backup else null
        }
        val temp = File(dir, ".${backup.name}.tmp")
        if (isSymlink(temp)) return null
        if (temp.exists() && !temp.delete()) return null
        return try {
            target.inputStream().use { input ->
                val written = writeBounded(input, temp)
                if (written == null || written.first != contentDigest) {
                    temp.delete()
                    return null
                }
            }
            if (!moveAtomically(temp, backup)) {
                temp.delete()
                return null
            }
            if (sha256(backup) == contentDigest) backup else null
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    private fun writeMarker(marker: File, digest: String): Boolean {
        val temp = File(marker.parentFile, ".${marker.name}.tmp")
        if (isSymlink(marker) || isSymlink(temp) || isSymlink(marker.parentFile)) return false
        return try {
            FileOutputStream(temp).use { out ->
                out.write("$MARKER_PREFIX$digest\n".toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            val moved = moveAtomically(temp, marker)
            if (moved) syncDirectory(marker.parentFile)
            if (!moved) temp.delete()
            moved && readMarker(marker) == digest
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    private fun moveAtomically(from: File, to: File): Boolean {
        return try {
            try {
                Files.move(
                    from.toPath(), to.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                return false // Never silently downgrade the atomic-replacement contract.
            }
            syncDirectory(to.parentFile)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Best-effort directory fsync so a rename survives power loss (API 26+). */
    private fun syncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Not all filesystems allow opening a directory; the rename is still atomic.
        }
    }

    private fun ensureDirectory(dir: File): Boolean {
        if (isSymlink(dir)) return false
        if (dir.isDirectory) return true
        if (dir.exists() || isSymlink(dir)) return false
        return dir.mkdirs() && dir.isDirectory
    }

    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        false
    }

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = digits[v ushr 4]
            chars[i * 2 + 1] = digits[v and 0x0f]
        }
        return String(chars)
    }
}

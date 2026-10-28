package com.pyla.ai.config

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Publish a complete model/decoder-manifest generation without modifying retained assets.
 * The old canonical model and all older generations remain available for rollback. The only
 * activation point is the atomic marker replacement, after both new files are synced/read back.
 */
object TerrainModelInstaller {
    private const val DIRECTORY = ".terrain-generations"
    private const val MARKER = "active.txt"
    private const val MODEL = "tileDetector.onnx"
    private const val METADATA = "tileDetector.json"
    private val digestPattern = Regex("[0-9a-f]{64}")
    data class Installed(val model: File, val metadata: File, val generation: String, val changed: Boolean)

    @Synchronized
    fun synchronize(
        root: File,
        modelDigest: String,
        metadataDigest: String,
        openModel: () -> InputStream,
        openMetadata: () -> InputStream,
    ): Installed {
        require(digestPattern.matches(modelDigest) && digestPattern.matches(metadataDigest))
        val models = directory(root, "models")
        val generations = directory(models, DIRECTORY)
        val marker = child(generations, MARKER)
        val generation = digest((modelDigest + "\n" + metadataDigest + "\n").byteInputStream(), 256)
        val target = child(generations, generation)
        val active = if (Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) readMarker(marker) else null
        if (active == generation && Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return installed(generations, generation, false)
        }
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val stage = Files.createTempDirectory(generations.toPath(), ".staging-").toFile()
            try {
                copyVerified(openModel, child(stage, MODEL), modelDigest, 64L * 1024 * 1024)
                copyVerified(openMetadata, child(stage, METADATA), metadataDigest, 64L * 1024)
                syncDirectory(stage)
                // Do not replace an unexpected existing generation or merge its contents.
                check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
                Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(generations)
            } finally {
                // Only this call's private staging files; never recurse through unknown objects.
                if (Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(stage.toPath())) {
                    for (name in listOf(MODEL, METADATA)) {
                        val f = child(stage, name)
                        if (Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(f.toPath())
                    }
                    Files.delete(stage.toPath())
                }
            }
        }
        val ready = installed(generations, generation, true)
        // Adopt only exact bundle bytes after a crash before activation. Same-generation
        // user edits are preserved only when an already-committed marker establishes identity.
        check(hashFile(ready.model, 64L * 1024 * 1024) == modelDigest)
        check(hashFile(ready.metadata, 64L * 1024) == metadataDigest)
        val temp = Files.createTempFile(generations.toPath(), ".active-", ".tmp").toFile()
        try {
            FileOutputStream(temp).use { it.write((generation + "\n").toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            check(!Files.isSymbolicLink(marker.toPath()))
            if (Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) readMarker(marker)
            Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(generations)
            check(readMarker(marker) == generation)
        } finally {
            if (Files.isRegularFile(temp.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.delete(temp.toPath())
        }
        return ready
    }

    @Synchronized
    fun active(root: File): Installed {
        val models = existingDirectory(root, "models")
        val generations = existingDirectory(models, DIRECTORY)
        return installed(generations, readMarker(child(generations, MARKER)), false)
    }

    /** Redirect only the factory slot, never an independently configured custom model path. */
    fun resolve(root: File, configured: File): File =
        if (configured.canonicalFile == File(root, "models/$MODEL").canonicalFile) active(root).model else configured

    private fun installed(parent: File, generation: String, changed: Boolean): Installed {
        check(digestPattern.matches(generation))
        val dir = existingDirectory(parent, generation)
        val model = child(dir, MODEL); val metadata = child(dir, METADATA)
        for (file in listOf(model, metadata)) {
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() > 0)
        }
        check(model.length() <= 64L * 1024 * 1024 && metadata.length() <= 64L * 1024)
        return Installed(model, metadata, generation, changed)
    }

    private fun readMarker(file: File): String {
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() <= 65)
        val text = file.readText(Charsets.US_ASCII)
        check(text.length == 65 && text.endsWith("\n") && digestPattern.matches(text.dropLast(1)))
        return text.dropLast(1)
    }

    private fun child(parent: File, name: String): File {
        check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()))
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..")
        val f = File(parent, name)
        check(!Files.isSymbolicLink(f.toPath()) && f.canonicalFile.parentFile == parent.canonicalFile)
        return f
    }

    private fun existingDirectory(parent: File, name: String): File = child(parent, name).also {
        check(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    private fun directory(parent: File, name: String): File = child(parent, name).also {
        if (!Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(it.toPath())
        check(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    private fun copyVerified(open: () -> InputStream, destination: File, expected: String, limit: Long) {
        open().use { input ->
            FileOutputStream(destination).use { output ->
                val bytes = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = input.read(bytes); if (n < 0) break
                    if (n == 0) continue
                    total += n; check(total <= limit); output.write(bytes, 0, n)
                }
                check(total > 0); output.fd.sync()
            }
        }
        check(hashFile(destination, limit) == expected)
    }

    private fun hashFile(file: File, limit: Long): String = file.inputStream().use { digest(it, limit) }
    private fun digest(input: InputStream, limit: Long): String {
        val md = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(64 * 1024); var total = 0L
        while (true) { val n = input.read(bytes); if (n < 0) break; if (n == 0) continue
            total += n; check(total <= limit); md.update(bytes, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun syncDirectory(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}

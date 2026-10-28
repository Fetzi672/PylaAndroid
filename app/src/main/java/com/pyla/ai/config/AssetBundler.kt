package com.pyla.ai.config

import android.content.Context
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.diagnostics.DiagnosticLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object AssetBundler {

    private const val TAG = "PylaAssets"
    private const val PARENT = "pyla"
    private const val VERSION_FILE = "pyla_assets_version.txt"
    private const val ASSETS_VERSION = 8
    private const val CATALOG_ASSET_PATH = "pyla/cfg/brawlers_info.json"
    val version: Int get() = ASSETS_VERSION

    fun ensureExtracted(context: Context): File {
        val rootDir = File(context.filesDir, PARENT)
        if (!rootDir.exists()) rootDir.mkdirs()
        val versionFile = File(rootDir, VERSION_FILE)
        if (versionFile.exists() && versionFile.readText().trim().toIntOrNull() == ASSETS_VERSION) {
            // Retained install: the global asset version stays at 8 on purpose, so a user's
            // configuration, imported playstyles and settings are never overwritten. Only the
            // single managed brawler catalog is reconciled with the bytes in this APK.
            syncBrawlerCatalog(context, rootDir)
            syncTerrainModel(context, rootDir)
            return rootDir
        }
        Log.i(TAG, "First-run extraction of bundled Pyla assets -> ${rootDir.absolutePath}")

        copyAssetDir(context, PARENT, rootDir)
        val sane = File(rootDir, "cfg/general_config.toml").exists()
        if (!sane) {
            DiagnosticLog.failure("assets", "extraction", "required_config_missing")
            Log.e(TAG, "Extraction finished but cfg/general_config.toml is missing, the APK was " +
                "built without Pyla assets. Rebuild so the copyPylaAssets Gradle task runs.")
            com.pyla.ai.engine.BotStatus.error("APK has no bundled assets, rebuild the app")

            return rootDir
        }
        versionFile.writeText(ASSETS_VERSION.toString())
        syncBrawlerCatalog(context, rootDir)
        syncTerrainModel(context, rootDir)
        return rootDir
    }

    private fun syncTerrainModel(context: Context, rootDir: File) {
        val model = "pyla/models/tileDetector.onnx"
        val metadata = "pyla/models/tileDetector.json"
        try {
            TerrainModelInstaller.synchronize(
                rootDir, digestAsset(context, model), digestAsset(context, metadata),
                { context.assets.open(model) }, { context.assets.open(metadata) },
            )
        } catch (e: Exception) {
            DiagnosticLog.failure("assets", "terrain_sync", "generation_not_ready", e)
            // Unlike optional catalog metadata, a partial decoder/model pair must not be used.
            throw IllegalStateException("Terrain model generation is not ready", e)
        }
    }

    /**
     * Reconciles cfg/brawlers_info.json with the catalog packaged in this APK.
     *
     * The catalog is metadata, so a failure here is logged and retried on the next launch
     * instead of touching any engine/readiness gate; a failed migration never advances the
     * revision marker and never leaves a partial file behind.
     */
    private fun syncBrawlerCatalog(context: Context, rootDir: File) {
        if (!File(rootDir, "cfg/general_config.toml").exists()) {
            DiagnosticLog.failure("assets", "catalog", "retained_config_missing")
            Log.w(TAG, "Skipping brawler catalog sync: retained cfg/general_config.toml is missing")
            return
        }
        val digest = try {
            digestAsset(context, CATALOG_ASSET_PATH)
        } catch (e: Exception) {
            DiagnosticLog.failure("assets", "catalog_digest", "bundle_unreadable", e)
            Log.w(TAG, "Skipping brawler catalog sync: bundled catalog unreadable", e)
            return
        }
        val result = try {
            BrawlerCatalogInstaller.synchronize(rootDir, digest) {
                context.assets.open(CATALOG_ASSET_PATH)
            }
        } catch (e: Exception) {
            DiagnosticLog.failure("assets", "catalog_sync", "sync_exception", e)
            Log.w(TAG, "Brawler catalog sync failed", e)
            return
        }
        when {
            !result.ok -> {
                DiagnosticLog.failure("assets", "catalog_sync", "sync_incomplete")
                Log.w(TAG, "Brawler catalog sync incomplete; retrying next launch")
            }
            result.changed -> Log.i(TAG, "Brawler catalog migrated (${result.reason})" +
                (result.backup?.let { ", backup=${it.name}" } ?: ""))
            else -> Log.i(TAG, "Brawler catalog already current (${result.reason})")
        }
    }

    private fun digestAsset(context: Context, assetPath: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
        }
        val digits = "0123456789abcdef"
        val bytes = md.digest()
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(digits[v ushr 4]).append(digits[v and 0x0f])
        }
        return out.toString()
    }

    private fun copyAssetDir(context: Context, assetPath: String, outDir: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            if (assetPath.isEmpty()) return
            copyAssetFile(context, assetPath, outDir)
            return
        }
        if (!outDir.exists()) outDir.mkdirs()
        for (child in children) {
            val childAssetPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
            val subList = context.assets.list(childAssetPath) ?: emptyArray()
            if (subList.isEmpty()) {
                copyAssetFile(context, childAssetPath, outDir)
            } else {
                val childOut = File(outDir, child)
                copyAssetDir(context, childAssetPath, childOut)
            }
        }
    }

    private fun copyAssetFile(context: Context, assetPath: String, outDir: File) {

        val rawName = File(assetPath).name
        val dot = rawName.lastIndexOf('.')
        val name = if (dot >= 0) rawName.substring(0, dot) + rawName.substring(dot).lowercase() else rawName
        val out = File(outDir, name)
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            DiagnosticLog.failure("assets", "file_copy", "copy_exception", e)
            Log.w(TAG, "Skipping unreadable bundled asset", e)
        }
    }

    fun resolvePath(rootDir: File, relative: String): File = File(rootDir, relative)
}

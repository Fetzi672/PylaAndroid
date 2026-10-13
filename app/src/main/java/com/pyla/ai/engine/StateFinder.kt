package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.config.TomlLite
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

object StateFinder {

    private const val TAG = "PylaState"
    private const val ORIG_W = 1920
    private const val ORIG_H = 1080

    private val statesPath get() = PylaConfig.resolve("images/states/")
    private val starDropsPath get() = PylaConfig.resolve("images/star_drop_types/")
    private val endResultsPath get() = PylaConfig.resolve("images/end_results/")

    private val lobbyConfig: TomlLite = PylaConfig.load("cfg/lobby_config.toml")
    private val matchResultRegion: List<Int> = lobbyConfig.getIntArray("template_matching.match_result")
    private val regions: TomlLite = lobbyConfig

    private val starDropFiles: List<String> = run {
        val dir = starDropsPath
        if (!dir.exists()) {
            PylaLog.w(TAG, "star_drop_types/ missing at $dir")
            emptyList()
        } else {
            dir.listFiles().orEmpty().filter { it.name.contains("star_drop", ignoreCase = true) }.map { it.name }
        }
    }

    private val templateCache = HashMap<String, Mat>()
    private val templateMissingLogged = HashSet<String>()

    @Synchronized
    private fun loadTemplate(path: String, w: Int, h: Int): Mat {
        val key = "$path|$w|$h"
        templateCache[key]?.let { return it }
        val file = File(path)
        val videoCue = file.parentFile?.name == "video_state_cues"
        if (!videoCue && !file.exists() && path !in templateMissingLogged) {
            PylaLog.w(TAG, "Template file DOES NOT EXIST on disk: $path (cwd=${System.getProperty("user.dir")})")
            templateMissingLogged.add(path)
        }
        val raw = if (videoCue) {
            // Read new packaged cues directly. Bumping AssetBundler's global version would
            // overwrite the user's retained configuration on an in-place update.
            try {
                val bytes = com.pyla.ai.PylaApp.ctx().assets.open("pyla/images/video_state_cues/${file.name}").use { it.readBytes() }
                val encoded = org.opencv.core.MatOfByte(*bytes)
                try { org.opencv.imgcodecs.Imgcodecs.imdecode(encoded, org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR) }
                finally { encoded.release() }
            } catch (_: Exception) { Mat() }
        } else org.opencv.imgcodecs.Imgcodecs.imread(path)
        if (raw.empty()) {
            if (path !in templateMissingLogged) {
                PylaLog.w(TAG, "Template imread returned empty: $path exists=${file.exists()} size=${file.length()}")
                templateMissingLogged.add(path)
            }
            templateCache[key] = raw
            return raw
        }
        if (path !in templateMissingLogged) PylaLog.p(TAG, "Loaded template $path (${raw.cols()}x${raw.rows()})")
        val hr = h.toDouble() / if (videoCue) VideoScreenStateCues.REFERENCE_HEIGHT else ORIG_H
        val resized = Mat()
        Imgproc.resize(raw, resized, Size(raw.cols() * hr, raw.rows() * hr))
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        raw.release(); resized.release()
        templateCache[key] = rgb
        return rgb
    }

    private fun isTemplateInRegion(image: Mat, templatePath: String, region: List<Int>, threshold: Double = 0.7): Boolean {
        val res = isTemplateInRegionImpl(image, templatePath, region, threshold)
        return res
    }

    private fun isTemplateInRegionImpl(image: Mat, templatePath: String, region: List<Int>, threshold: Double = 0.7): Boolean {
        if (region.size < 4) return false
        val curH = image.rows(); val curW = image.cols()
        val wr = curW.toDouble() / ORIG_W; val hr = curH.toDouble() / ORIG_H
        val baseCenter = region[0] + region[2] / 2.0
        val anchoredX = when {
            baseCenter < ORIG_W / 3.0 -> region[0] * hr
            baseCenter > ORIG_W * 2 / 3.0 -> curW - (ORIG_W - region[0]) * hr
            else -> curW / 2.0 + (region[0] - ORIG_W / 2.0) * hr
        }
        val propX = region[0] * wr
        val pad = 12 * hr
        val ny = (region[1] * hr).toInt()
        val nh = (region[3] * hr).toInt()
        if (region[2] <= 0 || nh <= 0) return false
        val x0 = (minOf(propX, anchoredX) - pad).toInt().coerceIn(0, curW)
        val x1 = (maxOf(propX + region[2] * wr, anchoredX + region[2] * hr) + pad).toInt().coerceIn(0, curW)
        val y0 = ny.coerceIn(0, curH)
        val y1 = (ny + nh).coerceIn(0, curH)
        if (x1 - x0 <= 0 || y1 - y0 <= 0) return false
        val crop = Mat(image, Rect(x0, y0, x1 - x0, y1 - y0))
        val template = loadTemplate(templatePath, curW, curH)
        if (template.empty()) { crop.release(); return false }

        if (crop.cols() < template.cols() || crop.rows() < template.rows()) {
            crop.release(); return false
        }
        val result = Mat()
        Imgproc.matchTemplate(crop, template, result, Imgproc.TM_CCOEFF_NORMED)
        val mmr = org.opencv.core.Core.minMaxLoc(result)
        val score = mmr.maxVal
        crop.release(); result.release()
        val passed = score > threshold
        if (!passed && score > 0.4 && File(templatePath).name in DEBUG_NEAR_MISS_FILES) {
            PylaLog.p(TAG, "near miss: ${File(templatePath).name} score=%.3f thresh=%.2f".format(score, threshold))
        }
        return passed
    }

    private val DEBUG_NEAR_MISS_FILES = setOf("lobby_menu.png", "exit_match_making.png", "close_popup.png")

    private val showdownPlaceTemplates: Map<Int, List<String>> = mapOf(
        0 to listOf("1st.png"),
        1 to listOf("2nd.png"),
        2 to listOf("3rd.png", "3rd_alt.png"),
        3 to listOf("4th.png"),
    )

    private fun findGameResult(screenshot: Mat): String? {
        for ((place, files) in showdownPlaceTemplates) {
            for (f in files) {
                if (isTemplateInRegion(screenshot, File(endResultsPath, f).absolutePath, matchResultRegion, 0.9))
                    return "trio_showdown_$place"
            }
        }
        if (isTemplateInRegion(screenshot, File(endResultsPath, "victory.png").absolutePath, matchResultRegion)) return "victory"
        if (isTemplateInRegion(screenshot, File(endResultsPath, "defeat.png").absolutePath, matchResultRegion)) return "defeat"
        if (isTemplateInRegion(screenshot, File(endResultsPath, "draw.png").absolutePath, matchResultRegion)) return "draw"
        return null
    }

    private fun inShop(image: Mat) = isTemplateInRegion(image, File(statesPath, "powerpoint.png").absolutePath, regions.getIntArray("template_matching.powerpoint"))
    private fun inBrawlerSelection(image: Mat) = isTemplateInRegion(image, File(statesPath, "brawler_menu_heart.png").absolutePath, regions.getIntArray("template_matching.brawler_menu_heart"))
    private fun inOfferPopup(image: Mat) = isTemplateInRegion(image, File(statesPath, "close_popup.png").absolutePath, regions.getIntArray("template_matching.close_popup"))
    private fun inLobby(image: Mat) = isTemplateInRegion(image, File(statesPath, "lobby_menu.png").absolutePath, regions.getIntArray("template_matching.lobby_menu"))
    private fun inTrophyReward(image: Mat) = isTemplateInRegion(image, File(statesPath, "trophies_screen.png").absolutePath, regions.getIntArray("template_matching.trophies_screen"))
    private fun inBrawlPass(image: Mat) = isTemplateInRegion(image, File(statesPath, "brawl_pass_house.png").absolutePath, regions.getIntArray("template_matching.brawl_pass_house"))
    private fun inStarRoad(image: Mat) = isTemplateInRegion(image, File(statesPath, "go_back_arrow.png").absolutePath, regions.getIntArray("template_matching.go_back_arrow"))
    private fun inMatchMaking(image: Mat) = isTemplateInRegion(image, File(statesPath, "exit_match_making.png").absolutePath, regions.getIntArray("template_matching.exit_match_making"))
    private fun inPrestigeMilestone(image: Mat) = isTemplateInRegion(image, File(statesPath, "prestige_continue.png").absolutePath, regions.getIntArray("template_matching.prestige_continue"))
    private fun inNanoNoodles(image: Mat): Boolean {
        if (!isTemplateInRegion(image, File(statesPath, "nano_noodles.png").absolutePath, regions.getIntArray("template_matching.nano_noodles"))) return false
        return hasCanisterRow(image)
    }

    private fun hasCanisterRow(image: Mat): Boolean {
        val h = image.rows(); val w = image.cols()
        if (h < 100 || w < 100) return false
        val bandY = (h * 0.40).toInt()
        val bandH = (h * 0.55).toInt().coerceAtMost(h - bandY)
        val band = Mat(image, Rect(0, bandY, w, bandH))
        val hsv = Mat()
        Imgproc.cvtColor(band, hsv, Imgproc.COLOR_RGB2HSV)
        band.release()
        val mask = Mat()
        org.opencv.core.Core.inRange(hsv,
            org.opencv.core.Scalar(40.0, 120.0, 120.0),
            org.opencv.core.Scalar(75.0, 255.0, 255.0), mask)
        hsv.release()
        val minBlobArea = (h * 0.08) * (h * 0.08)
        if (org.opencv.core.Core.countNonZero(mask) < minBlobArea * 3) { mask.release(); return false }
        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        mask.release()
        val bigBlobs = contours.count { Imgproc.contourArea(it) >= minBlobArea }
        contours.forEach { it.release() }
        if (bigBlobs >= 3) {
            PylaLog.p(TAG, "Nano noodles confirmed by canister row ($bigBlobs blobs)")
            return true
        }
        return false
    }

    private fun inStarDrop(image: Mat): String? {
        for (f in starDropFiles) {
            val low = f.lowercase()
            if (isTemplateInRegion(image, File(starDropsPath, f).absolutePath, regions.getIntArray("template_matching.star_drop"))) {
                return when {
                    "angelic" in low -> "angelic"
                    "demonic" in low -> "demonic"
                    "starr_nova" in low -> "starr_nova"
                    else -> "regular"
                }
            }
        }
        return null
    }

    /** Locate the actual bottom-centre elimination EXIT, not the unrelated result-screen EXIT. */
    fun findDefeatedExit(screenshot: Mat): org.opencv.core.Point? {
        if (getState(screenshot) != "defeated") return null
        val w = screenshot.cols(); val h = screenshot.rows()
        if (w <= 0 || h <= 0) return null
        val scale = h.toDouble() / VideoScreenStateCues.REFERENCE_HEIGHT
        // Reference ROI is centred on the observed control; height scaling preserves game UI size.
        val x0 = (w / 2.0 - 110.0 * scale).toInt().coerceIn(0, w)
        val x1 = (w / 2.0 + 150.0 * scale).toInt().coerceIn(0, w)
        val y0 = (500.0 * scale).toInt().coerceIn(0, h)
        val y1 = (585.0 * scale).toInt().coerceIn(0, h)
        if (x1 <= x0 || y1 <= y0) return null
        val template = loadTemplate(PylaConfig.resolve("images/video_state_cues/defeated_exit.png").absolutePath, w, h)
        if (template.empty() || template.cols() > x1 - x0 || template.rows() > y1 - y0) return null
        val crop = Mat(screenshot, Rect(x0, y0, x1 - x0, y1 - y0))
        val scores = Mat()
        try {
            Imgproc.matchTemplate(crop, template, scores, Imgproc.TM_CCOEFF_NORMED)
            val match = org.opencv.core.Core.minMaxLoc(scores)
            if (!match.maxVal.isFinite() || match.maxVal <= VideoScreenStateCues.THRESHOLD) return null
            val patch = Mat(crop, Rect(match.maxLoc.x.toInt(), match.maxLoc.y.toInt(), template.cols(), template.rows()))
            try {
                // Correlation alone is brightness-invariant: a darkened disabled control must not
                // become action permission. Keep the real enabled reference's absolute pixels too.
                val error = org.opencv.core.Core.norm(patch, template, org.opencv.core.Core.NORM_L1) /
                    (template.total() * template.channels())
                val referenceMean = org.opencv.core.Core.mean(template).`val`.take(3).average()
                val liveMean = org.opencv.core.Core.mean(patch).`val`.take(3).average()
                if (!error.isFinite() || error > 22.0 || liveMean < referenceMean * 0.85) return null
            } finally { patch.release() }
            return org.opencv.core.Point(x0 + match.maxLoc.x + template.cols() / 2.0,
                y0 + match.maxLoc.y + template.rows() / 2.0)
        } finally { scores.release(); crop.release() }
    }

    private fun videoScreenState(screenshot: Mat): String? {
        val matches = VideoScreenStateCues.regions.filter { (name, roi) ->
            val path = PylaConfig.resolve("images/video_state_cues/$name.png").absolutePath
            isTemplateInRegion(screenshot, path, roi, VideoScreenStateCues.THRESHOLD)
        }.keys
        return VideoScreenStateCues.classify(matches)
    }

    fun getState(screenshot: Mat): String {
        findGameResult(screenshot)?.let { PylaLog.p(TAG, "State: end_$it (${screenshot.cols()}x${screenshot.rows()})"); return "end_$it" }
        videoScreenState(screenshot)?.let { return it }
        if (inNanoNoodles(screenshot)) { PylaLog.p(TAG, "State: nano_noodles"); return "nano_noodles" }
        inStarDrop(screenshot)?.let { PylaLog.p(TAG, "State: star_drop_$it"); return "star_drop_$it" }
        if (inTrophyReward(screenshot)) { PylaLog.p(TAG, "State: trophy_reward"); return "trophy_reward" }
        if (inPrestigeMilestone(screenshot)) { PylaLog.p(TAG, "State: prestige_milestone"); return "prestige_milestone" }
        if (inLobby(screenshot)) { PylaLog.p(TAG, "State: lobby"); return "lobby" }
        if (inMatchMaking(screenshot)) { PylaLog.p(TAG, "State: match_making"); return "match_making" }
        if (inBrawlerSelection(screenshot)) { PylaLog.p(TAG, "State: brawler_selection"); return "brawler_selection" }
        if (inShop(screenshot)) { PylaLog.p(TAG, "State: shop"); return "shop" }
        if (inOfferPopup(screenshot)) { PylaLog.p(TAG, "State: popup"); return "popup" }
        if (inBrawlPass(screenshot) || inStarRoad(screenshot)) { PylaLog.p(TAG, "State: shop (brawl_pass/star_road)"); return "shop" }
        PylaLog.p(TAG, "State: unknown (no positive menu evidence)")
        return "unknown"
    }
}
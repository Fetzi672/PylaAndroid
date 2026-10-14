package com.pyla.ai.engine

import android.os.Handler
import android.os.Looper
import com.pyla.ai.capture.InputCoordinates

import com.pyla.ai.engine.PylaLog
import com.pyla.ai.engine.PylaLog as Log
import com.pyla.ai.config.PylaConfig
import com.pyla.ai.config.TomlLite
import com.pyla.ai.diagnostics.DiagnosticLog
import com.pyla.ai.pyla.NativeFn
import com.pyla.ai.pyla.Py
import com.pyla.ai.pyla.PyDict
import com.pyla.ai.pyla.PyList
import com.pyla.ai.pyla.PyNone
import com.pyla.ai.pyla.PyTuple
import com.pyla.ai.pyla.PylaScript
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class Play(
    val windowController: WindowController,
    mainInfoModelPath: String,
    tileDetectorModelPath: String,
    closeTileDetectorModelPath: String,
    var pylaScript: PylaScript? = null,
    /**
     * Live authority check for gameplay input, bound by the host to its stop/pause state. Every
     * attack DOWN is gated on it at dispatch time, so a queued command cannot fire after the
     * engine stopped producing input.
     */
    private val inputAllowed: () -> Boolean = { true },
) {
    companion object {
        private const val TAG = "PylaPlay"
        /**
         * Max age of the capture that a gameplay decision may be derived from. Older frames can no
         * longer prove that the player is where the detections say, so they never authorize input.
         */
        const val MAX_EVIDENCE_FRAME_AGE_MS = 3_000L

        /** Movement smaller than this is not a meaningful joystick vector. */
        private const val MIN_SIGNIFICANT_MOVEMENT = 2.0

        /** Rate limit for reporting the observed screen back to the engine. */
        private const val STATE_REPORT_INTERVAL_MS = 1_000L
    }

    private fun botConfig(): TomlLite = PylaConfig.load("cfg/bot_config.toml")
    private fun timeConfig(): TomlLite = PylaConfig.load("cfg/time_tresholds.toml")
    private fun lobbyConfig(): TomlLite = PylaConfig.load("cfg/lobby_config.toml")

    private val superCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.super")
    private val gadgetCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.gadget")
    private val hyperchargeCropArea: List<Int> get() = lobbyConfig().getIntArray("pixel_counter_crop_area.hypercharge")

    private val verboseDebug: Boolean get() = PylaUtils.configBool(PylaConfig.load("cfg/debug_settings.toml").opt("verbose_debug"), false)

    private val detectMainInfo: Detect = Detect(
        mainInfoModelPath,
        classes = listOf("enemy", "teammate", "player"),
    )
    private val tileDetectorClasses: List<String> get() = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))
    private val centeredWallDetection: Boolean get() = PylaUtils.configBool(botConfig().opt("centered_wall_detection"), false)
    private val detectTileDetector: Detect? = if (!PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("centered_wall_detection"), false))
        Detect(tileDetectorModelPath, classes = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))) else null
    private val detectCenteredTileDetector: Detect? = if (PylaUtils.configBool(PylaConfig.load("cfg/bot_config.toml").opt("centered_wall_detection"), false))
        Detect(closeTileDetectorModelPath, classes = botConfig().getStringList("wall_model_classes", listOf("wall", "bush", "close_bush"))) else null

    private val tileSize: Double get() = botConfig().getDouble("perceived_tile_size", 54.0)
    val tileSizePx: Double get() = tileSize * windowController.scaleFactor
    private val centeredWallCropSize: Int = 640

    private val superTresholdSec: Double get() = timeConfig().getDouble("super", 0.1)
    private val gadgetTresholdSec: Double get() = timeConfig().getDouble("gadget", 0.1)
    private val hyperchargeTresholdSec: Double get() = timeConfig().getDouble("hypercharge", 0.1)
    private val wallsTresholdSec: Double get() = timeConfig().getDouble("wall_detection", 0.1)

    private val gadgetPixelsMin: Long get() = botConfig().getDouble("gadget_pixels_minimum", 1300.0).toLong()
    private val hyperchargePixelsMin: Long get() = botConfig().getDouble("hypercharge_pixels_minimum", 1800.0).toLong()
    private val superPixelsMin: Long get() = botConfig().getDouble("super_pixels_minimum", 1800.0).toLong()
    private val wallDetectionConfidence: Float get() = botConfig().getDouble("wall_detection_confidence", 0.5).toFloat()
    private val entityDetectionConfidence: Float get() = botConfig().getDouble("entity_detection_confidence", 0.65).toFloat()
    private val secondsToHoldAttackAfterReachingMax: Double get() = botConfig().getDouble("seconds_to_hold_attack_after_reaching_max", 1.5)

    private val fixMovementKeys = FixMovementState(
        delayToTrigger = PylaConfig.load("cfg/bot_config.toml").getDouble("unstuck_movement_delay", 2.4),
        duration = PylaConfig.load("cfg/bot_config.toml").getDouble("unstuck_movement_hold_time", 1.4),
    )

    private val brawlersInfo: Map<String, BrawlerInfo> = BrawlersInfo.load()
    private var brawlerRanges: Map<String, Triple<Int, Int, Int>>? = null

    // Python-visible dict of brawler info, built once from the raw JSON.
    private var brawlersInfoPyCache: PyDict? = null

    // Persistent dict shared across frames, matching the PC persistent_data behaviour.
    private val persistentDataPy: PyDict = PyDict(LinkedHashMap<Any, Any>().apply {
        put("time_since_holding_attack", PyNone)
        put("last_gadget_use", 0.0)
    })

    var currentBrawler: String? = null
    private var lastWallsData: List<FloatArray> = emptyList()
    private var lastBushesData: List<FloatArray> = emptyList()
    private var timeSinceWallsChecked: Double = 0.0
    private var timeSincePlayerLastFound: Double = nowSec()
    private var timeSinceGadgetChecked: Double = nowSec()
    private var timeSinceHyperchargeChecked: Double = nowSec()
    private var timeSinceSuperChecked: Double = nowSec()
    private var isGadgetReady: Boolean = false
    private var isHyperchargeReady: Boolean = false
    private var isSuperReady: Boolean = false

    val timeSinceDetections = HashMap<String, Double>().apply {
        put("player", nowSec()); put("enemy", nowSec())
    }

    /**
     * Gameplay admission. A classifier answer alone never authorizes input: it needs fresh, current
     * player evidence from two distinct frames (see [GameplayEvidenceGate]).
     */
    val gameplayEvidence = GameplayEvidenceGate(MAX_EVIDENCE_FRAME_AGE_MS)

    /**
     * Last script result, kept only as a value. It is never resumed as movement input: without
     * current positive player evidence no gameplay input is produced at all, so a stale vector can
     * not bypass the directed planner.
     */
    private var lastMovement: Any? = ""
    private var lastMovementChangeTime: Double = nowSec()

    /** Continuous intent is allowed only during fresh, positively admitted gameplay. */
    private val continuousPlanner = ContinuousMovementPlanner()
    private val sceneProgress = SceneProgressTracker()
    @Volatile private var gameplayGeometry: Long = -1L
    private val combatHandler by lazy { Handler(Looper.getMainLooper()) }
    private var attackPulse: Runnable? = null
    private var pulseBrawler: String = ""
    internal var postCombatPulse: (Runnable, Long) -> Unit = { task, delay -> combatHandler.postDelayed(task, delay); Unit }
    internal var removeCombatPulse: (Runnable) -> Unit = { task -> combatHandler.removeCallbacks(task) }

    private var frame: Mat? = null

    internal fun nowSec() = System.currentTimeMillis() / 1000.0

    /** Monotonic "now" for freshness checks; overridable in host tests. */
    internal var monotonicNowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Current-frame screen classification, injected for testability. Defaults to the production
     * StateFinder; an "unknown" classification must never be produced by a known menu screen.
     */
    internal var classifyFrame: (Mat) -> String = { StateFinder.getState(it) }


    fun getEntityPos(entity: FloatArray): Pair<Double, Double> =
        ((entity[0] + entity[2]) / 2.0) to ((entity[1] + entity[3]) / 2.0)

    fun getDistance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double = hypot(a.first - b.first, a.second - b.second)

    fun canAttackThroughWalls(brawler: String, skillType: String, info: Map<String, BrawlerInfo> = brawlersInfo): Boolean {
        val bi = info[brawler] ?: return false
        return if (skillType == "attack") bi.ignoreWallsForAttacks
        else if (skillType == "super") bi.ignoreWallsForSupers
        else false
    }

    fun mustHoldAttack(brawler: String, info: Map<String, BrawlerInfo> = brawlersInfo): Boolean =
        (info[brawler]?.holdAttack ?: 0.0) > 0

    fun wallsBlockLineOfSight(p1: Pair<Double, Double>, p2: Pair<Double, Double>, walls: List<FloatArray>): Boolean {
        if (walls.isEmpty()) return false
        val minx = minOf(p1.first, p2.first); val maxx = maxOf(p1.first, p2.first)
        val miny = minOf(p1.second, p2.second); val maxy = maxOf(p1.second, p2.second)
        for (w in walls) {
            val x1 = w[0].toDouble(); val y1 = w[1].toDouble(); val x2 = w[2].toDouble(); val y2 = w[3].toDouble()
            if (maxx < x1 || minx > x2 || maxy < y1 || miny > y2) continue
            val rect = Rect(x1.toInt(), y1.toInt(), (x2 - x1).toInt().coerceAtLeast(1), (y2 - y1).toInt().coerceAtLeast(1))
            val clipRet = Imgproc.clipLine(rect, Point(p1.first, p1.second), Point(p2.first, p2.second))
            if (clipRet) return true
        }
        return false
    }

    fun getPlayerHitCircle(playerBox: FloatArray?): Pair<Pair<Double, Double>, Double> {
        val radius = PylaUtils.PLAYER_HIT_CIRCLE_RADIUS * (windowController.scaleFactor.toDouble())
        if (playerBox != null && playerBox.size >= 4) {
            val cx = (playerBox[0] + playerBox[2]) / 2.0
            val cy = playerBox[3].toDouble() - radius
            return (cx to cy) to radius
        }
        return (0.0 to 0.0) to radius
    }

    fun getActualPlayerBox(playerBox: FloatArray?): FloatArray? {
        val (center, radius) = getPlayerHitCircle(playerBox)
        if (center.first == 0.0 && center.second == 0.0 && playerBox == null) return null
        return floatArrayOf(
            (center.first - radius).toFloat(), (center.second - radius).toFloat(),
            (center.first + radius).toFloat(), (center.second + radius).toFloat(),
        )
    }

    fun pointRectDistSq(p: Pair<Double, Double>, rect: FloatArray): Double {
        val x = p.first; val y = p.second
        val x1 = rect[0].toDouble(); val y1 = rect[1].toDouble(); val x2 = rect[2].toDouble(); val y2 = rect[3].toDouble()
        val dx = maxOf(x1 - x, 0.0, x - x2)
        val dy = maxOf(y1 - y, 0.0, y - y2)
        return dx * dx + dy * dy
    }

    fun wallsBlockSweptCircle(p1: Pair<Double, Double>, p2: Pair<Double, Double>, radius: Double, walls: List<FloatArray>): Boolean {
        if (walls.isEmpty()) return false
        val minx = minOf(p1.first, p2.first); val maxx = maxOf(p1.first, p2.first)
        val miny = minOf(p1.second, p2.second); val maxy = maxOf(p1.second, p2.second)
        val r = kotlin.math.ceil(radius).toInt()
        val rSq = radius * radius
        for (w in walls) {
            val x1 = w[0].toInt(); val y1 = w[1].toInt(); val x2 = w[2].toInt(); val y2 = w[3].toInt()
            val ex1 = x1 - r; val ey1 = y1 - r; val ex2 = x2 + r; val ey2 = y2 + r
            if (maxx < ex1 || minx > ex2 || maxy < ey1 || miny > ey2) continue
            val rect = Rect(ex1, ey1, (ex2 - ex1).coerceAtLeast(1), (ey2 - ey1).coerceAtLeast(1))
            val clip = Imgproc.clipLine(rect, Point(p1.first, p1.second), Point(p2.first, p2.second))
            if (clip) {
                val startD = pointRectDistSq(p1, w)
                val endD = pointRectDistSq(p2, w)
                if (startD <= rSq && endD > startD) continue
                return true
            }
        }
        return false
    }

    fun isEnemyHittable(playerPos: Pair<Double, Double>, enemyPos: Pair<Double, Double>, walls: List<FloatArray>, skillType: String): Boolean {
        if (canAttackThroughWalls(currentBrawler ?: "", skillType)) return true
        return !wallsBlockLineOfSight(playerPos, enemyPos, walls)
    }

    fun findClosestEnemy(enemyData: List<FloatArray>?, playerCoords: Pair<Double, Double>, walls: List<FloatArray>, skillType: String): Pair<Pair<Double, Double>?, Double?> {
        if (enemyData.isNullOrEmpty()) return null to null
        var hitClosestD = Double.MAX_VALUE; var hitClosest: Pair<Double, Double>? = null
        var unhitClosestD = Double.MAX_VALUE; var unhitClosest: Pair<Double, Double>? = null
        for (e in enemyData) {
            val ep = getEntityPos(e); val d = getDistance(ep, playerCoords)
            if (isEnemyHittable(playerCoords, ep, walls, skillType)) {
                if (d < hitClosestD) { hitClosestD = d; hitClosest = ep }
            } else {
                if (d < unhitClosestD) { unhitClosestD = d; unhitClosest = ep }
            }
        }
        if (hitClosest != null) return hitClosest to hitClosestD
        if (unhitClosest != null) return unhitClosest to unhitClosestD
        return null to null
    }

    fun findClosestTeammate(teammateData: List<FloatArray>?, playerCoords: Pair<Double, Double>): Pair<Pair<Double, Double>?, Double?> {
        if (teammateData.isNullOrEmpty()) return null to null
        var cd = Double.MAX_VALUE; var ct: Pair<Double, Double>? = null
        for (t in teammateData) {
            val tp = getEntityPos(t); val d = getDistance(tp, playerCoords)
            if (d < cd) { cd = d; ct = tp }
        }
        return ct to cd
    }

    fun loadBrawlerRanges(): Map<String, Triple<Int, Int, Int>> {
        val ratio = windowController.scaleFactor
        val out = HashMap<String, Triple<Int, Int, Int>>()
        for ((name, info) in brawlersInfo) {
            out[name] = Triple(
                (info.safeRange * ratio).toInt(),
                (info.attackRange * ratio).toInt(),
                (info.superRange * ratio).toInt(),
            )
        }
        return out
    }

    private var rangesScale: Float = 0f

    fun getBrawlerRange(brawler: String): Triple<Int, Int, Int> {
        val sf = windowController.scaleFactor
        if (brawlerRanges == null || kotlin.math.abs(sf - rangesScale) > 0.01f) {
            brawlerRanges = loadBrawlerRanges()
            rangesScale = sf
        }
        return brawlerRanges!![brawler] ?: Triple(0, 0, 0)
    }

    fun attack(touchUp: Boolean = true, touchDown: Boolean = true) {
        windowController.press("attack", touchUp = touchUp, touchDown = touchDown)
    }
    fun useSuper() { Log.i(TAG, "Using super"); windowController.press("super"); timeSinceSuperChecked = nowSec(); isSuperReady = false }
    fun useGadget() { Log.i(TAG, "Using gadget"); windowController.press("gadget"); timeSinceGadgetChecked = nowSec(); isGadgetReady = false }
    fun useHypercharge() { Log.i(TAG, "Using hypercharge"); windowController.press("hypercharge"); timeSinceHyperchargeChecked = nowSec(); isHyperchargeReady = false }

    fun isPathBlocked(
        playerBox: FloatArray?,
        moveDirection: Any?,
        walls: List<FloatArray>,
        distanceOverride: Double? = null,
    ): Boolean {
        val m = movementToVector(moveDirection) ?: return false
        val mag = hypot(m.first, m.second)
        if (mag < 1) return false
        val distance = distanceOverride ?: tileSize * windowController.scaleFactor
        val dx = m.first / mag * distance; val dy = m.second / mag * distance
        val (center, radius) = getPlayerHitCircle(playerBox)
        if (center.first == 0.0 && center.second == 0.0 && playerBox == null) return false
        val newPos = (center.first + dx) to (center.second + dy)
        return wallsBlockSweptCircle(center, newPos, radius, walls)
    }

    fun isTherePoisonGas(playerBox: FloatArray?, threshold: Int = 7000, areaFromPlayerChecked: Double = 1.5): Map<String, Int> {
        val image = frame ?: return emptyMap()
        val actual = getActualPlayerBox(playerBox) ?: playerBox ?: return emptyMap()
        val px1 = actual[0].toDouble(); val py1 = actual[1].toDouble()
        val px2 = actual[2].toDouble(); val py2 = actual[3].toDouble()
        val pw = maxOf(px2 - px1, 1.0); val ph = maxOf(py2 - py1, 1.0)
        val minX = maxOf(px1 - pw * areaFromPlayerChecked, 0.0).toInt()
        val maxX = minOf(px2 + pw * areaFromPlayerChecked, image.cols().toDouble()).toInt()
        val minY = maxOf(py1 - ph * areaFromPlayerChecked, 0.0).toInt()
        val maxY = minOf(py2 + ph * areaFromPlayerChecked, image.rows().toDouble()).toInt()
        if (minX >= maxX || minY >= maxY) return mapOf("up" to 0, "down" to 0, "left" to 0, "right" to 0)
        val roi = Mat(image, Rect(minX, minY, maxX - minX, maxY - minY))
        val hsv = Mat(); Imgproc.cvtColor(roi, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        org.opencv.core.Core.inRange(hsv,
            org.opencv.core.Scalar(30.0, 90.0, 221.0),
            org.opencv.core.Scalar(57.0, 114.0, 235.0), mask)
        val center = getEntityPos(actual)
        val rw = maxX - minX; val rh = maxY - minY
        val localX = PylaUtils.clamp((center.first - minX).toFloat(), 0f, rw.toFloat()).toInt()
        val localY = PylaUtils.clamp((center.second - minY).toFloat(), 0f, rh.toFloat()).toInt()
        val out = mutableMapOf(
            "up" to PylaUtils.countMaskPixels(mask, 0, 0, rw, localY),
            "down" to PylaUtils.countMaskPixels(mask, 0, localY, rw, rh),
            "left" to PylaUtils.countMaskPixels(mask, 0, 0, localX, rh),
            "right" to PylaUtils.countMaskPixels(mask, localX, 0, rw, rh),
        )
        roi.release(); hsv.release(); mask.release()
        val scaledThreshold = threshold * pixelAreaScale()
        return out.mapValues { if (it.value > scaledThreshold) it.value else 0 }
    }


    fun movementToVector(m: Any?): Pair<Double, Double>? {
        if (m is Pair<*, *>) {
            val x = m.first as? Number ?: return null
            val y = m.second as? Number ?: return null
            return x.toDouble() to y.toDouble()
        }
        if (m is FloatArray && m.size == 2) return m[0].toDouble() to m[1].toDouble()
        if (m is IntArray && m.size == 2) return m[0].toDouble() to m[1].toDouble()
        return null
    }

    fun rotateMovement(m: Pair<Double, Double>, angleRad: Double): Pair<Double, Double> {
        val c = cos(angleRad); val s = sin(angleRad)
        return (m.first * c - m.second * s) to (m.first * s + m.second * c)
    }

    fun movementDirectionKey(m: Pair<Double, Double>): Int? {
        val mag = hypot(m.first, m.second)
        if (mag < 1) return null
        val angle = atan2(m.second, m.first)
        // Python uses round(angle / (pi / 8)) % 16. Truncation changes the unstuck
        // direction too early and makes movement corrections noticeably less stable.
        return round(angle / (PI / 8)).toInt() and 15
    }

    /**
     * Retained legacy rotation helper (kept for regression API compatibility).
     *
     * It is *not* part of the production movement path any more: production movement comes only from
     * [DirectedMovementPlanner] behind positive gameplay evidence. Nothing in [main] calls this.
     */
    fun unstuckMovementIfNeeded(movement: Pair<Double, Double>, currentTime: Double = nowSec()): Pair<Double, Double> {
        val dirKey = movementDirectionKey(movement)
        if (dirKey == null) {
            fixMovementKeys.reset()
            timeSinceDifferentMovement = currentTime
            return movement
        }
        if (fixMovementKeys.toggled) {
            if (currentTime - fixMovementKeys.startedAt > fixMovementKeys.duration) {
                fixMovementKeys.toggled = false
                fixMovementKeys.lastDirectionKey = dirKey
                timeSinceDifferentMovement = currentTime
                return movement
            }
            return fixMovementKeys.fixed
        }
        if (fixMovementKeys.lastDirectionKey != dirKey) {
            fixMovementKeys.lastDirectionKey = dirKey
            fixMovementKeys.rotationSign = 1
            fixMovementKeys.rotationAngleStep = 1.0
            timeSinceDifferentMovement = currentTime
        }
        if (currentTime - timeSinceDifferentMovement > fixMovementKeys.delayToTrigger) {
            fixMovementKeys.rotationSign *= -1
            val step = fixMovementKeys.rotationAngleStep
            val rotated = rotateMovement(movement, fixMovementKeys.rotationSign * step * PI / 4)
            if (fixMovementKeys.rotationSign > 0) {
                fixMovementKeys.rotationAngleStep += 1.0
                if (fixMovementKeys.rotationAngleStep > fixMovementKeys.maxRotationAngleStep)
                    fixMovementKeys.rotationAngleStep = 1.0
            }
            fixMovementKeys.fixed = rotated
            fixMovementKeys.toggled = true
            fixMovementKeys.startedAt = currentTime
            return rotated
        }
        return movement
    }

    private var timeSinceDifferentMovement: Double = nowSec()

    fun clampMovement(m: Pair<Double, Double>): Pair<Double, Double> {
        val tx = PylaUtils.clamp(m.first.toFloat(), (-PylaUtils.JOYSTICK_RADIUS * windowController.widthRatio).toFloat(), (PylaUtils.JOYSTICK_RADIUS * windowController.widthRatio).toFloat())
        val ty = PylaUtils.clamp(m.second.toFloat(), (-PylaUtils.JOYSTICK_RADIUS * windowController.heightRatio).toFloat(), (PylaUtils.JOYSTICK_RADIUS * windowController.heightRatio).toFloat())
        return tx.toDouble() to ty.toDouble()
    }

    fun doMovement(movement: Pair<Double, Double>?) {
        if (movement == null) {
            windowController.releaseMovement()
            return
        }
        windowController.move(movement.first.toFloat(), movement.second.toFloat())
    }


    private fun abilityCrop(frame: Mat, area: List<Int>): Mat? {
        if (area.size < 4) return null
        val wr = windowController.widthRatio.toDouble(); val hr = windowController.heightRatio.toDouble()
        val w = frame.cols(); val h = frame.rows()
        val x1 = minOf(area[0] * wr, w - (1920 - area[0]) * hr).toInt().coerceIn(0, w)
        val x2 = maxOf(area[2] * wr, w - (1920 - area[2]) * hr).toInt().coerceIn(0, w)
        val y1 = (area[1] * hr).toInt().coerceIn(0, h)
        val y2 = (area[3] * hr).toInt().coerceIn(0, h)
        if (x2 <= x1 || y2 <= y1) return null
        return Mat(frame, Rect(x1, y1, x2 - x1, y2 - y1))
    }

    private fun pixelAreaScale(): Double =
        (windowController.widthRatio.toDouble() * windowController.heightRatio.toDouble()).coerceAtLeast(0.01)

    fun checkIfSuperReady(frame: Mat): Boolean {
        val crop = abilityCrop(frame, superCropArea) ?: return false
        val n = PylaUtils.countHsvPixels(crop, doubleArrayOf(17.0, 170.0, 200.0), doubleArrayOf(27.0, 255.0, 255.0))
        crop.release()
        val min = superPixelsMin * pixelAreaScale()
        if (verboseDebug) Log.i(TAG, "super yellow=$n (min=${min.toInt()})")
        return n > min
    }

    fun checkIfGadgetReady(frame: Mat): Boolean {
        val crop = abilityCrop(frame, gadgetCropArea) ?: return false
        val n = PylaUtils.countHsvPixels(crop, doubleArrayOf(57.0, 219.0, 165.0), doubleArrayOf(62.0, 255.0, 255.0))
        crop.release()
        val min = gadgetPixelsMin * pixelAreaScale()
        if (verboseDebug) Log.i(TAG, "gadget green=$n (min=${min.toInt()})")
        return n > min
    }

    fun checkIfHyperchargeReady(frame: Mat): Boolean {
        val crop = abilityCrop(frame, hyperchargeCropArea) ?: return false
        val n = PylaUtils.countHsvPixels(crop, doubleArrayOf(137.0, 158.0, 159.0), doubleArrayOf(179.0, 255.0, 255.0))
        crop.release()
        val min = hyperchargePixelsMin * pixelAreaScale()
        if (verboseDebug) Log.i(TAG, "hypercharge purple=$n (min=${min.toInt()})")
        return n > min
    }


    private fun getCenteredWallCrop(frame: Mat, playerData: List<FloatArray>?): Triple<Mat, Int, Int> {
        val fh = frame.rows(); val fw = frame.cols()
        val size = centeredWallCropSize
        val (cx, cy) = if (!playerData.isNullOrEmpty()) {
            val e = playerData[0]; ((e[0] + e[2]) / 2.0) to ((e[1] + e[3]) / 2.0)
        } else (fw / 2.0) to (fh / 2.0)
        val x0 = PylaUtils.clamp((cx - size / 2).toFloat(), 0f, (fw - size).toFloat()).toInt()
        val y0 = PylaUtils.clamp((cy - size / 2).toFloat(), 0f, (fh - size).toFloat()).toInt()
        val crop = Mat(frame, Rect(x0, y0, size, size))
        return Triple(crop, x0, y0)
    }

    private fun getTileData(frame: Mat, playerData: List<FloatArray>?): Map<String, MutableList<FloatArray>> {
        if (centeredWallDetection && detectCenteredTileDetector != null) {
            val (crop, ox, oy) = getCenteredWallCrop(frame, playerData)
            val td = detectCenteredTileDetector.detectObjects(crop, confThresh = wallDetectionConfidence)
            crop.release()
            return offsetTileData(td, ox, oy)
        }
        return detectTileDetector!!.detectObjects(frame, confThresh = wallDetectionConfidence)
    }

    private fun offsetTileData(td: Map<String, MutableList<FloatArray>>, ox: Int, oy: Int): Map<String, MutableList<FloatArray>> {
        if (ox == 0 && oy == 0) return td
        val out = HashMap<String, MutableList<FloatArray>>()
        for ((cls, boxes) in td) {
            val shifted = boxes.map { floatArrayOf(it[0] + ox, it[1] + oy, it[2] + ox, it[3] + oy) }.toMutableList()
            out[cls] = shifted
        }
        return out
    }

    private fun processTileData(td: Map<String, MutableList<FloatArray>>): Pair<List<FloatArray>, List<FloatArray>> {
        val walls = ArrayList<FloatArray>(); val bushes = ArrayList<FloatArray>()
        for ((cls, boxes) in td) {
            if (!cls.contains("bush")) walls.addAll(boxes) else bushes.addAll(boxes)
        }
        return walls to bushes
    }


    fun getMainData(frame: Mat): MutableMap<String, MutableList<FloatArray>> =
        detectMainInfo.detectObjects(frame, confThresh = entityDetectionConfidence) as MutableMap<String, MutableList<FloatArray>>

    fun validateGameData(data: MutableMap<String, MutableList<FloatArray>>?): MutableMap<String, MutableList<FloatArray>>? {
        if (data == null) return null
        if (!data.containsKey("player")) return null
        data.getOrPut("enemy") { ArrayList() }
        data.getOrPut("teammate") { ArrayList() }
        data.getOrPut("wall") { ArrayList() }
        data.getOrPut("bush") { ArrayList() }
        return data
    }

    fun trackNoDetections(data: MutableMap<String, MutableList<FloatArray>>?) {
        for (key in listOf("player", "enemy")) {
            if (data != null && data[key]?.isNotEmpty() == true) {
                timeSinceDetections[key] = nowSec()
            }
        }
    }


    /**
     * Per-frame gameplay entry point.
     *
     * @param state classifier answer that caused the caller to invoke this method ("match" or
     *        "unknown"). A classifier answer is never proof of gameplay on its own.
     * @param capturedAtMs capture timestamp of [frame] (`snapshot.capturedAtMs`), used for the
     *        freshness and two-distinct-frames checks. Required: there is no fabricated default.
     * @param onStateChange reports the screen that this frame actually shows, so the engine can
     *        leave "match" when the player is not really detected.
     */
    fun main(
        frame: Mat,
        brawler: String,
        state: String?,
        capturedAtMs: Long,
        onStateChange: (String) -> Unit,
    ) {
        val issuingThread = Thread.currentThread()
        val frameGeneration = synchronized(this) {
            val geometry = InputCoordinates.geometryGeneration
            if (gameplayGeometry != geometry) {
                resetCombat()
                gameplayEvidence.reset()
                gameplayGeometry = geometry
            }
            combatGeneration
        }
        this.frame = frame
        val currentTime = nowSec()
        var data: MutableMap<String, MutableList<FloatArray>> = getMainData(frame)
        if (currentTime - timeSinceWallsChecked > wallsTresholdSec) {
            val td = getTileData(frame, data["player"])
            val (walls, bushes) = processTileData(td)
            timeSinceWallsChecked = currentTime
            lastWallsData = walls; lastBushesData = bushes
            data["wall"] = ArrayList(walls); data["bush"] = ArrayList(bushes)
        } else {
            data["wall"] = ArrayList(lastWallsData); data["bush"] = ArrayList(lastBushesData)
        }

        // Observe raw detections before the missing-player path discards them.
        DiagnosticLog.event("detection.player_presence", "present=${!data["player"].isNullOrEmpty()}")
        DiagnosticLog.sample("detection.raw", "player=${data["player"]?.size ?: 0} enemy=${data["enemy"]?.size ?: 0} teammate=${data["teammate"]?.size ?: 0} state=$state")
        data = validateGameData(data) ?: HashMap()
        trackNoDetections(data)

        val playerBoxes = data["player"]?.size ?: 0
        val boxValid = GameplayEvidenceGate.validPlayerBox(data["player"]?.firstOrNull(), frame.cols(), frame.rows())
        if (playerBoxes > 0) timeSincePlayerLastFound = currentTime

        val nowMs = monotonicNowMs()
        // "unknown" may only continue observation when the current frame itself is not a known menu
        // screen; a known menu is positive evidence that gameplay is not running.
        var reportedState: String? = null
        val current = classifyFrame(frame)
        reportedState = current
        val statePermitted = (state == "match" || state == "unknown") && !isMenuScreen(current)

        val evidence = if (statePermitted) {
            gameplayEvidence.observe(statePermitted, playerBoxes, boxValid, capturedAtMs, nowMs)
        } else {
            gameplayEvidence.observeMenuScreen()
        }

        if (!evidence.admitted) {
            resetCombat()
            // No positive player evidence: no gameplay input at all. Release the joystick instead of
            // keeping a stale vector alive, and never dismiss an unrecognized screen blindly.
            windowController.releaseMovement()
            lastMovement = ""
            val runtimeState = if (state == "unknown") RuntimeState.UNKNOWN else RuntimeState.MATCH
            val phase = if (evidence.reason == GameEvidenceReason.PLAYER_MISSING ||
                evidence.reason == GameEvidenceReason.PLAYER_AMBIGUOUS ||
                evidence.reason == GameEvidenceReason.PLAYER_BOX_INVALID
            ) VerificationPhase.AWAIT_PLAYER else VerificationPhase.VERIFY_STATE
            BotStatus.publish(runtimeState, phase, verified = false)
            DiagnosticLog.event("gameplay.admission", "reason=${evidence.reason.name} phase=${phase.name}")
            maybeReportObservedState(if (statePermitted) "unknown" else reportedState, nowMs, onStateChange)
            return
        }
        maybeReportObservedState("match", nowMs, onStateChange)
        if (closed || Thread.currentThread().isInterrupted || !inputAllowed()) {
            resetCombat()
            gameplayEvidence.reset()
            windowController.releaseMovement()
            return
        }

        if (currentTime - timeSinceHyperchargeChecked > hyperchargeTresholdSec) {
            isHyperchargeReady = checkIfHyperchargeReady(frame)
            timeSinceHyperchargeChecked = currentTime
        }
        if (currentTime - timeSinceGadgetChecked > gadgetTresholdSec) {
            isGadgetReady = checkIfGadgetReady(frame)
            timeSinceGadgetChecked = currentTime
        }
        if (currentTime - timeSinceSuperChecked > superTresholdSec) {
            isSuperReady = checkIfSuperReady(frame)
            timeSinceSuperChecked = currentTime
        }

        currentBrawler = brawler
        for (key in listOf("enemy", "teammate")) {
            data[key] = (data[key] ?: emptyList()).filter {
                GameplayEvidenceGate.validPlayerBox(it, frame.cols(), frame.rows())
            }.toMutableList()
        }
        synchronized(this) {
            if (frameGeneration != combatGeneration || closed || issuingThread.isInterrupted || !inputAllowed()) return
            // No enemy/range/line-of-sight requirement for basic auto attack. Specials retain targets.
            basicAttackEligible = true
            activeGameplayFrameMs = capturedAtMs
            activeGameplayTarget = !data["enemy"].isNullOrEmpty() || !data["teammate"].isNullOrEmpty()
        }
        val decision = planContinuousMovement(brawler, data, frame, capturedAtMs)
        // Playstyles retain special abilities; the engine owns movement and basic attacks.
        runPlaystyle(brawler, data, decision.vector)
        synchronized(this) {
            if (frameGeneration != combatGeneration || closed || issuingThread.isInterrupted || !inputAllowed() || !gameplayFrameCurrent()) {
                resetCombat()
                gameplayEvidence.reset()
                windowController.releaseMovement()
                BotStatus.publish(RuntimeState.UNKNOWN, VerificationPhase.VERIFY_STATE, false)
                maybeReportObservedState("unknown", monotonicNowMs(), onStateChange)
                return
            }
            applyContinuousMovement(decision, issuingThread)
            // A bounded heartbeat fills slow-capture gaps, but cannot outlive fresh gameplay evidence.
            startAttackPulse(brawler, issuingThread)
        }
    }

    /** Rate-limited hand-off of the actually observed screen to the engine. */
    private fun maybeReportObservedState(
        observed: String?,
        nowMs: Long,
        onStateChange: (String) -> Unit,
    ) {
        if (observed == null) return
        // Every observation carries a different capture authority. Suppressing repeated Match or
        // Unknown callbacks can leave the engine at a stale menu result or hide fresh player loss.
        lastStateReportMs = nowMs
        lastReportedState = observed
        onStateChange(observed)
    }

    private var lastReportedState: String? = null
    private var lastStateReportMs: Long = 0L

    @Volatile private var activeGameplayFrameMs: Long = 0L
    @Volatile private var activeGameplayTarget: Boolean = false
    @Volatile private var basicAttackEligible: Boolean = false
    private fun combatTargetCurrent(): Boolean = !closed && !Thread.currentThread().isInterrupted &&
        inputAllowed() && activeGameplayTarget && gameplayFrameCurrent()
    private fun gameplayFrameCurrent(): Boolean {
        val now = monotonicNowMs()
        return activeGameplayFrameMs > 0L && activeGameplayFrameMs <= now &&
            now - activeGameplayFrameMs <= MAX_EVIDENCE_FRAME_AGE_MS
    }

    // ---------- engine-owned basic attack ----------
    //
    // There is exactly ONE owner of the attack button: this class. A `.pyla` playstyle's `attack()`
    // is a no-op, preventing duplicate DOWNs and conflicting charge timers.
    //
    // [combatGeneration] is a volatile authority token. Every dispatched attack command captures the
    // generation current at issue time; a guard sampled later (possibly on a different thread) fails
    // closed as soon as the generation moved, so target loss / pause / stop cannot revive a queued
    // old DOWN. It advances on every [resetCombat] and [revokeCombat] even when nothing is held.

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var combatGeneration: Long = 0L

    /** True between a dispatched attack DOWN and its matching UP. Mirrors the policy's own state. */
    @Volatile
    private var attackButtonDown: Boolean = false
    @Volatile private var attackHeldSinceSec: Double = 0.0

    /** Engine-owned repeated basic attack. Pure state machine, no ambient clock. */
    private val attackPolicy = RepeatedAttackPolicy(MAX_EVIDENCE_FRAME_AGE_MS, requireNewCapture = false)

    /**
     * Terminal stop: no further attack input may ever be produced and anything held is released.
     * Idempotent.
     */
    @Synchronized fun revokeCombat() {
        closed = true
        resetCombat()
    }

    /**
     * Reversible observation denial (player lost, menu screen, stale frame, pause). Releases held
     * input and revokes queued commands by advancing the authority generation, but keeps the object
     * usable for the next admitted frame.
     */
    @Synchronized fun resetCombat() {
        basicAttackEligible = false
        activeGameplayFrameMs = 0L
        activeGameplayTarget = false
        combatGeneration++
        attackPulse?.let { removeCombatPulse(it) }
        attackPulse = null
        continuousPlanner.reset()
        sceneProgress.reset()
        releaseAttackInput(reason = "reset")
        windowController.releaseMovement()
    }

    /** One outstanding task; no catch-up burst and no new thread for each frame. */
    @Synchronized private fun startAttackPulse(brawler: String, issuingThread: Thread) {
        if (pulseBrawler != brawler && attackButtonDown) releaseAttackInput("brawler_changed")
        pulseBrawler = brawler
        if (attackPulse != null) return
        val generation = combatGeneration
        lateinit var task: Runnable
        task = Runnable {
            synchronized(this) {
                if (attackPulse !== task || generation != combatGeneration) return@synchronized
                try {
                    if (!attackCommandGuard(generation, activeGameplayFrameMs, false, issuingThread)()) {
                        resetCombat()
                        return@synchronized
                    }
                    stepEngineBasicAttack(pulseBrawler, issuingThread)
                    if (attackPulse === task) postCombatPulse(task, 100L)
                } catch (e: Exception) {
                    resetCombat()
                    DiagnosticLog.event("combat.pulse_failed", "type=${e.javaClass.simpleName}")
                }
            }
        }
        attackPulse = task
        task.run()
    }

    /**
     * Guard for an already-dispatched attack command, re-evaluated at native dispatch time.
     *
     * It never trusts the immutable evidence captured when the command was issued alone: authority,
     * closed state and the *live* eligibility of the current frame are all re-checked here, and a
     * finite TAP additionally pins the capture timestamp it was issued from so newer frames cannot
     * rejuvenate a stale queued tap. A held charge is guarded by live eligibility plus the original
     * generation, which is what lets an ordinary run of fresh consecutive frames keep the charge
     * alive.
     */
    private fun attackCommandGuard(
        generation: Long,
        capturedAtMs: Long,
        finite: Boolean,
        thread: Thread,
    ): () -> Boolean = guard@{
        if (closed) false
        // The guard may be sampled on the input thread, where Thread.currentThread() is not the
        // engine thread; check the issuing thread's own interrupt flag instead.
        else if (thread.isInterrupted) false
        else if (combatGeneration != generation) false
        else if (gameplayGeometry != InputCoordinates.geometryGeneration) false
        else if (!inputAllowed()) false
        else if (!basicAttackEligible || !gameplayFrameCurrent()) false
        else if (finite && (capturedAtMs <= 0L || capturedAtMs > monotonicNowMs() ||
            monotonicNowMs() - capturedAtMs > MAX_EVIDENCE_FRAME_AGE_MS)) false
        else true
    }

    /**
     * Release a held attack button exactly once. Releases are deliberately unguarded - a DOWN that
     * was legitimately dispatched must always be matched by its UP, otherwise the button stays
     * stuck down.
     */
    private fun releaseAttackInput(reason: String) {
        if (!attackButtonDown) {
            attackButtonDown = false
            return
        }
        attackButtonDown = false
        attackHeldSinceSec = 0.0
        windowController.press("attack", touchUp = true, touchDown = false)
        // Drops any in-flight charge; the cadence clock is deliberately NOT reset, so an immediate
        // re-acquisition cannot hand out a free burst.
        attackPolicy.revoke()
        DiagnosticLog.event("combat.attack_release", "reason=$reason")
    }

    /** Basic auto attack needs admitted gameplay, not an enemy/range/line-of-sight observation. */
    @Synchronized private fun stepEngineBasicAttack(brawler: String, issuingThread: Thread) {
        if (closed || issuingThread.isInterrupted || !inputAllowed() || !basicAttackEligible || !gameplayFrameCurrent()) {
            resetCombat()
            return
        }

        // Charge duration comes from the real catalog only; an unknown brawler degrades to a tap.
        val holdSeconds = brawlersInfo[brawler]?.holdAttack ?: 0.0
        val nowMs = monotonicNowMs()
        val decision = attackPolicy.step(
            nowMs = nowMs,
            frameCapturedAtMs = activeGameplayFrameMs,
            targetPresent = basicAttackEligible,
            holdSeconds = holdSeconds,
        )
        val generation = combatGeneration
        when (decision.action) {
            BasicAttackAction.NONE -> {
                DiagnosticLog.sample(
                    "combat.attack_idle",
                    "denial=${decision.denial.name} mode=continuous",
                )
            }
            BasicAttackAction.TAP -> {
                windowController.press(
                    "attack",
                    touchUp = true,
                    touchDown = true,
                    guard = attackCommandGuard(generation, activeGameplayFrameMs, finite = true, thread = issuingThread),
                )
                DiagnosticLog.sample("combat.attack_tap", "mode=continuous")
            }
            BasicAttackAction.HOLD -> {
                attackHeldSinceSec = nowSec()
                attackButtonDown = true
                windowController.press(
                    "attack",
                    touchUp = false,
                    touchDown = true,
                    guard = attackCommandGuard(generation, activeGameplayFrameMs, finite = false, thread = issuingThread),
                )
                DiagnosticLog.event("combat.attack_hold", "hold=${holdSeconds}s mode=continuous")
            }
            BasicAttackAction.RELEASE -> {
                releaseAttackInput(reason = decision.denial.name)
            }
        }
    }

    private fun isMenuScreen(state: String?): Boolean =
        state != null && state != "unknown" && state != "match"

    /**
     * Deterministic movement decision from the current frame only: the fresh player position, the
     * currently detected enemies/teammates and the currently detected walls.
     */
    private fun planContinuousMovement(
        brawler: String,
        data: Map<String, MutableList<FloatArray>>,
        frame: Mat,
        capturedAtMs: Long,
    ): ContinuousMovementDecision {
        val playerBox = data["player"]?.firstOrNull()
        val player = playerBox?.let { getPlayerHitCircle(it) }?.first
        val enemies = positions(data["enemy"], playerBox)
        val allies = positions(data["teammate"], playerBox)
        val walls = data["wall"] ?: emptyList()
        val (_, attackRange, _) = getBrawlerRange(brawler)
        val range = if (attackRange > 0) attackRange.toDouble() else tileSizePx
        val (_, radius) = getPlayerHitCircle(playerBox)
        val samples = ArrayList<Int>(30)
        // World-only grid: no lower controls, top HUD or centre player/health-bar region.
        for (y in listOf(0.20, 0.30, 0.40, 0.50, 0.60)) {
            for (x in listOf(0.10, 0.20, 0.30, 0.70, 0.80, 0.90)) {
                val pixel = frame.get((frame.rows() * y).toInt(), (frame.cols() * x).toInt())
                if (pixel != null && pixel.size >= 3) samples.add(((pixel[0] + pixel[1] + pixel[2]) / 3).toInt().coerceIn(0, 255))
            }
        }
        val now = monotonicNowMs()
        val progress = sceneProgress.observe(samples.toIntArray(), capturedAtMs, now)
        return continuousPlanner.plan(
            player = player,
            enemies = enemies,
            allies = allies,
            attackRange = range,
            playerRadius = radius,
            stepPx = tileSizePx,
            capturedAtMs = capturedAtMs,
            nowMs = now,
            progressObserved = progress,
            wallProbe = { from, to, r -> wallsBlockSweptCircle(from, to, r, walls) },
        )
    }

    private fun positions(boxes: List<FloatArray>?, playerBox: FloatArray?): List<Pair<Double, Double>> {
        if (boxes.isNullOrEmpty()) return emptyList()
        val fw = frame?.cols() ?: 0
        val fh = frame?.rows() ?: 0
        return boxes
            .filter { GameplayEvidenceGate.validPlayerBox(it, fw, fh) }
            .map { getEntityPos(it) }
            .distinct()
    }

    private fun applyContinuousMovement(decision: ContinuousMovementDecision, issuingThread: Thread) {
        val runtimeState = RuntimeState.MATCH // This path is reachable only after positive current-frame gameplay admission.
        val phase = when (decision.mode) {
            ContinuousMovementMode.DENIED -> VerificationPhase.VERIFY_STATE
            ContinuousMovementMode.ROAM -> VerificationPhase.ROAM
            ContinuousMovementMode.APPROACH -> VerificationPhase.APPROACH
            ContinuousMovementMode.RETREAT -> VerificationPhase.RETREAT
            ContinuousMovementMode.FOLLOW -> VerificationPhase.FOLLOW
            ContinuousMovementMode.STRAFE -> VerificationPhase.STRAFE
            ContinuousMovementMode.ESCAPE -> VerificationPhase.ESCAPE
            ContinuousMovementMode.DETOUR -> VerificationPhase.AVOID
        }
        BotStatus.publish(runtimeState, phase, verified = decision.vector != null)
        DiagnosticLog.sample("movement.continuous", "mode=${decision.mode.name}")
        val vector = decision.vector
        if (vector == null) {
            doMovement(null)
            return
        }
        val movement = clampMovement(vector)
        windowController.moveGuarded(movement.first.toFloat(), movement.second.toFloat(),
            attackCommandGuard(combatGeneration, activeGameplayFrameMs, finite = false, thread = issuingThread))
    }

    /**
     * Playstyle execution behind positive player evidence. Its movement result is recorded for the
     * script's own `last_movement` context only; production movement input is owned exclusively by
     * the directed planner, so no stale or random vector can reach the joystick.
     */
    private fun runPlaystyle(brawler: String, data: Map<String, MutableList<FloatArray>>, movement: Pair<Double, Double>?) {
        persistentDataPy.map["time_since_holding_attack"] = if (attackButtonDown) attackHeldSinceSec else PyNone
        val script = pylaScript ?: return
        val playerBox = data["player"]?.firstOrNull() ?: return
        val raw = script.run(buildContext(brawler, playerBox, data, movement))
        val move = clampMovement(pyToPair(raw) ?: return)
        if (hypot(move.first, move.second) >= MIN_SIGNIFICANT_MOVEMENT) {
            lastMovement = move
            lastMovementChangeTime = nowSec()
        }
    }

    // ---------- .pyla context bridge ----------

    private fun brawlersInfoPy(): PyDict {
        brawlersInfoPyCache?.let { return it }
        val outer = LinkedHashMap<Any, Any>()
        try {
            val file = PylaConfig.resolve("cfg/brawlers_info.json")
            if (file.exists()) {
                val root = JSONObject(file.readText())
                for (key in root.keys()) {
                    outer[key] = jsonToPy(root.get(key))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "brawlers_info parse: ${t.message}")
        }
        val d = PyDict(outer)
        brawlersInfoPyCache = d
        return d
    }

    private fun jsonToPy(v: Any?): Any = when (v) {
        null, JSONObject.NULL -> PyNone
        is JSONObject -> {
            val m = LinkedHashMap<Any, Any>()
            for (k in v.keys()) m[k] = jsonToPy(v.get(k))
            PyDict(m)
        }
        is JSONArray -> {
            val l = ArrayList<Any>()
            for (i in 0 until v.length()) l.add(jsonToPy(v.get(i)))
            PyList(l)
        }
        is Boolean -> v
        is Int -> v.toLong()
        is Long -> v
        is Double -> v
        is Float -> v.toDouble()
        is String -> v
        is Number -> v.toDouble()
        else -> v.toString()
    }

    private fun boxToPy(box: FloatArray): PyList =
        PyList(box.map { it.toDouble() as Any }.toMutableList())

    private fun boxesToPy(boxes: List<FloatArray>): PyList =
        PyList(boxes.map { boxToPy(it) as Any }.toMutableList())

    private fun pyToBox(v: Any?): FloatArray {
        val items = when (v) {
            is PyList -> v.items
            is PyTuple -> v.items
            else -> return FloatArray(0)
        }
        return FloatArray(items.size) { Py.toDouble(items[it]).toFloat() }
    }

    private fun pyToBoxes(v: Any?): List<FloatArray> {
        val items = when (v) {
            is PyList -> v.items
            is PyTuple -> v.items
            else -> return emptyList()
        }
        return items.map { pyToBox(it) }
    }

    private fun pairToPy(p: Pair<Double, Double>): PyTuple = PyTuple(listOf(p.first, p.second))

    private fun pyToPair(v: Any?): Pair<Double, Double>? {
        val items = when (v) {
            is PyTuple -> v.items
            is PyList -> v.items
            else -> return null
        }
        if (items.size != 2) return null
        val x = items[0]; val y = items[1]
        if (!Py.isNumber(x) || !Py.isNumber(y)) return null
        return Py.toDouble(x) to Py.toDouble(y)
    }

    private fun buildContext(brawler: String, playerBox: FloatArray, data: Map<String, MutableList<FloatArray>>, movement: Pair<Double, Double>?): Map<String, Any> {
        val ctx = HashMap<String, Any>()

        ctx["player_data"] = boxToPy(playerBox)
        ctx["enemy_data"] = boxesToPy(data["enemy"] ?: emptyList())
        ctx["teammate_data"] = boxesToPy(data["teammate"] ?: emptyList())
        ctx["walls"] = boxesToPy(data["wall"] ?: emptyList())
        ctx["bushes"] = boxesToPy(data["bush"] ?: emptyList())
        ctx["brawler"] = brawler
        ctx["current_brawler"] = currentBrawler ?: PyNone
        ctx["brawlers_info"] = brawlersInfoPy()
        ctx["is_gadget_ready"] = isGadgetReady
        ctx["is_hypercharge_ready"] = isHyperchargeReady
        ctx["is_super_ready"] = isSuperReady
        ctx["TILE_SIZE"] = tileSizePx
        ctx["JOYSTICK_RADIUS"] = PylaUtils.JOYSTICK_RADIUS.toLong()
        ctx["width"] = 1920L
        ctx["height"] = 1080L
        ctx["debug"] = verboseDebug
        ctx["seconds_to_hold_attack_after_reaching_max"] = secondsToHoldAttackAfterReachingMax
        ctx["persistent_data"] = persistentDataPy
        ctx["last_movement"] = when (val previous = lastMovement) {
            is Pair<*, *> -> pairAsPy(previous)
            is String -> previous
            else -> PyNone
        }
        ctx["last_movement_change_time"] = lastMovementChangeTime

        ctx["get_entity_pos"] = NativeFn("get_entity_pos") { a, _ -> pairToPy(getEntityPos(pyToBox(a[0]))) }
        ctx["get_distance"] = NativeFn("get_distance") { a, _ ->
            getDistance(pyToPair(a[0]) ?: (0.0 to 0.0), pyToPair(a[1]) ?: (0.0 to 0.0))
        }
        ctx["get_actual_player_box"] = NativeFn("get_actual_player_box") { a, _ ->
            getActualPlayerBox(pyToBox(a[0]))?.let { boxToPy(it) } ?: PyNone
        }
        ctx["get_brawler_range"] = NativeFn("get_brawler_range") { a, _ ->
            val (s, at, su) = getBrawlerRange(a[0] as? String ?: brawler)
            PyList(mutableListOf(s.toLong(), at.toLong(), su.toLong()))
        }
        ctx["is_there_enemy"] = NativeFn("is_there_enemy") { a, _ ->
            val v = a.getOrNull(0)
            when (v) { is PyList -> v.items.isNotEmpty(); is PyTuple -> v.items.isNotEmpty(); else -> false }
        }
        // Single owner of the basic attack: the engine. A playstyle calling `attack()` (or a script that
        // errors, or no script at all) must not produce a second, competing DOWN - the engine manages the
        // hold timer and the repeated cadence itself. This stays a callable no-op so playstyles keep
        // running unchanged.
        ctx["attack"] = NativeFn("attack") { _, _ ->
            if (combatTargetCurrent()) DiagnosticLog.sample("combat.attack_script_noop", "owner=engine")
            PyNone
        }
        ctx["use_hypercharge"] = NativeFn("use_hypercharge") { _, _ -> if (combatTargetCurrent()) useHypercharge(); PyNone }
        ctx["use_super"] = NativeFn("use_super") { _, _ -> if (combatTargetCurrent()) useSuper(); PyNone }
        ctx["use_gadget"] = NativeFn("use_gadget") { _, _ -> if (combatTargetCurrent()) useGadget(); PyNone }
        // Keep the bridge name, returning this frame's already-planned intent without advancing it.
        ctx["get_random_movement"] = NativeFn("get_random_movement") { _, _ ->
            movement?.takeIf { gameplayFrameCurrent() }?.let { pairToPy(it) } ?: PyTuple(listOf(0.0, 0.0))
        }
        ctx["must_brawler_hold_attack"] = NativeFn("must_brawler_hold_attack") { a, _ ->
            mustHoldAttack(a.getOrNull(0) as? String ?: brawler)
        }
        ctx["find_closest_enemy"] = NativeFn("find_closest_enemy") { a, _ ->
            val enemies = pyToBoxes(a.getOrNull(0))
            val pc = pyToPair(a.getOrNull(1)) ?: (0.0 to 0.0)
            val walls = pyToBoxes(a.getOrNull(2))
            val skill = a.getOrNull(3) as? String ?: "attack"
            val (coords, dist) = findClosestEnemy(enemies, pc, walls, skill)
            PyTuple(listOf(coords?.let { pairToPy(it) } ?: PyNone, dist ?: PyNone))
        }
        ctx["find_closest_teammate"] = NativeFn("find_closest_teammate") { a, _ ->
            val teammates = pyToBoxes(a.getOrNull(0))
            val pc = pyToPair(a.getOrNull(1)) ?: (0.0 to 0.0)
            val (coords, dist) = findClosestTeammate(teammates, pc)
            PyTuple(listOf(coords?.let { pairToPy(it) } ?: PyNone, dist ?: PyNone))
        }
        ctx["is_there_poison_gas"] = NativeFn("is_there_poison_gas") { a, kw ->
            val box = pyToBox(a.getOrNull(0))
            val threshold = (kw["threshold"] ?: a.getOrNull(1))?.let { Py.toLong(it).toInt() } ?: 7000
            val area = (kw["area_from_player_checked"] ?: a.getOrNull(2))?.let { Py.toDouble(it) } ?: 1.5
            val gas = isTherePoisonGas(box, threshold, area)
            val m = LinkedHashMap<Any, Any>()
            m["up"] = (gas["up"] ?: 0).toLong()
            m["down"] = (gas["down"] ?: 0).toLong()
            m["left"] = (gas["left"] ?: 0).toLong()
            m["right"] = (gas["right"] ?: 0).toLong()
            PyDict(m)
        }
        ctx["is_path_blocked"] = NativeFn("is_path_blocked") { a, _ ->
            val box = pyToBox(a.getOrNull(0))
            val move = pyToPair(a.getOrNull(1))
            val walls = pyToBoxes(a.getOrNull(2))
            val distance = a.getOrNull(3)?.let { Py.toDouble(it) }
            isPathBlocked(box, move, walls, distance)
        }
        ctx["is_enemy_hittable"] = NativeFn("is_enemy_hittable") { a, _ ->
            val pp = pyToPair(a.getOrNull(0)) ?: (0.0 to 0.0)
            val ep = pyToPair(a.getOrNull(1)) ?: (0.0 to 0.0)
            val walls = pyToBoxes(a.getOrNull(2))
            val skill = a.getOrNull(3) as? String ?: "attack"
            isEnemyHittable(pp, ep, walls, skill)
        }
        ctx["rotate_movement"] = NativeFn("rotate_movement") { a, _ ->
            val m = pyToPair(a.getOrNull(0)) ?: (0.0 to 0.0)
            pairToPy(rotateMovement(m, Py.toDouble(a[1])))
        }

        return ctx
    }

    private fun pairAsPy(p: Pair<*, *>): PyTuple =
        PyTuple(listOf((p.first as Number).toDouble(), (p.second as Number).toDouble()))

    fun close() {
        // Terminal: no attack input may ever be produced after close, and anything held comes up.
        revokeCombat()
        frame = null
        detectMainInfo.close()
        detectTileDetector?.close()
        detectCenteredTileDetector?.close()
    }
}

class FixMovementState(
    val delayToTrigger: Double,
    val duration: Double,
) {
    @Volatile var toggled: Boolean = false
    @Volatile var startedAt: Double = 0.0
    @Volatile var fixed: Pair<Double, Double> = 0.0 to 0.0
    @Volatile var lastDirectionKey: Int? = null
    @Volatile var rotationSign: Int = 1
    @Volatile var rotationAngleStep: Double = 1.0
    val maxRotationAngleStep: Double = 4.0
    fun reset() {
        toggled = false; lastDirectionKey = null; rotationSign = 1; rotationAngleStep = 1.0
    }
}

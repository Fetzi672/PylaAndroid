package com.pyla.ai.engine

import android.os.SystemClock
import com.pyla.ai.PylaApp
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Observation only. Never grants session/input authority or persists user configuration. */
object PhoneVisualCues {
    data class SuperPoint(val x: Float, val y: Float)
    private val templates = HashMap<String, Mat>()
    private val chaosGate = ChaosContinuationGate()
    private fun size(image: Mat) = (image.cols().toLong() shl 32) or image.rows().toLong()

    /**
     * Lifecycle generation to capture BEFORE a frame whose observation may arm the coins
     * continuation. It is threaded through the classifier so an observation that straddled a
     * Pause/Stop/session replacement can neither arm the continuation nor continue the dismissal.
     */
    fun chaosObservationGeneration(): Long = chaosGate.observationGeneration()

    @Synchronized fun revokeChaosContinuation(expectedGeneration: Long? = null) {
        chaosGate.invalidate(SystemClock.elapsedRealtime(), expectedGeneration)
    }

    private fun template(name: String, h: Int): Mat? {
        val key = "$name:$h"
        templates[key]?.let { return it }
        return try {
            val bytes = PylaApp.ctx().assets.open("pyla/images/phone_feedback_cues/$name.png").use { it.readBytes() }
            val encoded = MatOfByte(*bytes)
            val raw = try { Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE) } finally { encoded.release() }
            if (raw.empty()) { raw.release(); null } else {
                val scaled = Mat()
                try { Imgproc.resize(raw, scaled, Size((raw.cols() * h / 720.0).coerceAtLeast(1.0), (raw.rows() * h / 720.0).coerceAtLeast(1.0))) }
                finally { raw.release() }
                templates[key] = scaled; scaled
            }
        } catch (_: Exception) { null }
    }
    private data class Match(val score: Double, val x: Double, val y: Double)
    private fun match(gray: Mat, name: String, x0: Double, y0: Double, x1: Double, y1: Double): Match? {
        val scale = gray.rows() / 720.0
        val left = (x0 * scale).toInt().coerceIn(0, gray.cols())
        val right = (x1 * scale).toInt().coerceIn(0, gray.cols())
        val top = (y0 * scale).toInt().coerceIn(0, gray.rows())
        val bottom = (y1 * scale).toInt().coerceIn(0, gray.rows())
        return match(gray, name, SuperReadinessPolicy.Bounds(left, top, right, bottom))
    }
    private fun match(gray: Mat, name: String, bounds: SuperReadinessPolicy.Bounds): Match? {
        val t = template(name, gray.rows()) ?: return null
        val (left, top, right, bottom) = bounds
        if (right-left < t.cols() || bottom-top < t.rows()) return null
        val crop = Mat(gray, Rect(left,top,right-left,bottom-top)); val scores = Mat()
        return try {
            Imgproc.matchTemplate(crop,t,scores,Imgproc.TM_CCOEFF_NORMED)
            val m = Core.minMaxLoc(scores)
            if (!m.maxVal.isFinite()) null else Match(m.maxVal,left+m.maxLoc.x+t.cols()/2.0,top+m.maxLoc.y+t.rows()/2.0)
        } finally { scores.release(); crop.release() }
    }
    @Synchronized fun superReady(image: Mat, policy: SuperReadinessPolicy? = SuperReadinessPolicy.defaults()): SuperPoint? {
        if (image.empty() || image.channels()!=3) return null
        val bounds = policy?.searchBounds(image.cols(), image.rows()) ?: return null
        val gray=Mat(); Imgproc.cvtColor(image,gray,Imgproc.COLOR_RGB2GRAY)
        val s=image.rows()/720.0
        val m=try {
            listOf("super_ready_skull", "super_ready_skull_active", "super_ready_skull_pulse")
                .mapNotNull { match(gray,it,bounds) }.maxByOrNull { it.score }
        } finally { gray.release() }
        if (m==null || m.score<=0.90) return null
        val radius=44.0*s;val x0=(m.x-radius).toInt();val y0=(m.y-radius).toInt();val x1=(m.x+radius).toInt();val y1=(m.y+radius).toInt()
        if (x0<0 || y0<0 || x1>image.cols() || y1>image.rows() || x1<=x0 || y1<=y0) return null
        val roi=Mat(image,Rect(x0,y0,x1-x0,y1-y0));val hsv=Mat();val mask=Mat()
        return try {
            Imgproc.cvtColor(roi,hsv,Imgproc.COLOR_RGB2HSV)
            Core.inRange(hsv,Scalar(17.0,170.0,200.0),Scalar(34.0,255.0,255.0),mask)
            if (policy.acceptsYellow(Core.countNonZero(mask).toLong(), mask.total())) SuperPoint(m.x.toFloat(),m.y.toFloat()) else null
        } finally { mask.release();hsv.release();roi.release() }
    }
    /** Coins dismissal is admitted only immediately after positively observed Chaosdrop artwork. */
    @Synchronized fun isChaosDrop(
        image: Mat,
        nowMs: Long = SystemClock.elapsedRealtime(),
        chaosGeneration: Long? = chaosGate.observationGeneration(),
        capturedAtMs: Long = nowMs,
    ): Boolean {
        if (image.empty() || image.channels()!=3) return false
        val gray=Mat();Imgproc.cvtColor(image,gray,Imgproc.COLOR_RGB2GRAY)
        val s=image.rows()/720.0;val center=image.cols()/s/2.0
        return try {
            val header=match(gray,"chaos_daily_wins",0.0,0.0,600.0,140.0)?.score ?: 0.0
            val face=if(header>0.90) listOf("rare","super_rare","epic").maxOf { match(gray,"chaos_face_$it",center-280.0,220.0,center+300.0,600.0)?.score ?: 0.0 } else 0.0
            // Unbound read-only classifiers may identify artwork, but cannot create a chain.
            if(header>0.90 && face>0.86) chaosGeneration == null || chaosGate.arm(chaosGeneration, nowMs, size(image), capturedAtMs)
            else if(chaosGeneration != null && chaosGate.continues(chaosGeneration, nowMs, size(image), capturedAtMs)) {
                val title=match(gray,"chaos_reward_coins_title",center-330.0,30.0,center+340.0,210.0)?.score ?: 0.0
                val coin=match(gray,"chaos_reward_coin_icon",center-260.0,330.0,center+160.0,600.0)?.score ?: 0.0
                title>0.90 && coin>0.90
            } else false
        } finally { gray.release() }
    }
}

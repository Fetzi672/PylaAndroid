package com.pyla.ai.engine

import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

/** Robust outer-circle fit: attached red scenery must not move the attack control's center. */
internal object AttackButtonCircleFit {
    fun fit(red: BooleanArray, width: Int, height: Int, bounds: IntArray,
            roughX: Double, roughY: Double, roughRadius: Double,
            labels: IntArray, component: Int): AttackButtonPoint? {
        val xs = DoubleArray(1024)
        val ys = DoubleArray(1024)
        var count = 0
        var seen = 0
        val random = Random(1L)
        for (y in bounds[1]..bounds[3]) for (x in bounds[0]..bounds[2]) {
            val i = y * width + x
            if (labels[i] != component) continue
            val distance = hypot(x - roughX, y - roughY)
            if (distance < roughRadius * .65 || distance > roughRadius * 1.35) continue
            if (x > 0 && y > 0 && x < width - 1 && y < height - 1 &&
                red[i - 1] && red[i + 1] && red[i - width] && red[i + width]) continue
            seen++
            val slot = if (count < xs.size) count++ else random.nextInt(seen)
            if (slot < xs.size) { xs[slot] = x.toDouble(); ys[slot] = y.toDouble() }
        }
        if (count < 24) return null
        val tolerance = maxOf(1.5, roughRadius * .035)
        fun allowed(x: Double, y: Double, radius: Double) =
            x.isFinite() && y.isFinite() && radius.isFinite() &&
            radius in roughRadius * .70..roughRadius * 1.35 &&
            hypot(x - roughX, y - roughY) <= roughRadius * .45
        var bestCount = 0
        var bestX = 0.0; var bestY = 0.0; var bestR = 0.0
        repeat(384) {
            val a = random.nextInt(count); val b = random.nextInt(count); val c = random.nextInt(count)
            val x0 = xs[a]; val y0 = ys[a]; val x1 = xs[b]; val y1 = ys[b]; val x2 = xs[c]; val y2 = ys[c]
            val denominator = 2 * (x0 * (y1 - y2) + x1 * (y2 - y0) + x2 * (y0 - y1))
            if (abs(denominator) < 1.0) return@repeat
            val s0 = x0 * x0 + y0 * y0; val s1 = x1 * x1 + y1 * y1; val s2 = x2 * x2 + y2 * y2
            val x = (s0 * (y1 - y2) + s1 * (y2 - y0) + s2 * (y0 - y1)) / denominator
            val y = (s0 * (x2 - x1) + s1 * (x0 - x2) + s2 * (x1 - x0)) / denominator
            val radius = hypot(x - x0, y - y0)
            if (!allowed(x, y, radius)) return@repeat
            var score = 0
            for (i in 0 until count) if (abs(hypot(xs[i] - x, ys[i] - y) - radius) <= tolerance) score++
            if (score > bestCount) { bestCount = score; bestX = x; bestY = y; bestR = radius }
        }
        if (bestCount < count * .38) return null
        // Least-squares refinement over the consensus, not over the attached outlier pixels.
        val indices = (0 until count).filter { abs(hypot(xs[it] - bestX, ys[it] - bestY) - bestR) <= tolerance }
        val meanX = indices.sumOf { xs[it] } / indices.size
        val meanY = indices.sumOf { ys[it] } / indices.size
        var uu = 0.0; var vv = 0.0; var uv = 0.0; var uq = 0.0; var vq = 0.0
        for (i in indices) {
            val u = xs[i] - meanX; val v = ys[i] - meanY; val q = u * u + v * v
            uu += u * u; vv += v * v; uv += u * v; uq += u * q; vq += v * q
        }
        val denominator = 2 * (uu * vv - uv * uv)
        if (abs(denominator) < 1e-6) return null
        val x = meanX + (uq * vv - vq * uv) / denominator
        val y = meanY + (vq * uu - uq * uv) / denominator
        val radius = indices.sumOf { hypot(xs[it] - x, ys[it] - y) } / indices.size
        if (!allowed(x, y, radius)) return null
        val sectors = BooleanArray(16)
        for (i in indices) {
            val angle = Math.atan2(ys[i] - y, xs[i] - x) + Math.PI
            sectors[(angle / (2 * Math.PI) * 16).toInt().coerceIn(0, 15)] = true
        }
        if (sectors.count { it } < 12) return null
        return AttackButtonPoint(x.toFloat(), y.toFloat(), radius.toFloat())
    }
}

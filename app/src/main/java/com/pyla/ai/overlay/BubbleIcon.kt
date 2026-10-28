package com.pyla.ai.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * Draws the application's own packaged launcher icon inside the floating bubble.
 *
 * The icon comes from the installed package (PackageManager), never from downloaded artwork, so the
 * bubble always shows exactly what the launcher shows. The drawable is rasterised once and cached;
 * adaptive icons are drawn full-bleed inside the circular clip, legacy icons get a small inset so
 * they do not touch the ring.
 */
internal class BubbleIcon(private val context: Context) {
    private var bitmap: Bitmap? = null
    private var adaptive = false
    private var unavailable = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()

    /** True once a real packaged icon has been rasterised. */
    val isLoaded: Boolean get() = bitmap != null

    /** Drop the cached raster (configuration change, density change, destroy). */
    fun invalidate() {
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
        adaptive = false
        unavailable = false
    }

    fun draw(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val icon = ensureBitmap() ?: return
        val inset = if (adaptive) 1f else LEGACY_INSET
        val half = radius * inset
        val destination = RectF(centerX - half, centerY - half, centerX + half, centerY + half)
        val source = Rect(0, 0, icon.width, icon.height)
        clip.reset()
        clip.addCircle(centerX, centerY, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(icon, source, destination, paint)
        canvas.restore()
    }

    private fun ensureBitmap(): Bitmap? {
        bitmap?.let { if (!it.isRecycled) return it }
        if (unavailable) return null
        val drawable = resolveDrawable()
        if (drawable == null) {
            unavailable = true
            Log.w(TAG, "no packaged application icon available for the bubble")
            return null
        }
        adaptive = drawable is AdaptiveIconDrawable
        val target = when {
            drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0 ->
                maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight)
            else -> (MIN_RENDER_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }.coerceIn(1, MAX_RENDER_PX)
        val raster = runCatching {
            Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888).also { bmp ->
                bmp.eraseColor(Color.TRANSPARENT)
                drawable.setBounds(0, 0, target, target)
                val canvas = Canvas(bmp)
                drawable.draw(canvas)
            }
        }.getOrElse {
            Log.w(TAG, "application icon raster failed: ${it.message}")
            null
        }
        if (raster == null) {
            unavailable = true
            return null
        }
        bitmap = raster
        return raster
    }

    private fun resolveDrawable(): Drawable? {
        val manager = context.packageManager
        val direct = runCatching { manager.getApplicationIcon(context.packageName) }.getOrNull()
        if (direct != null) return direct
        return runCatching { context.applicationInfo.loadIcon(manager) }.getOrNull()
    }

    private companion object {
        const val TAG = "PylaOverlay"
        const val LEGACY_INSET = 0.82f
        const val MIN_RENDER_DP = 48f
        const val MAX_RENDER_PX = 512
    }
}

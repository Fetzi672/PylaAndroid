package com.pyla.ai.overlay

import android.content.Context
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Resolves the usable display area and the system-bar / cutout insets that the overlay windows must
 * respect. Uses the public WindowMetrics + WindowInsets APIs on API 30+, and a resource-based
 * fallback below that. No hidden APIs or reflection are used.
 */
object OverlayInsets {
    fun compute(context: Context, windowManager: WindowManager): ScreenGeometry {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                ScreenGeometry(
                    widthPx = bounds.width(),
                    heightPx = bounds.height(),
                    insetLeftPx = insets.left,
                    insetTopPx = insets.top,
                    insetRightPx = insets.right,
                    insetBottomPx = insets.bottom,
                )
            } catch (_: Throwable) {
                legacy(context)
            }
        }
        return legacy(context)
    }

    private fun legacy(context: Context): ScreenGeometry {
        val dm = context.resources.displayMetrics
        return ScreenGeometry(
            widthPx = dm.widthPixels,
            heightPx = dm.heightPixels,
            insetLeftPx = 0,
            insetTopPx = systemDimension(context, "status_bar_height"),
            insetRightPx = 0,
            insetBottomPx = systemDimension(context, "navigation_bar_height"),
        )
    }

    private fun systemDimension(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}

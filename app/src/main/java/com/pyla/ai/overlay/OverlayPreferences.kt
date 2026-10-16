package com.pyla.ai.overlay

import android.content.Context
import android.provider.Settings

/**
 * Durable overlay settings. Only the enabled flag, the docked side and the normalized vertical
 * position are persisted; the vertical position is written exclusively when a drag docks (never
 * per frame).
 */
object OverlayPreferences {
    private const val PREFS = "pyla"
    private const val KEY_ENABLED = "floating_overlay_enabled"
    private const val KEY_SIDE = "floating_overlay_side"
    private const val KEY_RELATIVE_Y = "floating_overlay_relative_y"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun side(context: Context): DockSide =
        if (prefs(context).getString(KEY_SIDE, DockSide.RIGHT.name) == DockSide.LEFT.name) DockSide.LEFT
        else DockSide.RIGHT

    fun setSide(context: Context, side: DockSide) {
        prefs(context).edit().putString(KEY_SIDE, side.name).apply()
    }

    fun relativeY(context: Context): Float =
        OverlayGeometry.clampRelativeY(
            prefs(context).getFloat(KEY_RELATIVE_Y, OverlayGeometry.DEFAULT_RELATIVE_Y),
        )

    fun setRelativeY(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_RELATIVE_Y, OverlayGeometry.clampRelativeY(value)).apply()
    }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context.applicationContext)
}

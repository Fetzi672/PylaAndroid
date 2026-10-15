package com.pyla.ai.ui

import com.pyla.ai.config.PlaystyleSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No config/asset access before startup extraction; never resolve on the UI caller. */
internal suspend fun loadPlaystyleSelection(
    assetsReady: Boolean,
    resolve: () -> String = PlaystyleSelection::effectivePlaystyle,
): String? {
    if (!assetsReady) return null
    // withContext also rejects a late result when the owning LaunchedEffect is cancelled.
    return withContext(Dispatchers.IO) { resolve() }
}

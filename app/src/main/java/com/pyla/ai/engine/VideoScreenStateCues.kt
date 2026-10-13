package com.pyla.ai.engine

/** Independent text/control cues cropped from the user's manual reference, never character/name art.
 * Pixels authorize only the named menu/wait state. No combination can authorize gameplay.
 */
object VideoScreenStateCues {
    const val REFERENCE_HEIGHT = 590
    const val THRESHOLD = 0.90
    val regions: Map<String, List<Int>> = linkedMapOf(
        "players_found" to listOf(700,100,600,110),
        "search_exit" to listOf(200,900,500,160),
        "search_exit_disabled" to listOf(200,900,500,160),
        "rank" to listOf(90,15,600,155),
        "result_exit" to listOf(1530,910,370,160),
        "play_again" to listOf(1150,910,390,160),
        "result_trophy" to listOf(100,165,200,115),
        "defeated" to listOf(690,180,550,170),
        "brawlers_left" to listOf(120,0,500,130),
        "showdown" to listOf(670,460,650,120),
        "sole_survivor" to listOf(670,555,650,100),
        "every_brawler" to listOf(470,900,1000,170),
        "starts_in" to listOf(1550,920,350,160),
    )

    fun classify(matches: Set<String>): String? {
        fun has(vararg names: String) = names.all { it in matches }
        val candidates = mutableListOf<String>()
        if (has("players_found", "search_exit") || has("players_found", "search_exit_disabled")) candidates += "match_making"
        if (has("rank", "result_trophy")) candidates +=
            if (has("result_exit", "play_again")) "end_ranked" else "result_preview"
        if (has("defeated", "brawlers_left")) candidates += "defeated"
        if (has("showdown", "sole_survivor") || has("every_brawler", "starts_in")) candidates += "match_start"
        return when(candidates.size) { 0 -> null; 1 -> candidates.single(); else -> "transition" }
    }
}

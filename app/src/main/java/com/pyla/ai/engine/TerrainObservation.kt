package com.pyla.ai.engine

/** One terrain generation for a frame. Movement-only obstacles never become projectile walls. */
data class TerrainObservation(
    val walls: List<FloatArray> = emptyList(),
    val bushes: List<FloatArray> = emptyList(),
    val waters: List<FloatArray> = emptyList(),
    val ropeFences: List<FloatArray> = emptyList(),
    val outOfBounds: List<FloatArray> = emptyList(),
) {
    fun movementObstacles(projectileWalls: List<FloatArray>): List<FloatArray> =
        if (waters.isEmpty() && ropeFences.isEmpty() && outOfBounds.isEmpty()) projectileWalls
        else projectileWalls + waters + ropeFences + outOfBounds

    fun populate(data: MutableMap<String, MutableList<FloatArray>>) {
        for ((key, value) in listOf("wall" to walls, "bush" to bushes, "water" to waters,
            "rope_fence" to ropeFences, "out_of_bounds" to outOfBounds)) {
            data[key] = value.mapTo(ArrayList(value.size)) { it.copyOf() }
        }
    }

    companion object {
        fun fromDetections(data: Map<String, List<FloatArray>>, semantic: Boolean): TerrainObservation {
            val walls = ArrayList<FloatArray>(); val bushes = ArrayList<FloatArray>()
            val waters = ArrayList<FloatArray>(); val fences = ArrayList<FloatArray>(); val bounds = ArrayList<FloatArray>()
            for ((key, value) in data) {
                val destination = if (!semantic) {
                    if (key.contains("bush")) bushes else walls // Exact legacy close-model convention.
                } else when (key) {
                    "wall" -> walls
                    "bush" -> bushes
                    "water" -> waters
                    "rope_fence" -> fences
                    "out_of_bounds" -> bounds
                    else -> throw IllegalArgumentException("Unknown semantic terrain class")
                }
                destination.addAll(value.map { it.copyOf() })
            }
            return TerrainObservation(walls, bushes, waters, fences, bounds)
        }
    }
}

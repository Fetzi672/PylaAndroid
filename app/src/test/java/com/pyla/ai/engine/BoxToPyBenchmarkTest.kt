package com.pyla.ai.engine

import org.junit.Test
import kotlin.system.measureTimeMillis

class BoxToPyBenchmarkTest {

    private fun boxToPyOld(box: FloatArray): MutableList<Any> =
        box.map { it.toDouble() as Any }.toMutableList()

    private fun boxesToPyOld(boxes: List<FloatArray>): MutableList<Any> =
        boxes.map { boxToPyOld(it) as Any }.toMutableList()

    private fun boxToPyNew(box: FloatArray): MutableList<Any> =
        box.mapTo(ArrayList(box.size)) { it.toDouble() as Any }

    private fun boxesToPyNew(boxes: List<FloatArray>): MutableList<Any> =
        boxes.mapTo(ArrayList(boxes.size)) { boxToPyNew(it) as Any }

    @Test
    fun benchmark() {
        val testBoxes = List(1000) { FloatArray(4) { 1.0f } }

        // Warm up
        for (i in 0 until 1000) {
            boxesToPyOld(testBoxes)
            boxesToPyNew(testBoxes)
        }

        val iterations = 50000
        val timeOld = measureTimeMillis {
            for (i in 0 until iterations) {
                boxesToPyOld(testBoxes)
            }
        }
        val timeNew = measureTimeMillis {
            for (i in 0 until iterations) {
                boxesToPyNew(testBoxes)
            }
        }

        println("BENCHMARK_RESULT: Old: $timeOld ms, New: $timeNew ms, Improvement: ${timeOld - timeNew} ms (${(timeOld - timeNew) * 100 / timeOld.coerceAtLeast(1)}%)")
    }
}

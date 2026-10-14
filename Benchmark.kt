import kotlin.system.measureTimeMillis

val SANITIZE_REGEX = Regex("[^A-Za-z0-9._-]")

fun sanitizeNameOld(name: String): String {
    return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

fun sanitizeNameNew(name: String): String {
    return name.replace(SANITIZE_REGEX, "_")
}

fun main() {
    val testString = "my-awesome_Script.pyla!@#$"
    val iterations = 100000

    // Warmup
    for (i in 0..10000) {
        sanitizeNameOld(testString)
        sanitizeNameNew(testString)
    }

    val oldTime = measureTimeMillis {
        for (i in 0..iterations) {
            sanitizeNameOld(testString)
        }
    }

    val newTime = measureTimeMillis {
        for (i in 0..iterations) {
            sanitizeNameNew(testString)
        }
    }

    println("Old time: $oldTime ms")
    println("New time: $newTime ms")
}

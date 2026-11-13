package kr.meter29.core

enum class Beam { OFF, LOW, HIGH }

fun visibleDistanceM(beam: Beam): Double = when (beam) {
    Beam.OFF -> 16.0
    Beam.LOW -> 29.0
    Beam.HIGH -> 79.0
}

fun stoppingDistanceM(speedKmh: Double, wet: Boolean): Double {
    val v = speedKmh / 3.6
    val mu = if (wet) 0.4 else 0.7
    return v * 1.0 + v * v / (2.0 * mu * 9.81)
}

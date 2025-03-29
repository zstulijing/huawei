package com.k2fsa.sherpa.ncnn.recorder

fun calculateRMS(samples: FloatArray): Float {
    var sumOfSquares = 0.0f
    for (sample in samples) {
        sumOfSquares += sample * sample
    }
    val meanSquare = sumOfSquares / samples.size
    return kotlin.math.sqrt(meanSquare)
}
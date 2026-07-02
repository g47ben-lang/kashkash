package com.voicechanger.app.rvc

/** Linear-interpolation resampler + PCM format converters. */
object Resampler {

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val ratio  = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        val out    = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos  = i * ratio
            val idx0 = pos.toInt()
            val idx1 = (idx0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }

    fun shortToFloat(src: ShortArray): FloatArray =
        FloatArray(src.size) { src[it] / 32768f }

    fun floatToShort(src: FloatArray): ShortArray =
        ShortArray(src.size) { (src[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
}

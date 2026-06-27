package com.voicechanger.app.effects

object PitchShifter {
    fun shift(input: ShortArray, pitchFactor: Float): ShortArray {
        val output = ShortArray(input.size)
        for (i in output.indices) {
            val srcIdx = i * pitchFactor
            val idx0 = srcIdx.toInt()
            val idx1 = idx0 + 1
            val frac = srcIdx - idx0
            val s0 = if (idx0 < input.size) input[idx0].toFloat() else 0f
            val s1 = if (idx1 < input.size) input[idx1].toFloat() else 0f
            output[i] = (s0 + frac * (s1 - s0)).toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }
}

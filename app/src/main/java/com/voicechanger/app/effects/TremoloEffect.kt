package com.voicechanger.app.effects

import kotlin.math.PI
import kotlin.math.sin

object TremoloEffect {
    private var phase = 0.0

    fun apply(input: ShortArray, sampleRate: Int, rate: Float = 6f, depth: Float = 0.45f): ShortArray {
        val output = ShortArray(input.size)
        val phaseInc = 2.0 * PI * rate / sampleRate
        for (i in input.indices) {
            val mod = 1.0 - depth * (1.0 - sin(phase)) / 2.0
            phase += phaseInc
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            output[i] = (input[i] * mod).toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    fun reset() { phase = 0.0 }
}

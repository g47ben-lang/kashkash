package com.voicechanger.app.effects

import kotlin.math.PI
import kotlin.math.sin

object RobotEffect {
    private var phase = 0.0

    fun apply(input: ShortArray, sampleRate: Int, carrierFreq: Float = 80f): ShortArray {
        val output = ShortArray(input.size)
        val phaseIncrement = 2.0 * PI * carrierFreq / sampleRate
        for (i in input.indices) {
            val carrier = sin(phase).toFloat()
            phase += phaseIncrement
            if (phase > 2.0 * PI) phase -= 2.0 * PI
            output[i] = (input[i] * carrier).toInt().coerceIn(-32768, 32767).toShort()
        }
        return output
    }

    fun reset() { phase = 0.0 }
}

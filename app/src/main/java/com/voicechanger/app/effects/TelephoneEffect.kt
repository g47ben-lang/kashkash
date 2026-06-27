package com.voicechanger.app.effects

object TelephoneEffect {
    // Simple single-pole highpass (300 Hz) + lowpass (3400 Hz) to mimic telephone bandwidth
    private var hpPrev = 0.0
    private var lpPrev = 0.0

    fun apply(input: ShortArray, sampleRate: Int): ShortArray {
        val output = ShortArray(input.size)
        val dt = 1.0 / sampleRate
        val hpRC = 1.0 / (2 * Math.PI * 300.0)
        val lpRC = 1.0 / (2 * Math.PI * 3400.0)
        val hpAlpha = hpRC / (hpRC + dt)
        val lpAlpha = dt / (lpRC + dt)

        for (i in input.indices) {
            val x = input[i].toDouble()
            val hp = hpAlpha * (hpPrev + x - (if (i > 0) input[i - 1].toDouble() else 0.0))
            hpPrev = hp
            val lp = lpPrev + lpAlpha * (hp - lpPrev)
            lpPrev = lp
            // slight overdrive for "crackly phone" character
            val driven = (lp * 1.8).coerceIn(-32767.0, 32767.0)
            output[i] = driven.toInt().toShort()
        }
        return output
    }

    fun reset() { hpPrev = 0.0; lpPrev = 0.0 }
}

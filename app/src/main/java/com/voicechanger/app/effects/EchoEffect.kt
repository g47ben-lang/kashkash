package com.voicechanger.app.effects

object EchoEffect {
    private const val MAX_DELAY_SAMPLES = 44100 / 3  // ~333ms at 44100Hz
    private val delayBuffer = ShortArray(MAX_DELAY_SAMPLES)
    private var writePos = 0

    fun apply(input: ShortArray, decayFactor: Float = 0.45f): ShortArray {
        val output = ShortArray(input.size)
        for (i in input.indices) {
            val delayed = delayBuffer[writePos]
            val mixed = (input[i] + delayed * decayFactor).toInt().coerceIn(-32768, 32767).toShort()
            output[i] = mixed
            delayBuffer[writePos] = mixed
            writePos = (writePos + 1) % MAX_DELAY_SAMPLES
        }
        return output
    }

    fun reset() {
        delayBuffer.fill(0)
        writePos = 0
    }
}

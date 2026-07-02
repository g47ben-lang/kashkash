package com.voicechanger.app.rvc

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * YIN-based pitch (F0) extractor.
 * Designed for 16 kHz input, which is what HuBERT expects.
 */
object F0Extractor {

    private const val HOP      = 320    // 20 ms at 16 kHz — matches HuBERT feature stride
    private const val FRAME    = 1024   // analysis window
    private const val THRESHOLD = 0.15f

    /**
     * Extract F0 per HuBERT frame.
     * @param audio  Float32 PCM at 16 kHz, normalized to [-1, 1]
     * @return F0 in Hz per frame (0 = unvoiced)
     */
    fun extract(audio: FloatArray, sampleRate: Int = 16000): FloatArray {
        val nFrames = maxOf(1, (audio.size - FRAME) / HOP + 1)
        val f0      = FloatArray(nFrames)

        val minPeriod = (sampleRate / 1100f).toInt().coerceAtLeast(2)
        val maxPeriod = (sampleRate / 50f).toInt().coerceAtMost(FRAME / 2)

        for (i in 0 until nFrames) {
            val start = i * HOP
            val end   = (start + FRAME).coerceAtMost(audio.size)
            val frame = audio.sliceArray(start until end)
            f0[i] = yinF0(frame, minPeriod, maxPeriod, sampleRate.toFloat())
        }
        return f0
    }

    /**
     * Convert raw Hz F0 to the pair that RVC synthesizer expects:
     *   coarse: LongArray of 0..255 bins (log-scale pitch quantisation)
     *   fine:   FloatArray of raw Hz values
     */
    fun toCoarseAndFine(f0: FloatArray): Pair<LongArray, FloatArray> {
        val coarse = LongArray(f0.size)
        for (i in f0.indices) {
            if (f0[i] <= 0f) {
                coarse[i] = 0L
            } else {
                // Standard RVC log-scale quantisation: 256 bins from ~32 Hz to ~3600 Hz
                val bin = ((ln(f0[i] / 32.70f) / ln(2f)) * 48f).toInt().coerceIn(1, 255)
                coarse[i] = bin.toLong()
            }
        }
        return Pair(coarse, f0.copyOf())
    }

    // ── YIN algorithm ────────────────────────────────────────────────────────

    private fun yinF0(frame: FloatArray, minPeriod: Int, maxPeriod: Int, sr: Float): Float {
        val n = frame.size

        // Step 1: difference function
        val diff = FloatArray(maxPeriod + 1)
        for (tau in 1..maxPeriod) {
            var s = 0f
            for (j in 0 until n - maxPeriod) {
                val d = frame[j] - frame[j + tau]
                s += d * d
            }
            diff[tau] = s
        }

        // Step 2: cumulative mean normalized difference
        val cmnd = FloatArray(maxPeriod + 1)
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..maxPeriod) {
            running += diff[tau]
            cmnd[tau] = if (running == 0f) 1f else diff[tau] * tau / running
        }

        // Step 3: find first dip below threshold
        for (tau in minPeriod..maxPeriod) {
            if (cmnd[tau] < THRESHOLD) {
                // Parabolic interpolation for sub-sample accuracy
                val refined = if (tau in 1 until maxPeriod) {
                    val s0 = cmnd[tau - 1]
                    val s1 = cmnd[tau]
                    val s2 = cmnd[tau + 1]
                    val denom = 2 * (2 * s1 - s2 - s0)
                    if (denom == 0f) tau.toFloat() else tau + (s2 - s0) / denom
                } else tau.toFloat()
                return sr / refined
            }
        }

        // No voiced frame found → global minimum as fallback
        var bestTau = minPeriod
        for (tau in minPeriod..maxPeriod) if (cmnd[tau] < cmnd[bestTau]) bestTau = tau
        return if (cmnd[bestTau] < 0.45f) sr / bestTau else 0f
    }
}

package com.voicechanger.app.rvc

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Real-time RVC inference engine.
 *
 * Pipeline per chunk:
 *  1. ShortArray (44100 Hz) → Float (16 kHz)          [Resampler]
 *  2. Float (16 kHz) → features (T × phoneDim)        [HuBERT ONNX]
 *  3. Float (16 kHz) → F0 Hz per frame                [F0Extractor]
 *  4. features + F0 → audio Float (model sr)          [Synthesizer ONNX]
 *  5. Float (model sr) → ShortArray (44100 Hz)        [Resampler]
 *
 * Expected latency on a mid-range phone: ~300–700 ms per 500 ms chunk.
 * Use a ring buffer in AudioProcessor to hide latency via cross-fading.
 */
class RvcEngine(
    hubertPath: String,
    synthPath:  String,
    val modelSr: Int   = 40000,
    val phoneDim: Int  = 256,
) : AutoCloseable {

    private val env    = OrtEnvironment.getEnvironment()
    private val hubert = env.createSession(hubertPath, OrtSession.SessionOptions())
    private val synth  = env.createSession(synthPath,  OrtSession.SessionOptions())

    private val INPUT_SR   = 44100
    private val HUBERT_SR  = 16000

    /**
     * Process one chunk of audio.
     * @param audio raw PCM 44100 Hz
     * @return converted PCM 44100 Hz
     */
    fun process(audio: ShortArray): ShortArray {
        // 1. Convert to float, resample to 16 kHz for HuBERT
        val float44 = Resampler.shortToFloat(audio)
        val float16 = Resampler.resample(float44, INPUT_SR, HUBERT_SR)

        // 2. HuBERT features → (1, T, phoneDim)
        val features = runHubert(float16)  // FloatArray length = T * phoneDim
        val T        = features.size / phoneDim

        // 3. F0 extraction (one value per HuBERT frame)
        val f0raw = F0Extractor.extract(float16, HUBERT_SR)
        val f0 = alignF0(f0raw, T)            // ensure same length T as features
        val (coarseF0, fineF0) = F0Extractor.toCoarseAndFine(f0)

        // 4. Run synthesizer
        val outFloat = runSynth(features, T, coarseF0, fineF0)

        // 5. Resample from model sr back to 44100
        val resampled = Resampler.resample(outFloat, modelSr, INPUT_SR)
        return Resampler.floatToShort(resampled)
    }

    // ── ONNX calls ────────────────────────────────────────────────────────────

    private fun runHubert(audio16k: FloatArray): FloatArray {
        val buf   = FloatBuffer.wrap(audio16k)
        val shape = longArrayOf(1, audio16k.size.toLong())
        OnnxTensor.createTensor(env, buf, shape).use { input ->
            hubert.run(mapOf("audio" to input)).use { out ->
                return flattenToFloat(out[0].value)
            }
        }
    }

    private fun runSynth(features: FloatArray, T: Int,
                         coarseF0: LongArray, fineF0: FloatArray): FloatArray {
        val phoneShape  = longArrayOf(1, T.toLong(), phoneDim.toLong())
        val seqShape    = longArrayOf(1, T.toLong())
        val scalarShape = longArrayOf(1)

        val phoneTensor  = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), phoneShape)
        val lenTensor    = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(T.toLong())), scalarShape)
        val pitchTensor  = OnnxTensor.createTensor(env, LongBuffer.wrap(coarseF0), seqShape)
        val pitchfTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(fineF0),  seqShape)
        val dsTensor     = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), scalarShape)

        val inputs = mapOf(
            "phone"         to phoneTensor,
            "phone_lengths" to lenTensor,
            "pitch"         to pitchTensor,
            "pitchf"        to pitchfTensor,
            "ds"            to dsTensor,
        )
        synth.run(inputs).use { out ->
            return flattenToFloat(out[0].value)
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Pad or truncate f0 to exactly targetLen frames. */
    private fun alignF0(f0: FloatArray, targetLen: Int): FloatArray =
        when {
            f0.size == targetLen -> f0
            f0.size > targetLen  -> f0.copyOf(targetLen)
            else                 -> FloatArray(targetLen).also { f0.copyInto(it) }
        }

    /** Flatten any nested array/tensor to a flat FloatArray. */
    @Suppress("UNCHECKED_CAST")
    private fun flattenToFloat(value: Any?): FloatArray {
        return when (value) {
            is FloatArray                -> value
            is Array<*>                  -> {
                val inner = (value as Array<Any?>)
                when (val first = inner.firstOrNull()) {
                    is FloatArray       -> inner.flatMap { (it as FloatArray).asIterable() }.toFloatArray()
                    is Array<*>         -> inner.flatMap { row ->
                        (row as Array<FloatArray>).flatMap { it.asIterable() }
                    }.toFloatArray()
                    else                -> FloatArray(0)
                }
            }
            else -> FloatArray(0)
        }
    }

    override fun close() {
        hubert.close()
        synth.close()
    }
}

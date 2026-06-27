package com.voicechanger.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.voicechanger.app.effects.EchoEffect
import com.voicechanger.app.effects.PitchShifter
import com.voicechanger.app.effects.RobotEffect
import com.voicechanger.app.effects.TelephoneEffect
import com.voicechanger.app.effects.TremoloEffect
import kotlin.math.PI
import kotlin.math.sin

enum class VoiceEffect {
    NORMAL, CHIPMUNK, DEEP_VOICE, ROBOT, ECHO,
    CHILD, BIBI, TRUMP, OLD_MAN, TELEPHONE
}

class AudioProcessor(private val context: Context) {

    private val sampleRate = 44100
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
    private val bufferSize = maxOf(minBufferSize * 2, 4096)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile var currentEffect: VoiceEffect = VoiceEffect.NORMAL
    @Volatile var intensity: Float = 1.0f
    @Volatile private var running = false
    @Volatile private var previewRunning = false

    fun start() {
        if (running) return
        running = true

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, channelIn, encoding, bufferSize
        )

        val sessionId = audioRecord!!.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }

        audioTrack = buildAudioTrack()
        audioRecord?.startRecording()
        audioTrack?.play()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val processed = applyEffect(buffer.copyOf(read), currentEffect, intensity)
                    audioTrack?.write(processed, 0, processed.size)
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        echoCanceler?.release(); echoCanceler = null
        noiseSuppressor?.release(); noiseSuppressor = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
        EchoEffect.reset()
        RobotEffect.reset()
        TremoloEffect.reset()
        TelephoneEffect.reset()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // Plays a synthetic voice sample through the given effect so the user can preview it
    fun preview(effect: VoiceEffect) {
        if (running || previewRunning) return
        Thread {
            previewRunning = true
            try {
                val signal = generateVoiceSignal()
                val processed = applyEffect(signal, effect, intensity)
                playOnce(processed)
            } finally {
                previewRunning = false
            }
        }.apply { isDaemon = true; start() }
    }

    // Generates a voiced excitation signal (sawtooth-like, resembles a human vowel)
    private fun generateVoiceSignal(durationMs: Int = 900): ShortArray {
        val samples = sampleRate * durationMs / 1000
        val result = ShortArray(samples)
        val f0 = 110.0 // A2 – typical male speaking fundamental
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            var s = 0.0
            for (h in 1..10) s += sin(2 * PI * f0 * h * t) / h  // harmonic series
            // Short fade-in and fade-out envelope
            val env = minOf(i / (sampleRate * 0.04), 1.0) *
                      minOf((samples - i) / (sampleRate * 0.08), 1.0)
            result[i] = (s * 0.28 * env * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return result
    }

    private fun playOnce(buffer: ShortArray) {
        val track = buildPreviewTrack()
        track.play()
        track.write(buffer, 0, buffer.size)
        Thread.sleep((buffer.size * 1000L / sampleRate) + 150)
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    private fun applyEffect(input: ShortArray, effect: VoiceEffect, lvl: Float): ShortArray =
        when (effect) {
            VoiceEffect.NORMAL     -> input
            VoiceEffect.CHIPMUNK   -> PitchShifter.shift(input, 1.5f * lvl.coerceIn(0.5f, 2.0f))
            VoiceEffect.DEEP_VOICE -> PitchShifter.shift(input, (0.7f / lvl.coerceIn(0.5f, 1.5f)).coerceAtLeast(0.3f))
            VoiceEffect.ROBOT      -> RobotEffect.apply(input, sampleRate, 80f * lvl)
            VoiceEffect.ECHO       -> EchoEffect.apply(input, 0.45f * lvl)
            VoiceEffect.CHILD      -> PitchShifter.shift(input, 1.9f * lvl.coerceIn(0.7f, 2.0f))
            VoiceEffect.BIBI       -> EchoEffect.apply(PitchShifter.shift(input, 0.88f), 0.12f)
            VoiceEffect.TRUMP      -> EchoEffect.apply(PitchShifter.shift(input, 0.80f), 0.18f)
            VoiceEffect.OLD_MAN    -> TremoloEffect.apply(PitchShifter.shift(input, 0.83f), sampleRate, 5.5f, 0.5f * lvl)
            VoiceEffect.TELEPHONE  -> TelephoneEffect.apply(input, sampleRate)
        }

    private fun buildAudioTrack(): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, channelOut, encoding, bufferSize, AudioTrack.MODE_STREAM)
        }

    private fun buildPreviewTrack(): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelOut)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelOut, encoding, bufferSize * 4, AudioTrack.MODE_STREAM)
        }
}

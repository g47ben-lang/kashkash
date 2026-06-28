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

enum class MicSource(val audioSource: Int) {
    MAIN(MediaRecorder.AudioSource.MIC),
    COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
}

enum class OutputMode { EARPIECE, SPEAKER, BLUETOOTH }

class AudioProcessor(private val context: Context) {

    private val sampleRate   = 44100
    private val channelIn    = AudioFormat.CHANNEL_IN_MONO
    private val channelOut   = AudioFormat.CHANNEL_OUT_MONO
    private val encoding     = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
    private val bufferSize    = maxOf(minBufferSize * 2, 4096)

    private var audioRecord:     AudioRecord?          = null
    private var audioTrack:      AudioTrack?           = null
    private var echoCanceler:    AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor?      = null

    @Volatile var currentEffect: VoiceEffect = VoiceEffect.NORMAL
    @Volatile var intensity:     Float       = 1.0f
    @Volatile var micSource:     MicSource   = MicSource.COMMUNICATION
    @Volatile var outputMode:    OutputMode  = OutputMode.EARPIECE
    @Volatile private var running     = false
    @Volatile private var previewBusy = false

    // ── Start / stop ──────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true

        val am = audioManager()

        // Detect if wired/BT headset is already connected; prefer those over earpiece
        val headsetOn = isHeadsetConnected(am)

        if (headsetOn && outputMode == OutputMode.EARPIECE) {
            // Route through headset: use STREAM_MUSIC mode to avoid mic picking up playback
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
        } else {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applyOutputRouting(am)
        }

        audioRecord = AudioRecord(micSource.audioSource, sampleRate, channelIn, encoding, bufferSize)

        val sessionId = audioRecord!!.audioSessionId
        if (micSource == MicSource.COMMUNICATION && AcousticEchoCanceler.isAvailable())
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable())
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }

        audioTrack = if (headsetOn && outputMode == OutputMode.EARPIECE)
            buildTrack(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.STREAM_MUSIC)
        else
            buildTrack(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.CONTENT_TYPE_SPEECH, AudioManager.STREAM_VOICE_CALL)

        audioRecord?.startRecording()
        audioTrack?.play()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val out = applyEffect(buffer.copyOf(read), currentEffect, intensity)
                    audioTrack?.write(out, 0, out.size)
                }
            }
        }.apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    fun stop() {
        running = false
        echoCanceler?.release();    echoCanceler    = null
        noiseSuppressor?.release(); noiseSuppressor = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop();  audioTrack?.release()  } catch (_: Exception) {}
        audioRecord = null; audioTrack = null
        EchoEffect.reset(); RobotEffect.reset(); TremoloEffect.reset(); TelephoneEffect.reset()

        val am = audioManager()
        @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
        try { am.stopBluetoothSco() } catch (_: Exception) {}
        am.mode = AudioManager.MODE_NORMAL
    }

    fun release() = stop()

    // ── Output routing ────────────────────────────────────────────────────────

    fun applyOutputRouting(am: AudioManager = audioManager()) {
        try { am.stopBluetoothSco() } catch (_: Exception) {}
        @Suppress("DEPRECATION") am.isBluetoothScoOn = false
        when (outputMode) {
            OutputMode.EARPIECE  -> { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
            OutputMode.SPEAKER   -> { @Suppress("DEPRECATION") am.isSpeakerphoneOn = true  }
            OutputMode.BLUETOOTH -> {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
                try { am.startBluetoothSco(); @Suppress("DEPRECATION") am.isBluetoothScoOn = true } catch (_: Exception) {}
            }
        }
    }

    // ── Preview (synthetic signal) ────────────────────────────────────────────

    fun preview(effect: VoiceEffect) {
        if (running || previewBusy) return
        previewBusy = true
        Thread {
            try {
                val signal    = generateVoiceSignal()
                val processed = applyEffect(signal, effect, intensity)
                playOnce(processed)
            } finally { previewBusy = false }
        }.apply { isDaemon = true; start() }
    }

    // Harmonic sawtooth at 110 Hz with smooth envelope — sounds like a sustained vowel
    private fun generateVoiceSignal(durationMs: Int = 900): ShortArray {
        val samples = sampleRate * durationMs / 1000
        val result  = ShortArray(samples)
        val f0 = 110.0
        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            var s = 0.0
            for (h in 1..10) s += sin(2 * PI * f0 * h * t) / h
            val env = minOf(i / (sampleRate * 0.04), 1.0) *
                      minOf((samples - i) / (sampleRate * 0.08), 1.0)
            result[i] = (s * 0.28 * env * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return result
    }

    private fun playOnce(buffer: ShortArray) {
        val track = buildTrack(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.STREAM_MUSIC)
        track.play()
        track.write(buffer, 0, buffer.size)
        Thread.sleep(buffer.size * 1000L / sampleRate + 200)
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    // ── DSP ───────────────────────────────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun isHeadsetConnected(am: AudioManager): Boolean =
        am.isWiredHeadsetOn || am.isBluetoothA2dpOn || am.isBluetoothScoOn

    private fun audioManager() =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun buildTrack(usage: Int, contentType: Int, legacyStream: Int): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(usage).setContentType(contentType).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate).setChannelMask(channelOut).setEncoding(encoding).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(legacyStream, sampleRate, channelOut, encoding, bufferSize, AudioTrack.MODE_STREAM)
        }
}

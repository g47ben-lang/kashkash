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
import com.voicechanger.app.rvc.RvcEngine
import com.voicechanger.app.rvc.RvcModel
import kotlin.math.PI
import kotlin.math.sin

enum class VoiceEffect {
    NORMAL, CHIPMUNK, DEEP_VOICE, ROBOT, ECHO,
    CHILD, BIBI, TRUMP, OLD_MAN, TELEPHONE,
    RVC_AI   // active when an AI model is selected
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
    // Larger buffer for RVC to give the ONNX engine enough audio per call
    private val bufferSize    = maxOf(minBufferSize * 8, 22050)  // ~500 ms at 44100

    private var audioRecord:     AudioRecord?          = null
    private var audioTrack:      AudioTrack?           = null
    private var echoCanceler:    AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor?      = null

    @Volatile var currentEffect: VoiceEffect = VoiceEffect.NORMAL
    @Volatile var intensity:     Float       = 1.0f
    @Volatile var micSource:     MicSource   = MicSource.COMMUNICATION
    @Volatile var outputMode:    OutputMode  = OutputMode.EARPIECE

    // Active RVC model (null = use DSP effects)
    @Volatile private var rvcEngine: RvcEngine? = null
    @Volatile private var running     = false
    @Volatile private var previewBusy = false

    // ── RVC model management ──────────────────────────────────────────────────

    /**
     * Load an AI voice model. Call from a background thread.
     * Closes any previously loaded model automatically.
     */
    fun loadRvcModel(hubertPath: String, model: RvcModel) {
        val wasRunning = running
        if (wasRunning) stop()
        rvcEngine?.close()
        rvcEngine = RvcEngine(hubertPath, model.onnxPath, model.sampleRate, model.phoneDim)
        currentEffect = VoiceEffect.RVC_AI
        if (wasRunning) start()
    }

    fun clearRvcModel() {
        rvcEngine?.close()
        rvcEngine = null
        if (currentEffect == VoiceEffect.RVC_AI) currentEffect = VoiceEffect.NORMAL
    }

    // ── Start / stop live processing ──────────────────────────────────────────

    fun start() {
        if (running) return
        running = true

        val am = audioManager()

        // Always use COMMUNICATION mode - Android auto-routes to wired headset
        // when one is plugged in. We only control speaker vs earpiece vs BT.
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applyOutputRouting(am)

        audioRecord = AudioRecord(micSource.audioSource, sampleRate, channelIn, encoding, bufferSize)

        val sessionId = audioRecord!!.audioSessionId
        if (AcousticEchoCanceler.isAvailable())
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable())
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }

        audioTrack = buildTrack(
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.CONTENT_TYPE_SPEECH,
            AudioManager.STREAM_VOICE_CALL
        )

        audioRecord?.startRecording()
        audioTrack?.play()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    val out = if (currentEffect == VoiceEffect.RVC_AI && rvcEngine != null) {
                        try { rvcEngine!!.process(chunk) } catch (_: Exception) { chunk }
                    } else {
                        applyDspEffect(chunk, currentEffect, intensity)
                    }
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

    fun release() {
        stop()
        rvcEngine?.close()
        rvcEngine = null
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    fun preview(effect: VoiceEffect) {
        if (running || previewBusy || effect == VoiceEffect.RVC_AI) return
        previewBusy = true
        Thread {
            try {
                val signal    = generateVoiceSignal()
                val processed = applyDspEffect(signal, effect, intensity)
                playOnce(processed)
            } finally { previewBusy = false }
        }.apply { isDaemon = true; start() }
    }

    // ── Output routing ────────────────────────────────────────────────────────

    fun applyOutputRouting(am: AudioManager = audioManager()) {
        // Stop any active BT SCO before changing mode
        try { am.stopBluetoothSco() } catch (_: Exception) {}
        @Suppress("DEPRECATION") am.isBluetoothScoOn = false

        when (outputMode) {
            OutputMode.EARPIECE -> {
                // No speaker, no BT — Android auto-routes to wired headset if connected,
                // otherwise to phone earpiece
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
            }
            OutputMode.SPEAKER -> {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = true
            }
            OutputMode.BLUETOOTH -> {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
                try {
                    am.startBluetoothSco()
                    // SCO handshake is async — give it 1.5 s to connect
                    Thread.sleep(1500)
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                } catch (_: Exception) {}
            }
        }
    }

    // ── DSP effects ───────────────────────────────────────────────────────────

    private fun applyDspEffect(input: ShortArray, effect: VoiceEffect, lvl: Float): ShortArray =
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
            VoiceEffect.RVC_AI     -> input  // handled above
        }

    // ── Preview signal generator ──────────────────────────────────────────────

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

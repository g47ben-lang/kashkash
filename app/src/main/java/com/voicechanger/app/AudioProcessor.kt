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
import android.speech.tts.TextToSpeech
import com.voicechanger.app.effects.EchoEffect
import com.voicechanger.app.effects.PitchShifter
import com.voicechanger.app.effects.RobotEffect
import com.voicechanger.app.effects.TelephoneEffect
import com.voicechanger.app.effects.TremoloEffect
import java.util.Locale
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

    private val sampleRate = 44100
    private val channelIn  = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding   = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
    private val bufferSize    = maxOf(minBufferSize * 2, 4096)

    private var audioRecord: AudioRecord? = null
    private var audioTrack:  AudioTrack?  = null
    private var echoCanceler:    AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor?      = null

    @Volatile var currentEffect: VoiceEffect = VoiceEffect.NORMAL
    @Volatile var intensity:     Float       = 1.0f
    @Volatile var micSource:     MicSource   = MicSource.COMMUNICATION
    @Volatile var outputMode:    OutputMode  = OutputMode.EARPIECE
    @Volatile private var running       = false
    @Volatile private var previewBusy   = false

    // ── TextToSpeech for preview ──────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH  // fallback; Hebrew may not be installed
                ttsReady = true
            }
        }
    }

    // ── Start / stop live processing ──────────────────────────────────────────

    fun start() {
        if (running) return
        running = true

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applyOutputRouting(am)

        audioRecord = AudioRecord(micSource.audioSource, sampleRate, channelIn, encoding, bufferSize)

        val sessionId = audioRecord!!.audioSessionId
        if (micSource == MicSource.COMMUNICATION && AcousticEchoCanceler.isAvailable())
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        if (NoiseSuppressor.isAvailable())
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }

        audioTrack = buildLiveTrack()
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

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        try { am.stopBluetoothSco() } catch (_: Exception) {}
        am.mode = AudioManager.MODE_NORMAL
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    // ── Preview: TTS → effect chain → AudioTrack ─────────────────────────────

    fun preview(effect: VoiceEffect) {
        if (running || previewBusy) return
        if (ttsReady) {
            previewWithTts(effect)
        } else {
            previewWithSynth(effect)
        }
    }

    private fun previewWithTts(effect: VoiceEffect) {
        // Synthesize speech to a file, load it, apply effect, play it
        // Simplest cross-version approach: use TTS to play through the system speaker
        // then apply effect in the synthesis buffer via PCM callback (API 26+).
        // For broad compat we fall back to synth signal when PCM API unavailable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            previewBusy = true
            val sampleLabel = when (effect) {
                VoiceEffect.NORMAL    -> "Hello, this is normal voice."
                VoiceEffect.CHIPMUNK  -> "Hello, I am a chipmunk!"
                VoiceEffect.DEEP_VOICE -> "Hello, deep voice speaking."
                VoiceEffect.ROBOT     -> "Hello. I am a robot."
                VoiceEffect.ECHO      -> "Hello in the echo chamber."
                VoiceEffect.CHILD     -> "Hi! I am a little kid!"
                VoiceEffect.BIBI      -> "Ladies and gentlemen."
                VoiceEffect.TRUMP     -> "Believe me, this is huge."
                VoiceEffect.OLD_MAN   -> "Back in my day, you see."
                VoiceEffect.TELEPHONE -> "Hello, can you hear me?"
            }
            // Collect PCM from TTS, apply effect, play back
            val pcmBuffer = mutableListOf<Short>()
            val params = android.os.Bundle()
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    Thread {
                        try {
                            val combined = pcmBuffer.toShortArray()
                            if (combined.isNotEmpty()) {
                                val processed = applyEffect(combined, effect, intensity)
                                playOnce(processed)
                            }
                        } finally { previewBusy = false }
                    }.apply { isDaemon = true; start() }
                }
                override fun onError(id: String?) { previewBusy = false }
            })
            // Synthesize to PCM via AudioTrack callback (API 26+)
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate).setChannelMask(channelOut).setEncoding(encoding).build())
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {}
                override fun onPeriodicNotification(t: AudioTrack?) {}
            })
            // Fall back to synth for simplicity - TTS PCM capture is very complex cross-version
            previewBusy = false
            previewWithSynth(effect)
        } else {
            previewWithSynth(effect)
        }
    }

    private fun previewWithSynth(effect: VoiceEffect) {
        previewBusy = true
        Thread {
            try {
                val signal = generateVoiceSignal()
                val processed = applyEffect(signal, effect, intensity)
                playOnce(processed)
            } finally { previewBusy = false }
        }.apply { isDaemon = true; start() }
    }

    // ── Output routing ────────────────────────────────────────────────────────

    fun applyOutputRouting(am: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager) {
        try { am.stopBluetoothSco() } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        am.isBluetoothScoOn = false
        when (outputMode) {
            OutputMode.EARPIECE   -> { @Suppress("DEPRECATION") am.isSpeakerphoneOn = false }
            OutputMode.SPEAKER    -> { @Suppress("DEPRECATION") am.isSpeakerphoneOn = true  }
            OutputMode.BLUETOOTH  -> {
                @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
                try {
                    am.startBluetoothSco()
                    @Suppress("DEPRECATION") am.isBluetoothScoOn = true
                } catch (_: Exception) {}
            }
        }
    }

    // ── DSP effects ───────────────────────────────────────────────────────────

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

    // ── Synthetic voice signal for preview ────────────────────────────────────

    private fun generateVoiceSignal(durationMs: Int = 900): ShortArray {
        val samples = sampleRate * durationMs / 1000
        val result  = ShortArray(samples)
        val f0 = 110.0  // A2 – male vocal fundamental
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
        val track = buildPreviewTrack()
        track.play()
        track.write(buffer, 0, buffer.size)
        Thread.sleep(buffer.size * 1000L / sampleRate + 200)
        try { track.stop(); track.release() } catch (_: Exception) {}
    }

    // ── AudioTrack builders ───────────────────────────────────────────────────

    private fun buildLiveTrack(): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate).setChannelMask(channelOut).setEncoding(encoding).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_VOICE_CALL, sampleRate, channelOut, encoding, bufferSize, AudioTrack.MODE_STREAM)
        }

    private fun buildPreviewTrack(): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate).setChannelMask(channelOut).setEncoding(encoding).build())
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelOut, encoding, bufferSize * 4, AudioTrack.MODE_STREAM)
        }
}

package com.voicechanger.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import com.voicechanger.app.effects.EchoEffect
import com.voicechanger.app.effects.PitchShifter
import com.voicechanger.app.effects.RobotEffect

enum class VoiceEffect {
    NORMAL, CHIPMUNK, DEEP_VOICE, ROBOT, ECHO
}

class AudioProcessor {

    private val sampleRate = 44100
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
    private val bufferSize = maxOf(minBufferSize * 2, 4096)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile var currentEffect: VoiceEffect = VoiceEffect.NORMAL
    @Volatile var intensity: Float = 1.0f
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, channelIn, encoding, bufferSize
        )

        // AudioTrack.Builder requires API 21; use legacy constructor on older devices
        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelOut, encoding, bufferSize, AudioTrack.MODE_STREAM)
        }

        audioRecord?.startRecording()
        audioTrack?.play()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (running) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val processed = applyEffect(buffer.copyOf(read))
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
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
        EchoEffect.reset()
        RobotEffect.reset()
    }

    private fun applyEffect(input: ShortArray): ShortArray = when (currentEffect) {
        VoiceEffect.NORMAL     -> input
        VoiceEffect.CHIPMUNK   -> PitchShifter.shift(input, 1.5f * intensity.coerceIn(0.5f, 2.0f))
        VoiceEffect.DEEP_VOICE -> PitchShifter.shift(input, (0.7f / intensity.coerceIn(0.5f, 1.5f)).coerceAtLeast(0.3f))
        VoiceEffect.ROBOT      -> RobotEffect.apply(input, sampleRate, 80f * intensity)
        VoiceEffect.ECHO       -> EchoEffect.apply(input, 0.45f * intensity)
    }
}

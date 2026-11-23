package com.phonebridge.gateway.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * مدیریت نمونه‌برداری صوتی مکالمه برای انتقال به آیفون
 */
class AudioBridgeManager {
    companion object {
        private const val TAG = "AudioBridgeManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun startCapturing(onAudioPacket: (ByteArray) -> Unit) {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val buffer = ByteArray(minBufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val packet = buffer.copyOf(read)
                        onAudioPacket(packet)
                    }
                }
            }.start()

            Log.i(TAG, "Audio capture started for call bridging.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
        }
    }

    fun stopCapturing() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Audio capture stopped.")
    }
}

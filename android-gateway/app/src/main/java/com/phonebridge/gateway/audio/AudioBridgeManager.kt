package com.phonebridge.gateway.audio

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusSignal as ConcentusOpusSignal
import java.io.IOException
import kotlin.concurrent.thread

/**
 * مدیریت نمونه‌برداری صوتی مکالمه برای انتقال به آیفون با کدک Opus
 */
class AudioBridgeManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioBridgeManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val FRAME_SIZE = 320 // 20ms at 16kHz
        private const val BITRATE = 20000 // 20kbps
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var opusEncoder: OpusEncoder? = null
    private var opusDecoder: OpusDecoder? = null
    
    private var isStreaming = false
    var isMuted = false
        private set

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var l2capSocket: BluetoothSocket? = null

    /**
     * تنظیم وضعیت میوت میکروفون
     */
    fun setMute(muted: Boolean) {
        this.isMuted = muted
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.isMicrophoneMute = muted
        Log.i(TAG, "Audio bridge mute set to: $muted")
    }

    /**
     * شروع استریم دوطرفه صوت با استفاده از سوکت L2CAP
     */
    fun startStreaming(socket: BluetoothSocket) {
        if (isStreaming) {
            Log.w(TAG, "Audio streaming already active")
            return
        }

        l2capSocket = socket
        isStreaming = true

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = false

            initializeAudioComponents()
            Log.i(TAG, "Audio streaming started با Opus codec (16kHz mono, 20ms frames, 20kbps)")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در راه‌اندازی audio streaming", e)
            // تلاش مجدد یک بار
            retryAudioSetup()
        }
    }
    
    /**
     * راه‌اندازی کامپوننت‌های صوتی
     */
    private fun initializeAudioComponents() {
        // راه‌اندازی کدک‌های Opus
        opusEncoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
        opusEncoder?.setBitrate(BITRATE)
        opusEncoder?.setSignalType(ConcentusOpusSignal.OPUS_SIGNAL_VOICE)
        
        opusDecoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

        // راه‌اندازی AudioRecord برای ضبط صدا با تلاش از چند منبع صوتی سازگار
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE * 2 * 4)

        val sourcesToTry = arrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        for (source in sourcesToTry) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    Log.i(TAG, "AudioRecord initialized with source: $source")
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed initializing AudioRecord with source $source", e)
            }
        }

        // راه‌اندازی AudioTrack برای پخش صدای دریافتی از آیفون
        val minPlaybackBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_OUT, AUDIO_FORMAT)
        audioTrack = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT,
            maxOf(minPlaybackBuffer, FRAME_SIZE * 2 * 4),
            AudioTrack.MODE_STREAM
        )

        audioRecord?.startRecording()
        audioTrack?.play()

        // Thread برای ضبط، کدگذاری و ارسال
        captureThread = thread(name = "Audio-Capture-Encode-Send") {
            captureEncodeAndSend()
        }

        // Thread برای دریافت، دیکد و پخش
        playbackThread = thread(name = "Audio-Receive-Decode-Play") {
            receiveDecodeAndPlay()
        }
    }
    
    /**
     * تلاش مجدد برای راه‌اندازی audio
     */
    private fun retryAudioSetup() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isStreaming) return@postDelayed
            
            Log.i(TAG, "Retrying audio setup...")
            try {
                initializeAudioComponents()
                Log.i(TAG, "✅ Audio streaming started on retry")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start audio streaming after retry", e)
                stopStreaming()
            }
        }, 1000)
    }

    /**
     * حلقه ضبط صدا، کدگذاری با Opus و ارسال از طریق L2CAP
     */
    private fun captureEncodeAndSend() {
        val pcmBuffer = ShortArray(FRAME_SIZE)
        val opusPacket = ByteArray(1024) // حداکثر اندازه بسته Opus

        try {
            val outputStream = l2capSocket?.outputStream

            while (isStreaming && outputStream != null) {
                if (isMuted) {
                    Thread.sleep(20)
                    continue
                }

                val samplesRead = audioRecord?.read(pcmBuffer, 0, FRAME_SIZE) ?: 0

                if (samplesRead == FRAME_SIZE) {
                    // کدگذاری با Opus
                    val encodedBytes = opusEncoder?.encode(pcmBuffer, 0, FRAME_SIZE, opusPacket, 0, opusPacket.size) ?: 0

                    if (encodedBytes > 0) {
                        // ارسال اندازه بسته (2 بایت) + داده
                        val sizeBytes = ByteArray(2)
                        sizeBytes[0] = (encodedBytes and 0xFF).toByte()
                        sizeBytes[1] = ((encodedBytes shr 8) and 0xFF).toByte()

                        outputStream.write(sizeBytes)
                        outputStream.write(opusPacket, 0, encodedBytes)
                        outputStream.flush()

                        Log.v(TAG, "Opus encode/send: $encodedBytes bytes")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "خطا در ارسال audio data", e)
            stopStreaming()
        } catch (e: Exception) {
            Log.e(TAG, "خطا در encode audio", e)
        }
    }

    /**
     * حلقه دریافت، دیکد Opus و پخش صدا
     */
    private fun receiveDecodeAndPlay() {
        val opusPacket = ByteArray(1024)
        val pcmBuffer = ShortArray(FRAME_SIZE)

        try {
            val inputStream = l2capSocket?.inputStream

            while (isStreaming && inputStream != null) {
                // خواندن اندازه بسته (2 بایت)
                val sizeBytes = ByteArray(2)
                val sizeRead = inputStream.read(sizeBytes)

                if (sizeRead == 2) {
                    val packetSize = (sizeBytes[0].toInt() and 0xFF) or ((sizeBytes[1].toInt() and 0xFF) shl 8)

                    if (packetSize in 1..opusPacket.size) {
                        // خواندن داده بسته Opus
                        var totalRead = 0
                        while (totalRead < packetSize) {
                            val bytesRead = inputStream.read(opusPacket, totalRead, packetSize - totalRead)
                            if (bytesRead < 0) break
                            totalRead += bytesRead
                        }

                        if (totalRead == packetSize) {
                            // دیکد Opus به PCM
                            val decodedSamples = opusDecoder?.decode(opusPacket, 0, packetSize, pcmBuffer, 0, FRAME_SIZE, false) ?: 0

                            if (decodedSamples > 0) {
                                // پخش از طریق AudioTrack
                                audioTrack?.write(pcmBuffer, 0, decodedSamples)
                                Log.v(TAG, "Opus decode/play: $decodedSamples samples")
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "خطا در دریافت audio data", e)
            stopStreaming()
        } catch (e: Exception) {
            Log.e(TAG, "خطا در decode audio", e)
        }
    }

    /**
     * توقف استریم صوت و آزادسازی منابع
     */
    fun stopStreaming() {
        if (!isStreaming) return

        isStreaming = false
        l2capSocket = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.mode = AudioManager.MODE_NORMAL

        captureThread?.interrupt()
        playbackThread?.interrupt()
        captureThread = null
        playbackThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            opusEncoder = null
            opusDecoder = null

            Log.i(TAG, "Audio streaming stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در توقف audio streaming", e)
        }
    }

    /**
     * تغییر مسیر صدا بین اسپیکر و گوشی
     */
    fun setAudioRouting(useSpeaker: Boolean) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isSpeakerphoneOn = useSpeaker
            Log.i(TAG, "Audio routing changed to: ${if (useSpeaker) "Speaker" else "Earpiece"}")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در تغییر مسیر صوت", e)
        }
    }

    // سازگاری با کد قدیمی
    @Deprecated("Use startStreaming instead")
    fun startCapturing(onAudioPacket: (ByteArray) -> Unit) {
        Log.w(TAG, "startCapturing deprecated - use startStreaming با L2CAP socket")
    }

    @Deprecated("Use stopStreaming instead")
    fun stopCapturing() {
        stopStreaming()
    }
}

// تعریف ثابت‌های Opus که در کتابخانه concentus نیستند
object OpusSignal {
    const val OPUS_SIGNAL_VOICE = 3001
}

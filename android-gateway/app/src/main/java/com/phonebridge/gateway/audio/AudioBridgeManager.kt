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
        
        // Adaptive bitrate settings
        private const val MIN_BITRATE = 12000  // 12kbps
        private const val MAX_BITRATE = 32000  // 32kbps
        private const val DEFAULT_BITRATE = 20000  // 20kbps
        
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
    
    // Adaptive bitrate state
    private var currentBitrate: Int = DEFAULT_BITRATE
    private var bitrateAdjustmentHandler: android.os.Handler? = null
    private var bitrateAdjustmentRunnable: Runnable? = null
    
    // Audio processing for echo cancellation and noise suppression
    private var audioProcessor: AudioProcessing? = null
    private var referenceAudioBuffer: ShortArray = ShortArray(0) // برای echo cancellation

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
     * هنگام تماس فعال، صدا را از خط مخابراتی دریافت و به آیفون ارسال می‌کند
     * و صدای آیفون را دریافت و به خط مخابراتی تزریق می‌کند
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

            // مرحله ۱: تنظیم حالت صوت به MODE_IN_CALL برای دسترسی به خط مخابراتی
            audioManager?.mode = AudioManager.MODE_IN_CALL


            // مرحله ۳: اسپیکر را فعال کنیم تا AudioRecord بتواند صدای طرف مقابل را بگیرد
            audioManager?.isSpeakerphoneOn = true
            
            // ✅ مرحله ۴: تنظیم volume برای کیفیت بهتر
            audioManager?.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )

            Log.i(TAG, "✅ Audio mode: IN_CALL + Speakerphone + Volume optimized")

            initializeAudioComponents()
            Log.i(TAG, "Audio streaming started با Opus codec (16kHz mono, 20ms frames, 20kbps)")
        } catch (e: Exception) {
            Log.e(TAG, "خطا در راه‌اندازی audio streaming", e)
            retryAudioSetup()
        }
    }
    
    /**
     * راه‌اندازی کامپوننت‌های صوتی
     */
    private fun initializeAudioComponents() {
        // راه‌اندازی کدک‌های Opus
        opusEncoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
        opusEncoder?.setBitrate(currentBitrate)
        opusEncoder?.setSignalType(ConcentusOpusSignal.OPUS_SIGNAL_VOICE)
        
        // ✅ بهینه‌سازی اضافی Opus برای VoIP
        opusEncoder?.setComplexity(5)  // 0-10, 5 = متوسط (CPU friendly, کیفیت خوب)
        opusEncoder?.setPacketLossPerc(10)  // تحمل 10% packet loss
        
        Log.i(TAG, "Opus encoder initialized: bitrate=$currentBitrate bps, complexity=5, loss_tolerance=10%")
        
        opusDecoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

        // راه‌اندازی AudioRecord برای ضبط صدا
        // ✅ FIX: تغییر ترتیب برای phone bridge - باید میکروفون capture شود نه فقط downlink
        // VOICE_COMMUNICATION: بهترین گزینه برای VoIP - میکروفون با echo cancellation built-in
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE * 2 * 4)

        val sourcesToTry = arrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // ✅ اولویت اول: میکروفون + echo cancellation
            MediaRecorder.AudioSource.MIC,                 // ✅ Fallback: میکروفون خام
            MediaRecorder.AudioSource.DEFAULT              // ✅ آخرین گزینه
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
        
        // راه‌اندازی audio processor
        audioProcessor = AudioProcessing(frameSize = FRAME_SIZE, sampleRate = SAMPLE_RATE)
        Log.i(TAG, "Audio processor initialized with Echo Cancellation + Noise Suppression")
        
        // شروع adaptive bitrate adjustment
        startAdaptiveBitrateAdjustment()

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
     * شروع تنظیم خودکار bitrate (در Android فعلاً ثابت می‌مونه، iOS کنترل می‌کنه)
     */
    private fun startAdaptiveBitrateAdjustment() {
        // Note: در حال حاضر bitrate از سمت iOS کنترل می‌شود
        // Android فقط در شرایط خاص (مثل battery low) bitrate رو کاهش می‌ده
        
        bitrateAdjustmentHandler = android.os.Handler(android.os.Looper.getMainLooper())
        bitrateAdjustmentRunnable = object : Runnable {
            override fun run() {
                adjustBitrateBasedOnBatteryLevel()
                bitrateAdjustmentHandler?.postDelayed(this, 5000) // هر 5 ثانیه
            }
        }
        bitrateAdjustmentHandler?.postDelayed(bitrateAdjustmentRunnable!!, 5000)
        
        Log.i(TAG, "Adaptive bitrate adjustment started ($MIN_BITRATE-$MAX_BITRATE bps)")
    }
    
    /**
     * تنظیم bitrate بر اساس سطح باتری (صرفه‌جویی در مصرف)
     */
    private fun adjustBitrateBasedOnBatteryLevel() {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            val batteryLevel = batteryManager?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            
            val targetBitrate = when {
                batteryLevel < 15 -> MIN_BITRATE  // کاهش به 12kbps
                batteryLevel < 30 -> 16000        // کاهش به 16kbps
                else -> DEFAULT_BITRATE           // 20kbps معمولی
            }
            
            if (targetBitrate != currentBitrate) {
                currentBitrate = targetBitrate
                opusEncoder?.setBitrate(currentBitrate)
                Log.i(TAG, "🔋 Bitrate adjusted to ${currentBitrate/1000}kbps (Battery: $batteryLevel%)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error adjusting bitrate", e)
        }
    }
    
    /**
     * دریافت bitrate فعلی
     */
    fun getCurrentBitrate(): Int {
        return currentBitrate
    }
    
    /**
     * تلاش مجدد برای راه‌اندازی audio
     */
    private fun retryAudioSetup() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isStreaming) {
                Log.d(TAG, "Streaming already stopped, skipping retry")
                return@postDelayed
            }
            
            Log.i(TAG, "🔄 Retrying audio setup after failure...")
            try {
                initializeAudioComponents()
                Log.i(TAG, "✅ Audio streaming recovered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to recover audio streaming", e)
                stopStreaming()
                
                // اطلاع‌رسانی به iPhone که audio streaming متوقف شد
                val errorPayload = org.json.JSONObject().apply {
                    put("type", "AUDIO_ERROR")
                    put("message", "Failed to start audio streaming")
                }
                BLEPeripheralServer.instance?.sendNotification(errorPayload.toString())
            }
        }, 1000)
    }

    /**
     * حلقه ضبط صدا، اعمال audio processing، کدگذاری با Opus و ارسال از طریق L2CAP
     */
    private fun captureEncodeAndSend() {
        val pcmBuffer = ShortArray(FRAME_SIZE)
        val opusPacket = ByteArray(1024) // حداکثر اندازه بسته Opus
        
        var frameCount = 0

        try {
            val outputStream = l2capSocket?.outputStream

            while (isStreaming && outputStream != null) {
                if (isMuted) {
                    Thread.sleep(20)
                    continue
                }

                val samplesRead = audioRecord?.read(pcmBuffer, 0, FRAME_SIZE) ?: 0

                if (samplesRead == FRAME_SIZE) {
                    // اعمال audio processing (Echo Cancellation + Noise Suppression)
                    audioProcessor?.let { processor ->
                        val reference = if (referenceAudioBuffer.isNotEmpty()) referenceAudioBuffer else null
                        processor.processAudio(pcmBuffer, reference)
                        
                        // Log stats هر 50 فریم (یک بار در ثانیه)
                        frameCount++
                        if (frameCount % 50 == 0) {
                            val stats = processor.getStats()
                            if (stats.isVoiceActive) {
                                Log.d(TAG, "🎤 Voice detected - Noise: ${String.format("%.1f", stats.noiseFloorDB)}dB, Signal: ${String.format("%.1f", stats.averageEnergyDB)}dB")
                            }
                        }
                    }
                    
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
                                // ذخیره reference signal برای echo cancellation
                                referenceAudioBuffer = pcmBuffer.copyOf(decodedSamples)
                                
                                // پخش از طریق AudioTrack
                                audioTrack?.write(pcmBuffer, 0, decodedSamples)
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
        
        // توقف adaptive bitrate adjustment
        bitrateAdjustmentRunnable?.let { bitrateAdjustmentHandler?.removeCallbacks(it) }
        bitrateAdjustmentHandler = null
        bitrateAdjustmentRunnable = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        audioManager?.isSpeakerphoneOn = false
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
            
            audioProcessor = null
            referenceAudioBuffer = ShortArray(0)

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

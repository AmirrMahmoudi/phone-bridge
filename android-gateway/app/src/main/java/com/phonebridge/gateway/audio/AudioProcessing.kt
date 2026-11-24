package com.phonebridge.gateway.audio

import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * پردازش سیگنال صوتی برای بهبود کیفیت
 * شامل: Echo Cancellation و Noise Suppression
 * 
 * Note: این implementation ساده است برای شروع.
 * برای production، استفاده از WebRTC AEC3 و APM توصیه می‌شود.
 */
class AudioProcessing(
    private val frameSize: Int = 320,
    private val sampleRate: Int = 16000
) {
    
    companion object {
        private const val TAG = "AudioProcessing"
        private const val ECHO_BUFFER_SIZE = 3200 // 200ms at 16kHz
        private const val ENERGY_HISTORY_SIZE = 10
    }
    
    // Echo Cancellation state
    private val echoBuffer = ArrayDeque<Short>(ECHO_BUFFER_SIZE)
    
    // Noise Suppression state
    private var noiseFloor: Float = 0.0f
    private val noiseFloorAlpha: Float = 0.95f // smoothing factor
    
    // Simple VAD (Voice Activity Detection)
    private var vadThreshold: Float = 0.01f
    private val recentEnergy = ArrayDeque<Float>(ENERGY_HISTORY_SIZE)
    
    init {
        // Initialize energy history
        repeat(ENERGY_HISTORY_SIZE) {
            recentEnergy.addLast(0.0f)
        }
    }
    
    // MARK: - Echo Cancellation
    
    /**
     * Simple echo cancellation using adaptive filtering
     * 
     * این یک implementation ساده است. برای production:
     * - استفاده از WebRTC AEC3 (Acoustic Echo Cancellation v3)
     * - یا کتابخانه Speex AEC
     */
    fun cancelEcho(inputBuffer: ShortArray, referenceSignal: ShortArray?) {
        if (referenceSignal == null || referenceSignal.isEmpty()) return
        
        // اضافه کردن reference signal به echo buffer
        referenceSignal.forEach { echoBuffer.addLast(it) }
        while (echoBuffer.size > ECHO_BUFFER_SIZE) {
            echoBuffer.removeFirst()
        }
        
        // برآورد echo با استفاده از همبستگی ساده
        if (echoBuffer.size >= frameSize) {
            val recentEcho = echoBuffer.takeLast(frameSize).toShortArray()
            
            // محاسبه ضریب همبستگی
            val correlation = calculateCorrelation(inputBuffer, recentEcho)
            
            // اگر همبستگی بالا بود (احتمال echo)، کاهش بده
            if (correlation > 0.5) {
                val suppressionFactor = 1.0f - correlation * 0.7f
                for (i in inputBuffer.indices) {
                    inputBuffer[i] = (inputBuffer[i] * suppressionFactor).toInt().toShort()
                }
            }
        }
    }
    
    /**
     * محاسبه ضریب همبستگی بین دو سیگنال
     */
    private fun calculateCorrelation(signal1: ShortArray, signal2: ShortArray): Float {
        if (signal1.size != signal2.size) return 0.0f
        
        var correlation = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        for (i in signal1.indices) {
            val s1 = signal1[i].toDouble()
            val s2 = signal2[i].toDouble()
            correlation += s1 * s2
            norm1 += s1 * s1
            norm2 += s2 * s2
        }
        
        val denominator = sqrt(norm1 * norm2)
        return if (denominator > 0) {
            abs((correlation / denominator).toFloat())
        } else {
            0.0f
        }
    }
    
    // MARK: - Noise Suppression
    
    /**
     * Basic noise suppression using spectral subtraction
     * 
     * این یک implementation ساده است. برای production:
     * - استفاده از RNNoise (Deep Learning-based noise suppression)
     * - یا WebRTC NS (Noise Suppression module)
     */
    fun suppressNoise(buffer: ShortArray) {
        // محاسبه energy فریم
        val energy = calculateEnergy(buffer)
        
        // به‌روزرسانی noise floor با exponential smoothing
        updateNoiseFloor(energy)
        
        // Voice Activity Detection
        val isVoice = detectVoice(energy)
        
        if (!isVoice) {
            // اگر صدا نیست، noise suppression شدیدتر
            applyNoiseGate(buffer, vadThreshold * 2.0f)
        } else {
            // اگر صدا است، فقط noise floor رو حذف کن
            subtractNoiseFloor(buffer)
        }
    }
    
    /**
     * محاسبه energy یک فریم صوتی
     */
    private fun calculateEnergy(buffer: ShortArray): Float {
        var energy = 0.0
        for (sample in buffer) {
            val normalized = sample.toFloat() / 32768.0f
            energy += normalized * normalized
        }
        return (energy / buffer.size).toFloat()
    }
    
    /**
     * به‌روزرسانی برآورد noise floor
     */
    private fun updateNoiseFloor(currentEnergy: Float) {
        noiseFloor = if (noiseFloor == 0.0f) {
            currentEnergy
        } else {
            noiseFloorAlpha * noiseFloor + (1.0f - noiseFloorAlpha) * currentEnergy
        }
    }
    
    /**
     * Voice Activity Detection ساده
     */
    private fun detectVoice(energy: Float): Boolean {
        // به‌روزرسانی تاریخچه energy
        recentEnergy.removeFirst()
        recentEnergy.addLast(energy)
        
        // محاسبه میانگین energy اخیر
        val avgEnergy = recentEnergy.sum() / ENERGY_HISTORY_SIZE
        
        // اگر energy فعلی بیش از 2 برابر میانگین باشد، احتمالاً صدا است
        return energy > avgEnergy * 2.0f && energy > noiseFloor * 3.0f
    }
    
    /**
     * اعمال noise gate (حذف سیگنال‌های زیر threshold)
     */
    private fun applyNoiseGate(buffer: ShortArray, threshold: Float) {
        val thresholdInt = (threshold * 32768).toInt()
        for (i in buffer.indices) {
            if (abs(buffer[i].toInt()) < thresholdInt) {
                buffer[i] = 0
            }
        }
    }
    
    /**
     * کم کردن noise floor از سیگنال
     */
    private fun subtractNoiseFloor(buffer: ShortArray) {
        val noiseThreshold = sqrt(noiseFloor) * 32768
        
        for (i in buffer.indices) {
            val sign = if (buffer[i] >= 0) 1 else -1
            val magnitude = abs(buffer[i].toInt())
            
            buffer[i] = if (magnitude > noiseThreshold) {
                (sign * (magnitude - noiseThreshold * 0.5)).toInt().toShort()
            } else {
                (buffer[i] * 0.5).toInt().toShort()
            }
        }
    }
    
    // MARK: - Combined Processing
    
    /**
     * اعمال تمام پردازش‌ها به ترتیب
     * 1. Echo Cancellation
     * 2. Noise Suppression
     */
    fun processAudio(inputBuffer: ShortArray, referenceSignal: ShortArray? = null) {
        // 1. Echo Cancellation
        cancelEcho(inputBuffer, referenceSignal)
        
        // 2. Noise Suppression
        suppressNoise(inputBuffer)
    }
    
    // MARK: - Statistics
    
    /**
     * دریافت آمار فعلی برای monitoring
     */
    fun getStats(): AudioProcessingStats {
        val avgEnergy = recentEnergy.sum() / ENERGY_HISTORY_SIZE
        return AudioProcessingStats(
            noiseFloor = noiseFloor,
            averageEnergy = avgEnergy,
            isVoiceActive = detectVoice(avgEnergy)
        )
    }
}

// MARK: - Statistics Data Class

data class AudioProcessingStats(
    val noiseFloor: Float,
    val averageEnergy: Float,
    val isVoiceActive: Boolean
) {
    val noiseFloorDB: Float
        get() = 10 * log10(max(noiseFloor, 1e-10f))
    
    val averageEnergyDB: Float
        get() = 10 * log10(max(averageEnergy, 1e-10f))
}

// MARK: - Future Integration Guide

/**
 * راهنمای Integration با WebRTC APM:
 * 
 * ## WebRTC Audio Processing Module (APM)
 * 
 * 1. اضافه کردن WebRTC library:
 *    ```gradle
 *    implementation 'org.webrtc:google-webrtc:1.0.+'
 *    ```
 * 
 * 2. استفاده:
 *    ```kotlin
 *    import org.webrtc.audio.AudioDeviceModule
 *    import org.webrtc.audio.JavaAudioDeviceModule
 *    
 *    val audioDeviceModule = JavaAudioDeviceModule.builder(context)
 *        .setUseHardwareAcousticEchoCanceler(true)
 *        .setUseHardwareNoiseSuppressor(true)
 *        .setAudioRecordErrorCallback(...)
 *        .setAudioTrackErrorCallback(...)
 *        .createAudioDeviceModule()
 *    ```
 * 
 * ## مزایای WebRTC APM:
 * - Hardware-accelerated AEC در صورت وجود
 * - بهترین noise suppression موجود (NS + AGC)
 * - Optimized برای real-time processing
 * - Battle-tested در میلیون‌ها تماس واقعی
 * 
 * ## RNNoise Alternative:
 * - Clone: https://github.com/xiph/rnnoise
 * - Build Android JNI wrapper
 * - 70-90% بهبود در noise suppression با Deep Learning
 */

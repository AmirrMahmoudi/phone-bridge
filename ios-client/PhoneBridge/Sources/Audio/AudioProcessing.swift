import Foundation
import Accelerate

/**
 * پردازش سیگنال صوتی برای بهبود کیفیت
 * شامل: Echo Cancellation و Noise Suppression
 * 
 * Note: این implementation ساده است برای شروع.
 * برای production، استفاده از WebRTC AEC3 و RNNoise توصیه می‌شود.
 */
public class AudioProcessing {
    
    // MARK: - Properties
    
    private let frameSize: Int
    private let sampleRate: Int
    
    // Echo Cancellation state
    private var echoBuffer: [Float] = []
    private let echoBufferSize: Int = 320 * 10 // 200ms at 16kHz
    private var echoEstimate: [Float] = []
    
    // Noise Suppression state
    private var noiseFloor: Float = 0.0
    private var noiseFloorAlpha: Float = 0.95 // smoothing factor
    private let noiseSuppressionThresholdDB: Float = -40.0 // -40dB
    
    // Simple VAD (Voice Activity Detection)
    private var vadThreshold: Float = 0.01
    private var recentEnergy: [Float] = []
    private let energyHistorySize: Int = 10
    
    // MARK: - Initialization
    
    public init(frameSize: Int = 320, sampleRate: Int = 16000) {
        self.frameSize = frameSize
        self.sampleRate = sampleRate
        self.echoEstimate = [Float](repeating: 0.0, count: frameSize)
        self.recentEnergy = [Float](repeating: 0.0, count: energyHistorySize)
    }
    
    // MARK: - Echo Cancellation
    
    /**
     * Simple echo cancellation using adaptive filtering
     * 
     * این یک implementation ساده است. برای production:
     * - استفاده از WebRTC AEC3 (Acoustic Echo Cancellation v3)
     * - یا کتابخانه Speex AEC
     */
    public func cancelEcho(inputBuffer: inout [Float], referenceSignal: [Float]) {
        guard inputBuffer.count == frameSize else { return }
        
        // اضافه کردن reference signal به echo buffer
        echoBuffer.append(contentsOf: referenceSignal)
        if echoBuffer.count > echoBufferSize {
            echoBuffer.removeFirst(echoBuffer.count - echoBufferSize)
        }
        
        // برآورد echo با استفاده از همبستگی ساده
        if echoBuffer.count >= frameSize {
            let recentEcho = Array(echoBuffer.suffix(frameSize))
            
            // محاسبه ضریب همبستگی
            let correlation = calculateCorrelation(inputBuffer, recentEcho)
            
            // اگر همبستگی بالا بود (احتمال echo)، کاهش بده
            if correlation > 0.5 {
                for i in 0..<frameSize {
                    let suppressionFactor = Float(1.0 - correlation * 0.7)
                    inputBuffer[i] *= suppressionFactor
                }
            }
        }
    }
    
    /**
     * محاسبه ضریب همبستگی بین دو سیگنال
     */
    private func calculateCorrelation(_ signal1: [Float], _ signal2: [Float]) -> Float {
        guard signal1.count == signal2.count else { return 0.0 }
        
        var correlation: Float = 0.0
        var norm1: Float = 0.0
        var norm2: Float = 0.0
        
        vDSP_dotpr(signal1, 1, signal2, 1, &correlation, vDSP_Length(signal1.count))
        vDSP_svesq(signal1, 1, &norm1, vDSP_Length(signal1.count))
        vDSP_svesq(signal2, 1, &norm2, vDSP_Length(signal2.count))
        
        let denominator = sqrt(norm1 * norm2)
        return denominator > 0 ? abs(correlation / denominator) : 0.0
    }
    
    // MARK: - Noise Suppression
    
    /**
     * Basic noise suppression using spectral subtraction
     * 
     * این یک implementation ساده است. برای production:
     * - استفاده از RNNoise (Deep Learning-based noise suppression)
     * - یا WebRTC NS (Noise Suppression module)
     */
    public func suppressNoise(buffer: inout [Float]) {
        guard buffer.count == frameSize else { return }
        
        // محاسبه energy فریم
        let energy = calculateEnergy(buffer)
        
        // به‌روزرسانی noise floor با exponential smoothing
        updateNoiseFloor(energy)
        
        // Voice Activity Detection
        let isVoice = detectVoice(energy: energy)
        
        if !isVoice {
            // اگر صدا نیست، noise suppression شدیدتر
            applyNoiseGate(buffer: &buffer, threshold: vadThreshold * 2.0)
        } else {
            // اگر صدا است، فقط noise floor رو حذف کن
            subtractNoiseFloor(buffer: &buffer)
        }
    }
    
    /**
     * محاسبه energy یک فریم صوتی
     */
    private func calculateEnergy(_ buffer: [Float]) -> Float {
        var energy: Float = 0.0
        vDSP_svesq(buffer, 1, &energy, vDSP_Length(buffer.count))
        return energy / Float(buffer.count)
    }
    
    /**
     * به‌روزرسانی برآورد noise floor
     */
    private func updateNoiseFloor(_ currentEnergy: Float) {
        if noiseFloor == 0.0 {
            noiseFloor = currentEnergy
        } else {
            noiseFloor = noiseFloorAlpha * noiseFloor + (1.0 - noiseFloorAlpha) * currentEnergy
        }
    }
    
    /**
     * Voice Activity Detection ساده
     */
    private func detectVoice(energy: Float) -> Bool {
        // به‌روزرسانی تاریخچه energy
        recentEnergy.removeFirst()
        recentEnergy.append(energy)
        
        // محاسبه میانگین energy اخیر
        let avgEnergy = recentEnergy.reduce(0.0, +) / Float(energyHistorySize)
        
        // اگر energy فعلی بیش از 2 برابر میانگین باشد، احتمالاً صدا است
        return energy > avgEnergy * 2.0 && energy > noiseFloor * 3.0
    }
    
    /**
     * اعمال noise gate (حذف سیگنال‌های زیر threshold)
     */
    private func applyNoiseGate(buffer: inout [Float], threshold: Float) {
        for i in 0..<buffer.count {
            if abs(buffer[i]) < threshold {
                buffer[i] = 0.0
            }
        }
    }
    
    /**
     * کم کردن noise floor از سیگنال
     */
    private func subtractNoiseFloor(buffer: inout [Float]) {
        let noiseThreshold = sqrt(noiseFloor)
        
        for i in 0..<buffer.count {
            let sign = buffer[i] >= 0 ? Float(1.0) : Float(-1.0)
            let magnitude = abs(buffer[i])
            
            if magnitude > noiseThreshold {
                buffer[i] = sign * (magnitude - noiseThreshold * 0.5)
            } else {
                buffer[i] *= 0.5 // کاهش به جای حذف کامل
            }
        }
    }
    
    // MARK: - Combined Processing
    
    /**
     * اعمال تمام پردازش‌ها به ترتیب
     * 1. Echo Cancellation
     * 2. Noise Suppression
     */
    public func processAudio(inputBuffer: inout [Float], referenceSignal: [Float]? = nil) {
        // 1. Echo Cancellation
        if let reference = referenceSignal {
            cancelEcho(inputBuffer: &inputBuffer, referenceSignal: reference)
        }
        
        // 2. Noise Suppression
        suppressNoise(buffer: &inputBuffer)
    }
    
    // MARK: - Statistics
    
    /**
     * دریافت آمار فعلی برای monitoring
     */
    public func getStats() -> AudioProcessingStats {
        let avgEnergy = recentEnergy.reduce(0.0, +) / Float(energyHistorySize)
        return AudioProcessingStats(
            noiseFloor: noiseFloor,
            averageEnergy: avgEnergy,
            isVoiceActive: detectVoice(energy: avgEnergy)
        )
    }
}

// MARK: - Statistics Structure

public struct AudioProcessingStats {
    public let noiseFloor: Float
    public let averageEnergy: Float
    public let isVoiceActive: Bool
    
    public var noiseFloorDB: Float {
        return 10 * log10(max(noiseFloor, 1e-10))
    }
    
    public var averageEnergyDB: Float {
        return 10 * log10(max(averageEnergy, 1e-10))
    }
}

// MARK: - Future Integration Guide

/**
 * راهنمای Integration با WebRTC AEC3 و RNNoise:
 * 
 * ## WebRTC AEC3 (Acoustic Echo Cancellation v3)
 * 
 * 1. اضافه کردن WebRTC framework:
 *    - از طریق CocoaPods: `pod 'GoogleWebRTC'`
 *    - یا compile کردن از source: https://webrtc.org
 * 
 * 2. استفاده:
 *    ```swift
 *    import WebRTC
 *    
 *    let aec = RTCAudioSessionConfiguration.webRTC()
 *    aec.category = AVAudioSession.Category.playAndRecord.rawValue
 *    aec.mode = AVAudioSession.Mode.voiceChat.rawValue
 *    aec.categoryOptions = [.allowBluetooth, .defaultToSpeaker]
 *    ```
 * 
 * ## RNNoise (Deep Learning Noise Suppression)
 * 
 * 1. اضافه کردن RNNoise library:
 *    - Clone: https://github.com/xiph/rnnoise
 *    - Build: `./autogen.sh && ./configure && make`
 *    - اضافه کردن librnnoise.a به Xcode project
 * 
 * 2. استفاده:
 *    ```c
 *    #include "rnnoise.h"
 *    
 *    DenoiseState *st = rnnoise_create(NULL);
 *    rnnoise_process_frame(st, output, input);
 *    rnnoise_destroy(st);
 *    ```
 * 
 * ## مزایای Libraries حرفه‌ای:
 * - WebRTC AEC3: 60-80% بهبود در echo cancellation
 * - RNNoise: 70-90% بهبود در noise suppression
 * - هر دو: Optimized برای real-time processing
 */

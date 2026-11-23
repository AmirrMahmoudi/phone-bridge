import Foundation
import AVFoundation

/// مدیریت کانال و نشست صوتی برای مکالمه بدون تاخیر
public class AudioStreamManager: NSObject {
    public static let shared = AudioStreamManager()

    private var audioEngine: AVAudioEngine?
    private var isRunning = false

    override init() {
        super.init()
    }

    public func startAudioSession() {
        guard !isRunning else { return }
        
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
            try audioSession.setPreferredSampleRate(16000.0)
            try audioSession.setPreferredIOBufferDuration(0.02) // 20ms بافر صوتی
            try audioSession.setActive(true)
            
            self.isRunning = true
            print("[Audio] Audio session started successfully in voiceChat mode.")
        } catch {
            print("[Audio] Failed to activate audio session: \(error)")
        }
    }

    public func stopAudioSession() {
        guard isRunning else { return }
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            self.isRunning = false
            print("[Audio] Audio session stopped.")
        } catch {
            print("[Audio] Error stopping audio session: \(error)")
        }
    }
}

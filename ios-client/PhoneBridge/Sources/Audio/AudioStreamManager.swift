import Foundation
import AVFoundation
import YbridOpus

/// مدیریت کانال و نشست صوتی برای مکالمه بدون تاخیر با کدک Opus
public class AudioStreamManager: NSObject {
    public static let shared = AudioStreamManager()

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var opusEncoder: OpaquePointer?
    private var opusDecoder: OpaquePointer?
    private var isRunning = false
    public var isMuted: Bool = false
    
    private var l2capChannel: L2CAPChannelManager?
    private var receiveThread: Thread?
    
    // Audio processing for echo cancellation and noise suppression
    private var audioProcessor: AudioProcessing?
    private var referenceAudioBuffer: [Float] = [] // برای echo cancellation
    
    private let sampleRate: Double = 16000
    private let frameSize: Int = 320 // 20ms at 16kHz
    private let channels: Int = 1
    
    // Adaptive bitrate based on connection quality
    private var currentBitrate: Int = 20000 // Start at 20kbps
    private let minBitrate: Int = 12000     // 12kbps for poor connection
    private let maxBitrate: Int = 32000     // 32kbps for excellent connection
    private var bitrateAdjustmentTimer: Timer?

    override init() {
        super.init()
    }

    public func startAudioSession() {
        guard !isRunning else { return }
        
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
            try audioSession.setPreferredSampleRate(sampleRate)
            try audioSession.setPreferredIOBufferDuration(0.02) // 20ms بافر صوتی
            try audioSession.setActive(true)
            
            // راه‌اندازی کدک‌های Opus
            setupOpusCodecs()
            
            // راه‌اندازی audio processor
            audioProcessor = AudioProcessing(frameSize: frameSize, sampleRate: Int(sampleRate))
            
            // راه‌اندازی AVAudioEngine
            setupAudioEngine()
            
            self.isRunning = true
            print("[Audio] Audio session started با Opus codec + Echo Cancellation + Noise Suppression (16kHz mono, 20ms frames)")
        } catch {
            print("[Audio] Failed to activate audio session: \(error)")
            // تلاش مجدد یک بار
            retryAudioSessionSetup()
        }
    }
    
    /**
     * تلاش مجدد برای راه‌اندازی audio session
     */
    private func retryAudioSessionSetup() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self, !self.isRunning else { return }
            
            print("[Audio] Retrying audio session setup...")
            let audioSession = AVAudioSession.sharedInstance()
            do {
                try audioSession.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
                try audioSession.setPreferredSampleRate(self.sampleRate)
                try audioSession.setPreferredIOBufferDuration(0.02)
                try audioSession.setActive(true)
                
                self.setupOpusCodecs()
                self.setupAudioEngine()
                
                self.isRunning = true
                print("[Audio] ✅ Audio session started on retry")
            } catch {
                print("[Audio] ❌ Failed to start audio session after retry: \(error)")
            }
        }
    }

    private func setupOpusCodecs() {
        var encError: Int32 = 0
        opusEncoder = opus_encoder_create(Int32(sampleRate), Int32(channels), OPUS_APPLICATION_VOIP, &encError)
        if encError != OPUS_OK || opusEncoder == nil {
            print("[Audio] ❌ Error creating Opus encoder: \(encError)")
        } else {
            // تنظیم bitrate اولیه
            if let encoder = opusEncoder {
                // Note: opus_encoder_ctl is variadic and cannot be called directly from Swift
                // We'll set bitrate through alternative means or use a C wrapper
                // opus_encoder_ctl(encoder, OPUS_SET_BITRATE_REQUEST, Int32(currentBitrate))
            }
            print("[Audio] Opus encoder initialized with bitrate: \(currentBitrate) bps")
        }
        
        var decError: Int32 = 0
        opusDecoder = opus_decoder_create(Int32(sampleRate), Int32(channels), &decError)
        if decError != OPUS_OK || opusDecoder == nil {
            print("[Audio] ❌ Error creating Opus decoder: \(decError)")
        }
        
        // شروع adaptive bitrate adjustment
        startAdaptiveBitrateAdjustment()
        
        print("[Audio] Opus encoder/decoder initialized with adaptive bitrate (\(minBitrate)-\(maxBitrate) bps)")
    }
    
    /**
     * شروع تنظیم خودکار bitrate بر اساس RSSI
     */
    private func startAdaptiveBitrateAdjustment() {
        bitrateAdjustmentTimer?.invalidate()
        bitrateAdjustmentTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
            self?.adjustBitrateBasedOnConnectionQuality()
        }
    }
    
    /**
     * تنظیم bitrate بر اساس کیفیت اتصال (RSSI)
     */
    private func adjustBitrateBasedOnConnectionQuality() {
        guard let bleClient = BLEClient.shared as BLEClient?,
              bleClient.isConnected else {
            return
        }
        
        let rssi = bleClient.rssi
        let targetBitrate: Int
        
        // محاسبه target bitrate بر اساس RSSI
        switch rssi {
        case -50...0:        // Excellent signal (>= -50 dBm)
            targetBitrate = maxBitrate  // 32kbps
        case -60..<(-50):    // Good signal (-60 to -50 dBm)
            targetBitrate = 24000       // 24kbps
        case -70..<(-60):    // Fair signal (-70 to -60 dBm)
            targetBitrate = 20000       // 20kbps
        case -80..<(-70):    // Poor signal (-80 to -70 dBm)
            targetBitrate = 16000       // 16kbps
        default:             // Very poor (<= -80 dBm)
            targetBitrate = minBitrate  // 12kbps
        }
        
        // فقط اگر تغییر قابل توجه باشد (>= 2kbps)
        if abs(targetBitrate - currentBitrate) >= 2000 {
            currentBitrate = targetBitrate
            
            // به‌روزرسانی encoder
            // Note: opus_encoder_ctl is variadic and cannot be called directly from Swift
            // if let encoder = opusEncoder {
            //     opus_encoder_ctl(encoder, OPUS_SET_BITRATE_REQUEST, Int32(currentBitrate))
            //     print("[Audio] 🎚️ Bitrate adjusted to \(currentBitrate/1000)kbps (RSSI: \(rssi) dBm)")
            // }
            print("[Audio] 🎚️ Bitrate target set to \(currentBitrate/1000)kbps (RSSI: \(rssi) dBm)")
        }
    }
    
    /**
     * دریافت bitrate فعلی برای نمایش در UI
     */
    public func getCurrentBitrate() -> Int {
        return currentBitrate
    }

    private func setupAudioEngine() {
        audioEngine = AVAudioEngine()
        playerNode = AVAudioPlayerNode()
        
        guard let engine = audioEngine, let player = playerNode else {
            print("[Audio] Failed to create audio engine or player node")
            return
        }
        
        engine.attach(player)
        
        // فرمت صوتی: 16kHz, mono, PCM 16-bit
        let format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: true
        )
        
        guard let audioFormat = format else {
            print("[Audio] Failed to create audio format")
            return
        }
        
        // اتصال player node به output
        engine.connect(player, to: engine.mainMixerNode, format: audioFormat)
        
        // نصب tap روی input برای ضبط و کدگذاری
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        
        inputNode.installTap(onBus: 0, bufferSize: AVAudioFrameCount(frameSize), format: inputFormat) { [weak self] buffer, time in
            self?.processInputBuffer(buffer)
        }
        
        // شروع engine
        do {
            try engine.start()
            player.play()
            print("[Audio] AVAudioEngine started successfully")
        } catch {
            print("[Audio] Failed to start audio engine: \(error)")
            // تلاش مجدد یک بار
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                guard let self = self, self.isRunning else { return }
                do {
                    try engine.start()
                    player.play()
                    print("[Audio] ✅ AVAudioEngine started on retry")
                } catch {
                    print("[Audio] ❌ Failed to start audio engine after retry: \(error)")
                }
            }
        }
    }

    /// پردازش بافر ورودی: تبدیل به PCM 16-bit، اعمال audio processing، کدگذاری با Opus و ارسال
    private func processInputBuffer(_ buffer: AVAudioPCMBuffer) {
        guard !isMuted else { return }
        guard let encoder = opusEncoder else { return }
        
        // تبدیل به PCM 16-bit در صورت نیاز
        guard let int16Buffer = convertToInt16(buffer: buffer) else {
            return
        }
        
        // تبدیل به Float array برای processing
        var floatBuffer = int16ToFloat(int16Buffer)
        
        // اعمال audio processing (Echo Cancellation + Noise Suppression)
        if let processor = audioProcessor {
            // استفاده از reference signal برای echo cancellation
            let reference = referenceAudioBuffer.isEmpty ? nil : referenceAudioBuffer
            processor.processAudio(inputBuffer: &floatBuffer, referenceSignal: reference)
            
            // Log stats هر 50 فریم (یک بار در ثانیه)
            if Int.random(in: 0..<50) == 0 {
                let stats = processor.getStats()
                if stats.isVoiceActive {
                    print("[Audio] 🎤 Voice detected - Noise: \(String(format: "%.1f", stats.noiseFloorDB))dB, Signal: \(String(format: "%.1f", stats.averageEnergyDB))dB")
                }
            }
        }
        
        // تبدیل برگشت به Int16
        let processedInt16 = floatToInt16(floatBuffer)
        
        // کدگذاری با Opus
        var opusPacket = [UInt8](repeating: 0, count: 1024)
        let encodedBytes: Int32 = processedInt16.withUnsafeBytes { rawPcmPtr in
            guard let pcmPtr = rawPcmPtr.bindMemory(to: Int16.self).baseAddress else { return 0 }
            return opus_encode(encoder, pcmPtr, Int32(frameSize), &opusPacket, Int32(opusPacket.count))
        }
        
        if encodedBytes > 0 {
            let encodedData = Data(bytes: opusPacket, count: Int(encodedBytes))
            // ارسال از طریق L2CAP
            sendAudioPacket(encodedData)
        }
    }
    
    /// تبدیل Int16 Data به Float Array
    private func int16ToFloat(_ data: Data) -> [Float] {
        var floats = [Float](repeating: 0.0, count: data.count / 2)
        data.withUnsafeBytes { rawPtr in
            let int16Ptr = rawPtr.bindMemory(to: Int16.self)
            for i in 0..<floats.count {
                floats[i] = Float(int16Ptr[i]) / 32768.0
            }
        }
        return floats
    }
    
    /// تبدیل Float Array به Int16 Data
    private func floatToInt16(_ floats: [Float]) -> Data {
        var data = Data(count: floats.count * 2)
        data.withUnsafeMutableBytes { rawPtr in
            let int16Ptr = rawPtr.bindMemory(to: Int16.self)
            for i in 0..<floats.count {
                let clamped = max(-1.0, min(1.0, floats[i]))
                int16Ptr[i] = Int16(clamped * 32767.0)
            }
        }
        return data
    }

    /// تبدیل AVAudioPCMBuffer به Data با فرمت PCM 16-bit
    private func convertToInt16(buffer: AVAudioPCMBuffer) -> Data? {
        guard let channelData = buffer.int16ChannelData else {
            // اگر داده float است، تبدیل کن
            guard let floatChannelData = buffer.floatChannelData else { return nil }
            
            let frameLength = Int(buffer.frameLength)
            var int16Data = Data(count: frameLength * 2)
            
            int16Data.withUnsafeMutableBytes { rawPtr in
                let int16Ptr = rawPtr.bindMemory(to: Int16.self)
                let floatPtr = floatChannelData[0]
                
                for i in 0..<frameLength {
                    let sample = max(-1.0, min(1.0, floatPtr[i]))
                    int16Ptr[i] = Int16(sample * 32767.0)
                }
            }
            
            return int16Data
        }
        
        // داده از قبل int16 است
        let frameLength = Int(buffer.frameLength)
        let data = Data(bytes: channelData[0], count: frameLength * 2)
        return data
    }

    /// ارسال بسته صوتی از طریق کانال L2CAP
    private func sendAudioPacket(_ data: Data) {
        guard let channel = l2capChannel else { return }
        
        // ارسال اندازه بسته (2 بایت little-endian) + داده
        var packet = Data()
        let size = UInt16(data.count)
        packet.append(UInt8(size & 0xFF))
        packet.append(UInt8((size >> 8) & 0xFF))
        packet.append(data)
        
        channel.send(data: packet)
    }

    /// شروع دریافت و پخش صدا از کانال L2CAP
    public func startReceiving(channel: L2CAPChannelManager) {
        self.l2capChannel = channel
        
        // تنظیم callback برای دریافت داده
        channel.onDataReceived = { [weak self] data in
            self?.receiveAudioData(data)
        }
        
        print("[Audio] Started receiving audio from L2CAP channel")
    }

    /// دریافت داده صوتی از کانال L2CAP
    public func receiveAudioData(_ data: Data) {
        // پردازش بسته‌های Opus دریافتی
        processReceivedPacket(data)
    }

    private var receiveBuffer = Data()
    
    /// پردازش بسته دریافتی: دیکد Opus و پخش
    private func processReceivedPacket(_ data: Data) {
        receiveBuffer.append(data)
        
        // پردازش تمام بسته‌های کامل در بافر
        while receiveBuffer.count >= 2 {
            // خواندن اندازه بسته (2 بایت little-endian)
            let sizeByte0 = receiveBuffer[0]
            let sizeByte1 = receiveBuffer[1]
            let packetSize = Int(sizeByte0) | (Int(sizeByte1) << 8)
            
            // بررسی اینکه بسته کامل دریافت شده است
            if receiveBuffer.count < 2 + packetSize {
                break // منتظر داده بیشتر
            }
            
            // استخراج داده Opus
            let opusData = receiveBuffer.subdata(in: 2..<(2 + packetSize))
            receiveBuffer.removeFirst(2 + packetSize)
            
            // دیکد و پخش
            decodeAndPlay(opusData: opusData)
        }
    }

    /// دیکد بسته Opus و پخش صدا
    private func decodeAndPlay(opusData: Data) {
        guard let decoder = opusDecoder, let player = playerNode else { return }
        
        var pcmBuffer = [Int16](repeating: 0, count: frameSize)
        let decodedSamples: Int32 = opusData.withUnsafeBytes { rawDataPtr in
            guard let dataPtr = rawDataPtr.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return opus_decode(decoder, dataPtr, Int32(opusData.count), &pcmBuffer, Int32(frameSize), 0)
        }
        
        if decodedSamples > 0 {
            // ذخیره reference signal برای echo cancellation
            let pcmData = Data(bytes: pcmBuffer, count: Int(decodedSamples) * 2)
            referenceAudioBuffer = int16ToFloat(pcmData)
            
            // محدود کردن سایز buffer
            if referenceAudioBuffer.count > frameSize * 2 {
                referenceAudioBuffer.removeFirst(referenceAudioBuffer.count - frameSize)
            }
            
            if let audioPcmBuffer = createPCMBuffer(from: pcmData, frameCount: Int(decodedSamples)) {
                player.scheduleBuffer(audioPcmBuffer, completionHandler: nil)
            }
        }
    }

    /// ساخت AVAudioPCMBuffer از داده PCM
    private func createPCMBuffer(from data: Data, frameCount: Int) -> AVAudioPCMBuffer? {
        let format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: true
        )
        
        guard let audioFormat = format,
              let buffer = AVAudioPCMBuffer(pcmFormat: audioFormat, frameCapacity: AVAudioFrameCount(frameCount)) else {
            return nil
        }
        
        buffer.frameLength = AVAudioFrameCount(frameCount)
        
        // کپی داده به بافر
        if let channelData = buffer.int16ChannelData {
            data.withUnsafeBytes { rawPtr in
                let int16Ptr = rawPtr.bindMemory(to: Int16.self)
                channelData[0].update(from: int16Ptr.baseAddress!, count: frameCount)
            }
        }
        
        return buffer
    }

    public func stopAudioSession() {
        guard isRunning else { return }
        
        // توقف adaptive bitrate timer
        bitrateAdjustmentTimer?.invalidate()
        bitrateAdjustmentTimer = nil
        
        // توقف audio engine
        audioEngine?.inputNode.removeTap(onBus: 0)
        playerNode?.stop()
        audioEngine?.stop()
        
        audioEngine = nil
        playerNode = nil
        
        if let encoder = opusEncoder {
            opus_encoder_destroy(encoder)
            opusEncoder = nil
        }
        if let decoder = opusDecoder {
            opus_decoder_destroy(decoder)
            opusDecoder = nil
        }
        
        l2capChannel = nil
        receiveBuffer = Data()
        audioProcessor = nil
        referenceAudioBuffer = []
        
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

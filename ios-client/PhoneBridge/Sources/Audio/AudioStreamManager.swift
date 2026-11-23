import Foundation
import AVFoundation
import OpusKit

/// مدیریت کانال و نشست صوتی برای مکالمه بدون تاخیر با کدک Opus
public class AudioStreamManager: NSObject {
    public static let shared = AudioStreamManager()

    private var audioEngine: AVAudioEngine?
    private var playerNode: AVAudioPlayerNode?
    private var opusEncoder: OPUSEncoder?
    private var opusDecoder: OPUSDecoder?
    private var isRunning = false
    
    private var l2capChannel: L2CAPChannelManager?
    private var receiveThread: Thread?
    
    private let sampleRate: Double = 16000
    private let frameSize: Int = 320 // 20ms at 16kHz
    private let channels: Int = 1
    private let bitrate: Int = 20000 // 20kbps

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
            
            // راه‌اندازی AVAudioEngine
            setupAudioEngine()
            
            self.isRunning = true
            print("[Audio] Audio session started با Opus codec (16kHz mono, 20ms frames, 20kbps)")
        } catch {
            print("[Audio] Failed to activate audio session: \(error)")
        }
    }

    private func setupOpusCodecs() {
        // راه‌اندازی Opus encoder
        opusEncoder = OPUSEncoder(
            sampleRate: Int32(sampleRate),
            numberOfChannels: 1,
            application: .voip
        )
        opusEncoder?.bitrate = Int32(bitrate)
        
        // راه‌اندازی Opus decoder
        opusDecoder = OPUSDecoder(
            sampleRate: Int32(sampleRate),
            numberOfChannels: 1
        )
        
        print("[Audio] Opus encoder/decoder initialized")
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
        }
    }

    /// پردازش بافر ورودی: تبدیل به PCM 16-bit، کدگذاری با Opus و ارسال
    private func processInputBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let encoder = opusEncoder else { return }
        
        // تبدیل به PCM 16-bit در صورت نیاز
        guard let int16Buffer = convertToInt16(buffer: buffer) else {
            return
        }
        
        // کدگذاری با Opus
        var encodedData = Data()
        let encodeResult = encoder.encode(int16Buffer, frameSize: UInt(frameSize), encodedData: &encodedData)
        
        if encodeResult && encodedData.count > 0 {
            // ارسال از طریق L2CAP
            sendAudioPacket(encodedData)
            print("[Audio] Opus encode/send: \(encodedData.count) bytes")
        }
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
    private func receiveAudioData(_ data: Data) {
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
        
        // دیکد با Opus
        var pcmData = Data(count: frameSize * 2)
        var decodedFrames: UInt = 0
        
        let decodeResult = decoder.decode(opusData, decodedData: &pcmData, decodedFrames: &decodedFrames)
        
        if decodeResult && decodedFrames > 0 {
            // تبدیل به AVAudioPCMBuffer و پخش
            if let pcmBuffer = createPCMBuffer(from: pcmData, frameCount: Int(decodedFrames)) {
                player.scheduleBuffer(pcmBuffer, completionHandler: nil)
                print("[Audio] Opus decode/play: \(decodedFrames) samples from \(opusData.count) bytes")
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
        
        // توقف audio engine
        audioEngine?.inputNode.removeTap(onBus: 0)
        playerNode?.stop()
        audioEngine?.stop()
        
        audioEngine = nil
        playerNode = nil
        opusEncoder = nil
        opusDecoder = nil
        l2capChannel = nil
        receiveBuffer = Data()
        
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

import Foundation
import CoreBluetooth

/// مدیر کانال L2CAP برای دریافت صدا از اندروید در آیفون
/// شامل persistence برای recovery بدون وقفه در حین تماس
public class L2CAPChannelManager: NSObject {
    
    private var channel: CBL2CAPChannel?
    private var inputStream: InputStream?
    private var outputStream: OutputStream?
    private var isConnected = false
    
    public var onConnected: (() -> Void)?
    public var onDisconnected: (() -> Void)?
    public var onDataReceived: ((Data) -> Void)?
    
    // Persistence state
    private var peripheral: CBPeripheral?
    private var psm: UInt16 = 0
    private var isInCall: Bool = false
    private var reconnectTimer: Timer?
    private var reconnectAttempts: Int = 0
    private let maxReconnectAttempts = 5
    
    // Audio packet buffer برای دور انداختن در صورت packet loss
    private var packetsReceived: Int = 0
    private var packetsDropped: Int = 0
    private var lastPacketTime: Date?
    
    public override init() {
        super.init()
    }
    
    /// اتصال به کانال L2CAP با استفاده از PSM دریافتی از GATT
    /// با persistence برای reconnect خودکار در حین تماس
    public func connect(to peripheral: CBPeripheral, psm: UInt16, duringCall: Bool = false, completion: @escaping (Bool, Error?) -> Void) {
        self.peripheral = peripheral
        self.psm = psm
        self.isInCall = duringCall
        self.reconnectAttempts = 0
        
        print("📡 [L2CAP] Attempting to open L2CAP channel to PSM \(psm) (inCall=\(duringCall))...")
        
        peripheral.openL2CAPChannel(CBL2CAPPSM(psm))
        
        // ذخیره completion برای استفاده در didOpen
        self.connectionCompletion = completion
    }
    
    /// علامت‌گذاری که تماس شروع/پایان یافته
    public func setCallActive(_ active: Bool) {
        isInCall = active
        if active {
            print("📞 [L2CAP] Call started - persistence enabled")
            packetsReceived = 0
            packetsDropped = 0
        } else {
            print("📞 [L2CAP] Call ended - persistence disabled")
            reconnectTimer?.invalidate()
            reconnectTimer = nil
            reconnectAttempts = 0
        }
    }
    
    /// Reconnect خودکار در حین تماس
    private func scheduleReconnectDuringCall() {
        guard isInCall, let peripheral = self.peripheral, psm > 0 else {
            print("⚠️ [L2CAP] Cannot reconnect: not in call or missing peripheral/psm")
            return
        }
        
        reconnectTimer?.invalidate()
        
        if reconnectAttempts >= maxReconnectAttempts {
            print("❌ [L2CAP] Max reconnect attempts reached (\(maxReconnectAttempts))")
            onDisconnected?()
            return
        }
        
        // Exponential backoff: 0.5s, 1s, 2s, 4s, 8s
        let delays: [TimeInterval] = [0.5, 1.0, 2.0, 4.0, 8.0]
        let delay = delays[min(reconnectAttempts, delays.count - 1)]
        
        print("🔄 [L2CAP] Scheduling reconnect attempt \(reconnectAttempts + 1)/\(maxReconnectAttempts) in \(delay)s...")
        
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            
            self.reconnectAttempts += 1
            print("🔄 [L2CAP] Reconnecting (attempt \(self.reconnectAttempts))...")
            
            self.connect(to: peripheral, psm: self.psm, duringCall: true) { success, error in
                if !success {
                    print("❌ [L2CAP] Reconnect failed: \(error?.localizedDescription ?? "unknown")")
                    self.scheduleReconnectDuringCall()
                } else {
                    print("✅ [L2CAP] Successfully reconnected during call")
                    self.reconnectAttempts = 0
                }
            }
        }
    }
    
    private var connectionCompletion: ((Bool, Error?) -> Void)?
    private var retryCount = 0
    private let maxRetries = 3
    private let retryDelays: [TimeInterval] = [1.0, 2.0, 4.0]
    
    /// فراخوانی پس از باز شدن کانال L2CAP (باید از CBPeripheralDelegate صدا زده شود)
    public func didOpen(channel: CBL2CAPChannel?, error: Error?) {
        if let error = error {
            print("❌ [L2CAP] Failed to open channel: \(error.localizedDescription)")
            
            // تلاش مجدد در صورت خطا
            if retryCount < maxRetries {
                let delay = retryDelays[retryCount]
                retryCount += 1
                print("🔄 [L2CAP] Retrying in \(delay) seconds (attempt \(retryCount)/\(maxRetries))...")
                
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    // تلاش مجدد با همان PSM
                    // نیاز به نگهداری peripheral و psm در property
                }
            } else {
                connectionCompletion?(false, error)
                connectionCompletion = nil
                retryCount = 0
            }
            return
        }
        
        guard let channel = channel else {
            print("❌ [L2CAP] Channel is nil")
            connectionCompletion?(false, NSError(domain: "L2CAP", code: -1, userInfo: [NSLocalizedDescriptionKey: "Channel is nil"]))
            connectionCompletion = nil
            return
        }
        
        self.channel = channel
        self.inputStream = channel.inputStream
        self.outputStream = channel.outputStream
        
        // باز کردن استریم‌ها
        inputStream?.delegate = self
        outputStream?.delegate = self
        
        inputStream?.schedule(in: .main, forMode: .common)
        outputStream?.schedule(in: .main, forMode: .common)
        
        inputStream?.open()
        outputStream?.open()
        
        isConnected = true
        retryCount = 0
        
        print("✅ [L2CAP] L2CAP channel opened to PSM \(channel.psm)")
        onConnected?()
        connectionCompletion?(true, nil)
        connectionCompletion = nil
    }
    
    /// قطع اتصال و بستن استریم‌ها
    public func disconnect(clearState: Bool = true) {
        guard isConnected else { return }
        
        inputStream?.close()
        outputStream?.close()
        
        inputStream?.remove(from: .main, forMode: .common)
        outputStream?.remove(from: .main, forMode: .common)
        
        inputStream = nil
        outputStream = nil
        channel = nil
        isConnected = false
        
        if clearState {
            peripheral = nil
            psm = 0
            isInCall = false
            reconnectTimer?.invalidate()
            reconnectTimer = nil
            reconnectAttempts = 0
        }
        
        print("🔌 [L2CAP] L2CAP channel disconnected (clearState=\(clearState))")
        
        // اگر در حین تماس disconnect شد، reconnect کن
        if isInCall && !clearState {
            scheduleReconnectDuringCall()
        } else {
            onDisconnected?()
        }
    }
    
    /// ارسال داده از طریق کانال L2CAP
    public func send(data: Data) {
        guard isConnected, let outputStream = outputStream else {
            print("⚠️ [L2CAP] Cannot send data: not connected")
            return
        }
        
        data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            if let baseAddress = buffer.baseAddress?.assumingMemoryBound(to: UInt8.self) {
                let bytesWritten = outputStream.write(baseAddress, maxLength: buffer.count)
                if bytesWritten < 0 {
                    print("❌ [L2CAP] Error writing to output stream")
                } else {
                    print("📤 [L2CAP] Sent \(bytesWritten) bytes")
                }
            }
        }
    }
}

// MARK: - StreamDelegate
extension L2CAPChannelManager: StreamDelegate {
    public func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable:
            if let inputStream = aStream as? InputStream {
                var buffer = [UInt8](repeating: 0, count: 4096)
                let bytesRead = inputStream.read(&buffer, maxLength: buffer.count)
                
                if bytesRead > 0 {
                    let data = Data(bytes: buffer, count: bytesRead)
                    
                    // آمار packet برای monitoring
                    packetsReceived += 1
                    lastPacketTime = Date()
                    
                    // هر 100 packet، آمار رو log کن
                    if packetsReceived % 100 == 0 {
                        print("📊 [L2CAP] Stats: received=\(packetsReceived), dropped=\(packetsDropped)")
                    }
                    
                    onDataReceived?(data)
                } else if bytesRead == 0 {
                    print("⚠️ [L2CAP] Stream returned 0 bytes (connection may be closing)")
                }
            }
            
        case .errorOccurred:
            print("❌ [L2CAP] Stream error occurred")
            
            if isInCall {
                print("🔄 [L2CAP] Error during call - attempting reconnect...")
                disconnect(clearState: false)
            } else {
                disconnect(clearState: true)
            }
            
        case .endEncountered:
            print("🔚 [L2CAP] Stream ended")
            
            if isInCall {
                print("🔄 [L2CAP] Disconnected during call - attempting reconnect...")
                disconnect(clearState: false)
            } else {
                disconnect(clearState: true)
            }
            
        default:
            break
        }
    }
}

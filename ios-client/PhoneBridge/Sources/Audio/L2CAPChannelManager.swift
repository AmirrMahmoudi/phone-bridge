import Foundation
import CoreBluetooth

/// مدیر کانال L2CAP برای دریافت صدا از اندروید در آیفون
public class L2CAPChannelManager: NSObject {
    
    private var channel: CBL2CAPChannel?
    private var inputStream: InputStream?
    private var outputStream: OutputStream?
    private var isConnected = false
    
    public var onConnected: (() -> Void)?
    public var onDisconnected: (() -> Void)?
    public var onDataReceived: ((Data) -> Void)?
    
    public override init() {
        super.init()
    }
    
    /// اتصال به کانال L2CAP با استفاده از PSM دریافتی از GATT
    public func connect(to peripheral: CBPeripheral, psm: UInt16, completion: @escaping (Bool, Error?) -> Void) {
        print("📡 [L2CAP] Attempting to open L2CAP channel to PSM \(psm)...")
        
        peripheral.openL2CAPChannel(CBL2CAPPSM(psm))
        
        // ذخیره completion برای استفاده در didOpen
        self.connectionCompletion = completion
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
    public func disconnect() {
        guard isConnected else { return }
        
        inputStream?.close()
        outputStream?.close()
        
        inputStream?.remove(from: .main, forMode: .common)
        outputStream?.remove(from: .main, forMode: .common)
        
        inputStream = nil
        outputStream = nil
        channel = nil
        isConnected = false
        
        print("🔌 [L2CAP] L2CAP channel disconnected")
        onDisconnected?()
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
                    print("📥 [L2CAP] Received \(bytesRead) bytes")
                    onDataReceived?(data)
                }
            }
            
        case .errorOccurred:
            print("❌ [L2CAP] Stream error occurred")
            disconnect()
            
        case .endEncountered:
            print("🔚 [L2CAP] Stream ended")
            disconnect()
            
        default:
            break
        }
    }
}

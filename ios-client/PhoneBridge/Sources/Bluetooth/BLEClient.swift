import Foundation
import CoreBluetooth
import UIKit
import UserNotifications

/// کلاینت بلوتوث آیفون برای اتصال به سرور اندروید
public class BLEClient: NSObject, ObservableObject {
    public static let shared = BLEClient()

    private var centralManager: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?
    private var eventCharacteristic: CBCharacteristic?
    private var psmCharacteristic: CBCharacteristic?

    public static let serviceUUID = CBUUID(string: "0000A001-0000-1000-8000-00805F9B34FB")
    public static let commandCharUUID = CBUUID(string: "0000A002-0000-1000-8000-00805F9B34FB")
    public static let eventCharUUID = CBUUID(string: "0000A003-0000-1000-8000-00805F9B34FB")
    public static let psmCharUUID = CBUUID(string: "0000A004-0000-1000-8000-00805F9B34FB")

    @Published public var isConnected: Bool = false
    @Published public var deviceName: String = "قطع ارتباط"
    @Published public var batteryLevel: Int = 0
    @Published public var simCarrier: String = "-"
    @Published public var logs: [String] = []
    @Published public var isReconnecting: Bool = false
    
    // مدیر کانال L2CAP برای انتقال صوت
    private var l2capManager: L2CAPChannelManager?
    
    // Callback برای ثبت رویدادها در ActivityLog
    public var onActivityEvent: ((String, String, String, String?, String?) -> Void)?
    
    // اتصال مجدد با exponential backoff
    private var reconnectAttempt: Int = 0
    private var reconnectTimer: Timer?
    private var lastHeartbeatTime: Date?
    private var heartbeatTimer: Timer?

    override init() {
        super.init()
        // شروع تایمر برای بررسی heartbeat timeout
        startHeartbeatMonitoring()
    }
    
    /**
     * شروع مانیتورینگ heartbeat (بررسی هر ۳۰ ثانیه)
     */
    private func startHeartbeatMonitoring() {
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
            self?.checkHeartbeatTimeout()
        }
    }
    
    /**
     * بررسی timeout برای heartbeat (۶۰ ثانیه)
     */
    private func checkHeartbeatTimeout() {
        guard isConnected, let lastTime = lastHeartbeatTime else { return }
        
        let elapsed = Date().timeIntervalSince(lastTime)
        if elapsed > 60.0 {
            log("⚠️ Heartbeat timeout detected (no DEVICE_STATUS for \(Int(elapsed))s)")
            // قطع اتصال و شروع reconnect
            disconnect()
        }
    }

    override init() {
        super.init()
        setupBluetooth()
        startHeartbeatMonitor()
    }

    public func setupBluetooth() {
        guard centralManager == nil else { return }
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: [
                CBCentralManagerOptionRestoreIdentifierKey: "PhoneBridgeCentralManager",
                CBCentralManagerOptionShowPowerAlertKey: true
            ]
        )
    }

    public func startScanning() {
        setupBluetooth()
        guard let central = centralManager, central.state == .poweredOn else { return }
        log("در حال جستجوی دستگاه اندروید...")
        central.scanForPeripherals(withServices: [BLEClient.serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false
        ])
    }

    public func disconnect() {
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        reconnectAttempt = 0
        
        if let peripheral = peripheral, let central = centralManager {
            central.cancelPeripheralConnection(peripheral)
        }
        // بستن کانال L2CAP در صورت قطع اتصال
        l2capManager?.disconnect()
        l2capManager = nil
    }
    
    /**
     * شروع اتصال مجدد با exponential backoff
     */
    private func scheduleReconnect() {
        reconnectTimer?.invalidate()
        
        // محاسبه تاخیر: 1s, 2s, 4s, 8s, max 30s
        let delays = [1.0, 2.0, 4.0, 8.0, 30.0]
        let delayIndex = min(reconnectAttempt, delays.count - 1)
        let delay = delays[delayIndex]
        
        reconnectAttempt += 1
        isReconnecting = true
        
        log("Connection lost, attempting reconnect \(reconnectAttempt)/5 after \(Int(delay))s")
        
        reconnectTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            
            if self.reconnectAttempt <= 5 {
                self.log("🔄 Reconnecting (attempt \(self.reconnectAttempt))...")
                self.startScanning()
            } else {
                self.log("❌ Max reconnection attempts reached")
                self.isReconnecting = false
                self.reconnectAttempt = 0
            }
        }
    }

    public func sendCommand(_ dict: [String: Any]) {
        guard let char = commandCharacteristic, let peripheral = peripheral else {
            log("خطا: ارتباط با اندروید برای ارسال دستور برقرار نیست.")
            return
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: dict)
            peripheral.writeValue(data, for: char, type: .withResponse)
            log("دستور به سامسونگ ارسال شد: \(dict["cmd"] ?? "")")
        } catch {
            log("خطا در سریالایز دستور: \(error)")
        }
    }



    public func log(_ msg: String) {
        DispatchQueue.main.async {
            let timestamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
            self.logs.insert("[\(timestamp)] \(msg)", at: 0)
        }
    }
    
    /// دسترسی به مدیر کانال L2CAP برای استفاده در AudioStreamManager
    public func getL2CAPChannel() -> L2CAPChannelManager? {
        return l2capManager
    }
}

// MARK: - CBCentralManagerDelegate
extension BLEClient: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            log("بلوتوث آیفون آماده به کار است.")
            startScanning()
        case .poweredOff:
            log("بلوتوث آیفون خاموش است.")
            isConnected = false
        case .unauthorized:
            log("خطا: دسترسی به بلوتوث در تنظیمات تایید نشده است.")
            isConnected = false
        default:
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        log("🔄 [BLE] Restoring Bluetooth state in background...")
        if let restoredPeripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let p = restoredPeripherals.first {
            self.peripheral = p
            self.peripheral?.delegate = self
            self.deviceName = p.name ?? "Samsung Gateway"
            self.isConnected = (p.state == .connected)
            log("✅ [BLE] Restored connection to: \(self.deviceName)")
            if p.state == .connected {
                p.discoverServices([BLEClient.serviceUUID])
            }
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        log("دستگاه اندروید پیدا شد: \(peripheral.name ?? "Android Gateway")")
        self.peripheral = peripheral
        centralManager?.stopScan()
        centralManager?.connect(peripheral, options: [
            CBConnectPeripheralOptionNotifyOnConnectionKey: true,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
        ])
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        self.isConnected = true
        self.deviceName = peripheral.name ?? "Samsung Gateway"
        
        // ریست reconnect state
        reconnectAttempt = 0
        isReconnecting = false
        reconnectTimer?.invalidate()
        reconnectTimer = nil
        
        // ریست heartbeat timer
        lastHeartbeatTime = Date()
        
        log("ارتباط با گوشی سامسونگ با موفقیت برقرار شد ✅")
        
        if reconnectAttempt > 0 {
            log("✅ Connection recovered after \(reconnectAttempt) attempts")
        }
        
        onActivityEvent?("antenna.radiowaves.left.and.right", "اتصال برقرار شد", "به \(self.deviceName)", nil, nil)
        peripheral.delegate = self
        peripheral.discoverServices([BLEClient.serviceUUID])
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        self.isConnected = false
        let previousName = self.deviceName
        self.deviceName = "قطع ارتباط"
        
        // بستن کانال L2CAP
        l2capManager?.disconnect()
        l2capManager = nil
        
        log("اتصال به سامسونگ قطع شد. تلاش برای اتصال مجدد...")
        onActivityEvent?("antenna.radiowaves.left.and.right.slash", "اتصال قطع شد", "از \(previousName)", nil, nil)
        
        // شروع اتصال مجدد با exponential backoff
        scheduleReconnect()
    }
}

// MARK: - CBPeripheralDelegate
extension BLEClient: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == BLEClient.serviceUUID {
            peripheral.discoverCharacteristics([
                BLEClient.commandCharUUID,
                BLEClient.eventCharUUID,
                BLEClient.psmCharUUID
            ], for: service)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }
        for char in characteristics {
            if char.uuid == BLEClient.commandCharUUID {
                self.commandCharacteristic = char
            } else if char.uuid == BLEClient.eventCharUUID {
                self.eventCharacteristic = char
                peripheral.setNotifyValue(true, for: char)
                log("کانال دریافت تماس‌ها و پیامک‌ها فعال گردید ⚡️")
            } else if char.uuid == BLEClient.psmCharUUID {
                self.psmCharacteristic = char
                // خواندن مقدار PSM از اندروید
                peripheral.readValue(for: char)
                log("در حال خواندن PSM از سرور اندروید...")
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        
        // پردازش PSM دریافتی از کاراکتریستیک
        if characteristic.uuid == BLEClient.psmCharUUID {
            if data.count >= 2 {
                // تبدیل 2 بایت little-endian به UInt16
                let psm = UInt16(data[0]) | (UInt16(data[1]) << 8)
                log("📡 Read PSM \(psm) from GATT")
                
                // ایجاد و اتصال مدیر L2CAP
                connectL2CAPChannel(to: peripheral, psm: psm)
            }
            return
        }
        
        // پردازش رویدادهای GATT معمولی
        do {
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                handleIncomingEvent(json)
            }
        } catch {
            log("خطا در پردازش پیام دریافتی")
        }
    }
    
    /// برقراری اتصال کانال L2CAP با PSM دریافتی
    private func connectL2CAPChannel(to peripheral: CBPeripheral, psm: UInt16) {
        l2capManager = L2CAPChannelManager()
        l2capManager?.onConnected = { [weak self] in
            self?.log("✅ L2CAP channel opened to PSM \(psm)")
        }
        l2capManager?.onDisconnected = { [weak self] in
            self?.log("❌ L2CAP channel closed")
        }
        l2capManager?.onDataReceived = { [weak self] data in
            // پردازش داده‌های صوتی دریافتی (فعلا فقط لاگ)
            self?.log("📥 Received \(data.count) bytes audio data")
        }
        
        l2capManager?.connect(to: peripheral, psm: psm) { [weak self] success, error in
            if success {
                self?.log("🎵 L2CAP audio channel ready")
            } else {
                self?.log("❌ Failed to connect L2CAP: \(error?.localizedDescription ?? "Unknown")")
            }
        }
    }
    
    /// فراخوانی پس از باز شدن کانال L2CAP
    public func peripheral(_ peripheral: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        l2capManager?.didOpen(channel: channel, error: error)
    }

    private func handleIncomingEvent(_ json: [String: Any]) {
        guard let type = json["type"] as? String else { return }

        switch type {
        case "CALL_INCOMING":
            let callerNumber = json["callerNumber"] as? String ?? "ناشناس"
            let callerName = json["callerName"] as? String ?? ""
            let callUUID = UUID(uuidString: json["callId"] as? String ?? "") ?? UUID()
            log("📞 تماس ورودی جدید از سامسونگ: \(callerName.isEmpty ? callerNumber : callerName)")
            
            // ثبت در ActivityLog
            let displayName = callerName.isEmpty ? callerNumber : callerName
            onActivityEvent?("phone.arrow.down.left", "تماس ورودی", displayName, nil, nil)
            
            // فراخوانی رابط کاربری بومی آیفون با CallKit (زنگ خوردن تمام‌صفحه آیفون ۱۷)
            CallManager.shared.reportIncomingCall(uuid: callUUID, handle: callerNumber, callerName: callerName)

        case "CALL_ENDED":
            if let activeUUID = CallManager.shared.activeCallUUID {
                CallManager.shared.endCall(uuid: activeUUID)
                log("تماس به پایان رسید.")
                onActivityEvent?("phone.down", "تماس پایان یافت", "", nil, nil)
            }

        case "SMS_INCOMING":
            let sender = json["sender"] as? String ?? "ناشناس"
            let senderName = json["senderName"] as? String ?? ""
            let body = json["body"] as? String ?? ""
            let displayName = senderName.isEmpty ? sender : senderName
            log("💬 پیامک جدید از \(displayName): \(body)")
            
            let preview = "از \(displayName): \(body.prefix(30))\(body.count > 30 ? "..." : "")"
            onActivityEvent?("message", "پیامک دریافتی", preview, body, displayName)
            
            // نمایش نوتیفیکیشن محلی برای SMS
            showSMSNotification(sender: displayName, body: body)

        case "DEVICE_STATUS":
            // به‌روزرسانی زمان آخرین heartbeat
            lastHeartbeatTime = Date()
            
            if let battery = json["batteryLevel"] as? Int {
                DispatchQueue.main.async { self.batteryLevel = battery }
            }
            if let carrier = json["simCarrier"] as? String {
                DispatchQueue.main.async { self.simCarrier = carrier }
            }

        default:
            break
        }
    }
    
    /// نمایش نوتیفیکیشن محلی برای پیامک دریافتی
    private func showSMSNotification(sender: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = "پیامک جدید"
        content.subtitle = "از: \(sender)"
        content.body = body
        content.sound = .default
        content.categoryIdentifier = "SMS_CATEGORY"
        
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil // نمایش فوری
        )
        
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                self.log("خطا در نمایش نوتیفیکیشن: \(error.localizedDescription)")
            }
        }
    }
}

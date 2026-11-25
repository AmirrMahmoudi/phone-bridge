import Foundation
import Combine
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

    public enum ConnectionQuality {
        case excellent, good, fair, poor, unknown

        public var icon: String {
            switch self {
            case .excellent, .good: return "wifi"
            case .fair: return "wifi.exclamationmark"
            case .poor: return "wifi.slash"
            case .unknown: return "questionmark.circle"
            }
        }

        public var description: String {
            switch self {
            case .excellent: return "عالی"
            case .good: return "خوب"
            case .fair: return "متوسط"
            case .poor: return "ضعیف"
            case .unknown: return "نامشخص"
            }
        }
    }

    @Published public var isConnected: Bool = false
    @Published public var deviceName: String = "قطع ارتباط"
    @Published public var batteryLevel: Int = 0
    @Published public var simCarrier: String = "-"
    @Published public var logs: [String] = []
    @Published public var isReconnecting: Bool = false
    @Published public var connectionQuality: ConnectionQuality = .unknown
    @Published public var rssi: Int = 0
    
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
        setupBluetooth()
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

    public func setupBluetooth() {
        guard centralManager == nil else { return }
        var options: [String: Any] = [
            CBCentralManagerOptionShowPowerAlertKey: true
        ]
        // فقط در صورتی که bluetooth-central در Info.plist تعریف شده باشد، Restore Identifier اضافه شود
        if let bgModes = Bundle.main.infoDictionary?["UIBackgroundModes"] as? [String],
           bgModes.contains("bluetooth-central") {
            options[CBCentralManagerOptionRestoreIdentifierKey] = "PhoneBridgeCentralManager"
        }
        centralManager = CBCentralManager(
            delegate: self,
            queue: nil,
            options: options
        )
    }

    public func startScanning() {
        setupBluetooth()
        guard let central = centralManager, central.state == .poweredOn else { return }

        // بررسی دستگاه‌های از قبل متصل‌شده در سیستم‌عامل
        let connectedPeripherals = central.retrieveConnectedPeripherals(withServices: [BLEClient.serviceUUID])
        if let existing = connectedPeripherals.first {
            log("دستگاه اندروید از قبل متصل پیدا شد: \(existing.name ?? "Unknown")")
            self.peripheral = existing
            existing.delegate = self
            central.connect(existing, options: nil)
            return
        }

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

    /// برقراری تماس خروجی از طریق سیم‌کارت اندروید
    public func dial(phoneNumber: String) {
        let trimmed = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        log("📞 در حال برقراری تماس خروجی با: \(trimmed)")
        sendCommand(["cmd": "DIAL_CALL", "phoneNumber": trimmed])

        // ثبت در ActivityLog
        onActivityEvent?("phone.arrow.up.right", "تماس خروجی", trimmed, nil, nil)

        // رجیستر تماس در CallKit
        CallManager.shared.startOutgoingCall(handle: trimmed)
        
        // ✅ FIX: AudioSession را الان شروع نکن - منتظر CALL_ACTIVE باش
        // AudioSession زمانی شروع می‌شود که Android تماس را برقرار کند و CALL_ACTIVE بفرستد
        // این جلوی timeout و هدر رفت منابع را می‌گیرد
    }

    /// ارسال پیامک از طریق سیم‌کارت اندروید
    public func sendSMS(recipient: String, message: String) {
        guard !recipient.isEmpty, !message.isEmpty else { return }
        log("✉️ در حال ارسال پیامک به \(recipient)")
        sendCommand(["cmd": "SEND_SMS", "recipient": recipient, "body": message])
        onActivityEvent?("paperplane.fill", "پیامک ارسال شد", "\(recipient): \(message)", message, recipient)
    }

    /// ارسال پیامک با شماره و callback نتیجه
    public func sendSMS(phoneNumber: String, message: String, completion: ((Bool, String?) -> Void)? = nil) {
        guard !phoneNumber.isEmpty, !message.isEmpty else {
            completion?(false, "شماره یا متن پیامک خالی است")
            return
        }
        guard isConnected else {
            completion?(false, "اتصال بلوتوث برقرار نیست")
            return
        }
        sendSMS(recipient: phoneNumber, message: message)
        completion?(true, nil)
    }

    /// برقراری تماس خروجی از طریق سیم‌کارت اندروید
    public func initiateCall(phoneNumber: String, completion: @escaping (Bool, String?, String?) -> Void) {
        guard !phoneNumber.isEmpty else {
            completion(false, nil, "شماره تلفن نامعتبر است")
            return
        }
        guard isConnected else {
            completion(false, nil, "اتصال بلوتوث برقرار نیست")
            return
        }
        let callId = "call_\(Int(Date().timeIntervalSince1970 * 1000))"
        log("📞 در حال برقراری تماس با \(phoneNumber)")
        sendCommand(["cmd": "DIAL_CALL", "phoneNumber": phoneNumber, "callId": callId])
        CallManager.shared.startOutgoingCall(handle: phoneNumber)
        onActivityEvent?("phone.fill.arrow.up.right", "تماس خروجی", phoneNumber, nil, phoneNumber)
        completion(true, callId, nil)
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
            if let mgr = self?.l2capManager {
                AudioStreamManager.shared.startReceiving(channel: mgr)
            }
        }
        l2capManager?.onDisconnected = { [weak self] in
            self?.log("❌ L2CAP channel closed")
        }
        l2capManager?.onDataReceived = { data in
            // ارسال مستقیم بسته‌های صوتی Opus دریافتی به موتور دیکد و پخش
            AudioStreamManager.shared.receiveAudioData(data)
        }
        
        l2capManager?.connect(to: peripheral, psm: psm) { [weak self] success, error in
            if success {
                self?.log("🎵 L2CAP audio channel ready")
                if let mgr = self?.l2capManager {
                    AudioStreamManager.shared.startReceiving(channel: mgr)
                }
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
            let callerName = json["callerName"] as? String ?? callerNumber
            let callUUID = UUID(uuidString: json["callId"] as? String ?? "") ?? UUID()
            let displayName = callerName.isEmpty ? callerNumber : callerName
            log("📞 تماس ورودی جدید: \(displayName) (\(callerNumber))")
            
            // ثبت در ActivityLog
            onActivityEvent?("phone.arrow.down.left", "تماس ورودی", displayName, nil, nil)
            
            // فراخوانی رابط کاربری بومی آیفون با CallKit (با قابلیت به‌روزرسانی زنده)
            CallManager.shared.reportOrUpdateIncomingCall(uuid: callUUID, handle: callerNumber, callerName: displayName)

        case "CALL_ACTIVE":
            log("🔊 تماس فعال شد — شروع استریم صدا روی آیفون")
            AudioStreamManager.shared.startAudioSession()
            if let channel = l2capManager {
                AudioStreamManager.shared.startReceiving(channel: channel)
            }

        case "CALL_ENDED":
            if let activeUUID = CallManager.shared.activeCallUUID {
                CallManager.shared.endCall(uuid: activeUUID)
                log("تماس به پایان رسید.")
                onActivityEvent?("phone.down", "تماس پایان یافت", "", nil, nil)
            }

        case "CALL_ANSWERED_LOCALLY":
            if let activeUUID = CallManager.shared.activeCallUUID {
                log("تماس مستقیماً از روی گوشی اندروید پاسخ داده شد.")
                CallManager.shared.dismissCall(uuid: activeUUID)
                onActivityEvent?("phone.bubble.left", "پاسخ روی اندروید", "مکالمه روی گوشی دوم ادامه دارد", nil, nil)
            }

        case "SMS_SENT_STATUS":
            let status = json["status"] as? String ?? ""
            let recipient = json["recipient"] as? String ?? ""
            if status == "SUCCESS" {
                log("✅ پیامک به \(recipient) با موفقیت ارسال شد.")
            } else {
                log("❌ خطا در ارسال پیامک به \(recipient)")
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
                DispatchQueue.main.async {
                    if battery < 20 && self.batteryLevel >= 20 {
                        self.log("⚠️ هشدار: باتری گوشی دوم به کمتر از ۲۰٪ رسید!")
                        self.showBatteryNotification(level: battery)
                    }
                    self.batteryLevel = battery
                }
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

    /// نمایش هشدار باتری ضعیف گوشی دوم
    private func showBatteryNotification(level: Int) {
        let content = UNMutableNotificationContent()
        content.title = "باتری ضعیف گوشی دوم"
        content.body = "شارژ گوشی اندروید به \(level)٪ رسید. لطفاً آن را به شارژر متصل کنید."
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "LOW_BATTERY_ALERT",
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                self.log("خطا در نمایش هشدار باتری: \(error.localizedDescription)")
            }
        }
    }
}

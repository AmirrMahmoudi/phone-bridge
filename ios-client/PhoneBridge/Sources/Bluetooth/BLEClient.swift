import Foundation
import CoreBluetooth
import UIKit

/// کلاینت بلوتوث آیفون برای اتصال به سرور اندروید
public class BLEClient: NSObject, ObservableObject {
    public static let shared = BLEClient()

    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?
    private var eventCharacteristic: CBCharacteristic?

    public static let serviceUUID = CBUUID(string: "0000A001-0000-1000-8000-00805F9B34FB")
    public static let commandCharUUID = CBUUID(string: "0000A002-0000-1000-8000-00805F9B34FB")
    public static let eventCharUUID = CBUUID(string: "0000A003-0000-1000-8000-00805F9B34FB")

    @Published public var isConnected: Bool = false
    @Published public var deviceName: String = "قطع ارتباط"
    @Published public var batteryLevel: Int = 0
    @Published public var simCarrier: String = "-"
    @Published public var logs: [String] = []

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    public func startScanning() {
        guard centralManager.state == .poweredOn else { return }
        log("در حال جستجوی دستگاه اندروید...")
        centralManager.scanForPeripherals(withServices: [BLEClient.serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false
        ])
    }

    public func disconnect() {
        if let peripheral = peripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }

    public func sendCommand(_ dict: [String: Any]) {
        guard let char = commandCharacteristic, let peripheral = peripheral else {
            log("خطا: دستگاه برای ارسال دستور متصل نیست.")
            return
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: dict)
            peripheral.writeValue(data, for: char, type: .withResponse)
            log("دستور ارسال شد: \(dict["cmd"] ?? "")")
        } catch {
            log("خطا در سریالایز دستور: \(error)")
        }
    }

    private func log(_ msg: String) {
        DispatchQueue.main.async {
            let timestamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
            self.logs.insert("[\(timestamp)] \(msg)", at: 0)
        }
    }
}

// MARK: - CBCentralManagerDelegate
extension BLEClient: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            log("بلوتوث آیفون روشن و آماده است.")
            startScanning()
        case .poweredOff:
            log("بلوتوث آیفون خاموش است.")
            isConnected = false
        default:
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        log("دستگاه اندروید یافت شد: \(peripheral.name ?? "Android Gateway")")
        self.peripheral = peripheral
        centralManager.stopScan()
        centralManager.connect(peripheral, options: [
            CBConnectPeripheralOptionNotifyOnConnectionKey: true,
            CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
        ])
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        self.isConnected = true
        self.deviceName = peripheral.name ?? "Android Gateway"
        log("با موفقیت به اندروید متصل شد.")
        peripheral.delegate = self
        peripheral.discoverServices([BLEClient.serviceUUID])
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        self.isConnected = false
        self.deviceName = "قطع ارتباط"
        log("اتصال به اندروید قطع شد. شروع اسکن مجدد...")
        startScanning()
    }
}

// MARK: - CBPeripheralDelegate
extension BLEClient: CBPeripheralDelegate {
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == BLEClient.serviceUUID {
            peripheral.discoverCharacteristics([BLEClient.commandCharUUID, BLEClient.eventCharUUID], for: service)
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
                log("کانال دریافت نوتیفیکیشن‌ها و تماس‌ها فعال شد.")
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        do {
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                handleIncomingEvent(json)
            }
        } catch {
            log("خطا در پارس پیام دریافتی")
        }
    }

    private func handleIncomingEvent(_ json: [String: Any]) {
        guard let type = json["type"] as? String else { return }

        switch type {
        case "CALL_INCOMING":
            let callerNumber = json["callerNumber"] as? String ?? "ناشناس"
            let callerName = json["callerName"] as? String ?? ""
            let callUUID = UUID(uuidString: json["callId"] as? String ?? "") ?? UUID()
            log("تماس ورودی از: \(callerName.isEmpty ? callerNumber : callerName)")
            
            // فراخوانی رابط کاربری بومی آیفون با CallKit
            CallManager.shared.reportIncomingCall(uuid: callUUID, handle: callerNumber, callerName: callerName)

        case "CALL_ENDED":
            if let activeUUID = CallManager.shared.activeCallUUID {
                CallManager.shared.endCall(uuid: activeUUID)
                log("تماس پایان یافت.")
            }

        case "SMS_INCOMING":
            let sender = json["sender"] as? String ?? "ناشناس"
            let body = json["body"] as? String ?? ""
            log("پیامک از \(sender): \(body)")

        case "DEVICE_STATUS":
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
}

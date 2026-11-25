import Foundation
import Combine
import CallKit
import AVFoundation

/// کلاس مدیریت ایمن CallKit با محافظت کامل در برابر کرش‌های سایدلود
public class CallManager: NSObject, ObservableObject {
    public static let shared = CallManager()

    private var provider: CXProvider?
    private let callController = CXCallController()
    
    public var onAnswerCall: ((UUID) -> Void)?
    public var onEndCall: ((UUID) -> Void)?

    @Published public var activeCallUUID: UUID?
    @Published public var callerName: String = ""
    @Published public var isCallKitAvailable: Bool = false

    override init() {
        super.init()
        setupCallKit()
    }

    /// راه‌اندازی ایمن CallKit بعد از لود شدن کامل رابط کاربری
    public func setupCallKit() {
        guard provider == nil else { return }
        
        let configuration = CXProviderConfiguration(localizedName: "PhoneBridge")
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]

        let newProvider = CXProvider(configuration: configuration)
        newProvider.setDelegate(self, queue: nil)
        self.provider = newProvider
        self.isCallKitAvailable = true
        print("[CallKit] CallKit provider initialized safely.")
    }

    /// نمایش صفحه تماس ورودی بومی در آیفون یا به‌روزرسانی نام و شماره
    public func reportOrUpdateIncomingCall(uuid: UUID, handle: String, callerName: String, completion: (@escaping (Error?) -> Void) = { _ in }) {
        let displayName = callerName.isEmpty ? handle : callerName
        
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .phoneNumber, value: handle)
        update.localizedCallerName = displayName
        update.hasVideo = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false
        
        guard let provider = self.provider else {
            print("[CallKit] Provider not ready")
            completion(nil)
            return
        }
        
        if self.activeCallUUID == uuid {
            self.callerName = displayName
            provider.reportCall(with: uuid, updated: update)
            print("[CallKit] Updated call details: \(displayName) (\(handle))")
            completion(nil)
        } else {
            self.activeCallUUID = uuid
            self.callerName = displayName
            provider.reportNewIncomingCall(with: uuid, update: update) { error in
                if let error = error {
                    print("[CallKit] Error reporting call: \(error.localizedDescription)")
                } else {
                    print("[CallKit] Incoming call reported: \(displayName)")
                }
                completion(error)
            }
        }
    }

    /// متد کمکی برای گزارش تماس
    public func reportIncomingCall(uuid: UUID, handle: String, callerName: String, completion: (@escaping (Error?) -> Void) = { _ in }) {
        reportOrUpdateIncomingCall(uuid: uuid, handle: handle, callerName: callerName, completion: completion)
    }

    /// @deprecated متد دیگر استفاده نمی‌شود - تماس‌های خروجی فقط از طریق اپ Phone بومی آیفون
    /// برقراری تماس خروجی از طریق CallKit
    public func startOutgoingCall(handle: String) {
        print("[CallKit] Warning: startOutgoingCall() is deprecated and unused. Use native iOS Phone app for outgoing calls.")
        let uuid = UUID()
        self.activeCallUUID = uuid
        self.callerName = handle

        let handleObj = CXHandle(type: .phoneNumber, value: handle)
        let startCallAction = CXStartCallAction(call: uuid, handle: handleObj)
        startCallAction.isVideo = false

        let transaction = CXTransaction(action: startCallAction)
        callController.request(transaction) { error in
            if let error = error {
                print("[CallKit] Request start outgoing call error: \(error.localizedDescription)")
            } else {
                print("[CallKit] Outgoing call requested: \(handle)")
            }
        }
    }

    /// بستن صفحه تماس هنگامی که تماس مستقیماً روی گوشی اندروید پاسخ داده شده است
    public func dismissCall(uuid: UUID) {
        guard self.activeCallUUID == uuid else { return }
        provider?.reportCall(with: uuid, endedAt: Date(), reason: .answeredElsewhere)
        self.activeCallUUID = nil
        print("[CallKit] Call dismissed (answered on Android device)")
    }

    /// پایان دادن به تماس در لایه CallKit
    public func endCall(uuid: UUID) {
        let endCallAction = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: endCallAction)
        callController.request(transaction) { error in
            if let error = error {
                print("[CallKit] Request end call error: \(error.localizedDescription)")
            }
        }
        self.activeCallUUID = nil
    }
}

// MARK: - CXProviderDelegate
extension CallManager: CXProviderDelegate {
    public func providerDidReset(_ provider: CXProvider) {
        AudioStreamManager.shared.stopAudioSession()
        activeCallUUID = nil
    }

    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        print("[CallKit] User answered the call on iPhone")
        action.fulfill()
        if let uuid = activeCallUUID {
            onAnswerCall?(uuid)
        }
    }

    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        print("[CallKit] User or remote ended the call")
        action.fulfill()
        if let uuid = activeCallUUID {
            onEndCall?(uuid)
        }
        activeCallUUID = nil
    }

    public func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        print("[CallKit] Outgoing call fulfilled by system")
        action.fulfill()
    }

    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        print("[CallKit] User toggled mute: \(action.isMuted)")
        action.fulfill()
        AudioStreamManager.shared.isMuted = action.isMuted
        BLEClient.shared.sendCommand(["cmd": "SET_MUTE", "muted": action.isMuted])
    }

    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("[CallKit] Audio session activated by system")
        AudioStreamManager.shared.startAudioSession()
        
        // شروع دریافت صدا از کانال L2CAP
        if let channel = BLEClient.shared.getL2CAPChannel() {
            AudioStreamManager.shared.startReceiving(channel: channel)
        }
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        print("[CallKit] Audio session deactivated")
        AudioStreamManager.shared.stopAudioSession()
    }
}

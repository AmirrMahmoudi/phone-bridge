import Foundation
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

    /// نمایش صفحه تماس ورودی بومی در آیفون
    public func reportIncomingCall(uuid: UUID, handle: String, callerName: String, completion: (@escaping (Error?) -> Void) = { _ in }) {
        self.activeCallUUID = uuid
        self.callerName = callerName.isEmpty ? handle : callerName

        guard let provider = self.provider else {
            print("[CallKit] Fallback: CallKit provider not initialized.")
            completion(nil)
            return
        }

        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .phoneNumber, value: handle)
        update.localizedCallerName = self.callerName
        update.hasVideo = false
        update.supportsHolding = false
        update.supportsGrouping = false
        update.supportsUngrouping = false

        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            if let error = error {
                print("[CallKit] Error reporting call: \(error.localizedDescription)")
            } else {
                print("[CallKit] Incoming call successfully reported to iOS: \(handle)")
            }
            completion(error)
        }
    }

    /// برقراری تماس خروجی از آیفون با رابط کاربری سیستمی CallKit
    public func startOutgoingCall(handle: String) {
        let uuid = UUID()
        self.activeCallUUID = uuid
        self.callerName = handle

        let handleObj = CXHandle(type: .phoneNumber, value: handle)
        let startCallAction = CXStartCallAction(call: uuid, handle: handleObj)
        let transaction = CXTransaction(action: startCallAction)

        callController.request(transaction) { error in
            if let error = error {
                print("[CallKit] Error starting outgoing call: \(error.localizedDescription)")
            } else {
                print("[CallKit] Outgoing call started successfully: \(handle)")
            }
        }
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

    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("[CallKit] Audio session activated by system")
        AudioStreamManager.shared.startAudioSession()
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        print("[CallKit] Audio session deactivated")
        AudioStreamManager.shared.stopAudioSession()
    }
}

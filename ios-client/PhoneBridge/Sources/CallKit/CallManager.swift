import Foundation
import CallKit
import AVFoundation

/// کلاس مدیریت یکپارچه CallKit اپل برای نمایش صفحه تماس بومی سیستم‌عامل
public class CallManager: NSObject, ObservableObject {
    public static let shared = CallManager()

    private let provider: CXProvider
    private let callController = CXCallController()
    
    public var onAnswerCall: ((UUID) -> Void)?
    public var onEndCall: ((UUID) -> Void)?

    @Published public var activeCallUUID: UUID?
    @Published public var callerName: String = ""

    override init() {
        let configuration = CXProviderConfiguration(localizedName: "PhoneBridge")
        configuration.supportsVideo = false
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.phoneNumber, .generic]

        self.provider = CXProvider(configuration: configuration)
        super.init()
        self.provider.setDelegate(self, queue: nil)
    }

    /// نمایش صفحه تماس ورودی بومی در آیفون (حتی در حالت لاک اسکرین)
    public func reportIncomingCall(uuid: UUID, handle: String, callerName: String, completion: (@escaping (Error?) -> Void) = { _ in }) {
        self.activeCallUUID = uuid
        self.callerName = callerName.isEmpty ? handle : callerName

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
        print("[CallKit] User answered the call")
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

    public func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        print("[CallKit] Audio session activated by system")
        AudioStreamManager.shared.startAudioSession()
    }

    public func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        print("[CallKit] Audio session deactivated")
        AudioStreamManager.shared.stopAudioSession()
    }
}

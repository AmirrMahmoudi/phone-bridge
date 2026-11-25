import SwiftUI

@main
struct PhoneBridgeApp: App {
    @StateObject private var bleClient = BLEClient.shared
    @StateObject private var callManager = CallManager.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleClient)
                .environmentObject(callManager)
                .onAppear {
                    // راه‌اندازی با تاخیر و ایمن بعد از ظاهر شدن صفحه اصلی در UI
                    bleClient.setupBluetooth()
                    callManager.setupCallKit()
                    
                    // وقتی کاربر در ایفون تماس را قبول می‌کند:
                    // فقط دستور ANSWER_CALL را به اندروید بفرست.
                    // audio session را اینجا شروع نکن — CallKit خودش از طریق
                    // provider(_:didActivate:AVAudioSession) آن را مدیریت می‌کند.
                    callManager.onAnswerCall = { callId in
                        bleClient.sendCommand(["cmd": "ANSWER_CALL", "callId": callId.uuidString])
                        // ⚠️ audio را اینجا شروع نکن — provider(_:didActivate:) این کار را می‌کند
                    }

                    callManager.onEndCall = { callId in
                        bleClient.sendCommand(["cmd": "HANGUP_CALL", "callId": callId.uuidString])
                        AudioStreamManager.shared.stopAudioSession()
                    }
                }
        }
    }
}

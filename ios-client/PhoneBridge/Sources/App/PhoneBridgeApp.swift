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
                    
                    callManager.onAnswerCall = { callId in
                        bleClient.sendCommand(["cmd": "ANSWER_CALL", "callId": callId.uuidString])
                        AudioStreamManager.shared.startAudioSession()
                    }
                    
                    callManager.onEndCall = { callId in
                        bleClient.sendCommand(["cmd": "HANGUP_CALL", "callId": callId.uuidString])
                        AudioStreamManager.shared.stopAudioSession()
                    }
                }
        }
    }
}

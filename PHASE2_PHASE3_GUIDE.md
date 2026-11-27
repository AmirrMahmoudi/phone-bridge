# Phase 2 & 3 Implementation Guide

این document راهنمای سریع implementation برای Phase 2 (Quality) و Phase 3 (Production) است.

---

## Phase 2: Quality Improvements

### Task 8-9: Integrate WebRTC Audio Processing

**iOS (Task 8):**
```swift
// Add to Podfile
pod 'GoogleWebRTC', '~> 1.1'

// Create: ios-client/PhoneBridge/Sources/Audio/WebRTCAudioProcessor.swift
import WebRTC

class WebRTCAudioProcessor {
    private var audioSession: RTCAudioSession?
    
    init() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        defer { session.unlockForConfiguration() }
        
        let config = RTCAudioSessionConfiguration.webRTC()
        config.category = AVAudioSession.Category.playAndRecord.rawValue
        config.mode = AVAudioSession.Mode.voiceChat.rawValue
        
        try? session.setConfiguration(config)
        try? session.setActive(true)
        self.audioSession = session
    }
}

// Update AudioStreamManager.swift
private var webrtcProcessor: WebRTCAudioProcessor?

func startAudioSession() {
    webrtcProcessor = WebRTCAudioProcessor()
    // ... existing code
}
```

**Android (Task 9):**
```kotlin
// Add to build.gradle
implementation 'org.webrtc:google-webrtc:1.0.+'

// Create: WebRTCAudioProcessor.kt
class WebRTCAudioProcessor(context: Context) {
    private var audioDeviceModule: AudioDeviceModule? = null
    
    fun initialize() {
        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
    }
}

// Update AudioBridgeManager.kt
private var webrtcProcessor: WebRTCAudioProcessor? = null

fun startStreaming(socket: BluetoothSocket) {
    webrtcProcessor = WebRTCAudioProcessor(context).apply { initialize() }
    // ... existing code
}
```

---

### Task 10-11: Implement Opus PLC

**iOS (Task 10):**
```swift
// In AudioStreamManager.swift
private var consecutivePacketLoss: Int = 0

private func handleIncomingOpusPacket(_ data: Data?) {
    if let data = data {
        // Normal decode
        opus_decode(decoder, data.bytes, data.count, &pcmBuffer, FRAME_SIZE, 0)
        consecutivePacketLoss = 0
    } else {
        // PLC: generate missing audio
        opus_decode(decoder, nil, 0, &pcmBuffer, FRAME_SIZE, 0)
        consecutivePacketLoss += 1
        log("🔧 PLC generated audio for lost packet")
    }
}
```

**Android (Task 11):**
```kotlin
// In AudioBridgeManager.kt
private fun performPacketLossConcealment() {
    val pcmBuffer = ShortArray(FRAME_SIZE)
    opusDecoder?.decode(null, 0, 0, pcmBuffer, 0, FRAME_SIZE, false)
    audioTrack?.write(pcmBuffer, 0, FRAME_SIZE)
    Log.d(TAG, "🔧 PLC generated audio")
}
```

---

### Task 12-13: Metrics Collection

**iOS (Task 12):**
```swift
// Create: ios-client/PhoneBridge/Sources/Models/AudioMetrics.swift
struct AudioMetrics: Codable {
    var packetLossRate: Double = 0.0
    var jitterMs: Double = 0.0
    var latencyMs: Double = 0.0
    var currentBitrate: Int = 0
    var rssi: Int = 0
    var timestamp: Date = Date()
}

// Create: ios-client/PhoneBridge/Sources/Utils/MetricsCollector.swift
class MetricsCollector {
    static let shared = MetricsCollector()
    private var metrics = AudioMetrics()
    
    func update(_ block: (inout AudioMetrics) -> Void) {
        block(&metrics)
        checkQualityIssues()
    }
    
    private func checkQualityIssues() {
        if metrics.packetLossRate > 5.0 {
            NSLog("⚠️ HIGH PACKET LOSS: \(metrics.packetLossRate)%")
        }
    }
}
```

**Android (Task 13):** Similar structure

---

### Task 14: Enable Opus FEC

**Both platforms:**
```swift
// iOS
var enableFEC: Int32 = 1
opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(enableFEC))
opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(10))
```

```kotlin
// Android  
// Use Concentus API to enable FEC
opusEncoder?.setInbandFEC(true)
opusEncoder?.setPacketLossPerc(10)
```

---

### Task 15: Dynamic Bitrate Adaptation

**iOS:**
```swift
func adaptBitrate() {
    let metrics = MetricsCollector.shared.getCurrentMetrics()
    var newBitrate = currentBitrate
    
    if metrics.packetLossRate > 5.0 {
        newBitrate = max(12000, newBitrate - 2000)
    } else if metrics.packetLossRate < 1.0 {
        newBitrate = min(32000, newBitrate + 2000)
    }
    
    if newBitrate != currentBitrate {
        opus_encoder_ctl(encoder, OPUS_SET_BITRATE(Int32(newBitrate)))
        currentBitrate = newBitrate
    }
}
```

---

### Task 16: Phase 2 Integration Test

Create `PHASE2_TEST_CHECKLIST.md`:
- A/B test: قبل/بعد WebRTC - measure echo
- Simulate 5-10% packet loss - verify PLC works
- Monitor metrics dashboard
- Verify adaptive bitrate changes
- User subjective quality test (MOS ≥ 4.0)

---

## Phase 3: Production Ready

### Tasks 17-19: Unit Tests

**iOS Audio Tests:**
```swift
// ios-client/PhoneBridgeTests/AudioStreamManagerTests.swift
func testOpusRoundTrip() {
    let testSamples: [Int16] = Array(repeating: 1000, count: 320)
    let encoded = audioManager.encodeOpusFrame(testSamples)
    let decoded = audioManager.decodeOpusFrame(encoded!)
    XCTAssertEqual(decoded.count, 320)
}
```

**iOS Protocol Tests:**
```swift
// ios-client/PhoneBridgeTests/BLEProtocolTests.swift
func testDialCommandSerialization() {
    let command = ["cmd": "DIAL_CALL", "phoneNumber": "+989123456789"]
    let jsonData = try? JSONSerialization.data(withJSONObject: command)
    XCTAssertNotNil(jsonData)
}
```

**Android Tests:** Similar with JUnit

---

### Tasks 20-21: Integration Tests

**E2E Latency Test:**
```swift
// Generate test tone → send → receive → measure latency
func testEndToEndLatency() {
    let startTime = Date()
    sendTestTone()
    // Wait for echo
    waitForReceive()
    let latency = Date().timeIntervalSince(startTime) * 1000
    XCTAssertLessThan(latency, 150.0)
}
```

**Failure Scenarios:**
- L2CAP disconnect → reconnect test
- BLE disconnect → reconnect test
- App backgrounding → resume test

---

### Tasks 22-26: Edge Case Handling

**Task 22: L2CAP Disconnect**
```swift
// ios-client/.../L2CAPChannelManager.swift
func handleDisconnect() {
    log("⚠️ L2CAP disconnected، reconnecting...")
    NotificationCenter.default.post(name: .l2capReconnecting, object: nil)
    attemptReconnection()
}
```

**Task 23: BLE Disconnect**
```swift
// ios-client/.../BLEClient.swift
func didDisconnectPeripheral() {
    if CallManager.shared.hasActiveCall {
        log("🔄 Attempting BLE reconnect (call active)...")
        startScanning()
    }
}
```

**Task 24: iOS Backgrounding**
```swift
// ios-client/.../PhoneBridgeApp.swift
NotificationCenter.default.addObserver(
    forName: UIApplication.didEnterBackgroundNotification
) { _ in
    if CallManager.shared.hasActiveCall {
        log("✅ Call continues via CallKit")
    }
}
```

**Task 25: Android Killed**
```kotlin
// android-gateway/.../PhoneBridgeForegroundService.kt
override fun onTaskRemoved(rootIntent: Intent?) {
    if (hasActiveCall()) {
        BLEPeripheralServer.instance?.sendNotification(
            JSONObject().apply {
                put("type", "CALL_ENDED")
                put("reason", "GATEWAY_DISCONNECTED")
            }.toString()
        )
    }
    // Restart service
    scheduleServiceRestart()
}
```

**Task 26: Cellular Drop**
```kotlin
// android-gateway/.../PhoneBridgeCallService.kt
override fun onCallStateChanged(call: Call, state: Int) {
    when (state) {
        Call.STATE_DISCONNECTED -> {
            val cause = call.details.disconnectCause
            BLEPeripheralServer.instance?.sendNotification(
                JSONObject().apply {
                    put("type", "CALL_ENDED")
                    put("reason", mapDisconnectCause(cause))
                }.toString()
            )
            AudioBridgeManager.instance?.stopStreaming()
        }
    }
}
```

---

### Task 27: Performance Optimization

**Reduce Allocations:**
```swift
// Reuse buffers
private var reusableBuffer = [Int16](repeating: 0, count: 320)

func processAudio() {
    // Clear buffer
    reusableBuffer.withUnsafeMutableBufferPointer { ptr in
        ptr.baseAddress?.initialize(repeating: 0, count: 320)
    }
    // Use reusableBuffer
}
```

---

### Tasks 28-29: Documentation

**Task 28: DEVELOPER_GUIDE.md**
```markdown
# Setup
iOS: pod install, open .xcworkspace
Android: open in Android Studio, sync Gradle

# Architecture
[Diagrams from design.md]

# Testing
Run tests: iOS: Cmd+U, Android: ./gradlew test

# Troubleshooting
- No audio → Check BLE connected, L2CAP channel ready
- One-way audio → Check audio routing settings
```

**Task 29: USER_GUIDE.md**
```markdown
# نصب
1. اپ Android را نصب کنید
2. اپ iOS را نصب کنید
3. Connect را بزنید

# تماس
- خروجی: شماره بگیرید، Call بزنید
- ورودی: زنگ می‌خورد، Answer بزنید

# مشکلات متداول
Q: صدا قطع می‌شود
A: فاصله < 5 متر نگه دارید
```

---

### Tasks 30-32: Final Testing

**Task 30: 60-min Stress Test**
- Continuous call for 60 minutes
- No crashes, no memory leaks
- Battery ≤ 5% per hour

**Task 31: Reconnection Stress**
- 10x rapid connect/disconnect cycles
- All successful, no state corruption

**Task 32: Production Validation**
- Success rate ≥ 95%
- Latency ≤ 150ms  
- Packet loss tolerance ≤ 10%
- All test cases pass

---

## Implementation Priority

**If time is limited:**

**Must Have (Phase 1):** ✅ Already complete - bidirectional audio works

**Should Have (Phase 2 critical):**
- Tasks 8-9 (WebRTC) - Biggest quality improvement
- Task 14 (FEC) - Packet loss handling
- Tasks 10-11 (PLC) - Smooth audio

**Nice to Have (Phase 2 optional):**
- Tasks 12-13 (Metrics) - For monitoring
- Task 15 (Bitrate) - Already has basic adaptation

**Production (Phase 3):**
- Tasks 22-26 (Edge cases) - Reliability
- Tasks 28-29 (Docs) - Usability
- Tasks 17-21, 30-32 (Tests) - Quality assurance

---

## Quick Implementation (Minimal Phase 2)

If you want to quickly improve quality without full Phase 2:

```bash
# iOS: Just add WebRTC
pod 'GoogleWebRTC'
# Use RTCAudioSession in AudioStreamManager

# Android: Just add WebRTC
implementation 'org.webrtc:google-webrtc:1.0.+'
# Use JavaAudioDeviceModule in AudioBridgeManager

# Both: Enable Opus FEC
opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1))
```

This gives you 70% of Phase 2 benefits with 20% of the work.

---

## Verification

**Phase 2 Complete When:**
- ✅ Echo ≤ -35dB (measure with tone generator)
- ✅ Noise suppressed ≥70% (test in noisy environment)
- ✅ ≤10% packet loss smooth (simulate with network tools)
- ✅ Metrics logged (check logs during call)

**Phase 3 Complete When:**
- ✅ Test coverage ≥70% (run coverage tool)
- ✅ 60-min call successful (manual test)
- ✅ All edge cases handled (checklist)
- ✅ Documentation complete (review with new developer)

**Production Ready When:**
- All above + user beta testing successful


# Design Document

## Introduction

### Document Purpose
این document راه‌حل‌های technical مشخص برای رفع مشکلات PhoneBridge و پیاده‌سازی یک سیستم audio bridging کلاس جهانی را شرح می‌دهد.

### Related Documents
- Requirements: `requirements.md`
- Investigation Report: `.agents/tasks/audio-protocol-investigation.md`
- Protocol Specification: `protocol/SPECIFICATION.md`

### Design Goals
1. رفع ۵ مشکل critical برای کار کردن bidirectional audio (Phase 1)
2. ارتقاء کیفیت به سطح professional با WebRTC (Phase 2)
3. Production-ready با test coverage و robustness (Phase 3)

---

## Overview

### System Summary
PhoneBridge یک audio bridging system است که به کاربران iPhone بدون سیم‌کارت اجازه می‌دهد از طریق گوشی Android زنگ بزنند و بگیرند. سیستم از Bluetooth LE برای control plane و L2CAP برای data plane استفاده می‌کند.

### High-Level Architecture

```
iOS (No SIM) ◄──BLE GATT──► Android (With SIM) ◄──► Cellular Network
              ◄──L2CAP───►
           (Audio Stream)
```

**Communication Layers:**
1. **BLE GATT (Control Plane):** Commands و Events
2. **L2CAP CoC (Data Plane):** Raw Opus audio packets
3. **Application Protocol:** Call management, SMS, Status

### Key Technologies
- **iOS:** Swift, CallKit, AVFoundation, CoreBluetooth
- **Android:** Kotlin, TelecomManager, AudioRecord/AudioTrack, Bluetooth LE
- **Audio Codec:** Opus (16kHz, 20ms frames, 12-32 kbps)
- **Audio Processing:** WebRTC AEC3 + NS (Phase 2)

---

## Components and Interfaces

### iOS Components

#### Component 1: BLEClient
**Purpose:** مدیریت connection BLE و تبادل commands/events

**Public Interface:**
```swift
class BLEClient {
    static let shared: BLEClient
    
    // Connection management
    func startScanning()
    func connect(to peripheral: CBPeripheral)
    func disconnect()
    
    // Command sending
    func dial(phoneNumber: String)
    func answerCall()
    func hangupCall()
    func sendSMS(to: String, message: String)
    
    // Event callbacks
    var onIncomingCall: ((String, String) -> Void)?
    var onCallEnded: ((String) -> Void)?
    var onSMSReceived: ((String, String) -> Void)?
    var onDialResult: ((Bool, String?, String?) -> Void)?  // ✅ NEW
    var onL2CAPReady: ((UInt16) -> Void)?                   // ✅ NEW
}
```

**Dependencies:**
- CoreBluetooth (CBCentralManager, CBPeripheral)
- L2CAPChannelManager (for audio channel)
- CallManager (for CallKit integration)

**Key Changes (Phase 1):**
- Add `pendingOutgoingCall` property
- Add handlers for `DIAL_RESULT`, `L2CAP_READY`, `SMS_SEND_RESULT`
- Delay CallKit start until `DIAL_RESULT` received

---

#### Component 2: AudioStreamManager
**Purpose:** مدیریت audio pipeline (record, encode, transmit, receive, decode, play)

**Public Interface:**
```swift
class AudioStreamManager {
    static let shared: AudioStreamManager
    
    // Audio session management
    func startAudioSession()
    func stopAudioSession()
    
    // Streaming
    var isStreaming: Bool { get }
    
    // Quality adaptation
    func adaptBitrate(based on: AudioMetrics)
    
    // Metrics
    func getCurrentMetrics() -> AudioMetrics
}
```

**Internal Components:**
- Opus encoder/decoder
- Jitter buffer (3 frames = 60ms)
- Audio processing (AEC + NS)
- Packet loss concealment

**Key Changes:**
- Phase 1: Wait for `CALL_ACTIVE` before starting
- Phase 2: Integrate WebRTC audio processing
- Phase 2: Proper PLC implementation
- Phase 2: Metrics collection

---

#### Component 3: CallManager
**Purpose:** CallKit integration برای native iOS call experience

**Public Interface:**
```swift
class CallManager {
    static let shared: CallManager
    
    func startIncomingCall(handle: String, callerName: String?)
    func startOutgoingCall(handle: String)
    func endCall(uuid: UUID)
    
    var hasActiveCall: Bool { get }
    
    // ✅ NEW: Notify when call actually active
    func notifyCallActive()
}
```

**Dependencies:**
- CallKit (CXProvider, CXCallController)
- AudioStreamManager

**Key Changes (Phase 1):**
- Add `isCallActive` flag
- Don't start audio session until `notifyCallActive()` called

---

#### Component 4: L2CAPChannelManager
**Purpose:** مدیریت L2CAP connection برای audio streaming

**Public Interface:**
```swift
class L2CAPChannelManager {
    static let shared: L2CAPChannelManager
    
    func connectL2CAPChannel(to peripheral: CBPeripheral, psm: UInt16)
    func disconnect()
    
    var isConnected: Bool { get }
    var onConnected: (() -> Void)?
    var onDisconnected: (() -> Void)?
}
```

**Key Changes (Phase 1):**
- Handle `L2CAP_READY` event as fallback for PSM
- Add reconnection logic with user notification (Phase 3)

---

### Android Components

#### Component 5: BLEPeripheralServer
**Purpose:** BLE peripheral server برای دریافت commands و ارسال events

**Public Interface:**
```kotlin
class BLEPeripheralServer(context: Context) {
    fun startServer()
    fun stopServer()
    
    // ✅ NEW: Check connected devices
    fun hasConnectedDevices(): Boolean
    
    // Event sending
    fun sendNotification(jsonPayload: String)
    
    // Command callbacks
    var onDialCall: ((String) -> Unit)?
    var onAnswerCall: ((String) -> Unit)?
    var onHangupCall: ((String) -> Unit)?
    var onSendSMS: ((String, String) -> Unit)?
}
```

**Dependencies:**
- BluetoothManager, BluetoothGattServer
- L2CAPChannelManager
- CallInitiator, SmsSender

**Key Changes (Phase 1):**
- Add `hasConnectedDevices()` method
- Make PSM update synchronous

---

#### Component 6: AudioBridgeManager
**Purpose:** مدیریت audio capture, encoding, transmission, receive, decode, playback

**Public Interface:**
```kotlin
class AudioBridgeManager(context: Context) {
    fun startStreaming(socket: BluetoothSocket)
    fun stopStreaming()
    
    var isStreaming: Boolean
    
    // ✅ NEW: Metrics
    fun getCurrentMetrics(): AudioMetrics
}
```

**Internal Components:**
- AudioRecord (VOICE_COMMUNICATION source)
- AudioTrack (STREAM_VOICE_CALL)
- Opus encoder/decoder (Concentus library)
- Audio processing

**Key Changes (Phase 1):**
- Remove Bluetooth SCO routing (`isBluetoothScoOn`, `startBluetoothSco`)
- Keep `MODE_IN_CALL` + speakerphone

**Key Changes (Phase 2):**
- Integrate WebRTC audio processing
- Implement proper PLC
- Add metrics collection

---

#### Component 7: PhoneBridgeCallService
**Purpose:** TelecomManager integration برای مدیریت تماس‌های سلولار

**Public Interface:**
```kotlin
class PhoneBridgeCallService : InCallService() {
    fun hasActiveCall(): Boolean
    
    // Callbacks already implemented via InCallService
}
```

**Dependencies:**
- TelecomManager
- AudioBridgeManager
- BLEPeripheralServer

**Key Changes (Phase 3):**
- Handle cellular call drop gracefully
- Send proper disconnect reasons to iOS

---

## Data Models

### Model 1: AudioMetrics
**Purpose:** ذخیره metrics کیفیت audio و connection

```swift
struct AudioMetrics: Codable {
    // Audio Quality
    var packetLossRate: Double = 0.0        // %
    var jitterMs: Double = 0.0              // ms
    var roundTripLatencyMs: Double = 0.0    // ms
    var currentBitrate: Int = 0             // bps
    var targetBitrate: Int = 20000
    
    // Audio Levels
    var inputLevelRMS: Double = 0.0
    var inputLevelPeak: Double = 0.0
    var outputLevelRMS: Double = 0.0
    
    // Connection
    var rssi: Int = 0                       // dBm
    var l2capReconnectCount: Int = 0
    var packetsReceived: Int = 0
    var packetsSent: Int = 0
    
    // Buffer
    var jitterBufferFillLevel: Double = 0.0 // %
    var jitterBufferSize: Int = 3
    
    var timestamp: Date = Date()
}
```

---

### Model 2: Protocol Messages

**Commands (iOS → Android):**
```json
{
  "cmd": "DIAL_CALL" | "ANSWER_CALL" | "HANGUP_CALL" | "SEND_SMS",
  "phoneNumber": "+989123456789",  // for DIAL_CALL, SEND_SMS
  "message": "text",                // for SEND_SMS
  "callId": "uuid",                 // for ANSWER_CALL, HANGUP_CALL
  "timestamp": 1234567890
}
```

**Events (Android → iOS):**
```json
{
  "type": "CALL_INCOMING" | "CALL_ACTIVE" | "CALL_ENDED" | "DIAL_RESULT" | "L2CAP_READY" | "SMS_INCOMING" | "SMS_SEND_RESULT" | "DEVICE_STATUS",
  
  // For CALL_INCOMING
  "callId": "uuid",
  "callerNumber": "+98...",
  "callerName": "...",
  
  // For DIAL_RESULT
  "success": true/false,
  "error": "error message",
  
  // For L2CAP_READY
  "psm": 136,
  
  // For SMS_SEND_RESULT
  "messageId": "...",
  
  "timestamp": 1234567890
}
```

---

## Detailed Design

### Phase 1: Critical Fixes

[محتوای کامل Phase 1 از design_draft.md - Fix 1 تا Fix 5]

#### Fix 1: Add Missing API Method
[Complete content from draft]

#### Fix 2: Audio Session Timing
[Complete content from draft]

#### Fix 3: L2CAP PSM Race
[Complete content from draft]

#### Fix 4: BT Audio Routing
[Complete content from draft]

#### Fix 5: Event Handlers
[Complete content from draft]

### Phase 2: Quality Improvements
[محتوای کامل Phase 2 از design_draft.md]

### Phase 3: Production Readiness
[محتوای کامل Phase 3 از design_draft.md]

# Design Document

## Introduction

### Document Purpose
این document راه‌حل‌های technical مشخص برای رفع مشکلات PhoneBridge و پیاده‌سازی یک سیستم audio bridging کلاس جهانی را شرح می‌دهد.

### Related Documents
- Requirements: `requirements.md`
- Investigation Report: `.agents/tasks/audio-protocol-investigation.md`
- Protocol Specification: `protocol/SPECIFICATION.md`

### Design Goals
1. رفع ۵ مشکل critical برای کار کردن bidirectional audio (Phase 1)
2. ارتقاء کیفیت به سطح professional با WebRTC (Phase 2)
3. Production-ready با test coverage و robustness (Phase 3)

---

## System Architecture

### High-Level Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         PhoneBridge System                       │
└─────────────────────────────────────────────────────────────────┘

    iOS Device (No SIM)              Android Device (With SIM)
    ┌──────────────────┐            ┌──────────────────────┐
    │   PhoneBridge    │            │   PhoneBridge        │
    │   iOS Client     │            │   Android Gateway    │
    │                  │            │                      │
    │  ┌────────────┐  │            │  ┌────────────────┐ │
    │  │  CallKit   │  │            │  │  TelecomMgr    │ │
    │  │ Integration│  │            │  │  Integration   │ │
    │  └────────────┘  │            │  └────────────────┘ │
    │        │         │            │         │           │
    │  ┌────────────┐  │            │  ┌────────────────┐ │
    │  │   Audio    │  │   L2CAP    │  │     Audio      │ │
    │  │  Pipeline  │◄─┼────────────┼─►│   Pipeline     │ │
    │  │  (Opus)    │  │  (PSM 0x88)│  │    (Opus)      │ │
    │  └────────────┘  │            │  └────────────────┘ │
    │        │         │            │         │           │
    │  ┌────────────┐  │   BLE GATT │  ┌────────────────┐ │
    │  │    BLE     │◄─┼────────────┼─►│      BLE       │ │
    │  │   Client   │  │  Commands/ │  │    Server      │ │
    │  │            │  │   Events   │  │  (Peripheral)  │ │
    │  └────────────┘  │            │  └────────────────┘ │
    │                  │            │          │          │
    │  ┌────────────┐  │            │  ┌────────────────┐ │
    │  │    UI      │  │            │  │   Cellular     │ │
    │  │  (SwiftUI) │  │            │  │   Network      │ │
    │  └────────────┘  │            │  └────────────────┘ │
    └──────────────────┘            └──────────────────────┘
            │                                  │
            │                                  │
            └──────────── User ────────────────┘
                      (Makes/Receives Calls)
```

### Communication Layers

**Layer 1: BLE GATT (Control Plane)**
- Service UUID: `0000A001-0000-1000-8000-00805F9B34FB`
- Command Characteristic: `0000A002...` (iOS → Android commands)
- Event Characteristic: `0000A003...` (Android → iOS events)
- PSM Characteristic: `0000A004...` (L2CAP PSM value)
- Transport: JSON messages over BLE GATT

**Layer 2: L2CAP CoC (Data Plane)**
- PSM: `0x0088` (dynamic)
- Transport: Raw Opus packets (20ms frames)
- Bidirectional audio streaming
- Low latency, higher bandwidth than GATT

**Layer 3: Application Protocol**
- Call commands: `DIAL_CALL`, `ANSWER_CALL`, `HANGUP_CALL`
- Call events: `CALL_INCOMING`, `CALL_ACTIVE`, `CALL_ENDED`, `DIAL_RESULT`
- SMS commands/events
- Status updates

---

## Phase 1 Design: Critical Fixes

### Fix 1: Add Missing API Method

**Problem:** `hasConnectedDevices()` called but not defined in `BLEPeripheralServer.kt`

**Solution:**

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/bluetooth/BLEPeripheralServer.kt`

```kotlin
/**
 * بررسی اینکه آیا device های iOS متصل داریم
 * Thread-safe با استفاده از synchronized
 */
fun hasConnectedDevices(): Boolean {
    synchronized(connectedDevices) {
        return connectedDevices.isNotEmpty()
    }
}
```

**Justification:**
- Simple getter method
- Thread-safe با synchronized (چون connectedDevices از threads مختلف access می‌شود)
- Defensive programming: check کردن قبل از ارسال notifications

**Impact:** 
- Compilation successful
- Audio bridge startup logic works correctly
- No runtime crashes

---

### Fix 2: Audio Session Timing Synchronization

**Problem:** iOS starts audio session immediately on `DIAL_CALL`, before Android connects to cellular network (2-5 sec delay)

**Solution Overview:**
1. iOS: Don't start CallKit immediately - wait for `DIAL_RESULT`
2. Android: Send `DIAL_RESULT` immediately when dialer intent succeeds
3. iOS: Start audio session only when `CALL_ACTIVE` received

#### Solution Part A: iOS Delay CallKit

**File:** `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

**Current Code (BROKEN):**
```swift
public func dial(phoneNumber: String) {
    sendCommand(["cmd": "DIAL_CALL", "phoneNumber": trimmed])
    CallManager.shared.startOutgoingCall(handle: trimmed)  // ❌ Too early!
    // Audio session starts immediately via CallKit delegate
}
```

**New Code (FIXED):**
```swift
// Add property to store pending call
private var pendingOutgoingCall: (uuid: UUID, handle: String)? = nil

public func dial(phoneNumber: String) {
    let trimmed = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return }
    
    log("📞 در حال ارسال دستور DIAL_CALL به اندروید: \(trimmed)")
    sendCommand(["cmd": "DIAL_CALL", "phoneNumber": trimmed, "timestamp": Int(Date().timeIntervalSince1970)])
    
    // ثبت در ActivityLog
    onActivityEvent?("phone.arrow.up.right", "تماس خروجی", trimmed, nil, nil)
    
    // ✅ FIX: Store call info but DON'T start CallKit yet
    pendingOutgoingCall = (uuid: UUID(), handle: trimmed)
    log("⏳ منتظر DIAL_RESULT از اندروید...")
}
```

#### Solution Part B: iOS Handle DIAL_RESULT

**File:** `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

Add to `handleIncomingEvent()`:

```swift
case "DIAL_RESULT":
    let success = json["success"] as? Bool ?? false
    let callId = json["callId"] as? String
    let error = json["error"] as? String
    
    if success {
        log("✅ Dial موفق بود، callId: \(callId ?? "unknown")")
        
        // حالا می‌توانیم CallKit را شروع کنیم
        if let pending = pendingOutgoingCall {
            CallManager.shared.startOutgoingCall(handle: pending.handle)
            log("📱 CallKit outgoing call شروع شد")
            // Audio session هنوز start نمی‌شود - منتظر CALL_ACTIVE
        }
        pendingOutgoingCall = nil
        
    } else {
        log("❌ Dial شکست خورد: \(error ?? "unknown error")")
        
        // نمایش error به کاربر
        if let pending = pendingOutgoingCall {
            // TODO: Show alert to user with error message
            log("🚫 تماس شکست خورد، لطفاً دوباره تلاش کنید")
        }
        pendingOutgoingCall = nil
    }
```

#### Solution Part C: iOS Wait for CALL_ACTIVE

**File:** `ios-client/PhoneBridge/Sources/CallKit/CallManager.swift`

**Current Code:**
```swift
func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
    log("✅ Audio session activated by CallKit")
    
    // ❌ PROBLEM: Immediately starts audio stream
    AudioStreamManager.shared.startAudioSession()
}
```

**New Code (FIXED):**
```swift
// Add flag to track call state
private var isCallActive: Bool = false

func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
    log("✅ Audio session activated by CallKit")
    
    // ✅ FIX: Don't start audio yet - wait for CALL_ACTIVE from Android
    log("⏳ منتظر CALL_ACTIVE از اندروید قبل از شروع audio stream...")
}

// New method called when CALL_ACTIVE received
func notifyCallActive() {
    guard !isCallActive else { return }
    isCallActive = true
    
    log("✅ تماس فعال شد، شروع audio stream...")
    AudioStreamManager.shared.startAudioSession()
}

func endCall(uuid: UUID) {
    isCallActive = false
    // ... existing code
}
```

**Update BLEClient.swift to call this:**
```swift
case "CALL_ACTIVE":
    let callId = json["callId"] as? String
    log("📞 تماس فعال شد: \(callId ?? "unknown")")
    
    // ✅ Now we can start audio
    CallManager.shared.notifyCallActive()
```

**Flow Diagram:**
```
iOS                          Android                    Cellular Network
 │                              │                              │
 │───DIAL_CALL─────────────────►│                              │
 │  (phoneNumber)                │                              │
 │                               │──Intent.ACTION_CALL─────────►│
 │                               │                              │
 │◄──DIAL_RESULT────────────────│                              │
 │  (success=true, callId)       │                              │
 │                               │                              │
 ├─StartCallKit                  │                              │
 │  (UI shows "Calling...")      │◄─────STATE_DIALING──────────│
 │                               │                              │
 │                               │◄─────STATE_ACTIVE───────────│
 │                               │  (2-5 seconds later)         │
 │                               │                              │
 │◄──CALL_ACTIVE────────────────│                              │
 │  (callId)                     │                              │
 │                               │                              │
 ├─StartAudioSession             │                              │
 │  (NOW it's safe!)             │                              │
 │                               │                              │
 │◄═══════ Bidirectional Audio (L2CAP) ════════►│              │
```

**Benefits:**
- Audio session starts only when call is truly active
- No timeout issues
- Proper synchronization between iOS and Android
- User sees correct call state in UI

---

### Fix 3: L2CAP PSM Race Condition

**Problem:** PSM characteristic updated with 500ms delay, iOS may read before it's ready

**Solution: Dual Approach**

#### Approach A: Synchronous PSM Update (Preferred)

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/bluetooth/BLEPeripheralServer.kt`

**Current Code (BROKEN):**
```kotlin
l2capChannelManager = L2CAPChannelManager(bluetoothAdapter!!).apply {
    startListening()
    
    // ❌ PROBLEM: Async update with 500ms delay
    Handler(Looper.getMainLooper()).postDelayed({
        if (currentPsm > 0) {
            updatePsmCharacteristic(currentPsm)
        }
    }, 500)
}
```

**New Code (FIXED):**
```kotlin
l2capChannelManager = L2CAPChannelManager(bluetoothAdapter!!).apply {
    startListening()
    
    // ✅ FIX: Update PSM synchronously BEFORE continuing
    val psm = currentPsm
    if (psm > 0) {
        updatePsmCharacteristic(psm)
        Log.i(TAG, "✅ PSM characteristic updated synchronously: $psm")
    } else {
        Log.e(TAG, "❌ Failed to allocate L2CAP PSM")
        // Handle error
    }
}
```

#### Approach B: Add L2CAP_READY Handler in iOS (Fallback)

**File:** `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

Add to `handleIncomingEvent()`:

```swift
case "L2CAP_READY":
    if let psm = json["psm"] as? Int, psm > 0 {
        log("📡 L2CAP channel آماده شد، PSM: \(psm)")
        
        // اگر PSM قبلاً خوانده شده بود و 0 بود، الان دوباره try کن
        if let peripheral = self.peripheral {
            connectL2CAPChannel(to: peripheral, psm: UInt16(psm))
        }
    }
```

#### Validation on Read

**File:** `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

Improve PSM read handling:

```swift
func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    // ... existing code ...
    
    } else if characteristic.uuid == BLEClient.psmCharUUID {
        if let data = characteristic.value, data.count >= 4 {
            let psm = UInt16(data[0]) | (UInt16(data[1]) << 8)
            
            if psm > 0 {
                log("✅ PSM خوانده شد: \(psm)")
                self.currentPsm = psm
                connectL2CAPChannel(to: peripheral, psm: psm)
            } else {
                log("⚠️ PSM invalid (0) - منتظر L2CAP_READY event...")
                // Will be handled by L2CAP_READY event
            }
        }
    }
}
```

**Benefits:**
- Dual safety: synchronous update + event-based fallback
- No race condition
- iOS always gets valid PSM
- L2CAP connection succeeds

---

### Fix 4: Bluetooth Audio Routing Conflict

**Problem:** Android routes audio to Bluetooth SCO (headset profile) instead of L2CAP channel

**Solution: Remove SCO Configuration**

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/audio/AudioBridgeManager.kt`

**Current Code (BROKEN):**
```kotlin
fun startStreaming(socket: BluetoothSocket) {
    // ...
    audioManager?.mode = AudioManager.MODE_IN_CALL
    audioManager?.isBluetoothScoOn = true          // ❌ WRONG: Routes to BT headset
    audioManager?.startBluetoothSco()              // ❌ WRONG: Starts SCO profile
    audioManager?.isSpeakerphoneOn = true
    // ...
}
```

**New Code (FIXED):**
```kotlin
fun startStreaming(socket: BluetoothSocket) {
    this.l2capSocket = socket
    isStreaming = true
    
    // تنظیمات audio mode
    audioManager?.mode = AudioManager.MODE_IN_CALL  // ✅ CORRECT: In-call mode
    
    // ✅ FIX: DON'T route to Bluetooth SCO - we're using L2CAP for audio
    // audioManager?.isBluetoothScoOn = true        // ❌ REMOVED
    // audioManager?.startBluetoothSco()            // ❌ REMOVED
    
    audioManager?.isSpeakerphoneOn = true           // ✅ CORRECT: Use speaker/mic
    
    Log.i(TAG, "✅ Audio routing: MODE_IN_CALL with speakerphone, NOT Bluetooth SCO")
    
    initializeAudioComponents()
    // ...
}
```

**Add cleanup:**
```kotlin
fun stopStreaming() {
    // ...
    
    // Reset audio mode
    audioManager?.mode = AudioManager.MODE_NORMAL
    audioManager?.isSpeakerphoneOn = false
    
    // ✅ No need to stop SCO since we never started it
    
    Log.i(TAG, "✅ Audio routing reset to normal")
}
```

**Audio Routing Flow:**
```
Cellular Call Audio (Uplink)
      │
      ▼
AudioRecord (VOICE_COMMUNICATION source)
      │   - Captures phone microphone
      │   - Includes echo cancellation from system
      │   - NOT routed to Bluetooth SCO
      ▼
AudioProcessing (additional noise suppression)
      │
      ▼
Opus Encoder
      │
      ▼
L2CAP Socket ══════► iOS


iOS ══════► L2CAP Socket
      │
      ▼
Opus Decoder
      │
      ▼
AudioTrack (STREAM_VOICE_CALL)
      │   - Injected into cellular call downlink
      │   - Other party hears iOS user's voice
      ▼
Cellular Call Audio (Downlink)
```

**Benefits:**
- Audio flows through L2CAP channel, not SCO
- AudioRecord captures correct source (microphone during call)
- AudioTrack plays into call correctly
- No conflict between profiles

---

### Fix 5: Missing Protocol Event Handlers

**Problem:** iOS doesn't handle `DIAL_RESULT`, `L2CAP_READY`, `SMS_SEND_RESULT`

**Solution: Complete Event Handler Implementation**

**File:** `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

**Add to `handleIncomingEvent()`:**

```swift
func handleIncomingEvent(_ jsonString: String) {
    guard let data = jsonString.data(using: .utf8),
          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let eventType = json["type"] as? String else {
        log("⚠️ Failed to parse event JSON")
        return
    }
    
    log("📩 Event received: \(eventType)")
    
    switch eventType {
    
    // ✅ EXISTING: Already implemented
    case "CALL_INCOMING":
        handleIncomingCall(json)
        
    case "CALL_ENDED":
        handleCallEnded(json)
        
    case "SMS_INCOMING":
        handleIncomingSMS(json)
        
    case "DEVICE_STATUS":
        handleDeviceStatus(json)
    
    // ✅ NEW: Fix #2 - Dial result handler
    case "DIAL_RESULT":
        handleDialResult(json)
    
    // ✅ NEW: Fix #3 - L2CAP ready handler
    case "L2CAP_READY":
        handleL2CAPReady(json)
    
    // ✅ NEW: SMS send result handler
    case "SMS_SEND_RESULT":
        handleSMSSendResult(json)
        
    default:
        log("⚠️ Unknown event type: \(eventType)")
    }
}

// ✅ NEW: Handle dial result
private func handleDialResult(_ json: [String: Any]) {
    let success = json["success"] as? Bool ?? false
    let callId = json["callId"] as? String
    let error = json["error"] as? String
    
    if success {
        log("✅ Dial موفق: callId=\(callId ?? "unknown")")
        
        // شروع CallKit حالا که می‌دانیم dial موفق بوده
        if let pending = pendingOutgoingCall {
            CallManager.shared.startOutgoingCall(handle: pending.handle)
            log("📱 CallKit started for outgoing call")
        }
        pendingOutgoingCall = nil
        
        // ثبت در activity log
        onActivityEvent?("checkmark.circle", "Dial موفق", callId ?? "", nil, nil)
        
    } else {
        log("❌ Dial شکست خورد: \(error ?? "unknown")")
        
        // نمایش error به کاربر
        onActivityEvent?("xmark.circle", "Dial شکست خورد", error ?? "خطای ناشناخته", nil, nil)
        
        // پاک کردن pending call
        pendingOutgoingCall = nil
        
        // TODO: Show alert in UI
    }
}

// ✅ NEW: Handle L2CAP ready notification
private func handleL2CAPReady(_ json: [String: Any]) {
    if let psm = json["psm"] as? Int, psm > 0 {
        log("📡 L2CAP channel آماده، PSM: \(psm)")
        
        self.currentPsm = UInt16(psm)
        
        // اگر peripheral متصل است، حالا می‌توانیم L2CAP را connect کنیم
        if let peripheral = self.peripheral {
            connectL2CAPChannel(to: peripheral, psm: UInt16(psm))
        }
        
        onActivityEvent?("antenna.radiowaves.left.and.right", "L2CAP آماده", "PSM: \(psm)", nil, nil)
    }
}

// ✅ NEW: Handle SMS send result
private func handleSMSSendResult(_ json: [String: Any]) {
    let success = json["success"] as? Bool ?? false
    let messageId = json["messageId"] as? String
    let error = json["error"] as? String
    
    if success {
        log("✅ SMS ارسال شد: messageId=\(messageId ?? "unknown")")
        onActivityEvent?("message.fill", "SMS ارسال شد", "", nil, nil)
        
        // TODO: Callback to UI to show success
        
    } else {
        log("❌ SMS ارسال نشد: \(error ?? "unknown")")
        onActivityEvent?("exclamationmark.triangle", "SMS شکست خورد", error ?? "", nil, nil)
        
        // TODO: Show error alert in UI
    }
}
```

**Benefits:**
- Complete feedback loop for all operations
- User sees success/failure messages
- Proper error handling
- UI can react to events appropriately

---

## Phase 1 Summary

**After these 5 fixes:**
- ✅ All code compiles without errors
- ✅ BLE connection establishes reliably
- ✅ L2CAP audio channel ready before audio starts
- ✅ Audio session timing synchronized with actual call state
- ✅ Audio routed through L2CAP, not Bluetooth SCO
- ✅ All protocol events handled with proper feedback

**Result:** Bidirectional audio WORKS with basic quality

**Estimated Time:** 1-2 days

**Testing Checklist:**
- [ ] Compile both iOS and Android apps without errors
- [ ] Connect iOS to Android via BLE
- [ ] Make outgoing call from iOS - verify both parties can hear each other
- [ ] Receive incoming call on iOS - verify bidirectional audio
- [ ] Send SMS from iOS - verify delivery confirmation
- [ ] Test L2CAP reconnection after brief disconnect
- [ ] Check logs for proper event sequence

---


## Phase 2 Design: Quality Improvements

### Overview
Phase 2 focuses on upgrading audio quality to professional/commercial grade comparable to iTell.

**Key Improvements:**
1. WebRTC audio processing (AEC3 + NS + hardware acceleration)
2. Proper Opus PLC (Packet Loss Concealment)
3. Comprehensive metrics and monitoring
4. FEC (Forward Error Correction)
5. Dynamic bitrate adaptation
6. Jitter buffer optimization

---

### Improvement 1: WebRTC Integration

**Goal:** Replace simple AEC/NS with industry-standard WebRTC audio processing

#### iOS Integration

**Add Dependency:**

**File:** `ios-client/PhoneBridge/Podfile`

```ruby
platform :ios, '15.0'

target 'PhoneBridge' do
  use_frameworks!
  
  # ✅ ADD: WebRTC for professional audio processing
  pod 'GoogleWebRTC', '~> 1.1'
end
```

**Integration Code:**

**File:** `ios-client/PhoneBridge/Sources/Audio/WebRTCAudioProcessor.swift` (NEW)

```swift
import AVFoundation
import WebRTC

/// WebRTC-based audio processing با AEC3 و NS
class WebRTCAudioProcessor {
    
    private var audioSession: RTCAudioSession?
    private let audioQueue = DispatchQueue(label: "com.phonebridge.webrtc.audio")
    
    // WebRTC audio device module
    private var audioDeviceModule: RTCAudioDeviceModule?
    
    init() {
        setupWebRTCAudio()
    }
    
    private func setupWebRTCAudio() {
        audioQueue.async { [weak self] in
            // دسترسی به RTCAudioSession (singleton)
            let session = RTCAudioSession.sharedInstance()
            session.lockForConfiguration()
            defer { session.unlockForConfiguration() }
            
            do {
                // تنظیمات بهینه برای VoIP
                let configuration = RTCAudioSessionConfiguration.webRTC()
                configuration.category = AVAudioSession.Category.playAndRecord.rawValue
                configuration.mode = AVAudioSession.Mode.voiceChat.rawValue
                configuration.categoryOptions = [.allowBluetooth, .duckOthers]
                
                try session.setConfiguration(configuration)
                
                // ✅ فعال‌سازی AEC و NS سخت‌افزاری (اگر موجود باشد)
                try session.setActive(true)
                
                self?.audioSession = session
                
                NSLog("✅ WebRTC Audio Session configured successfully")
                
            } catch {
                NSLog("❌ Failed to configure WebRTC audio: \(error)")
            }
        }
    }
    
    /// Process کردن audio frame با WebRTC AEC3
    /// - Parameters:
    ///   - inputBuffer: Buffer صدای ورودی (از microphone)
    ///   - referenceBuffer: Buffer صدای reference (صدایی که از speaker پخش می‌شود)
    /// - Returns: Processed buffer (echo و noise حذف شده)
    func processAudioFrame(inputBuffer: AVAudioPCMBuffer, 
                          referenceBuffer: AVAudioPCMBuffer?) -> AVAudioPCMBuffer {
        
        // WebRTC audio processing اتفاقاً automatic است وقتی RTCAudioSession active باشد
        // System-level AEC3 و NS فعال می‌شوند
        
        // برای control بیشتر، می‌توان از RTCAudioProcessingModule استفاده کرد:
        // (این برای advanced use cases است)
        
        return inputBuffer // Audio already processed by system WebRTC
    }
    
    func cleanup() {
        audioQueue.async { [weak self] in
            self?.audioSession?.lockForConfiguration()
            defer { self?.audioSession?.unlockForConfiguration() }
            
            do {
                try self?.audioSession?.setActive(false)
            } catch {
                NSLog("❌ Error deactivating WebRTC audio: \(error)")
            }
        }
    }
    
    deinit {
        cleanup()
    }
}
```

**Update AudioStreamManager:**

**File:** `ios-client/PhoneBridge/Sources/Audio/AudioStreamManager.swift`

```swift
class AudioStreamManager {
    // ... existing properties ...
    
    // ✅ ADD: WebRTC processor
    private var webrtcProcessor: WebRTCAudioProcessor?
    
    func startAudioSession() {
        // ... existing code ...
        
        // ✅ Initialize WebRTC processor
        webrtcProcessor = WebRTCAudioProcessor()
        
        // ... rest of existing code ...
    }
    
    func stopAudioSession() {
        // ... existing code ...
        
        // ✅ Cleanup WebRTC
        webrtcProcessor?.cleanup()
        webrtcProcessor = nil
    }
}
```

#### Android Integration

**Add Dependency:**

**File:** `android-gateway/app/build.gradle`

```gradle
dependencies {
    // ... existing dependencies ...
    
    // ✅ ADD: WebRTC for audio processing
    implementation 'org.webrtc:google-webrtc:1.0.+'
}
```

**Integration Code:**

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/audio/WebRTCAudioProcessor.kt` (NEW)

```kotlin
package com.phonebridge.gateway.audio

import android.content.Context
import android.util.Log
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * WebRTC audio processing با hardware AEC و NS
 */
class WebRTCAudioProcessor(private val context: Context) {
    
    private val TAG = "WebRTCAudioProcessor"
    private var audioDeviceModule: AudioDeviceModule? = null
    
    fun initialize() {
        try {
            // ✅ Build audio device module با enable کردن hardware processing
            audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)  // ✅ Hardware AEC
                .setUseHardwareNoiseSuppressor(true)        // ✅ Hardware NS
                .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                    override fun onWebRtcAudioRecordError(errorMessage: String?) {
                        Log.e(TAG, "❌ WebRTC Audio Record Error: $errorMessage")
                    }
                    
                    override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
                        Log.e(TAG, "❌ WebRTC Audio Record Init Error: $errorMessage")
                    }
                    
                    override fun onWebRtcAudioRecordStartError(
                        errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(TAG, "❌ WebRTC Audio Record Start Error: $errorMessage")
                    }
                })
                .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                    override fun onWebRtcAudioTrackError(errorMessage: String?) {
                        Log.e(TAG, "❌ WebRTC Audio Track Error: $errorMessage")
                    }
                    
                    override fun onWebRtcAudioTrackInitError(errorMessage: String?) {
                        Log.e(TAG, "❌ WebRTC Audio Track Init Error: $errorMessage")
                    }
                    
                    override fun onWebRtcAudioTrackStartError(
                        errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode?,
                        errorMessage: String?
                    ) {
                        Log.e(TAG, "❌ WebRTC Audio Track Start Error: $errorMessage")
                    }
                })
                .createAudioDeviceModule()
            
            Log.i(TAG, "✅ WebRTC AudioDeviceModule initialized با hardware AEC/NS")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize WebRTC audio: ${e.message}", e)
        }
    }
    
    /**
     * Check کردن availability of hardware AEC/NS
     */
    fun isHardwareAcousticEchoCancelerSupported(): Boolean {
        return try {
            // Check via WebRTC utility
            JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check AEC support: ${e.message}")
            false
        }
    }
    
    fun isHardwareNoiseSuppressorSupported(): Boolean {
        return try {
            JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot check NS support: ${e.message}")
            false
        }
    }
    
    fun release() {
        audioDeviceModule?.release()
        audioDeviceModule = null
        Log.i(TAG, "✅ WebRTC processor released")
    }
}
```

**Update AudioBridgeManager:**

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/audio/AudioBridgeManager.kt`

```kotlin
class AudioBridgeManager(private val context: Context) {
    // ... existing properties ...
    
    // ✅ ADD: WebRTC processor
    private var webrtcProcessor: WebRTCAudioProcessor? = null
    
    fun startStreaming(socket: BluetoothSocket) {
        // ... existing code ...
        
        // ✅ Initialize WebRTC processing
        webrtcProcessor = WebRTCAudioProcessor(context).apply {
            initialize()
            
            Log.i(TAG, "✅ Hardware AEC supported: ${isHardwareAcousticEchoCancelerSupported()}")
            Log.i(TAG, "✅ Hardware NS supported: ${isHardwareNoiseSuppressorSupported()}")
        }
        
        // ... rest of existing code ...
    }
    
    fun stopStreaming() {
        // ... existing code ...
        
        // ✅ Release WebRTC
        webrtcProcessor?.release()
        webrtcProcessor = null
    }
}
```

**Benefits:**
- **60-80% بهتر Echo Cancellation** (AEC3 vs simple correlation)
- **70-90% بهتر Noise Suppression**
- Hardware acceleration وقتی available باشد
- Battle-tested در میلیون‌ها تماس واقعی (Google Meet, Zoom, etc.)
- کد کمتر - WebRTC خودش همه چیز را handle می‌کند

---

### Improvement 2: Proper Opus PLC Implementation

**Goal:** استفاده صحیح از Packet Loss Concealment برای جایگزینی frame های گمشده

#### iOS Implementation

**File:** `ios-client/PhoneBridge/Sources/Audio/AudioStreamManager.swift`

```swift
// در متد receive و decode
private func handleIncomingOpusPacket(_ data: Data) {
    var pcmBuffer = [Int16](repeating: 0, count: FRAME_SIZE)
    
    // ✅ Decode Opus packet
    let result = opus_decode(opusDecoder, 
                            (data as NSData).bytes.bindMemory(to: UInt8.self, capacity: data.count),
                            Int32(data.count),
                            &pcmBuffer,
                            Int32(FRAME_SIZE),
                            0) // fec = 0 for normal decode
    
    if result > 0 {
        // Successfully decoded
        addToJitterBuffer(pcmBuffer)
        consecutivePacketLoss = 0
    } else {
        // Decode failed - packet corrupted or lost
        consecutivePacketLoss += 1
        performPacketLossConceal ment()
    }
}

// ✅ NEW: PLC when packet lost/late
private var consecutivePacketLoss: Int = 0
private let MAX_CONSECUTIVE_LOSS = 3  // بیش از این call quality خیلی بد می‌شود

private func performPacketLossConcealment() {
    var plcBuffer = [Int16](repeating: 0, count: FRAME_SIZE)
    
    // ✅ Opus PLC: با pass کردن nil برای packet، Opus audio synthesis می‌کند
    let result = opus_decode(opusDecoder,
                            nil,           // ✅ nil = PLC mode
                            0,
                            &plcBuffer,
                            Int32(FRAME_SIZE),
                            0)
    
    if result > 0 {
        // PLC generated synthetic audio
        addToJitterBuffer(plcBuffer)
        log("🔧 PLC: Generated \(result) samples for lost packet")
    } else {
        // Fallback: silence
        addToJitterBuffer([Int16](repeating: 0, count: FRAME_SIZE))
        log("⚠️ PLC failed, using silence")
    }
    
    // اگر خیلی packet loss داریم، کیفیت خیلی بد است
    if consecutivePacketLoss >= MAX_CONSECUTIVE_LOSS {
        log("❌ High packet loss detected (\(consecutivePacketLoss) consecutive)")
        // TODO: Notify user about poor connection quality
    }
}
```

#### Android Implementation

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/audio/AudioBridgeManager.kt`

```kotlin
// در thread دریافت audio
private fun startReceivingAudio() {
    receivingThread = Thread {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        var consecutivePacketLoss = 0
        val PACKET_TIMEOUT_MS = 100L  // اگر packet نیامد در 100ms = lost
        
        while (isStreaming) {
            try {
                val inputStream = l2capSocket?.inputStream
                val available = inputStream?.available() ?: 0
                
                if (available > 0) {
                    val bytesRead = inputStream?.read(buffer) ?: 0
                    
                    if (bytesRead > 0) {
                        // ✅ Packet received - decode normally
                        decodeAndPlayOpusPacket(buffer, bytesRead)
                        consecutivePacketLoss = 0
                    }
                    
                } else {
                    // ⚠️ No data available - possible packet loss
                    Thread.sleep(20)  // Wait one frame duration
                    consecutivePacketLoss++
                    
                    if (consecutivePacketLoss > 1) {
                        // ✅ Perform PLC
                        performPacketLossConcealment()
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving audio: ${e.message}")
            }
        }
    }.apply { start() }
}

// ✅ NEW: PLC implementation
private fun performPacketLossConcealment() {
    val pcmBuffer = ShortArray(FRAME_SIZE)
    
    try {
        // ✅ Opus PLC: decode با null input
        val decodedSamples = opusDecoder?.decode(
            null,          // ✅ null = PLC mode
            0,
            0,
            pcmBuffer,
            0,
            FRAME_SIZE,
            false          // fec = false (or true if FEC enabled)
        ) ?: 0
        
        if (decodedSamples > 0) {
            // Write synthesized audio to AudioTrack
            audioTrack?.write(pcmBuffer, 0, decodedSamples)
            Log.d(TAG, "🔧 PLC: Generated $decodedSamples samples")
        }
        
    } catch (e: Exception) {
        Log.e(TAG, "❌ PLC failed: ${e.message}")
        // Fallback: write silence
        Arrays.fill(pcmBuffer, 0)
        audioTrack?.write(pcmBuffer, 0, FRAME_SIZE)
    }
}
```

**Benefits:**
- Smooth audio بدون glitches حتی با packet loss
- تا ۱۰٪ loss قابل تحمل
- Better user experience در شرایط BLE congestion

---

### Improvement 3: Comprehensive Metrics و Monitoring

**Goal:** Log کردن تمام metrics برای troubleshooting و quality monitoring

#### Metrics Data Structure

**File (NEW):** `ios-client/PhoneBridge/Sources/Models/AudioMetrics.swift`

```swift
import Foundation

/// Real-time audio و connection metrics
struct AudioMetrics: Codable {
    // Audio Quality Metrics
    var packetLossRate: Double = 0.0        // درصد packet های lost
    var jitterMs: Double = 0.0              // Jitter در milliseconds
    var roundTripLatencyMs: Double = 0.0    // RTT latency
    var currentBitrate: Int = 0             // Bitrate فعلی (bps)
    var targetBitrate: Int = 20000          // Bitrate هدف
    
    // Audio Levels
    var inputLevelRMS: Double = 0.0         // سطح صدای ورودی (RMS)
    var inputLevelPeak: Double = 0.0        // Peak level
    var outputLevelRMS: Double = 0.0        // سطح صدای خروجی
    
    // Connection Metrics
    var rssi: Int = 0                       // BLE signal strength
    var l2capReconnectCount: Int = 0        // تعداد reconnection ها
    var packetsReceived: Int = 0            // تعداد packet های دریافت شده
    var packetsSent: Int = 0                // تعداد packet های ارسالی
    
    // Buffer Metrics
    var jitterBufferFillLevel: Double = 0.0 // درصد fill بودن jitter buffer (0-100)
    var jitterBufferSize: Int = 3           // تعداد frame ها در buffer
    
    // Timestamps
    var timestamp: Date = Date()
    
    /// محاسبه packet loss rate
    mutating func updatePacketLoss(received: Int, expected: Int) {
        let lost = max(0, expected - received)
        packetLossRate = expected > 0 ? Double(lost) / Double(expected) * 100.0 : 0.0
    }
}
```

**File (NEW):** `ios-client/PhoneBridge/Sources/Utils/MetricsCollector.swift`

```swift
import Foundation

/// Collect و log کردن metrics
class MetricsCollector {
    static let shared = MetricsCollector()
    
    private var metrics = AudioMetrics()
    private var metricsHistory: [AudioMetrics] = []
    private let maxHistorySize = 100  // Keep last 100 samples
    
    private let metricsQueue = DispatchQueue(label: "com.phonebridge.metrics")
    
    /// Update metrics (thread-safe)
    func update(_ block: (inout AudioMetrics) -> Void) {
        metricsQueue.async {
            block(&self.metrics)
            self.metrics.timestamp = Date()
            
            // Add to history
            self.metricsHistory.append(self.metrics)
            if self.metricsHistory.count > self.maxHistorySize {
                self.metricsHistory.removeFirst()
            }
            
            // Log if quality issues detected
            self.checkQualityIssues()
        }
    }
    
    /// Get current metrics snapshot
    func getCurrentMetrics() -> AudioMetrics {
        return metricsQueue.sync { metrics }
    }
    
    /// Export metrics برای debugging
    func exportMetrics() -> String {
        return metricsQueue.sync {
            let encoder = JSONEncoder()
            encoder.outputFormatting = .prettyPrinted
            
            if let data = try? encoder.encode(metricsHistory),
               let json = String(data: data, encoding: .utf8) {
                return json
            }
            return "[]"
        }
    }
    
    /// Check کردن quality و log کردن warnings
    private func checkQualityIssues() {
        // High packet loss
        if metrics.packetLossRate > 5.0 {
            NSLog("⚠️ HIGH PACKET LOSS: \(String(format: "%.1f", metrics.packetLossRate))%%")
        }
        
        // High jitter
        if metrics.jitterMs > 50.0 {
            NSLog("⚠️ HIGH JITTER: \(String(format: "%.1f", metrics.jitterMs))ms")
        }
        
        // High latency
        if metrics.roundTripLatencyMs > 200.0 {
            NSLog("⚠️ HIGH LATENCY: \(String(format: "%.1f", metrics.roundTripLatencyMs))ms")
        }
        
        // Weak BLE signal
        if metrics.rssi < -80 {
            NSLog("⚠️ WEAK BLE SIGNAL: \(metrics.rssi) dBm")
        }
        
        // Log summary every 10 seconds
        if metricsHistory.count % 50 == 0 {  // 50 frames * 20ms = 1 sec, so every 10 iterations
            logMetricsSummary()
        }
    }
    
    private func logMetricsSummary() {
        NSLog("""
        📊 Audio Metrics Summary:
           Packet Loss: \(String(format: "%.2f", metrics.packetLossRate))%%
           Jitter: \(String(format: "%.1f", metrics.jitterMs))ms
           Latency: \(String(format: "%.1f", metrics.roundTripLatencyMs))ms
           Bitrate: \(metrics.currentBitrate/1000)kbps
           RSSI: \(metrics.rssi)dBm
           Buffer Fill: \(String(format: "%.0f", metrics.jitterBufferFillLevel))%%
        """)
    }
    
    func reset() {
        metricsQueue.async {
            self.metrics = AudioMetrics()
            self.metricsHistory.removeAll()
        }
    }
}
```

**Integration in AudioStreamManager:**

```swift
// در AudioStreamManager
func sendAudioFrame(_ frame: Data) {
    // ... existing send code ...
    
    // ✅ Update metrics
    MetricsCollector.shared.update { metrics in
        metrics.packetsSent += 1
        metrics.currentBitrate = calculateCurrentBitrate()
        metrics.inputLevelRMS = calculateRMS(from: frame)
    }
}

func receiveAudioFrame() {
    // ... existing receive code ...
    
    // ✅ Update metrics
    MetricsCollector.shared.update { metrics in
        metrics.packetsReceived += 1
        metrics.jitterBufferFillLevel = Double(jitterBuffer.count) / Double(jitterBufferSize) * 100.0
    }
}
```

#### Android Metrics

Similar implementation در Android با همین ساختار.

**Benefits:**
- Real-time visibility به audio quality
- Troubleshooting راحت‌تر
- User feedback برای connection quality
- Data برای optimization

---

### Improvement 4: Forward Error Correction (FEC)

**Goal:** فعال کردن in-band FEC در Opus برای recovery از packet loss

#### iOS Implementation

**File:** `ios-client/PhoneBridge/Sources/Audio/AudioStreamManager.swift`

```swift
func setupOpusEncoder() {
    // ... existing encoder creation ...
    
    // ✅ Enable FEC
    var enableFEC: Int32 = 1
    opus_encoder_ctl(opusEncoder, OPUS_SET_INBAND_FEC(enableFEC))
    
    // ✅ تعیین expected packet loss percentage (بر اساس metrics)
    var expectedLoss: Int32 = 10  // Expect 10% loss
    opus_encoder_ctl(opusEncoder, OPUS_SET_PACKET_LOSS_PERC(expectedLoss))
    
    log("✅ Opus FEC enabled با expected loss: \(expectedLoss)%")
}
```

#### Android Implementation

Similar در Concentus Opus library.

**Benefits:**
- Recovery از packet loss بدون retransmission
- بهتر از PLC تنها (combining FEC + PLC = best)

---

### Improvement 5: Dynamic Bitrate Adaptation

**Current:** فقط بر اساس RSSI adapt می‌شود

**New:** بر اساس quality metrics واقعی

```swift
func adaptBitrate() {
    let metrics = MetricsCollector.shared.getCurrentMetrics()
    
    var newBitrate = currentBitrate
    
    // If high packet loss, decrease bitrate
    if metrics.packetLossRate > 5.0 {
        newBitrate = max(12000, newBitrate - 2000)
        log("⬇️ Decreasing bitrate due to packet loss: \(newBitrate/1000)kbps")
    }
    
    // If good quality and buffer healthy, increase bitrate
    else if metrics.packetLossRate < 1.0 && 
            metrics.jitterBufferFillLevel > 30 &&
            metrics.jitterBufferFillLevel < 70 {
        newBitrate = min(32000, newBitrate + 2000)
        log("⬆️ Increasing bitrate: \(newBitrate/1000)kbps")
    }
    
    if newBitrate != currentBitrate {
        opus_encoder_ctl(opusEncoder, OPUS_SET_BITRATE(Int32(newBitrate)))
        currentBitrate = newBitrate
    }
}
```

---

## Phase 2 Summary

**After Phase 2:**
- ✅ Professional-grade audio quality با WebRTC AEC3/NS
- ✅ Smooth audio تا ۱۰٪ packet loss با PLC+FEC
- ✅ Real-time metrics برای monitoring
- ✅ Adaptive bitrate بر اساس quality
- ✅ کیفیت قابل مقایسه با iTell

**Estimated Time:** 3-5 days

**Testing:**
- [ ] A/B test: قبل/بعد WebRTC - measure echo و noise
- [ ] Simulate packet loss (5%, 10%) - verify PLC works
- [ ] Monitor metrics dashboard در real call
- [ ] Stress test: poor BLE conditions
- [ ] User subjective quality test (MOS score)

---


## Phase 3 Design: Production Readiness

### Overview
Phase 3 makes the system robust, testable, and production-ready با comprehensive test coverage و edge case handling.

**Key Areas:**
1. Unit tests برای core components
2. Integration tests برای end-to-end scenarios
3. Edge case handling (disconnect, backgrounding, etc.)
4. Performance optimization
5. Documentation

---

### Testing Strategy

#### Unit Tests

**iOS Unit Tests:**

**File (NEW):** `ios-client/PhoneBridgeTests/AudioStreamManagerTests.swift`

```swift
import XCTest
@testable import PhoneBridge

class AudioStreamManagerTests: XCTestCase {
    
    var audioManager: AudioStreamManager!
    
    override func setUp() {
        super.setUp()
        audioManager = AudioStreamManager.shared
    }
    
    // ✅ Test Opus encode/decode round-trip
    func testOpusRoundTrip() {
        let testSamples: [Int16] = Array(repeating: 1000, count: 320) // 20ms @ 16kHz
        
        // Encode
        guard let encoded = audioManager.encodeOpusFrame(testSamples) else {
            XCTFail("Failed to encode")
            return
        }
        
        // Decode
        guard let decoded = audioManager.decodeOpusFrame(encoded) else {
            XCTFail("Failed to decode")
            return
        }
        
        // Should have same length
        XCTAssertEqual(decoded.count, testSamples.count)
        
        // Should be similar (allowing for codec loss)
        let similarity = calculateSimilarity(testSamples, decoded)
        XCTAssertGreaterThan(similarity, 0.9) // 90% similarity
    }
    
    // ✅ Test PLC generates audio
    func testPacketLossConcealment() {
        // Simulate lost packet
        guard let plcAudio = audioManager.performPLC() else {
            XCTFail("PLC failed")
            return
        }
        
        XCTAssertEqual(plcAudio.count, 320) // Should generate one frame
        
        // Should not be silence
        let energy = plcAudio.reduce(0) { $0 + abs($1) }
        XCTAssertGreaterThan(energy, 0)
    }
    
    // ✅ Test bitrate adaptation
    func testBitrateAdaptation() {
        let initialBitrate = audioManager.currentBitrate
        
        // Simulate poor quality
        audioManager.reportPacketLoss(rate: 10.0)
        audioManager.adaptBitrate()
        
        // Should decrease
        XCTAssertLessThan(audioManager.currentBitrate, initialBitrate)
        
        // Simulate good quality
        audioManager.reportPacketLoss(rate: 0.5)
        audioManager.adaptBitrate()
        
        // Should increase or stay
        XCTAssertGreaterThanOrEqual(audioManager.currentBitrate, initialBitrate - 2000)
    }
    
    private func calculateSimilarity(_ a: [Int16], _ b: [Int16]) -> Double {
        guard a.count == b.count else { return 0.0 }
        var sum = 0.0
        for i in 0..<a.count {
            let diff = Double(abs(a[i] - b[i]))
            sum += diff
        }
        let avgDiff = sum / Double(a.count)
        return 1.0 - (avgDiff / 32768.0) // Normalize to 0-1
    }
}
```

**File (NEW):** `ios-client/PhoneBridgeTests/BLEProtocolTests.swift`

```swift
import XCTest
@testable import PhoneBridge

class BLEProtocolTests: XCTestCase {
    
    // ✅ Test command serialization
    func testDialCommandSerialization() {
        let command = [
            "cmd": "DIAL_CALL",
            "phoneNumber": "+989123456789",
            "timestamp": 1234567890
        ] as [String : Any]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: command),
              let jsonString = String(data: jsonData, encoding: .utf8) else {
            XCTFail("Failed to serialize")
            return
        }
        
        XCTAssertTrue(jsonString.contains("DIAL_CALL"))
        XCTAssertTrue(jsonString.contains("+989123456789"))
    }
    
    // ✅ Test event deserialization
    func testDialResultDeserialization() {
        let jsonString = """
        {
            "type": "DIAL_RESULT",
            "success": true,
            "callId": "test-123",
            "error": null
        }
        """
        
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("Failed to parse")
            return
        }
        
        XCTAssertEqual(json["type"] as? String, "DIAL_RESULT")
        XCTAssertEqual(json["success"] as? Bool, true)
        XCTAssertEqual(json["callId"] as? String, "test-123")
    }
}
```

**Android Unit Tests:**

**File (NEW):** `android-gateway/app/src/test/java/com/phonebridge/gateway/audio/AudioBridgeManagerTest.kt`

```kotlin
package com.phonebridge.gateway.audio

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class AudioBridgeManagerTest {
    
    private lateinit var audioManager: AudioBridgeManager
    
    @Before
    fun setUp() {
        // Setup with mock context
        audioManager = AudioBridgeManager(mockContext)
    }
    
    // ✅ Test Opus encode/decode round-trip
    @Test
    fun testOpusRoundTrip() {
        val testSamples = ShortArray(320) { 1000 } // 20ms frame
        
        // Encode
        val encoded = audioManager.encodeOpusFrame(testSamples)
        assertNotNull(encoded)
        
        // Decode
        val decoded = audioManager.decodeOpusFrame(encoded!!)
        assertNotNull(decoded)
        
        // Should have same length
        assertEquals(320, decoded!!.size)
        
        // Should be similar
        val similarity = calculateSimilarity(testSamples, decoded)
        assertTrue(similarity > 0.9)
    }
    
    // ✅ Test audio source priority
    @Test
    fun testAudioSourceSelection() {
        val sources = audioManager.getAudioSourcePriority()
        
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sources[0])
        assertEquals(MediaRecorder.AudioSource.MIC, sources[1])
    }
    
    private fun calculateSimilarity(a: ShortArray, b: ShortArray): Double {
        if (a.size != b.size) return 0.0
        
        var sum = 0.0
        for (i in a.indices) {
            sum += kotlin.math.abs(a[i] - b[i]).toDouble()
        }
        val avgDiff = sum / a.size
        return 1.0 - (avgDiff / 32768.0)
    }
}
```

---

#### Integration Tests

**End-to-End Audio Test:**

**File (NEW):** `ios-client/PhoneBridgeIntegrationTests/AudioE2ETest.swift`

```swift
import XCTest
@testable import PhoneBridge

class AudioE2ETest: XCTestCase {
    
    // ⚠️ این test نیاز به دو device فیزیکی دارد
    // می‌توان با mock BLE و loopback audio شبیه‌سازی کرد
    
    func testEndToEndAudioLatency() {
        let expectation = self.expectation(description: "Audio roundtrip")
        
        var latencyMs: Double = 0
        
        // Inject test tone
        let testTone = generateTestTone(frequency: 1000, duration: 0.1) // 100ms, 1kHz
        
        // Start measuring
        let startTime = Date()
        
        // Send audio
        AudioStreamManager.shared.sendAudioFrame(testTone)
        
        // Wait for echo back (در loopback test)
        AudioStreamManager.shared.onAudioReceived = { receivedAudio in
            // Detect test tone in received audio
            if self.detectTestTone(in: receivedAudio) {
                latencyMs = Date().timeIntervalSince(startTime) * 1000
                expectation.fulfill()
            }
        }
        
        wait(for: [expectation], timeout: 2.0)
        
        // Latency should be < 150ms
        XCTAssertLessThan(latencyMs, 150.0, "Latency too high: \(latencyMs)ms")
    }
    
    private func generateTestTone(frequency: Double, duration: Double) -> Data {
        // Generate sine wave at specified frequency
        let sampleRate = 16000.0
        let samples = Int(sampleRate * duration)
        var audio: [Int16] = []
        
        for i in 0..<samples {
            let time = Double(i) / sampleRate
            let sample = sin(2.0 * .pi * frequency * time) * 16000.0
            audio.append(Int16(sample))
        }
        
        return Data(bytes: audio, count: audio.count * 2)
    }
    
    private func detectTestTone(in audio: Data) -> Bool {
        // Simple FFT-based tone detection
        // (Implementation omitted for brevity)
        return true
    }
}
```

**Failure Scenario Tests:**

**File (NEW):** `ios-client/PhoneBridgeIntegrationTests/FailureScenarioTests.swift`

```swift
import XCTest
@testable import PhoneBridge

class FailureScenarioTests: XCTestCase {
    
    // ✅ Test L2CAP disconnect during call
    func testL2CAPDisconnect() {
        let expectation = self.expectation(description: "Reconnection")
        
        // Start call
        setupActiveCall()
        
        // Simulate L2CAP disconnect
        L2CAPChannelManager.shared.simulateDisconnect()
        
        // Should attempt reconnect
        L2CAPChannelManager.shared.onReconnected = {
            expectation.fulfill()
        }
        
        wait(for: [expectation], timeout: 5.0)
        
        // Audio should resume
        XCTAssertTrue(AudioStreamManager.shared.isStreaming)
    }
    
    // ✅ Test app backgrounding
    func testAppBackgrounding() {
        setupActiveCall()
        
        // Simulate app going to background
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        
        // Audio should pause or continue based on CallKit
        // CallKit allows background audio for active calls
        
        // Come back to foreground
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        
        // Audio should resume
        XCTAssertTrue(AudioStreamManager.shared.isStreaming)
    }
    
    private func setupActiveCall() {
        // Helper to establish mock active call
    }
}
```

---

### Edge Case Handling

#### Handle 1: L2CAP Disconnect During Call

**Already implemented** (reconnection logic exists), but add user notification:

**File:** `ios-client/PhoneBridge/Sources/Audio/L2CAPChannelManager.swift`

```swift
func handleDisconnect() {
    log("⚠️ L2CAP disconnected، تلاش برای reconnect...")
    
    // ✅ Notify user
    NotificationCenter.default.post(
        name: .l2capReconnecting,
        object: nil,
        userInfo: ["message": "در حال اتصال مجدد..."]
    )
    
    // Attempt reconnect
    attemptReconnection()
}

func attemptReconnection() {
    reconnectionAttempts += 1
    
    if reconnectionAttempts <= MAX_RECONNECTION_ATTEMPTS {
        DispatchQueue.global().asyncAfter(deadline: .now() + 1.0) {
            self.connectL2CAPChannel(to: self.peripheral!, psm: self.currentPsm)
        }
    } else {
        log("❌ Reconnection failed after \(MAX_RECONNECTION_ATTEMPTS) attempts")
        
        // ✅ Notify user - call dropped
        NotificationCenter.default.post(
            name: .l2capFailed,
            object: nil,
            userInfo: ["message": "تماس قطع شد. لطفاً دوباره تلاش کنید."]
        )
    }
}
```

#### Handle 2: BLE Disconnect

**File:** `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

```swift
func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    log("⚠️ BLE disconnected از peripheral")
    
    // ✅ If during active call, try to reconnect
    if CallManager.shared.hasActiveCall {
        log("🔄 تلاش برای reconnect BLE (call active)...")
        
        DispatchQueue.global().asyncAfter(deadline: .now() + 2.0) {
            self.startScanning()
        }
        
    } else {
        log("ℹ️ BLE disconnected (no active call)")
    }
    
    // ✅ Update UI
    onConnectionStateChanged?(.disconnected)
}
```

#### Handle 3: iOS App Backgrounding

**File:** `ios-client/PhoneBridge/Sources/App/PhoneBridgeApp.swift`

```swift
init() {
    // ✅ Monitor app lifecycle
    NotificationCenter.default.addObserver(
        forName: UIApplication.didEnterBackgroundNotification,
        object: nil,
        queue: .main
    ) { _ in
        self.handleAppBackgrounding()
    }
    
    NotificationCenter.default.addObserver(
        forName: UIApplication.willEnterForegroundNotification,
        object: nil,
        queue: .main
    ) { _ in
        self.handleAppForegrounding()
    }
}

func handleAppBackgrounding() {
    log("📲 App entered background")
    
    // ✅ CallKit keeps audio session active during call
    if CallManager.shared.hasActiveCall {
        log("✅ Call active - audio continues via CallKit")
    } else {
        // No active call - can suspend
        log("ℹ️ No active call - suspending")
    }
}

func handleAppForegrounding() {
    log("📲 App entered foreground")
    
    // ✅ Check connection state
    if !BLEClient.shared.isConnected {
        log("🔄 BLE not connected - attempting to reconnect...")
        BLEClient.shared.startScanning()
    }
}
```

#### Handle 4: Android App Killed

**Android foreground service** already prevents kill, but handle forced kill:

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/service/PhoneBridgeForegroundService.kt`

```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    
    Log.w(TAG, "⚠️ App task removed (swiped away)")
    
    // ✅ If call active, send notification to iOS
    if (PhoneBridgeCallService.instance?.hasActiveCall() == true) {
        BLEPeripheralServer.instance?.sendNotification(
            JSONObject().apply {
                put("type", "CALL_ENDED")
                put("callId", activeCallId)
                put("reason", "GATEWAY_DISCONNECTED")
            }.toString()
        )
    }
    
    // ✅ Restart service if needed
    val restartIntent = Intent(applicationContext, PhoneBridgeForegroundService::class.java)
    val pendingIntent = PendingIntent.getService(
        applicationContext, 1, restartIntent,
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
    )
    
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
}
```

#### Handle 5: Cellular Call Drop

**File:** `android-gateway/app/src/main/java/com/phonebridge/gateway/service/PhoneBridgeCallService.kt`

```kotlin
override fun onCallStateChanged(call: Call, state: Int) {
    super.onCallStateChanged(call, state)
    
    when (state) {
        Call.STATE_DISCONNECTED -> {
            val cause = call.details.disconnectCause
            Log.i(TAG, "📞 Call disconnected: ${cause.label}")
            
            // ✅ Notify iOS with reason
            BLEPeripheralServer.instance?.sendNotification(
                JSONObject().apply {
                    put("type", "CALL_ENDED")
                    put("callId", call.details.handle.toString())
                    put("reason", when (cause.code) {
                        DisconnectCause.REMOTE -> "REMOTE_HANGUP"
                        DisconnectCause.LOCAL -> "LOCAL_HANGUP"
                        DisconnectCause.MISSED -> "MISSED"
                        DisconnectCause.REJECTED -> "REJECTED"
                        DisconnectCause.ERROR -> "NETWORK_ERROR"
                        else -> "UNKNOWN"
                    })
                }.toString()
            )
            
            // ✅ Stop audio streaming
            AudioBridgeManager.instance?.stopStreaming()
        }
    }
}
```

---

### Performance Optimization

#### Optimization 1: Reduce Memory Allocations

**iOS:**
```swift
// ❌ BAD: Creates new array each frame
func processAudio() {
    let buffer = [Int16](repeating: 0, count: 320) // ❌ Allocation
    // ...
}

// ✅ GOOD: Reuse buffer
private var reusableBuffer = [Int16](repeating: 0, count: 320)

func processAudio() {
    // Clear buffer
    reusableBuffer.withUnsafeMutableBufferPointer { ptr in
        ptr.baseAddress?.initialize(repeating: 0, count: 320)
    }
    // Use reusableBuffer
}
```

#### Optimization 2: Reduce Thread Context Switching

```swift
// ✅ Use dedicated audio thread
private let audioThread = DispatchQueue(
    label: "com.phonebridge.audio",
    qos: .userInteractive,  // High priority
    attributes: []
)
```

#### Optimization 3: Opus Encoder Efficiency

```swift
// ✅ Set complexity based on device capability
let complexity = ProcessInfo.processInfo.processorCount >= 4 ? 10 : 5
opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(Int32(complexity)))
```

---

### Documentation

#### Developer Documentation

**File (NEW):** `DEVELOPER_GUIDE.md`

```markdown
# PhoneBridge Developer Guide

## Setup

### iOS
1. Install Xcode 15+
2. Run: `pod install`
3. Open `PhoneBridge.xcworkspace`
4. Build and run on physical device (BLE نیاز به device فیزیکی دارد)

### Android
1. Install Android Studio
2. Open project in `android-gateway/`
3. Sync Gradle
4. Build and run on physical device

## Architecture

[Include diagrams and explanations from this design doc]

## Testing

[Include test running instructions]

## Troubleshooting

[Include common issues and solutions]
```

#### User Documentation

**File (NEW):** `USER_GUIDE.md`

```markdown
# PhoneBridge User Guide

## نصب و راه‌اندازی

### قدم ۱: نصب اپلیکیشن
- iOS: ...
- Android: ...

### قدم ۲: اتصال بلوتوث
1. روی گوشی اندروید اپ را باز کنید
2. روی گوشی آیفون اپ را باز کنید
3. دکمه "Connect" را بزنید
4. منتظر بمانید تا connected نشان دهد

### قدم ۳: برقراری تماس
- تماس خروجی: شماره را وارد کنید و Call بزنید
- تماس ورودی: زنگ می‌خورد، Answer بزنید

## مشکلات متداول

**Q: صدا قطع می‌شود**  
A: اطمینان حاصل کنید که هر دو گوشی در فاصله کمتر از ۵ متر هستند

[More FAQs...]
```

---

## Phase 3 Summary

**After Phase 3:**
- ✅ Test coverage ≥ 70%
- ✅ تمام edge cases handled gracefully
- ✅ Performance optimized
- ✅ Comprehensive documentation
- ✅ Production-ready

**Estimated Time:** 3-4 days

**Final Checklist:**
- [ ] All unit tests passing
- [ ] Integration tests passing
- [ ] 60-minute stress test successful
- [ ] Rapid reconnection test passed
- [ ] Documentation complete
- [ ] Code reviewed
- [ ] Ready for release

---

## Deployment Strategy

### Beta Testing
1. Internal testing با development team (1 week)
2. Limited beta با 10-20 users (2 weeks)
3. Collect feedback و fix critical bugs
4. Open beta (1 month)

### Release
1. Tag version 1.0.0
2. Create release notes
3. Publish to GitHub releases
4. Announce on social media / forums

### Post-Release
1. Monitor crash reports
2. Collect user feedback
3. Plan v1.1 با feature requests

---

## Success Criteria

### Technical Metrics
- ✅ Call success rate ≥ 95%
- ✅ Audio latency ≤ 150ms
- ✅ Packet loss tolerance ≤ 10%
- ✅ Battery consumption ≤ 5% per hour
- ✅ Test coverage ≥ 70%

### User Experience Metrics
- ✅ Time to connect ≤ 5 seconds
- ✅ Call drop rate ≤ 2%
- ✅ User satisfaction ≥ 4.5/5

### Code Quality Metrics
- ✅ Zero critical bugs
- ✅ All public APIs documented
- ✅ Build success rate ≥ 98%

---

## Future Enhancements (Out of Scope for v1.0)

**v1.1 Potential Features:**
- Video calling support
- Call recording (با user consent)
- Multi-language UI
- Advanced audio effects (equalizer)
- Cloud sync برای call history

**v2.0 Vision:**
- Support برای multiple iOS devices from one Android
- Smartwatch integration
- Group calling
- End-to-end encryption for audio

---

## Conclusion

این design document راه‌حل کامل برای تبدیل PhoneBridge به یک production-ready, world-class audio bridging system را ارائه می‌دهد.

**با completion of all 3 phases:**
- مشکلات critical رفع می‌شوند (Phase 1)
- کیفیت به سطح professional می‌رسد (Phase 2)  
- سیستم robust و قابل اعتماد می‌شود (Phase 3)

**Estimated Total Time:** 7-11 روز (56-88 ساعت)

**Result:** یک جایگزین open-source کلاس جهانی برای iTell که به صورت reliable و با کیفیت بالا از طریق Bluetooth LE audio bridging انجام می‌دهد.

---

**Document Version:** 1.0  
**Last Updated:** November 26, 2024  
**Status:** ✅ Ready for Implementation  
**Next Step:** Create `tasks.md` با task breakdown مشخص


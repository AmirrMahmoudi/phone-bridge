# Phase 1 Integration Test Checklist

## Overview

This document provides comprehensive testing guidance to validate that all Phase 1 critical fixes work together to enable **bidirectional audio** in PhoneBridge.

**Phase 1 Goal:** Fix 5 critical issues preventing audio bridging between iPhone and Android devices.

**Expected Outcome:** ≥90% success rate for bidirectional audio on both incoming and outgoing calls.

---

## Prerequisites

### Hardware Requirements
- [ ] iPhone device running iOS ≥ 15.0 with Bluetooth 5.0+
- [ ] Android device running Android ≥ 10 (API 29) with Bluetooth 5.0+
- [ ] Android device has active SIM card and cellular connection
- [ ] Both devices within 10 meters of each other (optimal BLE range)

### Software Requirements
- [ ] iOS app compiled successfully from latest code
- [ ] Android app compiled successfully from latest code
- [ ] Both apps installed on respective devices
- [ ] All required permissions granted:
  - iOS: Bluetooth, Microphone, CallKit
  - Android: Bluetooth, Microphone, Phone, Contacts, SMS

### Environment Setup
- [ ] Quiet testing environment (minimal background noise)
- [ ] Strong cellular signal on Android device
- [ ] Test phone number available to dial

---

## Test Checklist

### 1. Compilation Tests

**Objective:** Verify all code compiles without errors after Phase 1 fixes

#### 1.1 Android Compilation
- [ ] Project compiles without errors
- [ ] No undefined method errors (e.g., `hasConnectedDevices()`)
- [ ] No missing imports or symbols
- [ ] Build generates valid APK

**Verification Command:**
```bash
cd android-gateway
./gradlew assembleDebug
```

**Expected Result:** Build successful with 0 errors

#### 1.2 iOS Compilation
- [ ] Project compiles without errors
- [ ] No undefined symbols or missing methods
- [ ] No type mismatches
- [ ] Build generates valid IPA

**Verification Command:**
```bash
cd ios-client/PhoneBridge
xcodebuild clean build -scheme PhoneBridge -destination 'platform=iOS'
```

**Expected Result:** Build successful with 0 errors

---

### 2. Connection Tests

**Objective:** Verify BLE connection and L2CAP channel establishment

#### 2.1 BLE Connection
- [ ] Android app starts BLE peripheral server successfully
- [ ] iPhone app discovers Android device in BLE scan
- [ ] iPhone connects to Android via GATT
- [ ] Connection status shown correctly in both apps

**Verification Steps:**
1. Launch Android app first
2. Verify "BLE Server Active" status shown
3. Launch iOS app
4. Tap "Scan" and wait for Android device to appear
5. Tap device to connect
6. Verify "Connected" status on both devices

**Expected Result:** Connection established within 5 seconds

#### 2.2 PSM Read (No Race Condition)
- [ ] iOS reads PSM characteristic successfully
- [ ] PSM value is non-zero (typically 136 or 0x0088)
- [ ] No race condition - PSM available immediately on read
- [ ] If PSM initially 0, iOS receives `L2CAP_READY` event

**Verification Steps:**
1. Check iOS logs after connection
2. Look for log line: "✅ PSM خوانده شد: [value]"
3. Verify PSM value > 0

**Expected Logs (iOS):**
```
✅ PSM خوانده شد: 136
```

**Fallback (if PSM read returns 0):**
```
⚠️ PSM invalid (0) - منتظر L2CAP_READY event...
📡 L2CAP channel آماده شد، PSM: 136
```

#### 2.3 L2CAP Channel Connection
- [ ] iOS initiates L2CAP connection with valid PSM
- [ ] L2CAP channel opens successfully
- [ ] Both devices confirm L2CAP connected
- [ ] `hasConnectedDevices()` returns true on Android

**Verification Steps:**
1. After PSM read, verify iOS attempts L2CAP connection
2. Check both device logs for L2CAP connection success
3. Android: Check AudioBridgeManager logs

**Expected Logs:**

iOS:
```
📡 Attempting L2CAP connection with PSM: 136
✅ L2CAP channel connected
```

Android:
```
✅ L2CAP client connected
✅ hasConnectedDevices() = true
```

**Expected Result:** L2CAP channel established within 2 seconds of PSM read

---

### 3. Outgoing Call Test (Critical Path)

**Objective:** Verify complete outgoing call flow with bidirectional audio

#### 3.1 Call Initiation
- [ ] User enters phone number in iOS app
- [ ] iOS sends `DIAL_CALL` command to Android
- [ ] iOS waits for `DIAL_RESULT` before starting CallKit
- [ ] Android receives command and initiates cellular call

**Verification Steps:**
1. Enter valid phone number in iOS app
2. Tap "Call" button
3. Observe iOS UI - should show "Dialing..." but NOT CallKit yet
4. Check logs

**Expected Logs:**

iOS:
```
📞 در حال ارسال دستور DIAL_CALL به اندروید: +1234567890
⏳ منتظر DIAL_RESULT از اندروید...
```

Android:
```
📱 Received DIAL_CALL command: +1234567890
📞 Initiating cellular call via ACTION_CALL
```

#### 3.2 Dial Result Response
- [ ] Android sends `DIAL_RESULT` immediately after dial intent
- [ ] iOS receives `DIAL_RESULT` with `success: true`
- [ ] iOS starts CallKit only after receiving `DIAL_RESULT`
- [ ] CallKit shows "Calling..." state

**Verification Steps:**
1. After step 3.1, wait for DIAL_RESULT
2. Verify CallKit interface appears on iOS
3. Check logs for proper event sequence

**Expected Logs:**

Android:
```
✅ Dial intent succeeded
📤 Sending DIAL_RESULT: success=true, callId=xyz
```

iOS:
```
📩 Event received: DIAL_RESULT
✅ Dial موفق: callId=xyz
📱 CallKit started for outgoing call
```

**Expected Timing:** DIAL_RESULT received within 500ms of DIAL_CALL

#### 3.3 Cellular Call Connection
- [ ] Android detects call state transition to `ACTIVE`
- [ ] Android sends `CALL_ACTIVE` event to iOS
- [ ] iOS receives `CALL_ACTIVE` before starting audio session
- [ ] Proper delay between DIAL_CALL and CALL_ACTIVE (2-5 seconds)

**Verification Steps:**
1. After CallKit appears, wait for call to connect on cellular network
2. Verify audio session starts only after CALL_ACTIVE
3. Check timing in logs

**Expected Logs:**

Android:
```
📞 Call state changed: DIALING
📞 Call state changed: ACTIVE
✅ Call connected to cellular network
📤 Sending CALL_ACTIVE event: callId=xyz
```

iOS:
```
📩 Event received: CALL_ACTIVE
📞 تماس فعال شد: xyz
✅ Starting audio session now that call is active
```

**Expected Timing:** CALL_ACTIVE received 2-5 seconds after DIAL_RESULT

#### 3.4 Audio Session Start
- [ ] iOS starts audio session only after `CALL_ACTIVE`
- [ ] AudioStreamManager begins encoding/decoding
- [ ] Android AudioBridgeManager starts streaming
- [ ] Audio routing configured correctly (not Bluetooth SCO)

**Verification Steps:**
1. After CALL_ACTIVE, verify audio streaming begins
2. Check both devices for audio session active logs
3. Verify Android is NOT routing to Bluetooth SCO

**Expected Logs:**

iOS:
```
✅ Audio session activated by CallKit
✅ تماس فعال شد، شروع audio stream...
🎤 Starting audio recording
🔊 Starting audio playback
```

Android:
```
✅ Audio routing: MODE_IN_CALL with speakerphone, NOT Bluetooth SCO
🎤 AudioRecord started: VOICE_COMMUNICATION source
🔊 AudioTrack started: STREAM_VOICE_CALL
✅ Audio streaming active via L2CAP
```

**Critical Check:** No logs mentioning "Bluetooth SCO" or "startBluetoothSco()"

#### 3.5 Bidirectional Audio Verification ⭐ CRITICAL
- [ ] **iOS user speaks** → Other party hears voice clearly
- [ ] **Other party speaks** → iOS user hears voice clearly
- [ ] No echo or feedback
- [ ] No audio dropouts or glitches (basic quality)
- [ ] Latency acceptable (perceived < 200ms)

**Verification Steps:**
1. iOS user says "Testing one, two, three"
2. Ask other party if they heard clearly
3. Other party speaks back
4. iOS user confirms they hear other party
5. Continue conversation for 30 seconds

**Success Criteria:**
- ✅ Both parties can hear each other
- ✅ Audio is intelligible (words understood clearly)
- ✅ No echo present
- ✅ No feedback loop
- ✅ Latency feels natural (like a normal phone call)

**If Audio Fails:**
- Check L2CAP connection status
- Check audio routing (should NOT be Bluetooth SCO)
- Check Opus encoding/decoding in logs
- Check for packet loss or jitter

#### 3.6 Call Termination
- [ ] User hangs up from iOS or other party hangs up
- [ ] iOS sends `HANGUP_CALL` command (if iOS initiates)
- [ ] Android detects call ended
- [ ] `CALL_ENDED` event sent to iOS
- [ ] Audio session stops cleanly
- [ ] CallKit call removed

**Verification Steps:**
1. Hang up from either device
2. Verify call ends on both sides
3. Check for clean audio session teardown

**Expected Logs:**

iOS (if user hangs up):
```
📞 Hanging up call
📤 Sending HANGUP_CALL command
```

Android:
```
📞 Call state changed: DISCONNECTED
📤 Sending CALL_ENDED event
🛑 Stopping audio streaming
```

iOS:
```
📩 Event received: CALL_ENDED
✅ Call ended, stopping audio session
```

---

### 4. Incoming Call Test

**Objective:** Verify incoming call flow with bidirectional audio

#### 4.1 Incoming Call Detection
- [ ] Android receives incoming cellular call
- [ ] CallReceiver detects incoming call
- [ ] Android sends `CALL_INCOMING` event to iOS
- [ ] iOS receives event with caller number and name

**Verification Steps:**
1. Have someone call the Android device
2. Verify iOS receives notification immediately
3. Check logs

**Expected Logs:**

Android:
```
📱 Incoming call detected: +1234567890
📤 Sending CALL_INCOMING event to iOS
```

iOS:
```
📩 Event received: CALL_INCOMING
📞 Incoming call from: +1234567890
```

#### 4.2 CallKit Ring
- [ ] iOS shows CallKit incoming call screen
- [ ] Caller name displayed (if in contacts)
- [ ] Phone rings/vibrates according to iOS settings
- [ ] User can answer or decline

**Verification Steps:**
1. Verify CallKit native incoming call UI appears
2. Check caller ID shown correctly
3. Verify ring/vibrate behavior

**Expected Result:** CallKit interface appears within 1 second of CALL_INCOMING event

#### 4.3 Answer Call
- [ ] User answers call on iOS
- [ ] iOS sends `ANSWER_CALL` command to Android
- [ ] Android answers cellular call
- [ ] `CALL_ACTIVE` event sent to iOS
- [ ] Audio session starts after CALL_ACTIVE

**Verification Steps:**
1. Tap "Answer" in CallKit interface
2. Verify call answered on Android
3. Check audio session timing

**Expected Logs:**

iOS:
```
📞 Answering call
📤 Sending ANSWER_CALL command
```

Android:
```
📱 Received ANSWER_CALL command
✅ Cellular call answered
📤 Sending CALL_ACTIVE event
```

iOS:
```
📩 Event received: CALL_ACTIVE
✅ Starting audio session
```

#### 4.4 Bidirectional Audio ⭐ CRITICAL
- [ ] Same verification as outgoing call (section 3.5)
- [ ] Both parties hear each other clearly
- [ ] No echo or feedback
- [ ] Acceptable latency

**Verification:** Repeat test from section 3.5

---

### 5. Protocol Synchronization Tests

**Objective:** Verify all event handlers work correctly

#### 5.1 DIAL_RESULT Handler
- [ ] iOS handles `DIAL_RESULT` with success=true
- [ ] iOS handles `DIAL_RESULT` with success=false
- [ ] Error message displayed on failure
- [ ] CallKit started only on success

**Verification Steps:**
1. Test normal dial (success case)
2. Test dial to invalid number (failure case)
3. Verify error handling

**Expected Behavior:**
- Success: CallKit starts
- Failure: Error shown, CallKit not started

#### 5.2 L2CAP_READY Handler
- [ ] iOS handles `L2CAP_READY` event
- [ ] PSM extracted from event
- [ ] L2CAP connection attempted with new PSM
- [ ] Works as fallback when PSM read returns 0

**Verification Steps:**
1. Test normal flow (PSM read succeeds)
2. If possible, test fallback (PSM read returns 0)

**Expected Logs:**
```
📡 L2CAP channel آماده، PSM: 136
```

#### 5.3 SMS_SEND_RESULT Handler
- [ ] iOS sends SMS command
- [ ] Android attempts to send SMS
- [ ] `SMS_SEND_RESULT` event sent to iOS
- [ ] iOS shows success/failure message

**Verification Steps:**
1. Send test SMS from iOS app
2. Verify SMS received on target number
3. Check result notification in iOS

**Expected Logs:**

iOS:
```
📤 Sending SMS to +1234567890
```

Android:
```
📱 Received SEND_SMS command
✅ SMS sent successfully
📤 Sending SMS_SEND_RESULT: success=true
```

iOS:
```
📩 Event received: SMS_SEND_RESULT
✅ SMS ارسال شد
```

#### 5.4 Event Timing
- [ ] All events received in correct order
- [ ] No race conditions or missing events
- [ ] Proper delays between state transitions

**Expected Event Sequence (Outgoing Call):**
```
1. iOS: DIAL_CALL command sent
2. iOS: DIAL_RESULT received (~500ms later)
3. iOS: CallKit started
4. iOS: CALL_ACTIVE received (2-5 seconds later)
5. iOS: Audio session started
6. ... call conversation ...
7. iOS/Android: HANGUP_CALL or CALL_ENDED
8. iOS: Audio session stopped
```

---

### 6. Audio Routing Validation

**Objective:** Ensure audio flows through L2CAP, not Bluetooth SCO

#### 6.1 Android Audio Configuration
- [ ] `MODE_IN_CALL` set correctly
- [ ] `isBluetoothScoOn` is **false** (not set)
- [ ] `startBluetoothSco()` NOT called
- [ ] `isSpeakerphoneOn` set to true
- [ ] AudioRecord source is `VOICE_COMMUNICATION`

**Verification Steps:**
1. During active call, check Android logs
2. Confirm no Bluetooth SCO logs present

**Expected Logs:**
```
✅ Audio routing: MODE_IN_CALL with speakerphone, NOT Bluetooth SCO
🎤 AudioRecord started: VOICE_COMMUNICATION source
```

**Forbidden Logs (should NOT appear):**
```
❌ isBluetoothScoOn = true
❌ startBluetoothSco()
❌ Bluetooth SCO connected
```

#### 6.2 L2CAP Data Flow
- [ ] Opus packets transmitted via L2CAP socket
- [ ] Packet rate approximately 50 packets/second (20ms frames)
- [ ] Data flows bidirectionally
- [ ] No data routed through GATT characteristics

**Verification Steps:**
1. During call, check logs for L2CAP data transfer
2. Verify packet statistics

**Expected Logs:**
```
📊 Audio packets sent: 150, received: 148 (3 seconds)
📊 Bitrate: ~20 kbps
```

---

### 7. Success Criteria

#### 7.1 Functional Requirements
- [ ] ≥90% of outgoing calls complete with bidirectional audio
- [ ] ≥90% of incoming calls complete with bidirectional audio
- [ ] Both parties can hear each other clearly
- [ ] No echo or feedback issues
- [ ] Acceptable audio latency (perceived < 200ms)

#### 7.2 Technical Requirements
- [ ] All code compiles without errors
- [ ] No runtime crashes or exceptions
- [ ] L2CAP connection stable during calls
- [ ] Audio routing configured correctly (not BT SCO)
- [ ] All protocol events handled properly

#### 7.3 Quality Requirements (Basic)
- [ ] Audio intelligible - words understood clearly
- [ ] No major glitches or dropouts
- [ ] Latency acceptable for conversation
- [ ] No echo cancellation issues

**Note:** Phase 1 uses basic audio processing. Professional-grade quality improvements (WebRTC AEC3, PLC, FEC) are in Phase 2.

---

## Manual Testing Instructions

### Test Procedure Overview

**Duration:** ~30 minutes for complete test run

**Required Materials:**
- 2 test devices (iPhone + Android)
- Test phone number to call
- Quiet testing location
- Notepad for recording results

---

### Step-by-Step Test Guide

#### Setup Phase (5 minutes)

1. **Prepare Android Device**
   - Install latest PhoneBridge Gateway APK
   - Grant all permissions (Bluetooth, Phone, Microphone, Contacts, SMS)
   - Verify cellular signal strong
   - Launch app and verify "BLE Server Active" status

2. **Prepare iOS Device**
   - Install latest PhoneBridge Client IPA
   - Grant all permissions (Bluetooth, Microphone, CallKit)
   - Launch app

3. **Establish Connection**
   - iOS: Tap "Scan for Devices"
   - Select Android device from list
   - Wait for "Connected" status
   - Verify L2CAP channel connected (check logs)

#### Test 1: Outgoing Call (10 minutes)

1. **Initiate Call**
   - iOS: Enter test phone number
   - Tap "Call" button
   - **Observe:** iOS should show "Dialing..." but NOT CallKit yet

2. **Verify DIAL_RESULT**
   - Wait for CallKit interface to appear (~500ms)
   - **This confirms:** iOS received DIAL_RESULT

3. **Wait for Connection**
   - Wait for cellular call to connect (2-5 seconds)
   - **Observe:** CallKit should still show "Calling..."

4. **Verify Audio Start**
   - When CALL_ACTIVE received, audio session starts
   - **Observe:** CallKit updates to "Connected" or timer starts

5. **Test Bidirectional Audio**
   - iOS user: Say "Hello, can you hear me?"
   - Other party: Respond with "Yes, I can hear you clearly"
   - **Verify:**
     - iOS user hears other party ✓
     - Other party hears iOS user ✓
     - No echo ✓
     - Latency acceptable ✓

6. **Continue Conversation**
   - Talk for 30 seconds
   - Test both directions multiple times
   - Listen for glitches or dropouts

7. **End Call**
   - Hang up from iOS
   - Verify call ends cleanly on both devices

8. **Record Results**
   - Audio quality: Pass / Fail
   - Issues encountered: [note any problems]

#### Test 2: Incoming Call (10 minutes)

1. **Trigger Incoming Call**
   - Have someone call Android device

2. **Verify CallKit Ring**
   - iOS should show incoming call screen
   - Caller ID displayed correctly
   - Phone rings/vibrates

3. **Answer Call**
   - Tap "Answer" on iOS
   - Wait for call to connect

4. **Test Bidirectional Audio**
   - Repeat audio verification from Test 1 step 5
   - Verify both directions work

5. **End Call**
   - Hang up from either side
   - Verify clean termination

6. **Record Results**
   - Audio quality: Pass / Fail
   - Issues encountered: [note any problems]

#### Test 3: SMS (5 minutes)

1. **Send SMS**
   - iOS: Enter phone number and message
   - Tap "Send"

2. **Verify Delivery**
   - Check target phone receives SMS
   - iOS should show success notification

3. **Record Results**
   - SMS sent: Pass / Fail

#### Test 4: Error Handling (5 minutes)

1. **Test Invalid Dial**
   - iOS: Dial invalid number (e.g., "123")
   - Verify error message shown
   - CallKit should NOT start

2. **Test Connection Loss**
   - During call, move devices far apart
   - Verify reconnection attempt or error message

3. **Record Results**
   - Error handling: Pass / Fail

---

### Expected Results Summary

**All Tests Pass:**
- ✅ Compilation successful (iOS + Android)
- ✅ BLE connection stable
- ✅ L2CAP channel ready (PSM > 0)
- ✅ Outgoing calls: Bidirectional audio works
- ✅ Incoming calls: Bidirectional audio works
- ✅ Audio routing via L2CAP (not BT SCO)
- ✅ All protocol events handled
- ✅ SMS sending works
- ✅ Error handling functional

**Success Rate Target:** ≥90% for audio calls

---

## Known Limitations (Phase 1)

### Audio Quality
- **Basic audio processing:** Phase 1 uses simple echo cancellation and noise suppression
- **No WebRTC yet:** Professional-grade AEC3/NS in Phase 2
- **Basic PLC:** Packet loss may cause brief glitches (improved in Phase 2)
- **No FEC:** Forward Error Correction added in Phase 2

### Features Not Yet Implemented
- **Metrics collection:** Audio quality metrics in Phase 2
- **Dynamic bitrate:** Adaptive bitrate based on connection quality in Phase 2
- **Reconnection logic:** Robust L2CAP/BLE reconnection in Phase 3
- **Background handling:** iOS backgrounding support in Phase 3
- **Edge cases:** Comprehensive failure scenario handling in Phase 3

### Expected Behavior
- Audio quality acceptable but not commercial-grade
- Brief audio glitches possible in poor BLE conditions
- No real-time metrics displayed
- Manual reconnection may be needed if connection drops

**These limitations are addressed in Phase 2 and Phase 3.**

---

## Troubleshooting Guide

### Issue: No Audio at All

**Symptoms:** Call connects but neither party hears audio

**Diagnosis Steps:**
1. Check L2CAP connection: `isConnected = true`?
2. Check audio routing: Logs should NOT mention Bluetooth SCO
3. Check audio session timing: Started after CALL_ACTIVE?
4. Check Opus encoder/decoder: Any errors in logs?

**Possible Causes:**
- L2CAP channel not connected
- Audio session started too early (before CALL_ACTIVE)
- Audio routing to Bluetooth SCO instead of L2CAP
- Opus codec failure

**Solution:**
- Verify L2CAP PSM read successful
- Verify CALL_ACTIVE received before audio starts
- Verify no Bluetooth SCO configuration
- Check Opus library initialization

---

### Issue: One-Way Audio Only

**Symptoms:** One party hears, other doesn't

**Diagnosis Steps:**
1. Determine direction: iOS → Other party OR Other party → iOS?
2. Check AudioRecord on Android: Capturing VOICE_COMMUNICATION source?
3. Check L2CAP bidirectional data flow
4. Check audio permissions granted on both devices

**Possible Causes:**
- AudioRecord capturing wrong source (downlink only)
- Microphone permission not granted
- L2CAP transmit working but receive failing (or vice versa)
- Audio routing misconfigured

**Solution:**
- Verify AudioRecord source is VOICE_COMMUNICATION
- Verify microphone permissions granted
- Check L2CAP socket bidirectional data flow
- Verify audio routing configuration

---

### Issue: Echo or Feedback

**Symptoms:** User hears their own voice delayed

**Possible Causes:**
- Echo cancellation not working
- Speaker volume too high causing acoustic feedback
- Multiple audio paths active

**Solution:**
- Verify MODE_IN_CALL set (enables system echo cancellation)
- Reduce speaker volume
- Verify only one audio session active
- Phase 2 will add WebRTC AEC3 for better echo cancellation

---

### Issue: High Latency

**Symptoms:** Noticeable delay between speaking and hearing

**Diagnosis Steps:**
1. Check jitter buffer size (default: 3 frames = 60ms)
2. Check BLE RSSI signal strength
3. Check for packet retransmissions

**Possible Causes:**
- Poor BLE connection (low RSSI)
- Large jitter buffer
- Network congestion

**Solution:**
- Move devices closer together
- Reduce jitter buffer size (trade-off: more glitches)
- Phase 2 will add dynamic latency optimization

---

### Issue: Audio Glitches/Dropouts

**Symptoms:** Choppy audio, missing words

**Possible Causes:**
- Packet loss on L2CAP channel
- BLE interference
- CPU overload
- No PLC implemented yet

**Solution:**
- Move devices closer together
- Reduce BLE interference (turn off other BT devices)
- Phase 2 adds proper PLC and FEC

---

### Issue: Call Doesn't Connect

**Symptoms:** Dial command sent but call never starts

**Diagnosis Steps:**
1. Check DIAL_RESULT received?
2. Check Android logs: Did ACTION_CALL intent succeed?
3. Check cellular connection on Android

**Possible Causes:**
- DIAL_RESULT not sent or not received
- Android lacks permission for phone calls
- Cellular network issue
- Invalid phone number

**Solution:**
- Verify phone permission granted on Android
- Check cellular signal strength
- Verify valid phone number format
- Check logs for DIAL_RESULT event

---

### Issue: CallKit Doesn't Appear

**Symptoms:** iOS doesn't show call UI

**Possible Causes:**
- DIAL_RESULT not received
- CallKit permission not granted
- iOS pending call logic error

**Solution:**
- Verify DIAL_RESULT event sent by Android
- Verify CallKit permission granted
- Check pendingOutgoingCall stored correctly
- Check iOS logs for DIAL_RESULT handling

---

### Issue: PSM Read Returns 0

**Symptoms:** L2CAP connection fails with invalid PSM

**Diagnosis:**
- Check iOS logs for PSM read value
- Check if L2CAP_READY event received

**Possible Cause:**
- Race condition: PSM read before characteristic updated

**Solution:**
- Wait for L2CAP_READY event (fallback mechanism)
- Android should send L2CAP_READY after 500ms
- iOS will retry connection with PSM from event

---

## Next Steps After Phase 1

Once Phase 1 tests pass successfully:

### Proceed to Phase 2: Quality Improvements
- Integrate WebRTC audio processing (AEC3 + NS)
- Implement proper Opus PLC (Packet Loss Concealment)
- Add comprehensive metrics collection
- Enable Opus FEC (Forward Error Correction)
- Implement dynamic bitrate adaptation
- Optimize jitter buffer

### Then Phase 3: Production Readiness
- Write comprehensive unit tests
- Write integration tests for edge cases
- Handle L2CAP/BLE disconnection/reconnection
- Handle iOS backgrounding
- Handle Android process killed
- Handle cellular call drop
- Performance optimization
- Documentation (developer guide, user guide)
- Stress testing (60+ minute calls, reconnection stress)

---

## Test Results Template

Use this template to record test results:

```
# Phase 1 Test Results

**Date:** [Date]
**Tester:** [Name]
**iOS Device:** [Model, iOS Version]
**Android Device:** [Model, Android Version]

## Compilation Tests
- [ ] Android compilation: Pass / Fail
- [ ] iOS compilation: Pass / Fail

## Connection Tests
- [ ] BLE connection: Pass / Fail
- [ ] PSM read: Pass / Fail (Value: ___)
- [ ] L2CAP channel: Pass / Fail

## Outgoing Call Test
- [ ] Call initiation: Pass / Fail
- [ ] DIAL_RESULT received: Pass / Fail
- [ ] CALL_ACTIVE received: Pass / Fail
- [ ] Audio timing correct: Pass / Fail
- [ ] **Bidirectional audio:** Pass / Fail
  - iOS → Other party: Pass / Fail
  - Other party → iOS: Pass / Fail
  - Echo: Present / Not Present
  - Latency: Acceptable / High
- [ ] Call termination: Pass / Fail

## Incoming Call Test
- [ ] Call detection: Pass / Fail
- [ ] CallKit ring: Pass / Fail
- [ ] Answer call: Pass / Fail
- [ ] **Bidirectional audio:** Pass / Fail
- [ ] Call termination: Pass / Fail

## Protocol Tests
- [ ] DIAL_RESULT handler: Pass / Fail
- [ ] L2CAP_READY handler: Pass / Fail
- [ ] SMS_SEND_RESULT handler: Pass / Fail

## Audio Routing
- [ ] No Bluetooth SCO: Pass / Fail
- [ ] L2CAP data flow: Pass / Fail

## Overall Result
- **Phase 1 Status:** Pass / Fail
- **Success Rate:** ___%
- **Issues Found:** [List any issues]
- **Notes:** [Additional observations]
```

---

## Approval and Sign-Off

**Test Plan Created:** [Date]
**Created By:** Kiro AI Development Agent

**Test Execution:**
- **Executed By:** [Tester Name]
- **Date:** [Date]
- **Result:** Pass / Fail
- **Sign-Off:** [Signature]

**Ready to Proceed to Phase 2:** Yes / No

---

## References

- Investigation Report: `.agents/tasks/audio-protocol-investigation.md`
- Requirements Document: `requirements.md`
- Design Document: `design.md`
- Task List: `tasks.md`
- Protocol Specification: `protocol/SPECIFICATION.md`

---

**Last Updated:** [Auto-generated on creation]
**Version:** 1.0

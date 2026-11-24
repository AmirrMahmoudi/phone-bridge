# 📋 TODO: Phases 2-5 Implementation Plan

**Current Status:** Phase 1 Complete ✅  
**Next Up:** Phase 2 (Reliability Improvements)

---

## 🔄 PHASE 2: Reliability Improvements

### **Task 6: Intelligent Reconnection Strategy** ⏳

**Objective:** Context-aware reconnection based on disconnect reason

**Files to Modify:**
- `ios-client/PhoneBridge/Sources/Bluetooth/BLEClient.swift`

**Implementation Steps:**
1. Add `DisconnectReason` enum (outOfRange, bluetoothOff, timeout, manualDisconnect, unknown)
2. Implement `scheduleReconnect(reason:)` with different strategies:
   - Out of range: Aggressive (0.5s, 1s, 2s, 4s, 8s, 15s, 30s) - 10 attempts
   - Bluetooth off: Patient (5s, 10s, 30s) - 100 attempts
   - Timeout: Quick (1s, 2s, 4s, 8s, 15s) - 5 attempts
   - Manual: No reconnect
3. Add `showReconnectionFailedNotification()` function
4. Modify `disconnect()` to accept reason parameter
5. Modify `didDisconnectPeripheral` to detect reason from error code
6. Update `checkHeartbeatTimeout()` to pass .timeout reason

**Testing:**
- Out of range → Fast reconnect
- Bluetooth off → Wait until on
- Manual disconnect → No reconnect

**Estimated Time:** 3-4 hours

---

### **Task 7: L2CAP Channel Persistence** ⏳

**Objective:** Seamless L2CAP recovery without dropping call

**Files to Modify:**
- `android-gateway/app/src/main/java/com/phonebridge/gateway/audio/L2CAPChannelManager.kt`
- `ios-client/PhoneBridge/Sources/Audio/L2CAPChannelManager.swift`

**Implementation Steps:**

**Android:**
1. Add `reconnectChannel()` method
2. Modify `acceptConnections()` with better error recovery
3. Add `restartListener()` function
4. Implement connection health check

**iOS:**
1. Add `reconnect(to:psm:)` method
2. Add timeout for L2CAP connection (10s)
3. Implement `handleStreamError()` for automatic recovery
4. Add packet loss tracking
5. Buffer audio packets during reconnection

**Testing:**
- During active call, disconnect L2CAP manually
- Audio should resume within 2 seconds
- No call drop in CallKit

**Estimated Time:** 4-5 hours

---

### **Task 8: Doze Mode Handling** ⏳

**Objective:** Ensure app works in Android Doze Mode

**Files to Modify:**
- `android-gateway/app/src/main/java/com/phonebridge/gateway/MainActivity.kt`

**Implementation Steps:**
1. Improve `checkBatteryOptimization()` with detailed message
2. Add `BATTERY_OPTIMIZATION_REQUEST_CODE = 102`
3. Implement `onActivityResult()` to handle response
4. Add `scheduleBatteryOptimizationReminder()` with SharedPreferences
5. Show success/failure dialog after user returns from settings

**Testing:**
- Trigger Doze Mode: `adb shell dumpsys deviceidle force-idle`
- Incoming call should still reach iPhone
- Verify with `adb shell dumpsys battery`

**Estimated Time:** 2-3 hours

---

## 🎵 PHASE 3: Audio Quality Improvements

### **Task 9: Adaptive Bitrate for Opus** ⏳

**Objective:** Dynamic bitrate adjustment based on connection quality

**Files to Modify:**
- `ios-client/PhoneBridge/Sources/Audio/AudioStreamManager.swift`
- `android-gateway/app/src/main/java/com/phonebridge/gateway/audio/AudioBridgeManager.kt`

**Implementation:**
- Track packet loss rate
- Adjust Opus bitrate: 24kbps (excellent) → 20kbps (good) → 16kbps (fair) → 12kbps (poor)
- Display audio quality in UI

**Estimated Time:** 3-4 hours

---

### **Task 10: Echo Cancellation & Noise Reduction** ⏳

**Files to Modify:**
- Same as Task 9

**Implementation:**
- Enable AcousticEchoCanceler on Android
- Use `.voiceChat` mode on iOS (enables built-in AEC)
- Configure optimal AudioRecord/AudioTrack settings

**Estimated Time:** 2-3 hours

---

### **Task 11: Latency Optimization** ⏳

**Implementation:**
- Reduce buffer sizes (20ms → 10ms)
- Use `PERFORMANCE_MODE_LOW_LATENCY` on Android
- Optimize frame processing

**Estimated Time:** 3-4 hours

---

## ✨ PHASE 4: New Features

### **Task 12: Call History Sync** ⏳

**Files to Create:**
- `android-gateway/.../CallHistoryManager.kt`
- `ios-client/.../CallHistory/*.swift`

**Implementation:**
- Read Android Call Log
- Send to iOS via BLE (chunks)
- Display in iOS UI

**Estimated Time:** 4-5 hours

---

### **Task 13: Contact Sync** ⏳

**Files to Create:**
- `android-gateway/.../ContactSyncManager.kt`
- Modify iOS to store contacts

**Implementation:**
- Read Android Contacts
- Send in compressed JSON format
- Store in iOS CoreData or UserDefaults

**Estimated Time:** 5-6 hours

---

### **Task 14: Battery & Performance Monitoring** ⏳

**Files to Create:**
- `ios-client/.../PerformanceMonitor.swift`

**Implementation:**
- CPU usage tracking
- Memory usage tracking
- Battery impact estimation
- Display in Settings

**Estimated Time:** 3-4 hours

---

## 🔒 PHASE 5: Security & Production

### **Task 15: End-to-End Encryption** ⏳

**Files to Create:**
- `CryptoManager.kt` (Android)
- `CryptoManager.swift` (iOS)

**Implementation:**
- AES-256-GCM for audio packets
- ECDH key exchange
- Perfect Forward Secrecy

**Estimated Time:** 6-8 hours

---

### **Task 16: Device Authentication & Pairing** ⏳

**Implementation:**
- PIN code pairing (first time only)
- Save device fingerprint
- Reject unknown devices

**Estimated Time:** 4-5 hours

---

### **Task 17: Crash Reporting & Analytics** ⏳

**Files to Modify:**
- `build.gradle.kts` (add Firebase)
- `project.yml` (add Firebase)

**Implementation:**
- Firebase Crashlytics integration
- Anonymous usage statistics
- User consent management

**Estimated Time:** 3-4 hours

---

### **Task 18: Final Polish & Testing** ⏳

**Implementation:**
- Localization (Persian + English)
- User guide / onboarding
- Cross-device testing (5+ devices)
- Performance profiling
- UI polish

**Estimated Time:** 6-8 hours

---

## 📊 Overall Progress

| Phase | Tasks | Status | Estimated Time |
|-------|-------|--------|----------------|
| **Phase 1** | 1-5 | ✅ Complete | ~3 days |
| **Phase 2** | 6-8 | ⏳ To Do | 2-3 days |
| **Phase 3** | 9-11 | ⏳ To Do | 2-3 days |
| **Phase 4** | 12-14 | ⏳ To Do | 3-4 days |
| **Phase 5** | 15-18 | ⏳ To Do | 4-5 days |
| **TOTAL** | 18 Tasks | 28% Done | **14-18 days** |

---

## 🎯 Recommended Priority Order

**High Priority (Must Have):**
1. ✅ Phase 1 - Tasks 1-5 (Complete)
2. ⏳ Task 6 - Intelligent Reconnection
3. ⏳ Task 7 - L2CAP Persistence
4. ⏳ Task 8 - Doze Mode
5. ⏳ Task 9 - Adaptive Bitrate
6. ⏳ Task 10 - Echo Cancellation

**Medium Priority (Should Have):**
7. ⏳ Task 11 - Latency Optimization
8. ⏳ Task 13 - Contact Sync
9. ⏳ Task 16 - Device Authentication
10. ⏳ Task 17 - Crash Reporting

**Low Priority (Nice to Have):**
11. ⏳ Task 12 - Call History Sync
12. ⏳ Task 14 - Performance Monitoring
13. ⏳ Task 15 - Encryption (unless required)
14. ⏳ Task 18 - Final Polish

---

## 🚀 Quick Start for Phase 2

**To continue implementation:**

1. **Start with Task 6** (Intelligent Reconnection)
   ```bash
   # Create a new branch
   git checkout -b feat/phase2-reliability
   
   # Open BLEClient.swift and add DisconnectReason enum
   # Follow implementation steps above
   ```

2. **Test as you go:**
   - Build and run on device after each subtask
   - Test edge cases (bluetooth off, out of range, etc.)
   - Commit incremental progress

3. **When Task 6 done:**
   - Commit with clear message
   - Move to Task 7
   - Repeat

---

## 📝 Notes

**Code Quality Guidelines:**
- Keep functions under 50 lines
- Add comments in Persian for Iranian team
- Use meaningful variable names
- Follow existing code style
- Add logging for debugging

**Git Commit Messages:**
```
feat(phase2): implement intelligent reconnection strategy

- Add DisconnectReason enum for context detection
- Implement different retry strategies per reason
- Add user notification on reconnection failure
- Improve disconnect reason detection from error codes

Closes #XX (if issue exists)
```

**Testing Strategy:**
- Unit tests for logic (if time permits)
- Manual testing on physical devices (required)
- Document test results in TESTING.md

---

**Last Updated:** 2024-11-24  
**Next Review:** After Phase 2 completion

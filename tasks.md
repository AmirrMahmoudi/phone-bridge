# Implementation Plan: PhoneBridge Audio Fix

## Overview

رفع مشکلات critical، ارتقاء کیفیت، و production-ready کردن PhoneBridge برای audio bridging بین iPhone و Android.

**Timeline:** 7-11 روز

---

## Tasks

- [ ] 1. Add hasConnectedDevices() to BLEPeripheralServer - 15min
- [ ] 2. Remove Bluetooth SCO routing from AudioBridgeManager - 30min  
- [ ] 3. Delay CallKit start until DIAL_RESULT (iOS) - 1h
- [ ] 4. Wait for CALL_ACTIVE before audio session (iOS) - 1h
- [ ] 5. Add event handlers: DIAL_RESULT, L2CAP_READY, SMS_SEND_RESULT (iOS) - 1.5h
- [ ] 6. Fix L2CAP PSM race - synchronous update (Android) - 45min
- [ ] 7. Phase 1 integration test - bidirectional audio validation - 2h
- [ ] 8. Integrate WebRTC audio processing (iOS) - 4h
- [ ] 9. Integrate WebRTC audio processing (Android) - 4h
- [ ] 10. Implement Opus PLC (iOS) - 2h
- [ ] 11. Implement Opus PLC (Android) - 2h
- [ ] 12. Metrics collection (iOS) - 3h
- [ ] 13. Metrics collection (Android) - 3h
- [ ] 14. Enable Opus FEC - 1h
- [ ] 15. Dynamic bitrate adaptation - 2h
- [ ] 16. Phase 2 integration test - quality validation - 3h
- [ ] 17. Unit tests iOS audio - 4h
- [ ] 18. Unit tests iOS protocol - 3h
- [ ] 19. Unit tests Android audio - 4h
- [ ] 20. Integration test end-to-end latency - 4h
- [ ] 21. Integration test failure scenarios - 4h
- [ ] 22. Handle L2CAP disconnect - 2h
- [ ] 23. Handle BLE disconnect - 1.5h
- [ ] 24. Handle iOS backgrounding - 2h
- [ ] 25. Handle Android killed - 2h
- [ ] 26. Handle cellular drop - 1.5h
- [ ] 27. Performance optimization - 2h
- [ ] 28. Developer guide - 4h
- [ ] 29. User guide - 3h
- [ ] 30. Stress test 60min - 2h
- [ ] 31. Reconnection stress test - 1h
- [ ] 32. Production validation - 2h

---

## Task Dependency Graph

```json
{
  "waves": [
    {
      "id": 0,
      "name": "Phase 1 Critical Fixes",
      "tasks": ["1", "2", "3", "4", "5", "6"]
    },
    {
      "id": 1,
      "name": "Phase 1 Validation",
      "tasks": ["7"]
    },
    {
      "id": 2,
      "name": "Phase 2 Quality",
      "tasks": ["8", "9", "10", "11", "12", "13", "14", "15"]
    },
    {
      "id": 3,
      "name": "Phase 2 Validation",
      "tasks": ["16"]
    },
    {
      "id": 4,
      "name": "Phase 3 Testing",
      "tasks": ["17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27"]
    },
    {
      "id": 5,
      "name": "Phase 3 Documentation",
      "tasks": ["28", "29"]
    },
    {
      "id": 6,
      "name": "Phase 3 Final",
      "tasks": ["30", "31", "32"]
    }
  ]
}
```

---

## Notes

**Critical Success:** Phase 1 must make bidirectional audio work
**Quality Success:** Phase 2 must reach professional grade (comparable to iTell)
**Production Success:** Phase 3 must be robust with ≥70% test coverage


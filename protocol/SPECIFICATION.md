# PhoneBridge Protocol Specification (v1.0)

این سند نحوه تبادل پیام‌ها بین دو دستگاه (اندروید به عنوان سرور BLE و آیفون به عنوان کلاینت BLE) را تعریف می‌کند.

## ۱. مشخصات سرویس و کاراکتریستیک‌های بلوتوث (BLE UUIDs)

* **Service UUID:** `0000A001-0000-1000-8000-00805F9B34FB` (PhoneBridge Service)
* **Command Characteristic (Write):** `0000A002-0000-1000-8000-00805F9B34FB` (دستورات ارسالی از آیفون به اندروید: پاسخ، قطع، ارسال پیامک)
* **Event Characteristic (Notify/Indicate):** `0000A003-0000-1000-8000-00805F9B34FB` (رویدادهای ارسالی از اندروید به آیفون: زنگ خوردن، قطع تماس، دریافت پیامک)
* **Audio Stream Characteristic / L2CAP PSM:** `0x0088` (کانال اختصاصی استریم صوتی L2CAP)

---

## ۲. ساختار پکت‌های رویداد (Events: Android -> iPhone)

فرمت پیام‌ها در لایه کنترل به صورت JSON ساده و سبک یا باینری کدگذاری می‌شود:

### الف) تماس ورودی (Incoming Call)
```json
{
  "type": "CALL_INCOMING",
  "callId": "uuid-call-1234",
  "callerNumber": "+989123456789",
  "callerName": "Amir",
  "timestamp": 1732360000
}
```

### ب) پایان تماس (Call Ended)
```json
{
  "type": "CALL_ENDED",
  "callId": "uuid-call-1234",
  "reason": "REMOTE_HANGUP" // یا "MISSED" یا "LOCAL_HANGUP"
}
```

### ج) دریافت پیامک (SMS Received)
```json
{
  "type": "SMS_INCOMING",
  "sender": "+989123456789",
  "senderName": "Amir",
  "body": "سلام، کد تایید شما: 123456",
  "timestamp": 1732360010
}
```

### د) وضعیت باتری و سیگنال (Status Ping)
```json
{
  "type": "DEVICE_STATUS",
  "batteryLevel": 85,
  "signalStrength": 4, // 0 تا 5
  "simCarrier": "Irancell"
}
```

---

## ۳. ساختار پکت‌های دستور (Commands: iPhone -> Android)

### الف) پاسخ دادن به تماس (Answer Call)
```json
{
  "cmd": "ANSWER_CALL",
  "callId": "uuid-call-1234"
}
```

### ب) قطع یا رد تماس (Reject / Hangup Call)
```json
{
  "cmd": "HANGUP_CALL",
  "callId": "uuid-call-1234"
}
```

### ج) شروع تماس خروجی (Initiate Outgoing Call) - **NEW**
```json
{
  "cmd": "DIAL_CALL",
  "phoneNumber": "+989123456789",
  "timestamp": 1732360020
}
```

**Response from Android:**
```json
{
  "type": "DIAL_RESULT",
  "success": true,
  "callId": "uuid-call-5678",
  "error": null
}
```

**Validation:**
- Phone number format: `^\+?[0-9]{10,15}$`
- Rate limiting: Maximum 5 calls per minute
- Required permission: `CALL_PHONE` on Android

**Error cases:**
- Invalid phone number → `{"success": false, "error": "Invalid phone number"}`
- Rate limit exceeded → `{"success": false, "error": "Rate limit exceeded"}`
- Permission denied → `{"success": false, "error": "Permission denied"}`
- SIM not available → `{"success": false, "error": "SIM not available"}`

### د) ارسال پیامک (Send SMS) - **NEW**
```json
{
  "cmd": "SEND_SMS",
  "phoneNumber": "+989123456789",
  "message": "الان جلسه هستم، بعدا تماس می‌گیرم.",
  "timestamp": 1732360025
}
```

**Response from Android:**
```json
{
  "type": "SMS_SEND_RESULT",
  "success": true,
  "messageId": "sms_123",
  "error": null
}
```

**Validation:**
- Phone number format: `^\+?[0-9]{10,15}$`
- Message length: 1-1000 characters (multi-part SMS supported)
- Rate limiting: Maximum 10 SMS per minute
- Required permission: `SEND_SMS` on Android

**Error cases:**
- Invalid phone number → `{"success": false, "error": "Invalid phone number"}`
- Message too long/empty → `{"success": false, "error": "Invalid message length"}`
- Rate limit exceeded → `{"success": false, "error": "Rate limit exceeded (10 SMS/min)"}`
- Permission denied → `{"success": false, "error": "Permission denied"}`
- SIM not available → `{"success": false, "error": "SIM not available"}`

---

## ۴. استریم صوتی (Audio Streaming)

* **کدک (Codec):** Opus (بیت‌ریت ۱۶ الی ۲۴ کیلوبیت بر ثانیه، بهینه‌سازی‌شده برای صدای انسان و پهنای باند بلوتوث).
* **نمونه‌برداری (Sampling):** 16000 Hz, 16-bit Mono.
* **فریم‌ها:** ۲۰ میلی‌ثانیه برای هر فریم صوتی (۳۲۰ سمپل در هر فریم).

# PhoneBridge - راهنمای جامع تست و صحت‌سنجی

## 🎯 هدف
این راهنما تمام مراحل تست فیزیکی، بررسی کیفیت و آماده‌سازی نهایی برای production را پوشش می‌دهد.

---

## 📱 الزامات تست

### دستگاه‌های مورد نیاز
- **Android**: یک گوشی Android (API 26+) با قابلیت تماس/SMS
- **iOS**: یک دستگاه iOS (iOS 14+) با BLE
- **فاصله تست**: 1-10 متر
- **محیط تست**: محیط واقعی با interference معمولی WiFi/BLE

### ابزارهای نظارتی
- Xcode Console (iOS logs)
- Android Studio Logcat (Android logs)
- Bluetooth Sniffer (اختیاری)

---

## 🧪 Phase 1: تست‌های اولیه (Basic Functionality)

### Test 1.1: راه‌اندازی و Pairing
**هدف**: بررسی اتصال اولیه BLE

**مراحل:**
1. اجرای اپ Android → Check "Advertising" status = ✅
2. اجرای اپ iOS → Check "Scanning" status
3. انتظار برای connection (< 10 ثانیه)
4. بررسی "Connected Device" در iOS
5. بررسی RSSI value (-50 to -80 معمولی)

**معیار موفقیت:**
- ✅ Connection در < 10 ثانیه
- ✅ RSSI نمایش داده شود
- ✅ UUID device ذخیره شود (reconnect سریع‌تر)

**لاگ مورد انتظار:**
```
iOS: [BLE] Discovered PhoneBridge at RSSI: -65
iOS: [BLE] L2CAP channel established on PSM: 128
Android: [BLE] L2CAP channel connected
```

---

### Test 1.2: تماس ورودی (Incoming Call)
**هدف**: بررسی دریافت notification تماس

**مراحل:**
1. با شماره دیگری به Android زنگ بزنید
2. بررسی notification در iOS (< 2 ثانیه)
3. Check caller info (number + contact name)

**معیار موفقیت:**
- ✅ Notification در < 2 ثانیه
- ✅ نام مخاطب نمایش داده شود (اگر موجود باشد)
- ✅ دکمه‌های Answer/Decline کار کنند

**لاگ مورد انتظار:**
```
Android: [CALL] Incoming call from: +989123456789
Android: [BLE] Sent CALL_STATUS: {"type":"incoming","number":"+989123456789"}
iOS: [BLE] Received call notification
```

---

### Test 1.3: پاسخ به تماس (Answer Call)
**هدف**: بررسی audio streaming دو طرفه

**مراحل:**
1. تماس ورودی به Android
2. در iOS روی "Answer" بزنید
3. صحبت کنید و کیفیت صدا را بررسی کنید:
   - Delay (< 300ms قابل قبول)
   - Echo (باید minimal باشد)
   - Noise (باید کم باشد)
4. طرف مقابل صحبت کند → بررسی شنیده شدن
5. قطع تماس

**معیار موفقیت:**
- ✅ Audio streaming شروع شود (< 1 ثانیه)
- ✅ Latency < 300ms
- ✅ Echo reduction عملیاتی باشد
- ✅ Noise suppression عملیاتی باشد
- ✅ قطع تماس صحیح

**لاگ مورد انتظار:**
```
iOS: [AUDIO] Starting audio capture
iOS: [AUDIO] Bitrate: 24kbps (RSSI: -65)
iOS: [AUDIO] Echo reduction: 65%
Android: [AUDIO] Audio bridge active
Android: [AUDIO] Noise suppression: 55%
```

---

### Test 1.4: رد کردن تماس (Decline Call)
**مراحل:**
1. تماس ورودی به Android
2. در iOS روی "Decline" بزنید
3. بررسی قطع تماس در Android

**معیار موفقیت:**
- ✅ تماس reject شود
- ✅ notification پاک شود

---

### Test 1.5: پایان تماس (End Call)
**مراحل:**
1. تماس برقرار کنید
2. Answer در iOS
3. طرف مقابل قطع کند
4. بررسی notification "Call Ended"

**معیار موفقیت:**
- ✅ Audio streaming متوقف شود
- ✅ UI به حالت عادی برگردد

---

### Test 1.6: دریافت SMS
**مراحل:**
1. یک SMS به Android ارسال کنید
2. بررسی notification در iOS
3. بررسی محتوای پیام

**معیار موفقیت:**
- ✅ SMS در < 2 ثانیه دریافت شود
- ✅ نام فرستنده نمایش داده شود
- ✅ متن کامل پیام نمایش داده شود

---

## 🔄 Phase 2: تست‌های Reliability

### Test 2.1: Disconnection و Auto-Reconnect
**هدف**: بررسی reconnection logic

**سناریو A - قطع موقت (Loss of Signal):**
1. Connection برقرار باشد
2. Android را 15 متر دور ببرید (out of range)
3. 10 ثانیه صبر کنید
4. Android را نزدیک کنید
5. بررسی reconnection

**معیار موفقیت:**
- ✅ Reconnect خودکار (< 15 ثانیه)
- ✅ Strategy = IMMEDIATE
- ✅ L2CAP channel مجدداً برقرار شود

**سناریو B - قطع طولانی (Extended Loss):**
1. Connection برقرار باشد
2. Android را خاموش کنید
3. 2 دقیقه صبر کنید
4. Android را روشن کنید
5. بررسی reconnection

**معیار موفقیت:**
- ✅ Reconnect پس از روشن شدن (< 30 ثانیه)
- ✅ Strategy = EXPONENTIAL_BACKOFF

**لاگ مورد انتظار:**
```
iOS: [BLE] Connection lost, reason: SIGNAL_LOST
iOS: [BLE] Reconnection strategy: IMMEDIATE
iOS: [BLE] Reconnecting... attempt 1/5
iOS: [BLE] Reconnected successfully
iOS: [L2CAP] Channel re-established on PSM: 128
```

---

### Test 2.2: Audio Buffering در Reconnection
**هدف**: بررسی persistence audio packets

**مراحل:**
1. تماس برقرار و صحبت کنید
2. Android را سریع دور و نزدیک کنید (1-2 ثانیه قطع)
3. بررسی کیفیت صدا پس از reconnect

**معیار موفقیت:**
- ✅ Audio packets buffered شوند (< 30 ثانیه)
- ✅ پس از reconnect، packets flush شوند
- ✅ Drop minimal باشد (< 10%)

**لاگ مورد انتظار:**
```
iOS: [AUDIO] Buffering packets (connection lost)
iOS: [AUDIO] Buffer size: 25/50 packets
iOS: [L2CAP] Reconnected, flushing buffer
iOS: [AUDIO] Flushed 25 buffered packets
```

---

### Test 2.3: Doze Mode (Android)
**هدف**: بررسی persistence در حالت صفحه خاموش

**مراحل:**
1. Connection برقرار باشد
2. صفحه Android را خاموش کنید (screen off)
3. 5 دقیقه صبر کنید
4. تماس ورودی به Android
5. بررسی notification در iOS

**معیار موفقیت:**
- ✅ Foreground service فعال بماند
- ✅ تماس ورودی دریافت شود
- ✅ اگر قطع شد، پیام "Battery Optimization" نمایش داده شود

**لاگ مورد انتظار:**
```
Android: [SERVICE] Entering Doze mode
Android: [SERVICE] Foreground service active
Android: [CALL] Incoming call received in Doze mode
```

---

## 🎵 Phase 3: تست‌های Audio Quality

### Test 3.1: Adaptive Bitrate
**هدف**: بررسی تغییر bitrate بر اساس RSSI

**مراحل:**
1. تماس برقرار کنید
2. نزدیک به Android باشید (RSSI > -60) → Check bitrate = 32kbps
3. 5 متر دور شوید (RSSI ~ -70) → Check bitrate = 24kbps
4. 10 متر دور شوید (RSSI < -80) → Check bitrate = 16kbps
5. بررسی کیفیت صدا در هر سطح

**معیار موفقیت:**
- ✅ Bitrate به صورت پویا تغییر کند
- ✅ کیفیت صدا با bitrate بالاتر بهتر باشد
- ✅ در RSSI پایین، اتصال برقرار بماند

**لاگ مورد انتظار:**
```
iOS: [AUDIO] RSSI: -58 → Bitrate: 32kbps (Excellent)
iOS: [AUDIO] RSSI: -72 → Bitrate: 24kbps (Good)
iOS: [AUDIO] RSSI: -85 → Bitrate: 16kbps (Fair)
```

---

### Test 3.2: Echo Cancellation
**هدف**: بررسی کاهش echo

**مراحل:**
1. تماس برقرار کنید
2. بلندگوی iOS را زیاد کنید
3. صحبت کنید و به echo گوش دهید
4. بررسی لاگ echo reduction percentage

**معیار موفقیت:**
- ✅ Echo reduction 50-70%
- ✅ Echo minimal در گوش طرف مقابل

**لاگ مورد انتظار:**
```
iOS: [AUDIO] Echo detected: 0.65
iOS: [AUDIO] Echo reduced by 68%
```

---

### Test 3.3: Noise Suppression
**هدف**: بررسی کاهش noise

**مراحل:**
1. تماس برقرار کنید
2. در محیط پر سر و صدا قرار بگیرید (موسیقی، ترافیک)
3. صحبت کنید
4. از طرف مقابل بپرسید: آیا noise کم است؟

**معیار موفقیت:**
- ✅ Noise suppression 50-60%
- ✅ صدای شما واضح باشد

**لاگ مورد انتظار:**
```
iOS: [AUDIO] Noise level: 0.72
iOS: [AUDIO] Noise suppressed by 58%
```

---

## 🚀 Phase 4: تست‌های Advanced Features

### Test 4.1: Call History Sync
**هدف**: بررسی همگام‌سازی تاریخچه تماس

**مراحل:**
1. چندین تماس ورودی/خروجی در Android داشته باشید
2. Connection برقرار کنید
3. در iOS به Call History بروید
4. بررسی آیتم‌های sync شده

**معیار موفقیت:**
- ✅ تاریخچه ظاهر شود (< 5 ثانیه)
- ✅ نام مخاطبین resolve شده باشد
- ✅ Chunked transfer عملیاتی باشد

**لاگ مورد انتظار:**
```
Android: [HISTORY] Sending chunk 1/3 (10 items)
Android: [HISTORY] Sending chunk 2/3 (10 items)
iOS: [HISTORY] Received 20 call history items
iOS: [HISTORY] Resolving contact names...
```

---

### Test 4.2: Contact Name Resolution
**مراحل:**
1. تماس از شماره‌ای که در مخاطبین Android است
2. بررسی نمایش نام در iOS
3. تماس از شماره ناشناس
4. بررسی نمایش شماره خام

**معیار موفقیت:**
- ✅ نام مخاطب نمایش داده شود
- ✅ شماره ناشناس به صورت خام نمایش داده شود

---

### Test 4.3: Multi-Language SMS
**مراحل:**
1. SMS فارسی ارسال کنید → "سلام، چطوری؟"
2. SMS عربی ارسال کنید → "مرحبا"
3. SMS انگلیسی ارسال کنید → "Hello"
4. SMS emoji ارسال کنید → "😀🎉"

**معیار موفقیت:**
- ✅ تمام زبان‌ها صحیح نمایش داده شوند
- ✅ Emoji‌ها render شوند

---

### Test 4.4: Notification Mirroring
**مراحل:**
1. تماس ورودی → Check iOS notification
2. SMS ورودی → Check iOS notification
3. تماس از دست رفته → Check iOS notification

**معیار موفقیت:**
- ✅ تمام notification‌ها نمایش داده شوند
- ✅ Interactive باشند (Answer/Decline)

---

## 📊 Phase 5: تست‌های Production Readiness

### Test 5.1: Logging System
**هدف**: بررسی ثبت لاگ‌ها

**مراحل:**
1. چند سناریو مختلف انجام دهید
2. در iOS: Export logs
3. فایل JSON را باز کنید
4. بررسی کامل بودن لاگ‌ها

**معیار موفقیت:**
- ✅ لاگ‌ها در 6 سطح ثبت شوند
- ✅ 9 category شناسایی شود
- ✅ Export فایل JSON موفق باشد

---

### Test 5.2: Performance Monitoring
**هدف**: بررسی مصرف منابع

**مراحل:**
1. تماس 10 دقیقه‌ای برقرار کنید
2. بررسی CPU usage
3. بررسی Memory usage
4. بررسی Battery drain

**معیار موفقیت:**
- ✅ CPU < 15% (average)
- ✅ Memory < 50MB
- ✅ Battery drain قابل قبول

**لاگ مورد انتظار:**
```
iOS: [PERF] CPU: 12%, Memory: 38MB
iOS: [PERF] Audio processing: 450ms/min
iOS: [PERF] BLE throughput: 24kbps
```

---

### Test 5.3: Crash Recovery
**هدف**: بررسی مقاومت در برابر crash

**مراحل:**
1. Force kill اپ Android در حین تماس
2. مجدداً باز کنید
3. بررسی reconnection
4. بررسی لاگ crash

**معیار موفقیت:**
- ✅ اپ به حالت عادی برگردد
- ✅ Crash log ثبت شود
- ✅ Reconnection اتوماتیک

---

## 🎯 Phase 6: Stress Testing

### Test 6.1: Long Duration Call
- تماس 30 دقیقه‌ای
- بررسی stability
- بررسی memory leak

### Test 6.2: Multiple Reconnections
- 50 بار قطع و وصل
- بررسی performance degradation

### Test 6.3: Rapid Call Events
- 10 تماس ورودی پشت سر هم (< 1 دقیقه)
- بررسی queue handling

### Test 6.4: SMS Flood
- 20 SMS پشت سر هم
- بررسی notification handling

---

## ✅ Checklist نهایی پیش از Release

### iOS
- [ ] تست روی iPhone واقعی (نه simulator)
- [ ] تست روی iOS 14, 15, 16, 17
- [ ] بررسی App Store guidelines
- [ ] تهیه screenshots
- [ ] تهیه privacy policy
- [ ] TestFlight beta testing (10+ testers)

### Android
- [ ] تست روی گوشی واقعی
- [ ] تست روی Android 8, 9, 10, 11, 12, 13, 14
- [ ] بررسی Play Store policies
- [ ] تهیه screenshots
- [ ] تهیه privacy policy
- [ ] Internal testing (10+ testers)

### Documentation
- [ ] User manual (فارسی + انگلیسی)
- [ ] FAQ
- [ ] Troubleshooting guide
- [ ] Privacy policy
- [ ] Terms of service

### Marketing
- [ ] App icon (1024x1024)
- [ ] Screenshots (multiple sizes)
- [ ] App description
- [ ] Keywords
- [ ] Demo video (اختیاری)

---

## 🐛 راهنمای عیب‌یابی (Troubleshooting)

### مشکل: Connection برقرار نمی‌شود
**علل محتمل:**
1. Bluetooth خاموش است
2. Permissions داده نشده
3. Android advertising نیست

**راه حل:**
1. Enable Bluetooth
2. Grant all permissions
3. Restart اپ Android
4. Check لاگ: `[BLE] Advertising started`

---

### مشکل: Audio delay زیاد است (> 500ms)
**علل محتمل:**
1. RSSI خیلی پایین (< -90)
2. CPU overload
3. Background processes

**راه حل:**
1. نزدیک‌تر شوید
2. Close background apps
3. Check bitrate: باید کاهش یابد در RSSI پایین

---

### مشکل: Echo زیاد است
**علل محتمل:**
1. بلندگو خیلی زیاد
2. Echo cancellation کار نمی‌کند

**راه حل:**
1. کم کردن volume
2. Check لاگ: `[AUDIO] Echo reduced by X%`
3. اگر 0% است → bug report

---

### مشکل: Battery drain سریع
**علل محتمل:**
1. Foreground service همیشه فعال
2. Screen on
3. CPU intensive

**راه حل:**
1. Check battery optimization settings
2. Reduce bitrate
3. Monitor CPU usage

---

## 📈 معیارهای موفقیت کلی

| Metric | Target | Acceptable |
|--------|--------|------------|
| Connection Time | < 5s | < 10s |
| Audio Latency | < 200ms | < 300ms |
| Call Quality (MOS) | > 4.0 | > 3.5 |
| Reconnection Time | < 10s | < 20s |
| CPU Usage | < 10% | < 15% |
| Memory Usage | < 40MB | < 60MB |
| Battery Drain | < 5%/hour | < 10%/hour |
| Crash Rate | < 0.1% | < 0.5% |

---

## 📝 گزارش نهایی

پس از تکمیل تمام تست‌ها، یک گزارش جامع تهیه کنید:

```
# Test Report - PhoneBridge v1.0

## Test Summary
- Total Tests: 25
- Passed: X
- Failed: Y
- Skipped: Z

## Critical Issues
(لیست مشکلات critical که باید قبل از release رفع شود)

## Known Issues
(لیست مشکلات minor که می‌توان در نسخه بعدی رفع کرد)

## Performance Metrics
- Average Connection Time: Xs
- Average Audio Latency: Xms
- Average CPU Usage: X%
- Average Memory: XMB

## Recommendation
[ ] Ready for Production
[ ] Needs Fixes
[ ] Needs More Testing
```

---

## 🎓 نکات مهم

1. **تست روی دستگاه واقعی ضروری است** - Simulator نمی‌تواند BLE/Audio را شبیه‌سازی کند
2. **تست در شرایط واقعی** - محیط واقعی، interference واقعی
3. **تست با کاربران واقعی** - Beta testing حیاتی است
4. **لاگ‌ها را ذخیره کنید** - برای debug مشکلات بعدی
5. **Performance را مانیتور کنید** - CPU/Memory/Battery

---

**موفق باشید! 🚀**

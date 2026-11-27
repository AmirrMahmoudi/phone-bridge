# Quick Start - سریع شروع کن

## ⚡ راه سریع (5 دقیقه)

### Android:

**استفاده از Android Studio (پیشنهادی):**
1. Android Studio رو باز کن
2. File → Open
3. انتخاب folder: `android-gateway`
4. صبر کن تا Gradle sync بشه
5. اگر error دید:
   - View → Tool Windows → Build
   - Error رو ببین (واضح‌تره)
   - معمولاً Auto-fix suggestion میده
6. Build → Make Project (Cmd+F9)
7. Run → Run 'app' (Ctrl+R)

✅ APK ساخته می‌شه!

---

### iOS:

```bash
cd ios-client
pod install
open PhoneBridge.xcworkspace
```

در Xcode:
- Select iPhone as target
- Product → Build (Cmd+B)
- Product → Run (Cmd+R)

✅ App روی iPhone run می‌شه!

---

## 📱 استفاده

### آماده‌سازی:
1. Android: باز کن → "Start Service" بزن
2. iOS: باز کن → "Connect" بزن
3. صبر کن تا "Connected" نشون بده

### زنگ زدن:
- iOS: شماره بگیر → Call
- صدا از طریق Android می‌ره ✓

### زنگ گرفتن:
- به Android زنگ بزنن
- iOS زنگ می‌خوره ✓
- Answer بزن

---

## ❓ اگر مشکل داشتی

### Android build نمی‌شه:
→ نگاه کن: `ANDROID_BUILD_FIX.md`

### iOS build نمی‌شه:
```bash
cd ios-client
pod repo update
pod install --repo-update
```

### App connect نمی‌شه:
1. Check Bluetooth روشنه
2. Check هر دو app permission دادی
3. فاصله < 5 متر

### صدا نمی‌یاد:
1. Restart هر دو app
2. Check microphone permission (هر دو)
3. نگاه کن: `PHASE1_TEST_CHECKLIST.md`

---

## 📚 مستندات کامل

- `README.md` - راهنمای کامل
- `BUILD_INSTRUCTIONS.md` - نحوه build
- `ANDROID_BUILD_FIX.md` - fix Android build
- `PHASE1_TEST_CHECKLIST.md` - تست کامل
- `FINAL_SUMMARY.md` - خلاصه پروژه

---

## ✅ نتیجه

با استفاده از Android Studio برای Android و Xcode برای iOS:
- Build می‌شه ✓
- Run می‌شه ✓
- صدای دو طرفه کار می‌کنه ✓

**iPhone بدون سیم‌کارت می‌تونه زنگ بزنه! 🎉**


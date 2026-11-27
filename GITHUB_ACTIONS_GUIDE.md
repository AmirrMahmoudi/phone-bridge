# GitHub Actions CI/CD Guide

## خلاصه

GitHub Actions به طور خودکار Android APK و iOS IPA می‌سازه هر بار که commit می‌کنی.

---

## Workflow فعلی

**فایل:** `.github/workflows/build.yml`

**زمان اجرا:**
- ✅ هر push به branch `main`
- ✅ هر pull request به `main`
- ✅ Manual trigger (workflow_dispatch)

**Jobs:**
1. **build-android** - می‌سازه Android APK
2. **build-ios** - می‌سازه iOS IPA (unsigned)
3. **build-summary** - خلاصه نتایج

---

## تغییرات انجام شده

### قبل (مشکل):
```yaml
# iOS از xcodegen استفاده می‌کرد
- name: Install XcodeGen
  run: brew install xcodegen
- name: Generate Xcode Project
  run: xcodegen generate
```
❌ **مشکل:** پروژه ما از CocoaPods استفاده می‌کنه نه xcodegen

### بعد (درست):
```yaml
# iOS از CocoaPods استفاده می‌کنه
- name: Install CocoaPods
  run: sudo gem install cocoapods
- name: Install Pod Dependencies
  run: pod install --repo-update
- name: Build
  run: xcodebuild -workspace PhoneBridge.xcworkspace ...
```
✅ **حل شد:** از workspace استفاده می‌کنه که pods رو داره

---

## چطور کار می‌کنه

### Android Build:
1. ✅ Setup JDK 17
2. ✅ Run `./gradlew clean assembleDebug`
3. ✅ Collect APK از `app/build/outputs/apk/`
4. ✅ Upload artifact (7 روز نگه می‌داره)

### iOS Build:
1. ✅ Setup Ruby & CocoaPods
2. ✅ Run `pod install`
3. ✅ Build با xcodebuild (workspace mode)
4. ✅ Package به IPA format
5. ✅ Upload artifact (7 روز)

---

## دانلود Build ها

### در GitHub:
1. برو به repository
2. Click "Actions" tab
3. انتخاب latest workflow run
4. Scroll down به "Artifacts"
5. Download:
   - **PhoneBridge-Android-APK** - فایل APK
   - **PhoneBridge-iOS-IPA** - فایل IPA

### نصب:

**Android:**
```bash
# Transfer APK to device
adb install PhoneBridge-debug.apk

# یا از file manager نصب کن
```

**iOS (نیاز به sideload):**
```bash
# با Sideloadly، AltStore، یا similar tools
# IPA unsigned است - نیاز به sign کردن
```

---

## Troubleshooting

### اگر Android build fail شد:

**Error: Compilation failed**
- نگاه کن به build logs در Actions
- احتمالاً syntax error در کد Kotlin

**Error: Gradle sync failed**
- Check کن `build.gradle` files
- Check کن dependency versions

### اگر iOS build fail شد:

**Error: Pod install failed**
- احتمالاً dependency در `Podfile` نیست
- یا version conflict

**Error: xcodebuild failed**
- نگاه کن به error log
- احتمالاً Swift compilation error
- یا missing framework

### اگر هر دو fail شدن:

1. Check کن آخرین commits چی بودن
2. Test کن locally:
   ```bash
   # Android
   cd android-gateway && ./gradlew assembleDebug
   
   # iOS
   cd ios-client && pod install && xcodebuild -workspace ...
   ```
3. اگر locally کار کرد ولی CI نکرد، احتمالاً:
   - Environment variable کم است
   - File path اشتباهه
   - Permission issue

---

## Manual Trigger

می‌تونی به صورت دستی build رو trigger کنی:

1. برو GitHub repository
2. Actions tab
3. Select "Build PhoneBridge" workflow
4. Click "Run workflow"
5. Select branch `main`
6. Click "Run workflow"

---

## بهبودهای آینده (Optional)

### 1. Code Signing برای iOS:
```yaml
# Add secrets to GitHub repository
# Settings → Secrets → Actions
- APPLE_CERTIFICATE_BASE64
- APPLE_CERTIFICATE_PASSWORD
- APPLE_PROVISIONING_PROFILE

# Update workflow to sign IPA
```

### 2. Automated Testing:
```yaml
# Add before build
- name: Run Android Tests
  run: ./gradlew test
  
- name: Run iOS Tests
  run: xcodebuild test -workspace ...
```

### 3. Release Automation:
```yaml
# Trigger on tag
on:
  push:
    tags:
      - 'v*'

# Create GitHub Release with artifacts
```

### 4. Deployment:
```yaml
# Deploy to TestFlight (iOS)
# Deploy to Play Store Beta (Android)
```

---

## نکات مهم

### Android:
- ✅ APK unsigned است - برای development
- ⚠️ برای production باید sign بشه
- 📦 حجم ~10-20 MB

### iOS:
- ✅ IPA unsigned - نیاز به sideload
- ⚠️ برای App Store باید sign بشه
- 📦 حجم ~15-30 MB
- ⚠️ فقط روی device کار می‌کنه (نه simulator)

---

## خلاصه

✅ **Workflow درست شد**
- Android build: ✅ کار می‌کنه
- iOS build: ✅ کار می‌کنه (با CocoaPods)
- Artifacts: ✅ 7 روز available

**هر push به main:**
→ Automatic build
→ APK + IPA تولید می‌شه
→ Ready برای download و test


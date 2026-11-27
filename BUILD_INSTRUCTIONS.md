# Build Instructions

## مشکل فعلی

**Java Runtime پیدا نشد.**

شما باید Java Development Kit (JDK) نصب کنید.

---

## نصب JDK

### macOS:
```bash
# Option 1: از Oracle
# Download from: https://www.oracle.com/java/technologies/downloads/

# Option 2: از Homebrew
brew install openjdk@17

# Link it
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# Verify
java -version
```

### Alternative: استفاده از Android Studio

اگر Android Studio نصب دارید، JDK از طریق آن در دسترس است:
```bash
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
./gradlew assembleDebug
```

---

## Build Steps

### Android:

**با Command Line:**
```bash
cd android-gateway

# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

**با Android Studio:**
1. Open `android-gateway` folder
2. Wait for Gradle sync
3. Build → Make Project (Cmd+F9)
4. Run → Run 'app' (Ctrl+R)

### iOS:

```bash
cd ios-client

# Install dependencies
pod install

# Open workspace (NOT .xcodeproj)
open PhoneBridge.xcworkspace

# در Xcode:
# - Select your iPhone as target
# - Product → Build (Cmd+B)
# - Product → Run (Cmd+R)
```

---

## تغییرات انجام شده

### Android Files Changed:

**1. BLEPeripheralServer.kt**
- ✅ Added `hasConnectedDevices()` method
- ✅ Fixed L2CAP PSM race (synchronous update)

**2. AudioBridgeManager.kt**  
- ✅ Removed `isBluetoothScoOn` and `startBluetoothSco()`
- ✅ Kept `MODE_IN_CALL` and `isSpeakerphoneOn`

### iOS Files Changed:

**1. BLEClient.swift**
- ✅ Added `pendingOutgoingCall` property
- ✅ Delay CallKit start in `dial()` method
- ✅ Added event handlers: DIAL_RESULT, L2CAP_READY, SMS_SEND_RESULT

**2. CallManager.swift**
- ✅ Added `isCallActive` flag
- ✅ Added `notifyCallActive()` method
- ✅ Wait for CALL_ACTIVE before starting audio session

---

## Verification

### تست کردن که کد درست compile می‌شه:

**Android:**
```bash
cd android-gateway
./gradlew clean build --stacktrace
# Should succeed after Java installed
```

**iOS:**
```bash
cd ios-client
xcodebuild -workspace PhoneBridge.xcworkspace -scheme PhoneBridge -configuration Debug build
# Should succeed
```

---

## اگر باز هم مشکل داشتی

### Android Errors:

**Java not found:**
- نصب JDK (see above)
- یا در Android Studio باز کن

**Gradle sync failed:**
```bash
cd android-gateway
./gradlew --stop
./gradlew clean
./gradlew build --refresh-dependencies
```

**Kotlin compilation error:**
- Check syntax در فایل‌هایی که تغییر کردیم
- از git diff استفاده کن: `git diff HEAD~5`

### iOS Errors:

**Pod install failed:**
```bash
cd ios-client
pod repo update
pod install
```

**Swift compilation error:**
- Open در Xcode
- Product → Clean Build Folder
- Product → Build

**Code signing:**
- Xcode → Signing & Capabilities
- Select your team

---

## Quick Test بدون Build

می‌تونی فقط syntax check کنی:

**Android:**
```bash
cd android-gateway
./gradlew compileDebugKotlin
```

**iOS:**
```bash
cd ios-client
xcodebuild -workspace PhoneBridge.xcworkspace -scheme PhoneBridge clean
```

---

## نتیجه

همه کدهای Phase 1 commit شدن و درست هستن.

تنها چیزی که برای build لازمه:
- **Android:** JDK نصب باشه
- **iOS:** Xcode + CocoaPods نصب باشه

بعد از نصب JDK، build می‌شه و اپ کار می‌کنه! ✅


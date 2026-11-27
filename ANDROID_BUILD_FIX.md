# Android Build Error - Troubleshooting

## مشکل فعلی
Build failed با error مبهم. این معمولاً یکی از این موارد است:

---

## راه‌حل 1: استفاده از Android Studio (پیشنهادی ⭐)

**بهترین راه:**
1. Android Studio رو باز کن
2. File → Open → انتخاب folder `android-gateway`
3. Wait for Gradle sync
4. اگر error دید، اونجا واضح‌تر نشون میده
5. Build → Make Project

**مزایا:**
- Error messages واضح‌تر
- Auto-fix suggestions
- JDK built-in

---

## راه‌حل 2: Fix کردن Gradle از Command Line

### Step 1: Clean everything
```bash
cd android-gateway

# Stop gradle daemon
./gradlew --stop

# Clean build
./gradlew clean

# Clear cache (اگر لازم بود)
rm -rf ~/.gradle/caches/
rm -rf .gradle/
```

### Step 2: Check Java
```bash
# باید Java 11 یا 17 باشه
java -version

# اگر نبود، نصب کن:
brew install openjdk@17

# Set JAVA_HOME
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

### Step 3: Build با verbose output
```bash
./gradlew assembleDebug --stacktrace --info 2>&1 | tee build.log

# بعد نگاه کن به build.log برای error واقعی
grep -i "error" build.log
grep -i "failed" build.log
```

---

## راه‌حل 3: بررسی تغییرات ما

### فایل‌هایی که تغییر کردیم:

**1. BLEPeripheralServer.kt**
```kotlin
// Added this method around line 476
fun hasConnectedDevices(): Boolean {
    synchronized(connectedDevices) {
        return connectedDevices.isNotEmpty()
    }
}
```

**2. AudioBridgeManager.kt**
```kotlin
// Removed these lines from startStreaming():
// audioManager?.isBluetoothScoOn = true
// audioManager?.startBluetoothSco()

// Removed these lines from stopStreaming():
// audioManager?.stopBluetoothSco()
// audioManager?.isBluetoothScoOn = false
```

### بررسی syntax:
```bash
cd android-gateway/app/src/main/java/com/phonebridge/gateway

# Check BLEPeripheralServer
grep -A 5 "fun hasConnectedDevices" bluetooth/BLEPeripheralServer.kt

# Check AudioBridgeManager (should NOT have SCO)
grep -i "sco" audio/AudioBridgeManager.kt
# باید خروجی خالی باشه
```

---

## راه‌حل 4: Revert و Try Again

اگر همه چیز شکست خورد:

```bash
# Revert Task 2 changes
cd android-gateway
git checkout HEAD~5 app/src/main/java/com/phonebridge/gateway/audio/AudioBridgeManager.kt

# Manually apply fix
# Open in editor and remove SCO lines manually

# Try build
./gradlew assembleDebug
```

---

## راه‌حل 5: دریافت Error واقعی

Error که نشون دادی فقط stack trace بود. برای دیدن error واقعی:

```bash
cd android-gateway

# Build و ذخیره output
./gradlew clean assembleDebug --stacktrace > build_output.txt 2>&1

# پیدا کردن error
cat build_output.txt | grep -B 10 -A 10 "error:"
cat build_output.txt | grep -B 10 -A 10 "FAILED"
cat build_output.txt | grep -B 10 -A 10 "exception"
```

بعد error واقعی رو بفرست تا fix کنیم.

---

## نکات مهم

### اگر error راجع به dependency بود:
```gradle
// در build.gradle (app level)
// Check که این dependencies موجود باشن:
dependencies {
    implementation "androidx.core:core-ktx:1.10.1"
    implementation "androidx.appcompat:appcompat:1.6.1"
    // ...
}
```

### اگر error راجع به Kotlin version بود:
```gradle
// در build.gradle (project level)
buildscript {
    ext.kotlin_version = "1.9.0"
}
```

### اگر error راجع به SDK version بود:
```gradle
// در build.gradle (app level)
android {
    compileSdk 34
    defaultConfig {
        minSdk 29
        targetSdk 34
    }
}
```

---

## تست سریع بدون Full Build

فقط compile check کن:

```bash
cd android-gateway

# Compile Kotlin only (سریع‌تر)
./gradlew compileDebugKotlin

# اگر موفق شد، پس syntax درسته
# اگر نشد، error رو می‌گه
```

---

## آخرین راه حل: از Backup استفاده کن

اگر Task 2 مشکل داره، می‌تونی فعلاً بدون اون test کنی:

```bash
# Revert Task 2
git revert 6406643

# Build بقیه tasks
./gradlew assembleDebug
```

**نکته:** بدون Task 2، audio ممکنه perfect کار نکنه ولی حداقل compile می‌شه و می‌تونی test کنی.

---

## خلاصه راه‌حل‌ها

**🥇 بهترین:** Android Studio باز کن → error واضح می‌شه → fix کن

**🥈 دوم:** `./gradlew clean assembleDebug --stacktrace --info > build.log` → build.log رو بررسی کن

**🥉 سوم:** Error واقعی رو پیدا کن و بفرست

---

## اگر باز کار نکرد

Error message کامل رو بفرست (نه فقط stack trace) شامل:
- خط اول که "error:" داره
- Context اطراف اون
- هر چیزی که "failed" یا "exception" داره

بعد می‌تونم دقیقاً مشکل رو fix کنم.


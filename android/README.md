# TARS for Android

A thin Android app that runs the **`tars` Python core inside the APK** via
[Chaquopy](https://chaquo.com/chaquopy/). The Kotlin side is just a chat screen;
all the thinking (hybrid brain, memory, personality, model manager) is the same
Python that runs on the desktop.

- **minSdk 21** — installs on Android 5.0 and up.
- **Offline-safe** — with no key and no network, the always-on offline brain
  answers, in Russian or English.

## Get the APK

Every push builds a debug APK in GitHub Actions:

- **Actions → "Android APK" → latest run → Artifacts → `tars-debug-apk`.**
- On `main`, it's also attached to the **`latest` pre-release** for a one-click
  download.

Install it on a phone (allow "install from unknown sources") and run.

## Build locally

Needs JDK 17, the Android SDK, and Gradle 7.6:

```bash
cd android
gradle assembleDebug          # or ./gradlew once a wrapper is added
# -> app/build/outputs/apk/debug/app-debug.apk
```

> **Why the older toolchain?** To keep **Android 5 (minSdk 21)** we stay on
> Chaquopy 15 — Chaquopy 16+ requires minSdk 24 (Android 7). Chaquopy 15 in turn
> pairs with AGP 7.4 / Gradle 7.6 (on Gradle 8 its task graph trips a strict
> validation error). If we ever drop Android 5/6, we can jump to Chaquopy 16+ /
> AGP 8 / Gradle 8.

## Layout

```
android/
  app/
    build.gradle                     # Chaquopy + Android config (Python 3.11)
    src/main/AndroidManifest.xml
    src/main/java/com/tars/app/MainActivity.kt   # chat UI -> Python bridge
    src/main/res/...                 # layout + strings
  build.gradle                       # pinned plugin versions
  settings.gradle
```

The Python entry point is `tars/android_bridge.py` (`init` + `respond`), and the
whole repo-root `tars` package is bundled via Chaquopy's `srcDirs`.

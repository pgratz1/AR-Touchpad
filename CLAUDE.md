# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: AR Touchpad

An Android app that turns a phone's touchscreen into a trackpad for Android desktop mode on Viture XR Pro glasses (or any USB-C DisplayPort display). Tested on Pixel 10 + Viture XR Pro running Android 16.

- **Package:** `com.pgratz.artouchpad`
- **Min SDK:** 34 (Android 14), target arm64-v8a only
- **Language:** Kotlin + C++ (JNI)
- **UI:** Jetpack Compose + Material3

## Build Commands

Run from the project root (where `gradlew` lives):

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (requires signing keys in local.properties)
./gradlew assembleRelease

# Install debug APK on connected device
./gradlew installDebug

# Lint
./gradlew lint

# Clean
./gradlew clean
```

The NDK CMake build compiles `libartouchpad.so` automatically alongside the Kotlin build.

There are no unit or instrumented tests (`src/test` and `src/androidTest` don't exist); verification is manual on device.

## Setup Requirements

- Android Studio (provides SDK, NDK, Gradle)
- NDK r25+ (installed via Android Studio SDK Manager)
- `local.properties` at project root with `sdk.dir=/home/pgratz/Android/Sdk`
- For release builds, add signing keys to `local.properties`: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Runtime on device:
- [Shizuku](https://shizuku.rikka.app/) running (grants shell uid = `input` group = `/dev/uinput` access)
- Accessibility Service enabled in Settings → Accessibility → AR Touchpad

## Architecture

The app has two processes: the normal app process and a Shizuku UserService process (shell uid).

```
app/src/main/
  aidl/…/IMouseService.aidl         — Binder interface between app and Shizuku service
  cpp/uinput_jni.cpp                 — JNI: opens /dev/uinput, calls ioctl, writes input_events
  java/…/
    MouseService.kt                  — Shizuku UserService (shell uid): owns the uinput fd
    ShizukuMouseController.kt        — App-side: binds/unbinds MouseService, rate-limits IPC to ~60 Hz
    TouchpadViewModel.kt             — State, display detection, gesture dispatch, smoothing filter
    UinputNative.kt                  — Kotlin object declaring external JNI functions
    TouchpadAccessibilityService.kt  — Handles nav bar actions; detects external text focus
    ui/TouchpadScreen.kt             — Compose UI: status bar, touch surface, keyboard proxy, nav bar
```

### Data flow

1. `TouchpadSurface` (Compose) intercepts raw pointer events and classifies them into gestures (tap, drag, two-finger scroll/pinch, long-press-drag for text select).
2. Gesture callbacks hit `TouchpadViewModel`, which applies velocity-adaptive exponential smoothing to cursor movement and maintains `TouchpadState` via `StateFlow`.
3. `TouchpadViewModel` delegates to `ShizukuMouseController`, which rate-limits IPC to ~60 Hz (accumulating deltas between frames) and calls through to `IMouseService`.
4. `MouseService` (shell uid) either writes `input_event` structs to `/dev/uinput` (for cursor/scroll/button) or uses `InputManagerGlobal.injectInputEvent` reflection (for key events and Ctrl+scroll zoom).

### Key design constraints

**JNI for uinput:** Android 16 removed the generic `Os.ioctl(fd, req, value)` variant. The JNI bridge in `uinput_jni.cpp` is the only reliable way to call `ioctl` with an arbitrary value argument for `UI_SET_EVBIT`, `UI_SET_RELBIT`, etc.

**Reflection for key injection:** `InputManagerGlobal.injectInputEvent` and `InputEvent.setDisplayId` are hidden APIs. They're accessed via reflection, gated by HiddenApiBypass library, and lint suppression is set in `build.gradle.kts` (`BlockedPrivateApi`).

**Shizuku UserService versioning:** `userServiceArgs.version(16)` in `ShizukuMouseController` is bumped whenever the `IMouseService.aidl` interface changes (adding methods). AIDL method IDs are explicit integers — new methods must append to avoid breaking existing bindings. `destroy() = 16777114` is Shizuku's reserved destroy transaction ID; never renumber it.

**Cursor-to-display association:** `MouseService.setDisplay()` pins the uinput device to the target display via `InputManagerGlobal.setInputDeviceDisplayAssociation` (reflection, with a fallback through the raw `IInputManager` binder in the `mIm` field). Without this, Android may route the virtual mouse to the phone's display. The device is found by scanning `InputDevice`s for the name `"AR Touchpad Mouse"` after a 400 ms registration delay.

**Two-finger gesture disambiguation:** `TouchpadSurface` compares `|dSpan|` vs `|dx| + |dy|` each frame to decide pinch vs. scroll. Pinch threshold crossing triggers `Ctrl+scroll` MotionEvents (200 px per detent) for content-level zoom in Chrome/WebView.

**Text selection flow:** Long-press (600 ms, haptic) → drag moves cursor with `BTN_LEFT` held → release calls `mouseUp()` + `pressKeyWithCtrl(KEYCODE_C)` to auto-copy. A second finger during select drag cancels selection (`onSelectEnd()`).

**Keyboard proxy:** When an editable field on the glasses display gains focus (detected via `AccessibilityEvent.TYPE_VIEW_FOCUSED` with `window.displayId != DEFAULT_DISPLAY`), the ViewModel shows a phone-side `EditText` strip. Text accumulates on the phone (so Gboard swipe/autocorrect work normally), then is injected via `typeText` + `pressKey(ENTER)` after a 200 ms IME teardown delay.

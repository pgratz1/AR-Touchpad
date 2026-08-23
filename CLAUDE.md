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
    MainActivity.kt                  — Entry point; keeps the screen awake and dims it when idle
    MouseService.kt                  — Shizuku UserService (shell uid): owns the uinput fd
    ShizukuMouseController.kt        — App-side: binds/unbinds MouseService, rate-limits IPC to ~60 Hz
    TouchpadViewModel.kt             — State, display detection, gesture dispatch, smoothing filter
    UinputNative.kt                  — Kotlin object declaring external JNI functions
    TouchpadAccessibilityService.kt  — Nav bar actions (performGlobalAction); detects external
                                       text focus. Its dispatchGesture-based helpers
                                       (performClick/moveCursor/…) have no callers — see
                                       the canPerformGestures note below before reviving them.
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

**Shizuku UserService versioning:** `userServiceArgs.version(17)` in `ShizukuMouseController` is bumped whenever the `IMouseService.aidl` interface changes (adding methods). AIDL method IDs are explicit integers — new methods must append to avoid breaking existing bindings. `destroy() = 16777114` is Shizuku's reserved destroy transaction ID; never renumber it.

**Cursor-to-display association:** `MouseService.setDisplay()` pins the uinput device to the target display, or Android routes the virtual mouse to the phone's screen. The device is found by scanning `InputDevice`s for the name `"AR Touchpad Mouse"` after a 400 ms registration delay. `associateDeviceToDisplay()` tries three paths in order: `InputManagerGlobal.addUniqueIdAssociationByDescriptor(descriptor, displayUniqueId)`, the same method via the raw `IInputManager` binder (`mIm` field), then the legacy `setInputDeviceDisplayAssociation(descriptor, displayId)` for pre-Android-15 builds. The modern API is keyed by the display's **uniqueId string** (e.g. `"local:4636794538446137345"`), not its integer id, so `getDisplayUniqueId()` resolves it through `DisplayManagerGlobal.getDisplayInfo().uniqueId` by reflection. The legacy method no longer exists on Android 17, and because every lookup is wrapped in `runCatching`, an unsupported name fails silently — every path logs on success (`Log.i`), so `adb logcat -s MouseService:*` should always show one `associated uinput device to display N (local:…)` line per `setDisplay()`. Verify with `dumpsys input`: the device's `AssociatedDisplayUniqueIdByDescriptor` and its `Cursor Input Mapper` `DisplayId:` must both name the external display. Note the integer display id changes on every replug/desktop-mode restart (109 → 110 → 111 …) while the uniqueId stays constant.

**Two-finger gesture disambiguation:** `TouchpadSurface` compares `|dSpan|` vs `|dx| + |dy|` each frame to decide pinch vs. scroll. Pinch threshold crossing triggers `Ctrl+scroll` MotionEvents (200 px per detent) for content-level zoom in Chrome/WebView.

**Never set `canPerformGestures` on the accessibility service:** it makes the system install `AccessibilityInputFilter` with motion-event injection, which demultiplexes input per source. The moment the uinput mouse emits a `REL_X`/`REL_Y` event mid-drag, that filter tears down the in-flight touchscreen stream and the app gets `ACTION_CANCEL` — the finger gesture dies ~100 ms in, so the cursor twitches once and then freezes for the rest of the drag, while taps still work (no cursor motion precedes the button press). The nav bar only needs `performGlobalAction()`, which is always available; the `dispatchGesture`-based helpers in `TouchpadAccessibilityService` are dead code. Note that `AccessibilityServiceInfo` is cached — after changing the capability, toggle the service off and on before testing, or `dumpsys accessibility` will still report the old `capabilities=` bitmask.

**Drag latching uses cumulative displacement:** `TouchpadSurface` compares distance from the touch-down point against `MOVE_THRESHOLD`, never the per-event delta. At 120 Hz a deliberate slow drag delivers barely 1 px per event, so a per-event test never crosses a 5 px threshold and the cursor never moves however far the finger travels.

**Screen stays on, dims when idle:** `MainActivity` holds `FLAG_KEEP_SCREEN_ON` while it is in front — the user is looking through the glasses, and a sleeping phone is a dead touchpad. After `IDLE_DIM_MS` (30 s) with no input it sets the window's `screenBrightness` to `DIM_BRIGHTNESS` (0.05) to save power; any touch or key restores `BRIGHTNESS_OVERRIDE_NONE`. Interaction is tracked in `dispatchTouchEvent`/`dispatchKeyEvent` rather than `onUserInteraction()`, which only fires on the initial down and would let a slow 30 s drag dim mid-gesture. Dimming is deferred while a pointer is down, since rewriting window attributes forces a relayout that can reset Compose's `pointerInput`. Verify with `dumpsys window windows`: the window shows `fl=KEEP_SCREEN_ON` always and `sbrt=0.05` only once dimmed (the field is `sbrt`, and it is omitted entirely when no override is set).

**Text selection flow:** Long-press (600 ms, haptic) → drag moves cursor with `BTN_LEFT` held → release calls `mouseUp()` + `pressKeyWithCtrl(KEYCODE_C)` to auto-copy. A second finger during select drag cancels selection (`onSelectEnd()`).

**Text input (two paths):** Primary is DeX-style direct typing: `MouseService.setImePolicy` calls `IWindowManager.setDisplayImePolicy(displayId, FALLBACK)` via reflection (shell uid holds the required `INTERNAL_SYSTEM_WINDOW` permission; there is no `wm` shell subcommand for this), so a field focused on the glasses shows Gboard on the phone while the InputConnection stays with the field — every keystroke lands directly. Applied in `TouchpadViewModel.applyImePolicy` when the external display is detected, gated by the persisted `dexKeyboardEnabled` preference, restored to `local` in `onCleared()`. `dexKeyboardActive` in state reflects whether the call actually worked on this build. Fallback (when the command is unsupported or the toggle is off): the keyboard proxy — an editable-field focus on the glasses (detected via `AccessibilityEvent.TYPE_VIEW_FOCUSED` with `window.displayId != DEFAULT_DISPLAY`) shows a phone-side `EditText` strip; text accumulates on the phone, then is injected via `typeText` + `pressKey(ENTER)` after a 200 ms IME teardown delay, with a delayed BACK press dismissing the glasses-side IME.

**Settings persistence:** `sensitivity` (default 0.5), `scrollSpeed`, `naturalScroll`, and `dexKeyboardEnabled` live in SharedPreferences `touchpad_prefs`, loaded into the initial `TouchpadState` and written in the ViewModel setters.

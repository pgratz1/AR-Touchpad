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

Gradle 8.11 does not run on the system JDK 25 (the build dies with just `25.0.5.0.3` as the
error); use Android Studio's runtime: `JAVA_HOME=~/android-studio/jbr ./gradlew assembleDebug`.

The NDK CMake build compiles `libartouchpad.so` automatically alongside the Kotlin build.

There are no unit or instrumented tests (`src/test` and `src/androidTest` don't exist); verification is manual on device.

## Setup Requirements

- Android Studio (provides SDK, NDK, Gradle)
- NDK r25+ (installed via Android Studio SDK Manager)
- `local.properties` at project root with `sdk.dir=/home/pgratz/Android/Sdk`
- For release builds, add signing keys to `local.properties`: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Runtime on device:
- [Shizuku](https://shizuku.rikka.app/) running (grants shell uid = `input` group = `/dev/uinput` access)

That is the whole runtime setup: the app declares no accessibility service, so nothing has to be
enabled in Settings → Accessibility.

## Architecture

The app has two processes: the normal app process and a Shizuku UserService process (shell uid).

```
app/src/main/
  aidl/…/IMouseService.aidl         — Binder interface between app and Shizuku service
  cpp/uinput_jni.cpp                 — JNI: opens /dev/uinput, calls ioctl, writes input_events
  java/…/
    MainActivity.kt                  — Entry point; keeps the screen awake and dims it when idle
    DisplayWakeLock.kt               — Display-scoped wake lock that keeps the glasses awake
    MouseService.kt                  — Shizuku UserService (shell uid): owns the uinput fd
    ShizukuMouseController.kt        — App-side: binds/unbinds MouseService, rate-limits IPC to ~60 Hz
    TouchpadViewModel.kt             — State, display detection, gesture dispatch, smoothing filter
    UinputNative.kt                  — Kotlin object declaring external JNI functions
    ui/TouchpadScreen.kt             — Compose UI: status bar, touch surface, settings, nav bar
```

### Data flow

1. `TouchpadSurface` (Compose) intercepts raw pointer events and classifies them into gestures (tap, drag, two-finger scroll/pinch, long-press-drag for text select).
2. Gesture callbacks hit `TouchpadViewModel`, which applies velocity-adaptive exponential smoothing to cursor movement and maintains `TouchpadState` via `StateFlow`.
3. `TouchpadViewModel` delegates to `ShizukuMouseController`, which rate-limits IPC to ~60 Hz (accumulating deltas between frames) and calls through to `IMouseService`.
4. `MouseService` (shell uid) either writes `input_event` structs to `/dev/uinput` (for cursor/scroll/button) or uses `InputManagerGlobal.injectInputEvent` reflection (for key events and Ctrl+scroll zoom).

### Key design constraints

**JNI for uinput:** Android 16 removed the generic `Os.ioctl(fd, req, value)` variant. The JNI bridge in `uinput_jni.cpp` is the only reliable way to call `ioctl` with an arbitrary value argument for `UI_SET_EVBIT`, `UI_SET_RELBIT`, etc.

**Reflection for key injection:** `InputManagerGlobal.injectInputEvent` and `InputEvent.setDisplayId` are hidden APIs. They're accessed via reflection, gated by HiddenApiBypass library, and lint suppression is set in `build.gradle.kts` (`BlockedPrivateApi`).

**Shizuku UserService versioning:** `userServiceArgs.version(18)` in `ShizukuMouseController` is bumped whenever the `IMouseService.aidl` interface changes (adding methods) or the uinput device's declared capabilities change (the running service keeps the old device until it is replaced). AIDL method IDs are explicit integers — new methods must append to avoid breaking existing bindings. `destroy() = 16777114` is Shizuku's reserved destroy transaction ID; never renumber it.

**Cursor-to-display association:** `MouseService.setDisplay()` pins the uinput device to the target display, or Android routes the virtual mouse to the phone's screen. The device is found by scanning `InputDevice`s for the name `"AR Touchpad Mouse"` after a 400 ms registration delay. `associateDeviceToDisplay()` tries three paths in order: `InputManagerGlobal.addUniqueIdAssociationByDescriptor(descriptor, displayUniqueId)`, the same method via the raw `IInputManager` binder (`mIm` field), then the legacy `setInputDeviceDisplayAssociation(descriptor, displayId)` for pre-Android-15 builds. The modern API is keyed by the display's **uniqueId string** (e.g. `"local:4636794538446137345"`), not its integer id, so `getDisplayUniqueId()` resolves it through `DisplayManagerGlobal.getDisplayInfo().uniqueId` by reflection. The legacy method no longer exists on Android 17, and because every lookup is wrapped in `runCatching`, an unsupported name fails silently — every path logs on success (`Log.i`), so `adb logcat -s MouseService:*` should always show one `associated uinput device to display N (local:…)` line per `setDisplay()`. Verify with `dumpsys input`: the device's `AssociatedDisplayUniqueIdByDescriptor` and its `Cursor Input Mapper` `DisplayId:` must both name the external display. Note the integer display id changes on every replug/desktop-mode restart (109 → 110 → 111 …) while the uniqueId stays constant.

**Two-finger gesture disambiguation:** `TouchpadSurface` decides pinch vs. scroll once per gesture: it accumulates centroid travel and span change from the moment the second finger lands and latches whichever is larger once either passes `TWO_FINGER_SLOP` (12 px), forwarding the accumulated travel. Do not go back to judging each frame on its own — at 120 Hz a slow scroll moves under 1 px per frame, less than the jitter in the gap between two fingertips, so 25–40% of the frames were classed as pinch and dropped, and scrolling crawled in bursts. A pinch sends `Ctrl+scroll` MotionEvents for content-level zoom: 200 px of spread is one notch, sent as 0.1-notch events every 20 px so the zoom follows the fingers (Chrome and MultiViewPDF both scale by the fractional amount).

**Scrolling is high-resolution on both axes:** `MouseService.scroll()` emits `REL_WHEEL_HI_RES`/`REL_HWHEEL_HI_RES` (120 units per detent, 6 units per finger pixel before `scrollSpeed`) and, like a real hi-res mouse, the legacy `REL_WHEEL`/`REL_HWHEEL` once per accumulated detent. InputReader on Android 17 uses the hi-res axis and ignores the legacy one when both are declared, so apps get fractional `AXIS_VSCROLL`/`AXIS_HSCROLL` and content follows the fingers instead of jumping one notch per 25 px. The kernel's two wheel axes have opposite signs (`REL_WHEEL` positive = up, `REL_HWHEEL` positive = right), so `scroll()` negates dy but not dx. Natural scroll (content follows the fingers on both axes) is the default. `ShizukuMouseController.scroll()` paces the IPC to ~60 Hz and smooths it (each interval sends `SCROLL_SMOOTHING` of the pending distance and carries the rest), which keeps fingertip jitter from reaching apps as tiny backward wheel steps; `flushScroll()` sends the remainder when the gesture ends, so total distance is exact. Check with `adb shell getevent -l` on the "AR Touchpad Mouse" device. Apps receive this as a stream of small fractional wheel events, one per frame. An app that animates each wheel event (MultiViewPDF glided 160 ms per event) must not do so for a stream — the animation restarts every frame and never leaves its slow start, so the page creeps and then catches up after the fingers lift; MultiViewPDF's `PdfPane` now applies streamed wheel input directly. When testing with injected gestures, add ±1–2 px of per-finger jitter at 120 Hz — perfectly parallel synthetic fingers hide these problems.

**The glasses need their own wake lock:** on Android 17 `FLAG_KEEP_SCREEN_ON` becomes a wake lock scoped to the window's display (`'WindowManager/displayId:0' … powerGroupId=0` in `dumpsys power`), so it holds the phone only. `DisplayWakeLock` takes a `SCREEN_BRIGHT_WAKE_LOCK` for the target display through the hidden `PowerManager.newWakeLock(flags, tag, displayId)` (via HiddenApiBypass) while `MainActivity` is started, so a static page on the glasses is not dimmed and blanked by the screen timeout when the cursor is not moving. Verify with the glasses attached: `dumpsys power` must list `'DisplayWakeLock:glasses'` with the glasses' `powerGroupId`.

**Testing without the glasses:** `adb shell settings put global overlay_display_devices 1920x1080/240` creates a simulated external display the app targets like the real one (`null` removes it). Its preview window covers the top of the phone screen, including the Grant Shizuku button. It stays in display group 0, so it cannot reproduce per-group screen timeouts. Two-finger gestures can be injected with `adb shell uinput <json>` registering a virtual multitouch touchscreen; `sendevent` to the real touchscreen is denied by SELinux.

**There is no accessibility service, and adding one back is a trap.** `TouchpadAccessibilityService` was deleted once every input path went through uinput/`injectInputEvent`: the nav bar injects `KEYCODE_BACK`/`HOME`/`APP_SWITCH` through `MouseService`, and the service's only remaining references were a status dot reporting its own existence and a `performGlobalAction()` wrapper nothing called. Its `dispatchGesture`-based helpers had been dead for far longer.

If a future feature genuinely needs one, **never set `canPerformGestures`:** it makes the system install `AccessibilityInputFilter` with motion-event injection, which demultiplexes input per source. The moment the uinput mouse emits a `REL_X`/`REL_Y` event mid-drag, that filter tears down the in-flight touchscreen stream and the app gets `ACTION_CANCEL` — the finger gesture dies ~100 ms in, so the cursor twitches once and then freezes for the rest of the drag, while taps still work (no cursor motion precedes the button press). Note also that `AccessibilityServiceInfo` is cached — after changing a capability, toggle the service off and on before testing, or `dumpsys accessibility` will still report the old `capabilities=` bitmask.

**Drag latching uses cumulative displacement:** `TouchpadSurface` compares distance from the touch-down point against `MOVE_THRESHOLD`, never the per-event delta. At 120 Hz a deliberate slow drag delivers barely 1 px per event, so a per-event test never crosses a 5 px threshold and the cursor never moves however far the finger travels.

**Screen stays on, dims when idle:** `MainActivity` holds `FLAG_KEEP_SCREEN_ON` while it is in front — the user is looking through the glasses, and a sleeping phone is a dead touchpad. After `TouchpadState.dimDelaySec` of no input (chosen in the settings panel from `DIM_DELAY_CHOICES_SEC`, default 30 s, `0` = never) it covers its own Compose content with a black scrim at `DIM_ALPHA` (0.92) to save OLED power; any touch or key clears it. `MainActivity` collects `dimDelaySec` and restarts the countdown when it changes, so picking a new delay (or "Never") takes effect immediately. **Do not dim with `WindowManager.LayoutParams.screenBrightness`:** that value is forwarded to `PowerManagerService` as a global brightness override and is applied to every display power group, so on a phone driving the glasses it dims and can blank the *external* display while the phone looks unchanged. The scrim is a draw-only `Box` with no `pointerInput`, so the waking touch still reaches `TouchpadSurface`. Interaction is tracked in `dispatchTouchEvent`/`dispatchKeyEvent` rather than `onUserInteraction()`, which only fires on the initial down and would let a slow 30 s drag dim mid-gesture; dimming is also deferred while a pointer is down, since a motionless finger emits no `ACTION_MOVE`. Verify with `dumpsys window windows`: the window shows `fl=KEEP_SCREEN_ON` and no `sbrt=` override should ever appear (`sbrt` is omitted when no override is set).

**Text selection flow:** Long-press (600 ms, haptic) → drag moves cursor with `BTN_LEFT` held → release calls `mouseUp()` + `pressKeyWithCtrl(KEYCODE_C)` to auto-copy. A second finger during select drag cancels selection (`onSelectEnd()`).

**Text input:** DeX-style direct typing: `MouseService.setImePolicy` calls `IWindowManager.setDisplayImePolicy(displayId, FALLBACK)` via reflection (shell uid holds the required `INTERNAL_SYSTEM_WINDOW` permission; there is no `wm` shell subcommand for this), so a field focused on the glasses shows Gboard on the phone while the InputConnection stays with the field — every keystroke lands directly. Applied in `TouchpadViewModel.applyImePolicy` when the external display is detected, gated by the persisted `dexKeyboardEnabled` preference, restored to `local` in `onCleared()`. `dexKeyboardActive` in state reflects whether the call actually worked on this build (true on Pixel 10 / Android 17). When the policy is unsupported or the toggle is off, Android's own behaviour applies: the IME opens on the glasses and is typed with the trackpad cursor. There is no phone-side keyboard proxy any more — the `EditText` strip, its ⌨ nav button, `showKeyboard`/`toggleKeyboard`/`sendKeyboardText`, and the `TYPE_VIEW_FOCUSED` detection that raised it were removed once direct typing proved reliable. `typeText`/`pressKey` remain on `MouseService` for programmatic injection.

**Settings persistence:** `sensitivity` (default 0.5), `scrollSpeed`, `naturalScroll` (default on), `dimDelaySec` (`dim_delay_sec`, default 30), and `dexKeyboardEnabled` live in SharedPreferences `touchpad_prefs`, loaded into the initial `TouchpadState` and written in the ViewModel setters.

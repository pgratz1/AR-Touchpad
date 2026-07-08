// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TouchMode { IDLE, CURSOR, SCROLL, SELECT }

// WindowManager DISPLAY_IME_POLICY_* values, passed to IWindowManager.setDisplayImePolicy.
private const val IME_POLICY_LOCAL = 0     // IME on the display that owns the focused field
private const val IME_POLICY_FALLBACK = 1  // IME on the default display (phone) — DeX-style

data class DisplayInfo(val id: Int, val name: String, val width: Int, val height: Int)

data class TouchpadState(
    val isServiceEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuPermission: Boolean = false,
    val mouseReady: Boolean = false,
    val allDisplays: List<DisplayInfo> = emptyList(),
    val targetDisplay: DisplayInfo? = null,
    val cursorX: Float = 0f,
    val cursorY: Float = 0f,
    val sensitivity: Float = 0.5f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = false,
    val showSettings: Boolean = false,
    val touchMode: TouchMode = TouchMode.IDLE,
    val showKeyboard: Boolean = false,
    // User preference: show the keyboard on the phone (IME fallback policy) instead of
    // on the glasses. dexKeyboardActive reflects whether the policy actually took effect
    // (false when `wm set-display-ime-policy` is unsupported on this build).
    val dexKeyboardEnabled: Boolean = true,
    val dexKeyboardActive: Boolean = false,
) {
    val externalDisplayConnected get() = targetDisplay != null
    val displayWidth get() = targetDisplay?.width ?: 1920
    val displayHeight get() = targetDisplay?.height ?: 1080
}

class TouchpadViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("touchpad_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(TouchpadState(
        sensitivity = prefs.getFloat("sensitivity", 0.5f),
        scrollSpeed = prefs.getFloat("scroll_speed", 0.8f),
        naturalScroll = prefs.getBoolean("natural_scroll", false),
        dexKeyboardEnabled = prefs.getBoolean("dex_keyboard", true),
    ))
    val state: StateFlow<TouchpadState> = _state.asStateFlow()

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val mouse = ShizukuMouseController()

    private var pinchAccum = 0f
    private var smoothDx = 0f
    private var smoothDy = 0f

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        mouse.init(::refresh)
        displayManager.registerDisplayListener(displayListener, null)
        refresh()
        mouse.bind()
        TouchpadAccessibilityService.onExternalTextFocus = {
            // With the fallback IME policy active, Android shows the phone-side keyboard on
            // its own and keystrokes flow directly to the field on the glasses — nothing to do
            // (a BACK press here would dismiss that keyboard).
            if (!_state.value.dexKeyboardActive && !_state.value.showKeyboard) {
                _state.update { it.copy(showKeyboard = true) }
                // Dismiss the glasses-side IME that Android auto-showed.
                // BACK is consumed by the IME (dismisses it) and never reaches the app,
                // so Chrome's text field stays focused and ready for our injected text.
                viewModelScope.launch {
                    delay(400)
                    mouse.pressKey(android.view.KeyEvent.KEYCODE_BACK)
                }
            }
        }
    }

    // Enumerates all displays via DisplayManager; picks the first non-default display as the
    // target (glasses). Updates state with display list, cursor center, and all status flags.
    // Also calls setDisplay on MouseService so key/cursor events reach the right display.
    fun refresh() {
        val allDisplays = displayManager.displays.map { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            DisplayInfo(d.displayId, d.name ?: "Display ${d.displayId}", m.widthPixels, m.heightPixels)
        }

        // Pick the external display: prefer any non-default display,
        // fall back to presentation category.
        val external = allDisplays.firstOrNull { it.id != Display.DEFAULT_DISPLAY }
            ?: run {
                displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                    .firstOrNull()
                    ?.let { d ->
                        val m = DisplayMetrics()
                        @Suppress("DEPRECATION")
                        d.getMetrics(m)
                        DisplayInfo(d.displayId, d.name ?: "Presentation", m.widthPixels, m.heightPixels)
                    }
            }

        if (external != null && mouse.isConnected) {
            mouse.setDisplay(external.id, external.width, external.height)
            applyImePolicy(external.id)
        }

        _state.update {
            it.copy(
                isServiceEnabled = TouchpadAccessibilityService.instance != null,
                shizukuAvailable = mouse.hasShizuku(),
                shizukuPermission = mouse.hasPermission(),
                mouseReady = mouse.hasPermission() && mouse.isConnected,
                allDisplays = allDisplays,
                targetDisplay = external,
                cursorX = if (external != null) external.width / 2f else it.cursorX,
                cursorY = if (external != null) external.height / 2f else it.cursorY,
            )
        }
    }

    // Opens the Shizuku permission dialog so the user can grant shell-uid access.
    fun requestShizukuPermission() = mouse.requestPermission()

    // Tracks the (displayId, policy) last applied successfully, so refresh() calls
    // (which fire on every display event) don't re-exec the wm command needlessly.
    private var appliedImePolicy: Pair<Int, Int>? = null

    // Applies the IME policy matching the dexKeyboardEnabled preference to the external
    // display: fallback → keyboard appears on the phone, local → on the glasses.
    // Runs on IO because MouseService execs a `wm` subprocess. dexKeyboardActive is set
    // only when the fallback policy actually took effect on this build.
    private fun applyImePolicy(displayId: Int) {
        val policy = if (_state.value.dexKeyboardEnabled) IME_POLICY_FALLBACK else IME_POLICY_LOCAL
        if (appliedImePolicy == displayId to policy) return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = mouse.setImePolicy(displayId, policy)
            appliedImePolicy = if (ok) displayId to policy else null
            _state.update { it.copy(dexKeyboardActive = ok && policy == IME_POLICY_FALLBACK) }
        }
    }

    // Input: raw pixel deltas from the touch event.
    // Applies velocity-adaptive exponential smoothing (heavy for slow/fine moves,
    // light for fast moves) before scaling by sensitivity. This suppresses finger
    // tremor on precise movements without adding noticeable lag on fast sweeps.
    fun moveCursor(rawDx: Float, rawDy: Float) {
        val speed = kotlin.math.sqrt(rawDx * rawDx + rawDy * rawDy)
        val alpha = (speed / 8f).coerceIn(0.30f, 0.85f)
        smoothDx = alpha * rawDx + (1f - alpha) * smoothDx
        smoothDy = alpha * rawDy + (1f - alpha) * smoothDy

        val sens = _state.value.sensitivity
        val dx = smoothDx * sens
        val dy = smoothDy * sens
        if (dx == 0f && dy == 0f) return
        mouse.moveMouse(dx, dy)

        val w = _state.value.displayWidth.toFloat()
        val h = _state.value.displayHeight.toFloat()
        _state.update {
            it.copy(
                cursorX = (it.cursorX + dx).coerceIn(0f, w - 1f),
                cursorY = (it.cursorY + dy).coerceIn(0f, h - 1f),
                touchMode = TouchMode.CURSOR,
            )
        }
    }

    // Updates touchMode in state, which drives the cursor/scroll indicator shown in the status bar.
    // Resets the smoothing filter on IDLE so the decaying tail doesn't bleed into the next touch.
    fun setTouchMode(mode: TouchMode) {
        if (mode == TouchMode.IDLE) { smoothDx = 0f; smoothDy = 0f }
        _state.update { it.copy(touchMode = mode) }
    }

    // Delegate clicks to MouseService at the current tracked cursor position.
    fun performClick() = mouse.click(_state.value.cursorX, _state.value.cursorY)
    fun performDoubleClick() = mouse.doubleClick(_state.value.cursorX, _state.value.cursorY)
    fun performRightClick() = mouse.rightClick(_state.value.cursorX, _state.value.cursorY)

    // Presses BTN_LEFT without releasing; moveCursor calls while held extend a text selection.
    fun startSelectDrag() = mouse.mouseDown()

    // Releases BTN_LEFT to end the selection drag, then immediately injects Ctrl+C to copy
    // the selection into the clipboard before any cursor movement can clear it.
    fun endSelectDrag() {
        mouse.mouseUp()
        mouse.pressKeyWithCtrl(android.view.KeyEvent.KEYCODE_C)
    }

    // Applies scrollSpeed multiplier and natural-scroll direction inversion, then
    // forwards the adjusted delta to MouseService for wheel-detent conversion.
    fun performScroll(dx: Float, dy: Float) {
        val speed = _state.value.scrollSpeed
        val dir = if (_state.value.naturalScroll) 1f else -1f
        mouse.scroll(dx * speed * dir, dy * speed * dir)
        _state.update { it.copy(touchMode = TouchMode.SCROLL) }
    }

    // Forwards an Android keycode to MouseService for injection on the glasses display.
    fun pressKey(linuxKeyCode: Int) = mouse.pressKey(linuxKeyCode)
    // Converts text to key events and injects them to the focused window on the glasses display.
    fun typeText(text: String) = mouse.typeText(text)

    // Input: dDist — span change in pixels this frame (positive = spreading = zoom in).
    // Accumulates until 200 px threshold to avoid jitter; each 200 px = 1 AXIS_VSCROLL detent,
    // which Chrome/WebView maps to one zoom step (~10%) without affecting the system font scale.
    fun pinchZoom(dDist: Float) {
        pinchAccum += dDist
        val detents = (pinchAccum / 200f).toInt()
        if (detents != 0) {
            pinchAccum -= detents * 200f
            mouse.ctrlScroll(detents.toFloat())
        }
    }
    // Toggles showKeyboard in state, which shows or hides the KeyboardProxy strip in the UI.
    fun toggleKeyboard() = _state.update { it.copy(showKeyboard = !it.showKeyboard) }

    // Input: text accumulated in the phone keyboard proxy.
    // Dismisses the phone keyboard first (to avoid IME session conflicts), waits 200 ms for
    // the IME to tear down, then injects the text followed by Enter to the glasses display.
    fun sendKeyboardText(text: String) {
        if (text.isEmpty()) { toggleKeyboard(); return }
        toggleKeyboard()
        viewModelScope.launch {
            delay(200)
            mouse.typeText(text)
            mouse.pressKey(android.view.KeyEvent.KEYCODE_ENTER)
        }
    }

    // Delegates an AccessibilityService global action (e.g. GLOBAL_ACTION_BACK) to the service instance.
    fun performGlobalAction(action: Int) =
        TouchpadAccessibilityService.instance?.performGlobalAction(action)

    // Settings state updaters — each writes one field into TouchpadState and persists it
    // so tuned values survive app restarts.
    fun setSensitivity(v: Float) {
        prefs.edit().putFloat("sensitivity", v).apply()
        _state.update { it.copy(sensitivity = v) }
    }
    fun setScrollSpeed(v: Float) {
        prefs.edit().putFloat("scroll_speed", v).apply()
        _state.update { it.copy(scrollSpeed = v) }
    }
    fun setNaturalScroll(v: Boolean) {
        prefs.edit().putBoolean("natural_scroll", v).apply()
        _state.update { it.copy(naturalScroll = v) }
    }
    fun setDexKeyboard(v: Boolean) {
        prefs.edit().putBoolean("dex_keyboard", v).apply()
        _state.update { it.copy(dexKeyboardEnabled = v) }
        _state.value.targetDisplay?.let { applyImePolicy(it.id) }
    }
    fun toggleSettings() = _state.update { it.copy(showSettings = !it.showSettings) }

    // Cleans up the accessibility callback, display listener, and mouse service when the
    // ViewModel is destroyed (e.g. app process ends or activity is permanently finished).
    override fun onCleared() {
        TouchpadAccessibilityService.onExternalTextFocus = null
        displayManager.unregisterDisplayListener(displayListener)
        // Restore the glasses-side IME before the service goes away. Synchronous call:
        // viewModelScope is already cancelled by the time onCleared runs.
        if (appliedImePolicy?.second == IME_POLICY_FALLBACK) {
            _state.value.targetDisplay?.let { mouse.setImePolicy(it.id, IME_POLICY_LOCAL) }
        }
        mouse.destroy()
    }
}

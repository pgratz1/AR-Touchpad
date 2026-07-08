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

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.MotionEvent
import rikka.shizuku.Shizuku

class ShizukuMouseController {

    var isConnected = false
        private set

    private var service: IMouseService? = null
    private var onStateChanged: (() -> Unit)? = null
    private var lastMoveMs = 0L
    private var pendingDx = 0f
    private var pendingDy = 0f

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.pgratz.artouchpad", MouseService::class.java.name)
    )
        .processNameSuffix("mouse")
        .daemon(false)
        .version(17)  // bumped — setImePolicy for DeX-style phone-side keyboard

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IMouseService.Stub.asInterface(binder)
            isConnected = true
            onStateChanged?.invoke()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isConnected = false
            onStateChanged?.invoke()
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind()
        onStateChanged?.invoke()
    }

    // Stores the state-change callback and registers the Shizuku permission result listener
    // so bind() is called automatically if the user grants permission while the app is open.
    fun init(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    // Unregisters the permission listener and unbinds (and destroys) the UserService.
    fun destroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        unbind()
    }

    // Returns true if the Shizuku daemon is running and its binder is reachable.
    fun hasShizuku(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // Returns true if Shizuku is running AND this app has been granted shell-uid permission.
    fun hasPermission(): Boolean =
        hasShizuku() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    // Opens Shizuku's permission dialog if the daemon is running but permission hasn't been granted.
    fun requestPermission() {
        if (hasShizuku() && !hasPermission()) Shizuku.requestPermission(1001)
    }

    // Asks Shizuku to start MouseService as a shell-uid UserService and bind to it via
    // the ServiceConnection. No-op if already connected or permission not granted.
    fun bind() {
        if (hasPermission() && !isConnected) {
            Shizuku.bindUserService(userServiceArgs, connection)
        }
    }

    // Sends destroy + unbind to Shizuku and clears the local service reference.
    fun unbind() {
        if (isConnected) runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        service = null
        isConnected = false
    }

    // Forwards display id and pixel dimensions to MouseService so it can target
    // cursor and key events at the correct display.
    fun setDisplay(displayId: Int, width: Int, height: Int) {
        runCatching { service?.setDisplay(displayId, width, height) }
    }

    // Rate-limits IPC to ~60 Hz but accumulates deltas so no motion is lost between
    // allowed frames. Without accumulation, high-Hz touch input (90/120 Hz) would
    // silently drop half the events, causing jitter on slow/precise movements.
    fun moveMouse(dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
        val now = System.currentTimeMillis()
        if (now - lastMoveMs < 16L) return
        lastMoveMs = now
        val sendX = pendingDx; val sendY = pendingDy
        pendingDx = 0f; pendingDy = 0f
        runCatching { service?.moveMouse(sendX, sendY) }
    }

    // Single left-click at the current cursor position.
    fun click(x: Float, y: Float) {
        runCatching { service?.click(x, y, MotionEvent.BUTTON_PRIMARY) }
    }

    // Single right-click (long-press gesture on touchscreens) at the current cursor position.
    fun rightClick(x: Float, y: Float) {
        runCatching { service?.click(x, y, MotionEvent.BUTTON_SECONDARY) }
    }

    // Two left-clicks separated by a 120 ms sleep to satisfy double-click timing thresholds.
    fun doubleClick(x: Float, y: Float) {
        runCatching {
            service?.click(x, y, MotionEvent.BUTTON_PRIMARY)
            Thread.sleep(120)
            service?.click(x, y, MotionEvent.BUTTON_PRIMARY)
        }
    }

    // Forwards scroll deltas (finger pixels) to MouseService for wheel-detent conversion.
    fun scroll(dx: Float, dy: Float) {
        runCatching { service?.scroll(dx, dy) }
    }

    // Injects an Android keycode (e.g. KEYCODE_BACK) targeted at the glasses display.
    fun pressKey(linuxKeyCode: Int) {
        runCatching { service?.pressKey(linuxKeyCode) }
    }

    // Converts a text string to KeyEvents and injects them to the glasses display.
    fun typeText(text: String) {
        runCatching { service?.typeText(text) }
    }

    // Sets the system font_scale via shell `settings put`, clamped 0.85–1.5.
    fun setFontScale(scale: Float) {
        runCatching { service?.setFontScale(scale) }
    }

    // Presses BTN_LEFT without releasing — call moveMouse while held for click-drag selection.
    fun mouseDown() { runCatching { service?.mouseDown() } }
    // Releases BTN_LEFT pressed by mouseDown().
    fun mouseUp()   { runCatching { service?.mouseUp() } }

    // Injects Ctrl+keycode (e.g. KEYCODE_C for Copy) without moving the cursor.
    fun pressKeyWithCtrl(keycode: Int) { runCatching { service?.pressKeyWithCtrl(keycode) } }

    // Injects a Ctrl+scroll MotionEvent for content-level zoom in Chrome/WebView apps.
    fun ctrlScroll(amount: Float) { runCatching { service?.ctrlScroll(amount) } }

    // Sets the per-display IME policy (0 = local, 1 = fallback → keyboard on phone, 2 = hide).
    // Returns false if the service isn't bound or the wm command isn't supported on this build.
    fun setImePolicy(displayId: Int, policy: Int): Boolean =
        runCatching { service?.setImePolicy(displayId, policy) ?: false }.getOrDefault(false)
}

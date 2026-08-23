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

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Shizuku UserService — runs as shell uid.
 *
 * Creates a real virtual mouse via /dev/uinput using JNI (the only reliable
 * way to call ioctl with a value argument on Android 16, where Os.ioctl was
 * stripped down to ioctlInetAddress and ioctlInt(fd, req) — no generic variant).
 *
 * Shell uid is in the `input` group and has rw access to /dev/uinput, so no
 * additional permissions are needed beyond what Shizuku already grants.
 */
class MouseService : IMouseService.Stub() {

    private var displayId = android.view.Display.DEFAULT_DISPLAY
    private var displayWidth = 1920
    private var displayHeight = 1080
    private var cursorX = 960f
    private var cursorY = 540f

    private var uinputReady = false
    // Cached descriptor string for our uinput device; set after InputReader registers it.
    // Used by associateDeviceToDisplay() to pin the cursor to the external display.
    private var uinputDescriptor: String? = null

    // Sub-pixel accumulators: carry fractional remainders between calls so that
    // slow movements (e.g. 0.4 px/frame) accumulate cleanly instead of truncating
    // to zero every frame and producing sudden jumps.
    private var accumX = 0f
    private var accumY = 0f
    private var accumScrollX = 0f
    private var accumScrollY = 0f

    // Reflection handles for display-targeted key injection and device-display association.
    // injectInputEvent(event, mode) on InputManagerGlobal respects the displayId
    // embedded in the event, routing the key to the focused window on that display.
    private val imgClass by lazy {
        runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
    }
    private val imgInstance by lazy {
        imgClass?.getMethod("getInstance")?.invoke(null)
    }
    private val imgInjectEvent by lazy {
        imgClass?.getMethod("injectInputEvent",
            android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayIdMethod by lazy {
        runCatching {
            android.view.InputEvent::class.java
                .getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { it.isAccessible = true }
        }.getOrNull()
    }
    private val imgGetDeviceIds by lazy {
        runCatching { imgClass?.getMethod("getInputDeviceIds") }.getOrNull()
    }
    private val imgGetDevice by lazy {
        runCatching {
            imgClass?.getMethod("getInputDevice", Int::class.javaPrimitiveType)
        }.getOrNull()
    }
    // Current AOSP association API (replaced setInputDeviceDisplayAssociation at some point
    // before Android 17): keyed by the display's uniqueId string rather than its integer id.
    private val imgAddUniqueIdAssoc by lazy {
        runCatching {
            imgClass?.getMethod("addUniqueIdAssociationByDescriptor",
                String::class.java, String::class.java)
        }.getOrNull()
    }
    // Legacy association API, kept as a fallback for pre-Android-15 builds where the
    // uniqueId-based method above doesn't exist yet.
    private val imgSetDisplayAssoc by lazy {
        runCatching {
            imgClass?.getMethod("setInputDeviceDisplayAssociation",
                String::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    // Reflection handles for resolving a display's uniqueId string (e.g. "local:462...") from
    // its integer id, via the same hidden-global pattern as InputManagerGlobal above. Needed
    // because addUniqueIdAssociationByDescriptor takes a uniqueId, not an integer displayId.
    private val dmgClass by lazy {
        runCatching { Class.forName("android.hardware.display.DisplayManagerGlobal") }.getOrNull()
    }
    private val dmgInstance by lazy {
        runCatching { dmgClass?.getMethod("getInstance")?.invoke(null) }.getOrNull()
    }
    private val dmgGetDisplayInfo by lazy {
        runCatching { dmgClass?.getMethod("getDisplayInfo", Int::class.javaPrimitiveType) }.getOrNull()
    }

    // Reflection handles for IWindowManager.setDisplayImePolicy — controls which display
    // shows the IME for fields focused on a given display. Shell uid holds the required
    // INTERNAL_SYSTEM_WINDOW permission, so the binder call passes the WMS check.
    private val iwmInstance by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as android.os.IBinder
            Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull()
    }
    private val iwmSetImePolicy by lazy {
        runCatching {
            iwmInstance?.javaClass?.getMethod("setDisplayImePolicy",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    init {
        initUinput()
    }

    // Opens /dev/uinput via JNI, declares mouse capabilities (REL_X/Y, wheel, buttons),
    // writes the device name, and issues UI_DEV_CREATE. Waits 400 ms for InputReader to
    // enumerate the new device before marking uinputReady = true.
    private fun initUinput() {
        try {
            val fd = UinputNative.nOpen()
            if (fd < 0) { Log.e(TAG, "nOpen failed"); return }

            fun ioctl(req: Int, value: Int) {
                val r = UinputNative.nIoctl(req, value)
                if (r < 0) Log.w(TAG, "ioctl(0x${req.toString(16)}, $value) returned $r")
            }

            ioctl(UI_SET_EVBIT,  EV_SYN)
            ioctl(UI_SET_EVBIT,  EV_KEY)
            ioctl(UI_SET_EVBIT,  EV_REL)
            ioctl(UI_SET_RELBIT, REL_X)
            ioctl(UI_SET_RELBIT, REL_Y)
            ioctl(UI_SET_RELBIT, REL_WHEEL)
            ioctl(UI_SET_RELBIT, REL_HWHEEL)
            ioctl(UI_SET_KEYBIT, BTN_LEFT)
            ioctl(UI_SET_KEYBIT, BTN_RIGHT)
            ioctl(UI_SET_KEYBIT, BTN_MIDDLE)
            ioctl(UI_SET_KEYBIT, KEY_BACK)
            ioctl(UI_SET_KEYBIT, KEY_HOME)
            ioctl(UI_SET_KEYBIT, KEY_APPSWITCH)

            val n = UinputNative.nWriteDevInfo("AR Touchpad Mouse")
            if (n < 0) { Log.e(TAG, "nWriteDevInfo failed"); return }

            ioctl(UI_DEV_CREATE, 0)

            Thread.sleep(400) // give InputReader time to register the device
            uinputReady = true
            uinputDescriptor = findUinputDescriptor()
            Log.i(TAG, "uinput device ready, descriptor=$uinputDescriptor")
        } catch (e: Exception) {
            Log.e(TAG, "initUinput failed: $e")
        }
    }

    // Scans all registered input devices via InputManagerGlobal and returns the descriptor
    // of the one named "AR Touchpad Mouse". Returns null if not found or reflection fails.
    private fun findUinputDescriptor(): String? = runCatching {
        val instance = imgInstance ?: return@runCatching null
        val ids = imgGetDeviceIds?.invoke(instance) as? IntArray ?: return@runCatching null
        val getDevice = imgGetDevice ?: return@runCatching null
        ids.toList().mapNotNull { id ->
            (getDevice.invoke(instance, id) as? android.view.InputDevice)
                ?.takeIf { it.name == "AR Touchpad Mouse" }
                ?.descriptor
        }.firstOrNull()
    }.getOrNull()

    // Resolves the uniqueId string DisplayManagerGlobal uses to identify a display
    // (e.g. "local:4619827259835644672"), required by addUniqueIdAssociationByDescriptor.
    private fun getDisplayUniqueId(targetDisplayId: Int): String? = runCatching {
        val instance = dmgInstance ?: return@runCatching null
        val getInfo = dmgGetDisplayInfo ?: return@runCatching null
        val info = getInfo.invoke(instance, targetDisplayId) ?: return@runCatching null
        info.javaClass.getField("uniqueId").get(info) as? String
    }.getOrNull()

    // Tells Android's InputReader to route this uinput device's cursor to targetDisplayId.
    // Tries, in order: the current AOSP API (addUniqueIdAssociationByDescriptor, keyed by the
    // display's uniqueId string), the same method via the raw IInputManager binder (mIm field)
    // if not exposed directly on InputManagerGlobal, and finally the legacy integer-displayId
    // method (setInputDeviceDisplayAssociation) for any pre-Android-15 device still running
    // this app. The uniqueId-based API replaced the legacy one at some point before Android 17
    // — on builds where only the legacy name resolved, association silently no-opped and the
    // uinput device was never actually pinned to the external display, so REL_X/REL_Y motion
    // moved a cursor on the wrong (invisible) display while BTN_LEFT clicks, which are
    // dispatched to the focused window rather than a device-associated display, still worked.
    private fun associateDeviceToDisplay(descriptor: String, targetDisplayId: Int) {
        runCatching {
            val instance = imgInstance ?: return
            val uniqueId = getDisplayUniqueId(targetDisplayId)

            if (uniqueId != null) {
                val direct = imgAddUniqueIdAssoc
                if (direct != null) {
                    direct.invoke(instance, descriptor, uniqueId)
                    Log.i(TAG, "associated uinput device to display $targetDisplayId ($uniqueId)")
                    return
                }
                val mImField = imgClass?.getDeclaredField("mIm")?.also { it.isAccessible = true }
                val iim = mImField?.get(instance)
                val viaBinder = iim?.javaClass?.let {
                    runCatching {
                        it.getMethod("addUniqueIdAssociationByDescriptor",
                            String::class.java, String::class.java)
                    }.getOrNull()
                }
                if (iim != null && viaBinder != null) {
                    viaBinder.invoke(iim, descriptor, uniqueId)
                    Log.i(TAG, "associated uinput device (via mIm) to display $targetDisplayId ($uniqueId)")
                    return
                }
            }

            val legacy = imgSetDisplayAssoc
            if (legacy != null) {
                legacy.invoke(instance, descriptor, targetDisplayId)
                Log.i(TAG, "associated uinput device to display $targetDisplayId (legacy API)")
                return
            }

            Log.w(TAG, "no display-association API available (uniqueId=$uniqueId)")
        }.onFailure { Log.w(TAG, "associateDeviceToDisplay failed: $it") }
    }

    // Writes a single struct input_event{type, code, value} to the open uinput fd via JNI.
    private fun ev(type: Int, code: Int, value: Int) = UinputNative.nWriteEvent(type, code, value)
    // Flushes all buffered events to the input dispatcher with an EV_SYN/SYN_REPORT marker.
    private fun sync() = ev(EV_SYN, SYN_REPORT, 0)

    // Stores the target display id and pixel dimensions; resets cursor to center and clears
    // accumulators. Associates the uinput device to the target display so that REL_X/Y events
    // move the cursor on the glasses screen instead of the phone screen. Sends a 1-px nudge
    // to wake the OS cursor on the new display.
    override fun setDisplay(id: Int, width: Int, height: Int) {
        displayId = id
        displayWidth = width
        displayHeight = height
        cursorX = width / 2f
        cursorY = height / 2f
        accumX = 0f
        accumY = 0f
        accumScrollX = 0f
        accumScrollY = 0f

        // Pin the uinput device to this display so cursor movement lands on the glasses screen.
        // Without this, Android may default the virtual mouse to the phone's display on some setups.
        uinputDescriptor?.let { associateDeviceToDisplay(it, id) }

        // Nudge the pointer to wake the cursor.
        if (uinputReady) {
            ev(EV_REL, REL_X, 1); ev(EV_REL, REL_Y, 1); sync()
            Thread.sleep(50)
            ev(EV_REL, REL_X, -1); ev(EV_REL, REL_Y, -1); sync()
        }
        Log.i(TAG, "setDisplay id=$id ${width}x${height} uinputReady=$uinputReady")
    }

    // Accumulates fractional deltas in accumX/Y; only emits REL_X/REL_Y events for the
    // whole-pixel portion, carrying the remainder forward. Also clamps the tracked cursor
    // position to the display bounds so the ViewModel overlay stays in sync.
    override fun moveMouse(dx: Float, dy: Float) {
        if (!uinputReady) return
        accumX += dx
        accumY += dy
        val idx = accumX.toInt()
        val idy = accumY.toInt()
        if (idx == 0 && idy == 0) return
        accumX -= idx
        accumY -= idy
        cursorX = (cursorX + idx).coerceIn(0f, displayWidth - 1f)
        cursorY = (cursorY + idy).coerceIn(0f, displayHeight - 1f)
        ev(EV_REL, REL_X, idx)
        ev(EV_REL, REL_Y, idy)
        sync()
        Log.v(TAG, "moveMouse rel=($idx,$idy) displayId=$displayId")
    }

    // Presses (value=1) then releases (value=0) BTN_LEFT or BTN_RIGHT with a 50 ms hold.
    // x/y are accepted for interface symmetry but cursor position is already tracked by moveMouse.
    override fun click(x: Float, y: Float, button: Int) {
        if (!uinputReady) return
        val btn = if (button == MotionEvent.BUTTON_SECONDARY) BTN_RIGHT else BTN_LEFT
        ev(EV_KEY, btn, 1); sync()
        Thread.sleep(50)
        ev(EV_KEY, btn, 0); sync()
        Log.d(TAG, "click btn=0x${btn.toString(16)} displayId=$displayId")
    }

    // Input: Android keycode (e.g. KeyEvent.KEYCODE_BACK = 4).
    // Creates ACTION_DOWN + ACTION_UP KeyEvents, stamps each with the target displayId via
    // InputEvent.setDisplayId reflection, then calls InputManagerGlobal.injectInputEvent so
    // the key reaches the focused window on the glasses display rather than the phone.
    override fun pressKey(androidKeycode: Int) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, androidKeycode, 0)
            setDisp.invoke(down, displayId)
            inject.invoke(instance, down, 0 /*INJECT_INPUT_EVENT_MODE_ASYNC*/)
            Thread.sleep(20)
            val up = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, androidKeycode, 0)
            setDisp.invoke(up, displayId)
            inject.invoke(instance, up, 0)
            Log.d(TAG, "pressKey keycode=$androidKeycode displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "pressKey $androidKeycode failed: $e")
        }
    }

    // Input: a plain text string. Converts the full char array to a KeyEvent sequence via
    // KeyCharacterMap.VIRTUAL_KEYBOARD; falls back to per-character conversion for strings
    // that can't be mapped in one shot (e.g. mixed scripts). Delegates to injectKeyEvents.
    override fun typeText(text: String) {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray())
        if (events != null) {
            injectKeyEvents(events)
        } else {
            for (ch in text) {
                val evs = kcm.getEvents(charArrayOf(ch)) ?: continue
                injectKeyEvents(evs)
            }
        }
        Log.d(TAG, "typeText \"$text\" displayId=$displayId")
    }

    // For each KeyEvent in the array, constructs a new event carrying the same action/keycode/
    // meta state, stamps it with displayId via reflection, then calls injectInputEvent so it
    // lands on the focused window of the target display.
    private fun injectKeyEvents(events: Array<out KeyEvent>) {
        val instance = imgInstance ?: return
        val inject   = imgInjectEvent ?: return
        val setDisp  = setDisplayIdMethod ?: return
        for (ev in events) {
            val targeted = KeyEvent(ev.downTime, ev.eventTime, ev.action,
                                    ev.keyCode, ev.repeatCount, ev.metaState)
            setDisp.invoke(targeted, displayId)
            inject.invoke(instance, targeted, 0)
        }
    }

    // Input: (dx, dy) finger-pixel deltas (positive = fingers moving right/down).
    // Converts each axis to wheel detents at 20 px/detent using accumScrollX/Y; emits
    // REL_WHEEL/REL_HWHEEL only for whole detents, carrying sub-detent remainders forward.
    override fun scroll(dx: Float, dy: Float) {
        if (!uinputReady) return
        // 20px of finger movement = 1 wheel detent (adjustable via scrollSpeed in ViewModel).
        accumScrollX += dx / 20f
        accumScrollY += dy / 20f
        val stepsX = accumScrollX.toInt()
        val stepsY = accumScrollY.toInt()
        if (stepsX == 0 && stepsY == 0) return
        accumScrollX -= stepsX
        accumScrollY -= stepsY
        if (stepsY != 0) ev(EV_REL, REL_WHEEL, -stepsY)
        if (stepsX != 0) ev(EV_REL, REL_HWHEEL, -stepsX)
        sync()
    }

    // Input: desired font scale (clamped internally to 0.85–1.5).
    // Runs `settings put system font_scale <value>` as a subprocess; shell uid has permission
    // to write system settings, so no additional privileges are needed.
    override fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.85f, 1.5f)
        try {
            Runtime.getRuntime().exec(
                arrayOf("settings", "put", "system", "font_scale", "%.2f".format(clamped))
            ).waitFor()
            Log.d(TAG, "setFontScale $clamped")
        } catch (e: Exception) {
            Log.e(TAG, "setFontScale failed: $e")
        }
    }

    // Input: displayId and IME policy (0 = local, 1 = fallback, 2 = hide).
    // Calls IWindowManager.setDisplayImePolicy via reflection (there is no `wm` shell
    // subcommand for this). With policy 1 (fallback), a field focused on the target display
    // shows its IME on the default display (the phone) while the InputConnection stays with
    // the field — DeX-style typing. Returns false when unsupported on this build.
    override fun setImePolicy(displayId: Int, policy: Int): Boolean {
        val iwm = iwmInstance ?: run { Log.e(TAG, "IWindowManager unavailable"); return false }
        val method = iwmSetImePolicy ?: run { Log.e(TAG, "setDisplayImePolicy unavailable"); return false }
        return runCatching {
            method.invoke(iwm, displayId, policy)
            Log.i(TAG, "setDisplayImePolicy display=$displayId policy=$policy")
            true
        }.getOrElse {
            Log.w(TAG, "setDisplayImePolicy failed: $it")
            false
        }
    }

    // Input: Android keycode (e.g. KEYCODE_C). Injects Ctrl+keycode via InputManagerGlobal
    // with META_CTRL_ON|META_CTRL_LEFT_ON so apps see a real Ctrl+key shortcut.
    // Used for Copy/Cut/Paste/SelectAll after a text selection without moving the cursor.
    override fun pressKeyWithCtrl(keycode: Int) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

            val ctrlDown = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
            setDisp.invoke(ctrlDown, displayId); inject.invoke(instance, ctrlDown, 0)

            val kDown = KeyEvent(t, t, KeyEvent.ACTION_DOWN, keycode, 0, ctrlMeta)
            setDisp.invoke(kDown, displayId); inject.invoke(instance, kDown, 0)
            Thread.sleep(20)

            val kUp = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keycode, 0, ctrlMeta)
            setDisp.invoke(kUp, displayId); inject.invoke(instance, kUp, 0)

            val ctrlUp = KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0)
            setDisp.invoke(ctrlUp, displayId); inject.invoke(instance, ctrlUp, 0)

            Log.d(TAG, "pressKeyWithCtrl keycode=$keycode displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "pressKeyWithCtrl failed: $e")
        }
    }

    // Injects a Ctrl+scroll MotionEvent (ACTION_SCROLL + META_CTRL_ON + AXIS_VSCROLL) at the
    // tracked cursor position. Chrome and WebView-based apps zoom their page content in response;
    // positive amount = zoom in, negative = zoom out. Does not affect the system font scale.
    override fun ctrlScroll(amount: Float) {
        val instance = imgInstance ?: run { Log.e(TAG, "InputManagerGlobal unavailable"); return }
        val inject   = imgInjectEvent ?: run { Log.e(TAG, "injectInputEvent unavailable"); return }
        val setDisp  = setDisplayIdMethod ?: run { Log.e(TAG, "setDisplayId unavailable"); return }
        try {
            val t = SystemClock.uptimeMillis()
            val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

            val props = arrayOf(MotionEvent.PointerProperties().apply {
                id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                x = cursorX; y = cursorY; pressure = 0f; size = 0f
                setAxisValue(MotionEvent.AXIS_VSCROLL, amount)
            })
            val event = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_SCROLL,
                1, props, coords,
                ctrlMeta, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_MOUSE, 0
            )
            setDisp.invoke(event, displayId)
            inject.invoke(instance, event, 0)
            event.recycle()
            Log.d(TAG, "ctrlScroll $amount displayId=$displayId")
        } catch (e: Exception) {
            Log.e(TAG, "ctrlScroll failed: $e")
        }
    }

    // Presses BTN_LEFT without releasing — the paired mouseUp() call ends the drag.
    // Used for text selection: button held while moveMouse moves the cursor.
    override fun mouseDown() {
        if (!uinputReady) return
        ev(EV_KEY, BTN_LEFT, 1); sync()
        Log.d(TAG, "mouseDown displayId=$displayId")
    }

    // Releases BTN_LEFT previously pressed by mouseDown().
    override fun mouseUp() {
        if (!uinputReady) return
        ev(EV_KEY, BTN_LEFT, 0); sync()
        Log.d(TAG, "mouseUp displayId=$displayId")
    }

    // Closes the uinput file descriptor via JNI (sends UI_DEV_DESTROY internally) and marks
    // the device unavailable so subsequent calls are no-ops rather than crashing.
    override fun destroy() {
        UinputNative.nClose()
        uinputReady = false
    }

    companion object {
        private const val TAG = "MouseService"

        const val UI_SET_EVBIT  = 0x40045564
        const val UI_SET_KEYBIT = 0x40045565
        const val UI_SET_RELBIT = 0x40045566
        const val UI_DEV_CREATE  = 0x5501
        const val UI_DEV_DESTROY = 0x5502

        const val EV_SYN = 0; const val EV_KEY = 1; const val EV_REL = 2
        const val REL_X = 0; const val REL_Y = 1; const val REL_HWHEEL = 6; const val REL_WHEEL = 8
        const val BTN_LEFT = 0x110; const val BTN_RIGHT = 0x111; const val BTN_MIDDLE = 0x112
        const val KEY_BACK = 158; const val KEY_HOME = 102; const val KEY_APPSWITCH = 580
        const val SYN_REPORT = 0
    }
}

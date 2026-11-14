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

import android.content.Context
import android.os.PowerManager
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

// Keeps one specific display awake — the glasses.
//
// FLAG_KEEP_SCREEN_ON on the touchpad window is not enough: on Android 17 WindowManager
// turns that flag into a wake lock scoped to the window's own display
// ('WindowManager/displayId:0', powerGroupId=0 in `dumpsys power`), so it holds the phone
// and nothing else. A display in another power group keeps its own user-activity timer, fed
// only by input that lands on it; with a static page on the glasses and no cursor movement
// it runs the normal screen timeout — dim, then off — while the phone stays lit.
//
// PowerManager.newWakeLock(flags, tag, displayId) is the hidden overload that scopes a wake
// lock to a display's power group, the same thing WindowManager does for its own.
class DisplayWakeLock(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lock: PowerManager.WakeLock? = null
    private var heldFor: Int? = null

    // Holds the lock for displayId, replacing any lock held for another display.
    // null releases it.
    fun holdFor(displayId: Int?) {
        if (displayId == heldFor) return
        release()
        if (displayId == null) return
        runCatching {
            @Suppress("DEPRECATION") // SCREEN_BRIGHT: no window on that display to carry the flag
            val wl = HiddenApiBypass.invoke(
                PowerManager::class.java, powerManager, "newWakeLock",
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "$TAG:glasses", displayId,
            ) as PowerManager.WakeLock
            wl.setReferenceCounted(false)
            wl.acquire()
            lock = wl
            heldFor = displayId
            Log.i(TAG, "holding display $displayId awake")
        }.onFailure { Log.w(TAG, "could not hold display $displayId awake: $it") }
    }

    fun release() {
        lock?.let { runCatching { it.release() } }
        if (heldFor != null) Log.i(TAG, "released display $heldFor")
        lock = null
        heldFor = null
    }

    private companion object {
        const val TAG = "DisplayWakeLock"
    }
}

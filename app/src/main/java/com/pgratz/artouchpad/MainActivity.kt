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

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pgratz.artouchpad.ui.TouchpadScreen
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme

// Idle period after which the phone screen is dimmed to save power. The screen is never
// allowed to sleep while this activity is on top (FLAG_KEEP_SCREEN_ON) — the user is
// looking through the glasses, and a sleeping phone means a dead touchpad.
private const val IDLE_DIM_MS = 30_000L

// Window brightness override applied when idle. Low enough to save meaningful power on
// OLED, high enough that the surface is still visible if the user glances at the phone.
private const val DIM_BRIGHTNESS = 0.05f

// Single-activity entry point. Hosts the Compose UI and owns the ViewModel lifecycle.
class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()

    private val idleHandler = Handler(Looper.getMainLooper())
    private var isDimmed = false
    private var pointerDown = false

    // Dimming rewrites the window attributes, which forces a relayout — and a relayout
    // mid-gesture can reset Compose's pointerInput and cancel the drag. A finger resting
    // motionless emits no ACTION_MOVE, so defer while any pointer is still down.
    private val dimRunnable = object : Runnable {
        override fun run() {
            if (pointerDown) {
                idleHandler.postDelayed(this, IDLE_DIM_MS)
                return
            }
            applyBrightness(DIM_BRIGHTNESS)
            isDimmed = true
        }
    }

    // Sets up edge-to-edge display and mounts the full-screen TouchpadScreen composable.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Applies only while this window is in front, so the phone resumes its normal
        // timeout as soon as the touchpad is backgrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            ARTouchpadTheme {
                TouchpadScreen(viewModel = viewModel)
            }
        }
    }

    // Every touch — including ACTION_MOVE — counts as activity, so a long continuous drag
    // or scroll keeps the screen lit. Activity.onUserInteraction() alone is not enough:
    // it only fires on the initial down, so a slow 30 s drag would dim mid-gesture.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> pointerDown = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerDown = false
        }
        noteInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteInteraction()
        return super.dispatchKeyEvent(event)
    }

    // Restores full brightness if dimmed and restarts the idle countdown. Runs on every
    // touch event (~120 Hz during a drag), so it stays cheap: the window is only touched
    // on an actual dim→bright transition, otherwise this is just a handler re-post.
    private fun noteInteraction() {
        if (isDimmed) {
            applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            isDimmed = false
        }
        idleHandler.removeCallbacks(dimRunnable)
        idleHandler.postDelayed(dimRunnable, IDLE_DIM_MS)
    }

    private fun applyBrightness(value: Float) {
        window.attributes = window.attributes.also { it.screenBrightness = value }
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        noteInteraction()
    }

    // Drops the brightness override and the pending dim so a backgrounded touchpad never
    // holds the screen dark for whatever the user switched to.
    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(dimRunnable)
        applyBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        isDimmed = false
        pointerDown = false
    }
}

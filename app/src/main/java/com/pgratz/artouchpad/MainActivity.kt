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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pgratz.artouchpad.ui.TouchpadScreen
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// The touchpad UI is covered by a black scrim after TouchpadState.dimDelaySec of no input
// (user-configurable in the settings panel; 0 disables it). The screen is never allowed to
// sleep while this activity is on top (FLAG_KEEP_SCREEN_ON) — the user is looking through
// the glasses, and a sleeping phone means a dead touchpad.
//
// The scrim is deliberately in-app rather than a WindowManager.LayoutParams.screenBrightness
// override: that override is handed to PowerManagerService and applied to every display
// power group, so on a phone driving XR glasses it dims (and can blank) the glasses too.

// Opacity of the black scrim drawn over the touchpad when idle. High enough to blank the
// OLED almost completely (which is where the power saving comes from), low enough that the
// UI is still readable if the user glances down at the phone.
private const val DIM_ALPHA = 0.92f

// Single-activity entry point. Hosts the Compose UI and owns the ViewModel lifecycle.
class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()

    private val idleHandler = Handler(Looper.getMainLooper())
    private var isDimmed by mutableStateOf(false)
    private var pointerDown = false

    // A finger resting motionless emits no ACTION_MOVE, so a long press or a paused drag
    // looks identical to idleness. Defer while any pointer is still down rather than
    // dimming out from under an in-flight gesture.
    private val dimRunnable = object : Runnable {
        override fun run() {
            if (pointerDown) {
                scheduleDim()
                return
            }
            isDimmed = true
        }
    }

    // Idle timeout from the live setting, in ms; 0 (the "Never" chip) disables dimming.
    private val dimDelayMs: Long
        get() = viewModel.state.value.dimDelaySec.toLong() * 1000L

    // Sets up edge-to-edge display and mounts the full-screen TouchpadScreen composable.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Applies only while this window is in front, so the phone resumes its normal
        // timeout as soon as the touchpad is backgrounded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Changing the delay in settings restarts the countdown against the new value, so
        // picking "Never" clears a pending dim and picking a shorter delay takes effect now.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.map { it.dimDelaySec }.distinctUntilChanged().collect {
                    noteInteraction()
                }
            }
        }
        setContent {
            ARTouchpadTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    TouchpadScreen(viewModel = viewModel)
                    if (isDimmed) {
                        // Draw-only overlay: no pointerInput modifier, so touches fall
                        // straight through to the touchpad surface underneath and the
                        // gesture that wakes the screen still counts as a gesture.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = DIM_ALPHA)),
                        )
                    }
                }
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

    // Clears the scrim if present and restarts the idle countdown. Runs on every touch
    // event (~120 Hz during a drag), so it stays cheap: state is only written on an actual
    // dim→bright transition, otherwise this is just a handler re-post.
    private fun noteInteraction() {
        isDimmed = false
        scheduleDim()
    }

    private fun scheduleDim() {
        idleHandler.removeCallbacks(dimRunnable)
        val delay = dimDelayMs
        if (delay > 0) idleHandler.postDelayed(dimRunnable, delay)
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        noteInteraction()
    }

    // Drops the scrim and the pending dim so the touchpad never comes back to the
    // foreground already dark.
    override fun onPause() {
        super.onPause()
        idleHandler.removeCallbacks(dimRunnable)
        isDimmed = false
        pointerDown = false
    }
}

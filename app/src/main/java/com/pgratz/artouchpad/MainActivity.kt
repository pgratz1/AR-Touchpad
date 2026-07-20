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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pgratz.artouchpad.ui.TouchpadScreen
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme

// Single-activity entry point. Hosts the Compose UI and owns the ViewModel lifecycle.
class MainActivity : ComponentActivity() {

    private val viewModel: TouchpadViewModel by viewModels()

    // Sets up edge-to-edge display and mounts the full-screen TouchpadScreen composable.
    // The Activity window is marked FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL by default
    // so that touches on the touchpad surface do NOT cause Android desktop mode to switch
    // the "focused display" from the glasses display back to the phone (which would dim
    // the glasses-side window's status bar as if it had lost focus). The flags are cleared
    // dynamically whenever the in-app KeyboardProxy needs the EditText to receive focus.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        enableEdgeToEdge()
        setContent {
            ARTouchpadTheme {
                val state by viewModel.state.collectAsState()
                LaunchedEffect(state.showKeyboard) {
                    val notFocusable =
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    if (state.showKeyboard) window.clearFlags(notFocusable)
                    else window.addFlags(notFocusable)
                }
                TouchpadScreen(viewModel = viewModel)
            }
        }
    }

    // Re-checks display and Shizuku state when returning from Settings or the permission
    // dialog, so the UI reflects any changes the user made while away.
    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

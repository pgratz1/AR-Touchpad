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

package com.pgratz.artouchpad.ui

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent as AKeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pgratz.artouchpad.DisplayInfo
import com.pgratz.artouchpad.TouchMode
import com.pgratz.artouchpad.TouchpadViewModel
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF1A2332)
private val SURFACE_DISABLED = Color(0xFF111820)
private val ACCENT = Color(0xFF4FC3F7)
private val ACCENT_DIM = Color(0xFF1A4A6A)
private val TEXT = Color(0xFFE0E0E0)
private val TEXT_DIM = Color(0xFF90A4AE)
private val TEXT_MUTED = Color(0xFF546E7A)
private val NAV_ICON = Color(0xFFB0BEC5)

private const val MOVE_THRESHOLD = 5f
private const val TAP_MAX_MS = 220L
private const val LONG_PRESS_MS = 600L
private const val DOUBLE_TAP_WINDOW_MS = 300L

// Root composable. Collects ViewModel state and renders either SettingsPanel (when
// showSettings is true) or the main layout: StatusBar → TouchpadSurface → optional
// KeyboardProxy → NavigationBar.
@Composable
fun TouchpadScreen(viewModel: TouchpadViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
            .systemBarsPadding(),
    ) {
        StatusBar(
            shizukuAvailable = state.shizukuAvailable,
            shizukuPermission = state.shizukuPermission,
            mouseReady = state.mouseReady,
            serviceEnabled = state.isServiceEnabled,
            targetDisplay = state.targetDisplay,
            touchMode = state.touchMode,
            onSettingsClick = viewModel::toggleSettings,
            onGrantShizuku = viewModel::requestShizukuPermission,
            onConnectMouse = { viewModel.mouse.bind() },
            onEnableService = {
                context.startActivity(
                    android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )

        if (state.showSettings) {
            SettingsPanel(
                sensitivity = state.sensitivity,
                scrollSpeed = state.scrollSpeed,
                naturalScroll = state.naturalScroll,
                dexKeyboard = state.dexKeyboardEnabled,
                dexKeyboardActive = state.dexKeyboardActive,
                allDisplays = state.allDisplays,
                targetDisplay = state.targetDisplay,
                onSensitivity = viewModel::setSensitivity,
                onScrollSpeed = viewModel::setScrollSpeed,
                onNaturalScroll = viewModel::setNaturalScroll,
                onDexKeyboard = viewModel::setDexKeyboard,
                onDismiss = viewModel::toggleSettings,
            )
        } else {
            TouchpadSurface(
                modifier = Modifier.weight(1f),
                enabled = state.mouseReady,
                onMoveCursor = viewModel::moveCursor,
                onClick = { viewModel.performClick() },
                onDoubleClick = { viewModel.performDoubleClick() },
                onRightClick = { viewModel.performRightClick() },
                onScroll = viewModel::performScroll,
                onPinch = viewModel::pinchZoom,
                onTouchModeChanged = viewModel::setTouchMode,
                onSelectStart = viewModel::startSelectDrag,
                onSelectEnd = viewModel::endSelectDrag,
            )
            if (state.showKeyboard) {
                KeyboardProxy(
                    onSend    = { text -> viewModel.sendKeyboardText(text) },
                    onDismiss = viewModel::toggleKeyboard,
                )
            }
            NavigationBar(
                keyboardActive = state.showKeyboard,
                onBack         = { viewModel.pressKey(AKeyEvent.KEYCODE_BACK) },
                onHome         = { viewModel.pressKey(AKeyEvent.KEYCODE_HOME) },
                onRecents      = { viewModel.pressKey(AKeyEvent.KEYCODE_APP_SWITCH) },
                onToggleKeyboard = viewModel::toggleKeyboard,
            )
        }
    }
}

// Top status bar showing the app title, three status dots (Mouse/Display/Nav),
// the current touch mode indicator, and contextual action buttons for each
// unmet setup step (grant Shizuku → connect mouse → enable accessibility service).
@Composable
private fun StatusBar(
    shizukuAvailable: Boolean,
    shizukuPermission: Boolean,
    mouseReady: Boolean,
    serviceEnabled: Boolean,
    targetDisplay: DisplayInfo?,
    touchMode: TouchMode,
    onSettingsClick: () -> Unit,
    onGrantShizuku: () -> Unit,
    onConnectMouse: () -> Unit,
    onEnableService: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AR Touchpad", color = TEXT, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(mouseReady, "Mouse")
                    StatusDot(targetDisplay != null, "Display")
                    StatusDot(serviceEnabled, "Nav")
                    val modeLabel = when (touchMode) {
                        TouchMode.SCROLL -> "↕ scroll"
                        TouchMode.SELECT -> "⊹ select"
                        TouchMode.CURSOR -> "⊹ cursor"
                        TouchMode.IDLE   -> null
                    }
                    modeLabel?.let { Text(it, color = ACCENT, fontSize = 11.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    !shizukuAvailable ->
                        Text("Shizuku off", color = Color(0xFFFF7043), fontSize = 11.sp)
                    !shizukuPermission ->
                        TextButton(onClick = onGrantShizuku, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Grant Shizuku", color = Color(0xFFFF7043), fontSize = 12.sp)
                        }
                    !mouseReady ->
                        TextButton(onClick = onConnectMouse, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Connect", color = Color(0xFFFFB300), fontSize = 12.sp)
                        }
                    !serviceEnabled ->
                        TextButton(onClick = onEnableService, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("Enable Nav", color = Color(0xFFFFB300), fontSize = 12.sp)
                        }
                }
                IconButton(onClick = onSettingsClick) {
                    Text("⚙", color = TEXT_DIM, fontSize = 22.sp)
                }
            }
        }

        HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    }
}

// Small colored circle (green = active, gray = inactive) followed by a text label.
// Used in StatusBar to show Mouse/Display/Nav readiness at a glance.
@Composable
private fun StatusDot(active: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF4CAF50) else Color(0xFF424242))
        )
        Text(label, color = TEXT_MUTED, fontSize = 11.sp)
    }
}

// The main touch surface. Intercepts raw pointer events with a single pointerInput handler:
//   1 finger: tap (click), double-tap, long-press (right-click), long-press+drag (select text),
//             or drag (cursor move).
//   2 fingers: pinch (spread > translate → font zoom) or drag (translate > spread → scroll).
// Renders a dot grid and live touch point indicators on a Canvas.
@Composable
private fun TouchpadSurface(
    modifier: Modifier,
    enabled: Boolean,
    onMoveCursor: (Float, Float) -> Unit,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onRightClick: () -> Unit,
    onScroll: (Float, Float) -> Unit,
    onPinch: (Float) -> Unit,
    onTouchModeChanged: (TouchMode) -> Unit,
    onSelectStart: () -> Unit,
    onSelectEnd: () -> Unit,
) {
    var touchPoints by remember { mutableStateOf(listOf<Offset>()) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier.padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(20.dp))
                .background(if (enabled) SURFACE else SURFACE_DISABLED)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput

                    var lastPositions = mapOf<PointerId, Offset>()
                    var downPosition = Offset.Zero
                    var downTime = 0L
                    var didMove = false
                    var lastTapTime = 0L
                    var isLongPress = false
                    var isSelectMode = false
                    var longPressJob: Job? = null

                    coroutineScope {
                        val scope = this  // CoroutineScope for launching the long-press timer

                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val now = System.currentTimeMillis()
                                val pressed = event.changes.filter { it.pressed }
                                val justPressed = event.changes.filter { it.pressed && !it.previousPressed }
                                val justReleased = event.changes.filter { !it.pressed && it.previousPressed }

                                touchPoints = pressed.map { it.position }

                                if (justPressed.isNotEmpty() && pressed.size == 1) {
                                    longPressJob?.cancel()
                                    isLongPress = false
                                    isSelectMode = false
                                    downTime = now
                                    didMove = false
                                    downPosition = pressed.first().position
                                    lastPositions = pressed.associate { it.id to it.position }
                                    // Timer fires after LONG_PRESS_MS if finger hasn't moved;
                                    // haptic confirms entry; subsequent drag transitions into select mode.
                                    longPressJob = scope.launch {
                                        delay(LONG_PRESS_MS)
                                        isLongPress = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }

                                when (pressed.size) {
                                    1 -> {
                                        val p = pressed.first()
                                        val last = lastPositions[p.id]
                                        if (last != null) {
                                            val dx = p.position.x - last.x
                                            val dy = p.position.y - last.y
                                            // Latch the drag on cumulative displacement from the touch-down
                                            // point, never on the per-event delta: at 120 Hz a deliberate slow
                                            // drag delivers barely 1 px per event, so a per-event test never
                                            // crosses the threshold and the cursor stays frozen no matter how
                                            // far the finger actually travels.
                                            val totalDx = p.position.x - downPosition.x
                                            val totalDy = p.position.y - downPosition.y
                                            val moved = abs(totalDx) > MOVE_THRESHOLD || abs(totalDy) > MOVE_THRESHOLD

                                            // After long press fired: first movement enters select mode
                                            // (BTN_LEFT pressed; subsequent moves extend text selection).
                                            if (isLongPress && !isSelectMode && moved) {
                                                isSelectMode = true
                                                didMove = true
                                                onSelectStart()
                                                onTouchModeChanged(TouchMode.SELECT)
                                            }
                                            // Before long press fires: movement cancels the timer → normal drag.
                                            // Threshold only gates the tap→drag transition; once dragging,
                                            // every delta is forwarded so slow movements aren't swallowed.
                                            if (!didMove && moved && !isLongPress) {
                                                didMove = true
                                                longPressJob?.cancel()
                                            }

                                            if (isSelectMode || didMove) {
                                                onMoveCursor(dx, dy)
                                                if (!isSelectMode) onTouchModeChanged(TouchMode.CURSOR)
                                            }
                                        }
                                        lastPositions = pressed.associate { it.id to it.position }
                                        p.consume()
                                    }
                                    2 -> {
                                        // Second finger cancels any in-progress select drag.
                                        if (isSelectMode) { onSelectEnd(); isSelectMode = false }
                                        longPressJob?.cancel()

                                        val newPositions = pressed.associate { it.id to it.position }
                                        if (lastPositions.size == 2) {
                                            val ids = pressed.map { it.id }
                                            val p0prev = lastPositions[ids[0]]
                                            val p1prev = lastPositions[ids[1]]
                                            val p0curr = newPositions[ids[0]]
                                            val p1curr = newPositions[ids[1]]
                                            if (p0prev != null && p1prev != null && p0curr != null && p1curr != null) {
                                                val dx = ((p0curr.x - p0prev.x) + (p1curr.x - p1prev.x)) / 2f
                                                val dy = ((p0curr.y - p0prev.y) + (p1curr.y - p1prev.y)) / 2f
                                                val pdx = p1prev.x - p0prev.x; val pdy = p1prev.y - p0prev.y
                                                val cdx = p1curr.x - p0curr.x; val cdy = p1curr.y - p0curr.y
                                                val prevSpan = sqrt(pdx * pdx + pdy * pdy)
                                                val currSpan = sqrt(cdx * cdx + cdy * cdy)
                                                val dSpan = currSpan - prevSpan
                                                // When fingers spread/contract more than they translate, it's a pinch.
                                                // Otherwise treat as scroll.
                                                if (abs(dSpan) > abs(dx) + abs(dy)) {
                                                    if (dSpan != 0f) { onPinch(dSpan); didMove = true }
                                                } else if (dx != 0f || dy != 0f) {
                                                    onScroll(dx, dy)
                                                    onTouchModeChanged(TouchMode.SCROLL)
                                                    didMove = true
                                                }
                                            }
                                        }
                                        lastPositions = newPositions
                                        pressed.forEach { it.consume() }
                                    }
                                }

                                if (justReleased.isNotEmpty() && pressed.isEmpty()) {
                                    val duration = now - downTime
                                    longPressJob?.cancel()
                                    onTouchModeChanged(TouchMode.IDLE)

                                    when {
                                        isSelectMode -> onSelectEnd()
                                        !didMove && duration >= LONG_PRESS_MS -> onRightClick()
                                        !didMove && duration < TAP_MAX_MS -> {
                                            if (now - lastTapTime < DOUBLE_TAP_WINDOW_MS) {
                                                onDoubleClick()
                                                lastTapTime = 0L
                                            } else {
                                                onClick()
                                                lastTapTime = now
                                            }
                                        }
                                    }
                                    isLongPress = false
                                    isSelectMode = false
                                    lastPositions = emptyMap()
                                    touchPoints = emptyList()
                                }
                            }
                        }
                    }
                },
        ) {
            // Dot grid
            val spacing = 32.dp.toPx()
            val cols = (size.width / spacing).toInt() + 1
            val rows = (size.height / spacing).toInt() + 1
            val xOffset = (size.width - (cols - 1) * spacing) / 2f
            val yOffset = (size.height - (rows - 1) * spacing) / 2f
            for (col in 0 until cols) {
                for (row in 0 until rows) {
                    drawCircle(
                        color = Color(0xFF263545),
                        radius = 1.8.dp.toPx(),
                        center = Offset(xOffset + col * spacing, yOffset + row * spacing),
                    )
                }
            }

            if (!enabled) return@Canvas

            // Live touch points
            touchPoints.forEach { pt ->
                drawCircle(color = ACCENT.copy(alpha = 0.2f), radius = 28.dp.toPx(), center = pt)
                drawCircle(color = ACCENT, radius = 6.dp.toPx(), center = pt)
                drawCircle(
                    color = ACCENT.copy(alpha = 0.5f),
                    radius = 18.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        // Disabled overlay text (outside Canvas, inside Box)
        if (!enabled) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Touchpad Disabled", color = TEXT_MUTED, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Grant Shizuku permission to activate", color = Color(0xFF37474F), fontSize = 14.sp)
            }
        }
    }
}

// A thin input strip containing an Android EditText that hosts the system keyboard (Gboard).
// Text accumulates in the EditText; tapping ↵ or the send button calls onSend with the full
// string so it can be injected to the glasses after the phone IME is dismissed.
// Accumulates on the phone so Gboard swipe/autocorrect work normally without interfering
// with the glasses display's IME session.
@Composable
private fun KeyboardProxy(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val editRef = remember { mutableStateOf<EditText?>(null) }

    val doSend: () -> Unit = {
        val text = editRef.value?.text?.toString() ?: ""
        editRef.value?.setText("")
        onSend(text)
    }

    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SURFACE)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    hint = "Type here, then tap ↵"
                    setHintTextColor(android.graphics.Color.parseColor("#546E7A"))
                    setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    maxLines = 2
                    imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
                    }
                }
            },
            update = { view ->
                editRef.value = view
                view.requestFocus()
                val imm = view.context.getSystemService(InputMethodManager::class.java)
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = doSend, modifier = Modifier.size(40.dp)) {
            Text("↵", color = ACCENT, fontSize = 20.sp)
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Text("✕", color = TEXT_DIM, fontSize = 18.sp)
        }
    }
}

// Bottom navigation row with four buttons: Back, Home, Apps (Recents), and keyboard toggle.
// The keyboard button is tinted accent when the proxy is active.
@Composable
private fun NavigationBar(
    keyboardActive: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
    HorizontalDivider(color = Color(0xFF1E2A38), thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BG)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavButton("◀", "Back", onBack)
        NavButton("⬤", "Home", onHome)
        NavButton("▦", "Apps", onRecents)
        NavButton("⌨", "Keys", onToggleKeyboard,
            tint = if (keyboardActive) ACCENT else NAV_ICON)
    }
}

// A centered icon + small label stacked in a column; tint defaults to NAV_ICON gray
// but can be overridden (e.g. ACCENT) to indicate an active state.
@Composable
private fun NavButton(icon: String, label: String, onClick: () -> Unit, tint: Color = NAV_ICON) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
            Text(icon, color = tint, fontSize = 22.sp)
        }
        Text(label, color = TEXT_MUTED, fontSize = 10.sp)
    }
}

// Full-screen settings overlay (shown instead of the touchpad when gear is tapped).
// Contains cursor/scroll speed sliders, natural scroll toggle, connected display list,
// and a gesture reference guide. Dismissed via the "Done" button.
@Composable
private fun SettingsPanel(
    sensitivity: Float,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    dexKeyboard: Boolean,
    dexKeyboardActive: Boolean,
    allDisplays: List<DisplayInfo>,
    targetDisplay: DisplayInfo?,
    onSensitivity: (Float) -> Unit,
    onScrollSpeed: (Float) -> Unit,
    onNaturalScroll: (Boolean) -> Unit,
    onDexKeyboard: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onDismiss) {
                Text("Done", color = ACCENT, fontSize = 14.sp)
            }
        }

        SettingSlider("Cursor Speed", sensitivity, 0.4f..2.0f, "%.1f×", onSensitivity)
        SettingSlider("Scroll Speed", scrollSpeed, 0.3f..1.3f, "%.1f×", onScrollSpeed)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Natural Scroll", color = TEXT_DIM, fontSize = 14.sp)
                Text("Content follows finger direction", color = TEXT_MUTED, fontSize = 11.sp)
            }
            Switch(
                checked = naturalScroll,
                onCheckedChange = onNaturalScroll,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ACCENT,
                    checkedTrackColor = ACCENT_DIM,
                    uncheckedThumbColor = TEXT_DIM,
                    uncheckedTrackColor = Color(0xFF263545),
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Keyboard on Phone", color = TEXT_DIM, fontSize = 14.sp)
                Text(
                    when {
                        dexKeyboard && dexKeyboardActive ->
                            "Glasses text fields open the phone keyboard (DeX style)"
                        dexKeyboard && targetDisplay != null ->
                            "Not supported on this device — using proxy keyboard"
                        else ->
                            "Off: keyboard opens on the glasses; use ⌨ for the proxy"
                    },
                    color = TEXT_MUTED, fontSize = 11.sp,
                )
            }
            Switch(
                checked = dexKeyboard,
                onCheckedChange = onDexKeyboard,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ACCENT,
                    checkedTrackColor = ACCENT_DIM,
                    uncheckedThumbColor = TEXT_DIM,
                    uncheckedTrackColor = Color(0xFF263545),
                ),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Displays", color = TEXT_MUTED, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (allDisplays.isEmpty()) {
                Text("No displays detected", color = Color(0xFFFF7043), fontSize = 12.sp)
            } else {
                allDisplays.forEach { d ->
                    val isTarget = d.id == targetDisplay?.id
                    Text(
                        if (isTarget) "▶ ${d.name}  ${d.width}×${d.height}" else "  ${d.name}  ${d.width}×${d.height}",
                        color = if (isTarget) ACCENT else TEXT_MUTED,
                        fontSize = 12.sp,
                    )
                }
            }
            if (allDisplays.size == 1) {
                Text(
                    "Only 1 display — glasses may be in mirror mode. Switch to Desktop/Extended.",
                    color = Color(0xFFFFB300), fontSize = 11.sp,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gesture Guide", color = TEXT_MUTED, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            GestureHint("1 finger drag", "Move cursor")
            GestureHint("1 finger tap", "Left click")
            GestureHint("1 finger double-tap", "Double click")
            GestureHint("1 finger long-press", "Right click")
            GestureHint("1 finger long-press + drag", "Select text")
            GestureHint("2 finger drag", "Scroll")
            GestureHint("2 finger pinch", "Zoom page")
        }
    }
}

// A labeled Slider with the formatted current value displayed to its right.
// label: display name; value/range: current value and allowed bounds; format: printf string
// for the value (e.g. "%.1f×"); onChange: callback with the new float value.
@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TEXT_DIM, fontSize = 14.sp)
            Text(format.format(value), color = ACCENT, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ACCENT,
                activeTrackColor = ACCENT_DIM,
                inactiveTrackColor = Color(0xFF263545),
            ),
        )
    }
}

// A single row in the gesture guide: gesture description on the left, resulting action on the right.
@Composable
private fun GestureHint(gesture: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(gesture, color = TEXT_MUTED, fontSize = 12.sp)
        Text(action, color = TEXT_DIM, fontSize = 12.sp)
    }
}

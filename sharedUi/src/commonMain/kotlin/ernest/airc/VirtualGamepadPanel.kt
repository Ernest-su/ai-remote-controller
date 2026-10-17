package ernest.airc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fixed viewport: session state never adds rows or changes control positions. */
@Composable
fun VirtualGamepadPanel(state: VirtualGamepadState, available: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    val enabled = available && state.running && !state.starting && !state.reconnecting && !state.stopping && !state.switchingType
    key(state.type) {
        when (state.type) {
            GamepadType.NES -> NesGamepadPanel(state, enabled, onChange)
            GamepadType.DUAL_STICK -> DualStickGamepadPanel(state, enabled, onChange)
        }
    }
}

@Composable
private fun DualStickGamepadPanel(state: VirtualGamepadState, enabled: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.small, color = RemoteColors.Surface) {
        BoxWithConstraints(Modifier.padding(RemoteDimensions.GamepadPanelPadding)) {
            val wide = maxWidth >= RemoteDimensions.GamepadDualStickMinWidth ||
                (maxWidth >= RemoteDimensions.GamepadRowMinWidth && maxWidth >= maxHeight)
            val gap = RemoteDimensions.GamepadGap
            val rowHeight = if (wide) maxHeight else (maxHeight - gap) / 2
            val labelHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
                MaterialTheme.typography.bodyLarge.lineHeight.toDp()
            }
            // Reserve enough room for enlarged SELECT/START labels before sizing the sticks.
            val sessionWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                MaterialTheme.typography.bodyMedium.fontSize.toDp() * 6
            } + RemoteDimensions.GamepadButtonLabelPadding
            val faceSize = minOf(RemoteDimensions.GamepadFaceSize, rowHeight,
                if (wide) maxWidth - RemoteDimensions.GamepadMinStickSize * 2 - sessionWidth - gap * 3
                else (maxWidth - gap) * RemoteDimensions.GamepadFaceWidthFraction)
                .coerceAtLeast(RemoteDimensions.GamepadMinFaceSize)
            val stickSize = minOf(RemoteDimensions.GamepadStickSize, rowHeight - labelHeight,
                if (wide) (maxWidth - faceSize - sessionWidth - gap * 3) / 2 else maxWidth - gap - faceSize)
                .coerceAtLeast(RemoteDimensions.GamepadMinStickSize)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    StickPad("左摇杆", "left", state.input.leftX, state.input.leftY, enabled, stickSize) { x, y -> onChange { it.copy(leftX=x,leftY=y) } }
                    if (wide) {
                        Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                            GamepadButtons(listOf("select", "start"), state, enabled, onChange)
                        }
                        StickPad("右摇杆", "right", state.input.rightX, state.input.rightY, enabled, stickSize) { x, y -> onChange { it.copy(rightX=x,rightY=y) } }
                    }
                    FaceButtons(state, enabled, faceSize, onChange)
                }
                if (!wide) Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        GamepadButtons(listOf("select", "start"), state, enabled, onChange)
                    }
                    StickPad("右摇杆", "right", state.input.rightX, state.input.rightY, enabled, stickSize) { x, y -> onChange { it.copy(rightX=x,rightY=y) } }
                }
            }
        }
    }
}

@Composable
private fun GamepadButtons(buttons: List<String>, state: VirtualGamepadState, enabled: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit, buttonSize: Dp? = null) {
    buttons.forEach { button ->
        HoldButton(button.uppercase(), button, enabled, button in state.input.buttons,
            modifier = if (buttonSize != null) Modifier.size(buttonSize)
                else Modifier.heightIn(min = RemoteDimensions.GamepadSessionButtonHeight),
            round = buttonSize != null) { down ->
            onChange { old -> old.copy(buttons = if(down) (old.buttons + button).distinct() else old.buttons - button) }
        }
    }
}

@Composable
private fun HoldButton(label: String, tag: String, enabled: Boolean, pressed: Boolean, modifier: Modifier = Modifier, round: Boolean = false, arrowRotation: Float? = null, onHold: (Boolean) -> Unit) {
    val interactions = rememberRemoteKeyInteractions(enabled)
    var hapticPress by remember { mutableStateOf<PressInteraction.Press?>(null) }
    val hold by rememberUpdatedState<(Boolean) -> Unit>({ down ->
        if (down && hapticPress == null) {
            val press = PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
            hapticPress = press
            interactions.tryEmit(press)
        } else if (!down) {
            hapticPress?.let { interactions.tryEmit(PressInteraction.Release(it)) }
            hapticPress = null
        }
        onHold(down)
    })
    val scope = rememberCoroutineScope()
    var keyboardHeld by remember { mutableStateOf(false) }
    val controlModifier = modifier.heightIn(min=48.dp).widthIn(min=48.dp).testTag("virtual-button-$tag")
        .background(if(pressed) RemoteColors.Action else RemoteColors.Fill, if (round) CircleShape else RoundedCornerShape(8.dp))
        .semantics {
            role = Role.Button
            stateDescription = if(pressed) "按住" else "释放"
            if(!enabled) disabled()
            onClick(label="按压100毫秒") {
                if(enabled) { scope.launch { try { hold(true); delay(100) } finally { hold(false) } }; true } else false
            }
        }
        .onFocusChanged { if(!it.isFocused && keyboardHeld) { keyboardHeld=false; hold(false) } }
        .onKeyEvent { event ->
            if(enabled && event.key in listOf(Key.Spacebar, Key.Enter, Key.DirectionCenter)) {
                keyboardHeld = event.type == KeyEventType.KeyDown
                hold(keyboardHeld); true
            } else false
        }.focusable(enabled)
        .pointerInput(enabled) {
            if(enabled) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed=false)
                try {
                    down.consume(); hold(true)
                    while(true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break
                        if(!change.pressed || change.isConsumed || change.position.x !in 0f..size.width.toFloat() ||
                            change.position.y !in 0f..size.height.toFloat()) break
                        change.consume()
                    }
                } finally { hold(false) }
            }
        }
    Box(controlModifier.padding(12.dp), contentAlignment = Alignment.Center) {
        val color = if (!enabled) RemoteColors.Disabled else if (pressed) RemoteColors.OnAction else RemoteColors.Text
        if (arrowRotation != null) Icon(ManagementIcons.Up, label,
            Modifier.size(24.dp).graphicsLayer { rotationZ = arrowRotation }, tint = color)
        else Text(label, color = color, style = if (round) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StickPad(label: String, tag: String, x: Double, y: Double, enabled: Boolean, padSize: androidx.compose.ui.unit.Dp,
    onMove: (Double, Double) -> Unit) {
    val move by rememberUpdatedState(onMove)
    val divider = RemoteColors.Divider
    val knobColor = if (enabled) RemoteColors.Action else RemoteColors.Disabled
    Column {
        Text(label)
        Canvas(Modifier.size(padSize).testTag("virtual-stick-$tag")
            .background(RemoteColors.Fill, CircleShape)
            .semantics {
                contentDescription = label
                if(!enabled) disabled()
                customActions = listOf(CustomAccessibilityAction("回中") { if(enabled) { move(0.0,0.0); true } else false })
            }
            .pointerInput(enabled) {
                if(enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed=false)
                    fun submit(position: Offset) { move((position.x/size.width*2-1).toDouble().coerceIn(-1.0,1.0),
                        (position.y/size.height*2-1).toDouble().coerceIn(-1.0,1.0)) }
                    try {
                        down.consume(); submit(down.position)
                        while(true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.find { it.id == down.id } ?: break
                            if(!change.pressed || change.isConsumed) break
                            change.consume(); submit(change.position)
                        }
                    } finally { move(0.0,0.0) }
                }
            }) {
            val radius = size.minDimension / 2
            val knob = RemoteDimensions.GamepadStickKnobRadius.toPx()
            drawCircle(divider, radius - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawCircle(knobColor, knob,
                Offset(size.width / 2 + (x * (radius - knob - 8.dp.toPx())).toFloat(),
                    size.height / 2 + (y * (radius - knob - 8.dp.toPx())).toFloat()))
        }
    }
}

@Composable
private fun FaceButtons(state: VirtualGamepadState, enabled: Boolean, padSize: androidx.compose.ui.unit.Dp,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    Box(Modifier.size(padSize).testTag("gamepad-face-buttons")) {
        listOf("y" to Alignment.TopCenter, "x" to Alignment.CenterStart,
            "b" to Alignment.CenterEnd, "a" to Alignment.BottomCenter).forEach { (button, alignment) ->
            HoldButton(button.uppercase(), button, enabled, button in state.input.buttons,
                Modifier.align(alignment).size(minOf(RemoteDimensions.GamepadButtonSize,
                    padSize * RemoteDimensions.GamepadFaceButtonFraction)), round = true) { down ->
                onChange { old -> old.copy(buttons = if (down) (old.buttons + button).distinct() else old.buttons - button) }
            }
        }
    }
}

@Composable
private fun NesGamepadPanel(state: VirtualGamepadState, enabled: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.small, color = RemoteColors.Surface) {
        BoxWithConstraints(Modifier.padding(RemoteDimensions.GamepadPanelPadding)) {
            val wide = maxWidth >= RemoteDimensions.GamepadRowMinWidth
            val horizontalActions = maxWidth >= RemoteDimensions.GamepadDualStickMinWidth
            val gap = RemoteDimensions.GamepadGap
            val actionSize = minOf(RemoteDimensions.GamepadNesButtonSize,
                if (horizontalActions) maxHeight else RemoteDimensions.GamepadCompactNesButtonSize)
            val dpadSize = minOf(RemoteDimensions.GamepadDpadSize,
                maxHeight - if (wide) 0.dp else RemoteDimensions.GamepadSessionButtonHeight + gap,
                if (wide) maxWidth / 2 else maxWidth - actionSize - gap)
                .coerceAtLeast(RemoteDimensions.GamepadDirectionSize * 2)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    DirectionPad(state, enabled, dpadSize, onChange)
                    if (wide) Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        GamepadButtons(listOf("select", "start"), state, enabled, onChange)
                    }
                    if (horizontalActions) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        GamepadButtons(listOf("b", "a"), state, enabled, onChange, actionSize)
                    } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        GamepadButtons(listOf("b", "a"), state, enabled, onChange, actionSize)
                    }
                }
                if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    GamepadButtons(listOf("select", "start"), state, enabled, onChange)
                }
            }
        }
    }
}

@Composable
private fun DirectionPad(state: VirtualGamepadState, enabled: Boolean, padSize: Dp,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    // Keep individual holds so releasing one of two opposite directions restores the other.
    val held = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(enabled) { if (!enabled) held.clear() }
    Box(Modifier.size(padSize).testTag("gamepad-dpad")) {
        listOf(
            Triple("up", Alignment.TopCenter, 0f), Triple("right", Alignment.CenterEnd, 90f),
            Triple("down", Alignment.BottomCenter, 180f), Triple("left", Alignment.CenterStart, 270f),
        ).forEach { (direction, alignment, rotation) ->
            val label = when (direction) { "up" -> "上"; "down" -> "下"; "left" -> "左"; else -> "右" }
            HoldButton(label, "dpad-$direction", enabled, enabled && held[direction] == true,
                Modifier.align(alignment).size(padSize / 3), arrowRotation = rotation) { down ->
                held[direction] = down
                fun axis(negative: String, positive: String) =
                    (if (held[positive] == true) 1 else 0) - (if (held[negative] == true) 1 else 0)
                onChange { it.copy(hatX = axis("left", "right"), hatY = axis("up", "down")) }
            }
        }
    }
}

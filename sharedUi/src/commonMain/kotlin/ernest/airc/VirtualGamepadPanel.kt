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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Fixed viewport: session state never adds rows or changes control positions. */
@Composable
fun VirtualGamepadPanel(state: VirtualGamepadState, available: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    val enabled = available && state.running && !state.starting && !state.reconnecting && !state.stopping
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
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    buttons.forEach { button ->
        HoldButton(button.uppercase(), button, enabled, button in state.input.buttons) { down ->
            onChange { old -> old.copy(buttons = if(down) (old.buttons + button).distinct() else old.buttons - button) }
        }
    }
}

@Composable
private fun HoldButton(label: String, tag: String, enabled: Boolean, pressed: Boolean, modifier: Modifier = Modifier, round: Boolean = false, onHold: (Boolean) -> Unit) {
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
    Box(controlModifier.padding(12.dp), contentAlignment = Alignment.Center) { Text(label, color=if (!enabled) RemoteColors.Disabled else if (pressed) RemoteColors.OnAction else RemoteColors.Text, style = if (round) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium) }
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
                Modifier.align(alignment).size(RemoteDimensions.GamepadButtonSize), round = true) { down ->
                onChange { old -> old.copy(buttons = if (down) (old.buttons + button).distinct() else old.buttons - button) }
            }
        }
    }
}

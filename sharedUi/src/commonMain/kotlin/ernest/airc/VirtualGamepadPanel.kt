package ernest.airc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VirtualGamepadPanel(state: VirtualGamepadState, available: Boolean, onStart: () -> Unit,
    onStop: () -> Unit, onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    val enabled = available && state.running && !state.starting && !state.stopping
    Panel {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= RemoteDimensions.GamepadRowMinWidth
            val dualSticks = maxWidth >= RemoteDimensions.GamepadDualStickMinWidth
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    StickPad("左摇杆", "left", state.input.leftX, state.input.leftY, enabled) { x, y -> onChange { it.copy(leftX=x,leftY=y) } }
                    if (dualSticks) StickPad("右摇杆", "right", state.input.rightX, state.input.rightY, enabled) { x, y -> onChange { it.copy(rightX=x,rightY=y) } }
                    FaceButtons(state, enabled, onChange)
                }
                if (!wide) {
                    StickPad("左摇杆", "left", state.input.leftX, state.input.leftY, enabled) { x, y -> onChange { it.copy(leftX=x,leftY=y) } }
                    FaceButtons(state, enabled, onChange)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    GamepadButtons(listOf("select", "start"), state, enabled, onChange)
                }
                if (!state.running) ActionButton("开始持续手柄", available && !state.starting && !state.stopping, onStart)
                if (state.running || state.stopping) ActionButton("停止持续手柄", !state.stopping, onStop)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GamepadButtons(virtualGamepadButtons.filter { it !in listOf("a", "b", "x", "y", "start", "select") }, state, enabled, onChange)
                }
                if (!dualSticks) StickPad("右摇杆", "right", state.input.rightX, state.input.rightY, enabled) { x, y -> onChange { it.copy(rightX=x,rightY=y) } }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("上" to (0 to -1), "下" to (0 to 1), "左" to (-1 to 0), "右" to (1 to 0)).forEach { (label, axis) ->
                HoldButton(label, "hat-$label", enabled, if(axis.first != 0) state.input.hatX == axis.first else state.input.hatY == axis.second) { down ->
                    onChange { old -> if(axis.first != 0) old.copy(hatX = if(down) axis.first else if(old.hatX == axis.first) 0 else old.hatX)
                        else old.copy(hatY = if(down) axis.second else if(old.hatY == axis.second) 0 else old.hatY) }
                }
            }
        }
        Text("持续手柄需要 root 与 uinput，输入发送到当前焦点应用。", color = RemoteColors.Secondary)
        if(state.starting || state.stopping) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = RemoteColors.Warning) }
        if (!available) Text("请连接支持虚拟手柄工具的服务。", color = RemoteColors.Secondary)
        state.deviceName?.let { Text("设备：$it · 已确认快照 ${state.sequence}", color = RemoteColors.Secondary) }
        ActionButton("全部释放并回中", enabled) { onChange { VirtualGamepadInput() } }
        Text("按住按钮，松开释放；拖动摇杆，松开回中。无障碍点击按压100ms。离页、后台或断线停止，需手动重启。", color=RemoteColors.Secondary)
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
    val hold by rememberUpdatedState(onHold)
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
private fun StickPad(label: String, tag: String, x: Double, y: Double, enabled: Boolean,
    onMove: (Double, Double) -> Unit) {
    val move by rememberUpdatedState(onMove)
    val divider = RemoteColors.Divider
    val knobColor = if (enabled) RemoteColors.Action else RemoteColors.Disabled
    Column {
        Text(label)
        Canvas(Modifier.size(RemoteDimensions.GamepadStickSize).testTag("virtual-stick-$tag")
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
private fun FaceButtons(state: VirtualGamepadState, enabled: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    Box(Modifier.size(RemoteDimensions.GamepadFaceSize).testTag("gamepad-face-buttons")) {
        listOf("y" to Alignment.TopCenter, "x" to Alignment.CenterStart,
            "b" to Alignment.CenterEnd, "a" to Alignment.BottomCenter).forEach { (button, alignment) ->
            HoldButton(button.uppercase(), button, enabled, button in state.input.buttons,
                Modifier.align(alignment).size(RemoteDimensions.GamepadButtonSize), round = true) { down ->
                onChange { old -> old.copy(buttons = if (down) (old.buttons + button).distinct() else old.buttons - button) }
            }
        }
    }
}

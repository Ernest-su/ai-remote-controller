package ernest.airc

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
internal fun MouseScreen(state: RemoteUiState, model: RemoteViewModel) {
    val mouse by model.mouse.state.collectAsState()
    val context = LocalContext.current
    val sensors = remember(context) { context.getSystemService(SensorManager::class.java) }
    val gyro = remember(sensors) { sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    val gravitySensor = remember(sensors) { sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var sensitivity by rememberSaveable { mutableFloatStateOf(RemoteDimensions.MouseDefaultSensitivity) }
    var focused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    val available = state.connected && setOf("mouse.capabilities","mouse.open","mouse.update","mouse.close").all { it in state.tools } && gyro != null
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) model.mouse.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.mouse.stop() }
    }
    DisposableEffect(view, model) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            focused = hasFocus
            if (!hasFocus) model.mouse.stop()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
    MouseAutoSession(model.mouse, available, state.selected, resumed, focused)
    DisposableEffect(mouse.running, available, resumed, focused, sensitivity, view) {
        val mapper = GyroMouseMapper()
        var gravity: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!view.hasWindowFocus()) { model.mouse.stop(); return }
                if (event.sensor.type != Sensor.TYPE_GYROSCOPE) {
                    val previous = gravity
                    gravity = FloatArray(3) { axis ->
                        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && previous != null) previous[axis]*0.9f+event.values[axis]*0.1f else event.values[axis]
                    }
                    return
                }
                mapper.sample(event.timestamp,event.values[0],event.values[1],view.display?.rotation ?: 0,sensitivity,event.values[2],gravity)
                    ?.let { (dx,dy) -> model.mouse.move(dx,dy) }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val enabled = mouse.running && available && resumed && focused
        if (enabled) {
            gravitySensor?.let { sensors.registerListener(listener,it,SensorManager.SENSOR_DELAY_GAME) }
            if (!sensors.registerListener(listener,gyro,SensorManager.SENSOR_DELAY_GAME)) model.mouse.stop()
        }
        onDispose { sensors.unregisterListener(listener) }
    }
    MousePanel(mouse, available, gyro != null, state.connected, sensitivity,
        { sensitivity = it }, model.mouse::button, model.mouse::scroll)
}

@Composable
internal fun MouseAutoSession(controller: MouseController, available: Boolean, target: String?, resumed: Boolean, focused: Boolean) {
    LaunchedEffect(controller, available, target, resumed, focused) {
        if (available && resumed && focused) controller.activate(true) else controller.stop()
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
}

@Composable
internal fun MousePanel(mouse: MouseState, available: Boolean, hasGyro: Boolean, connected: Boolean,
    sensitivity: Float, onSensitivity: (Float) -> Unit, button: (Int,Boolean) -> Unit, scroll: (Int) -> Unit) {
    val enabled = mouse.running && available
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("mouse-page"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
                verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val problem = when {
                        !hasGyro -> "此手机没有陀螺仪，无法使用空中鼠标。"
                        !connected -> "请先连接设备。"
                        !available -> "目标尚不支持鼠标，请在服务管理更新配套服务。"
                        else -> mouse.error
                    }
                    problem?.let { Text(it, color = if (mouse.error != null) RemoteColors.Warning else RemoteColors.Secondary,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("mouse-problem")) }
                    Panel {
                        Text("灵敏度 ${"%.1f".format(sensitivity)} 倍", style = MaterialTheme.typography.bodyLarge)
                        Slider(sensitivity,onSensitivity,enabled = available,
                            valueRange = RemoteDimensions.MouseMinSensitivity..RemoteDimensions.MouseMaxSensitivity,
                            steps = RemoteDimensions.MouseSensitivitySteps, modifier = Modifier.testTag("mouse-sensitivity"))
                    }
                }
                Column {
                    Spacer(Modifier.height(16.dp))
                    Panel {
                        Text("滚轮", style = MaterialTheme.typography.titleMedium)
                        val step = with(LocalDensity.current) { RemoteDimensions.MouseWheelStep.toPx() }
                        Box(Modifier.fillMaxWidth().height(RemoteDimensions.MouseWheelHeight).background(RemoteColors.Fill,MaterialTheme.shapes.small)
                            .testTag("mouse-wheel").semantics { contentDescription = "鼠标滚轮，上下滑动滚动"; if (!enabled) disabled() }
                            .pointerInput(enabled,step) {
                                if (enabled) {
                                    var remainder = 0f
                                    detectVerticalDragGestures(onDragStart = { remainder = 0f }, onDragCancel = { remainder = 0f }, onDragEnd = { remainder = 0f }) { change, amount ->
                                        change.consume(); remainder -= amount / step
                                        val steps = remainder.toInt()
                                        if (steps != 0) { scroll(steps); remainder -= steps }
                                    }
                                }
                            }, contentAlignment = Alignment.Center) {
                            Text("上下滑动", style = MaterialTheme.typography.bodyLarge, color = if (enabled) RemoteColors.Text else RemoteColors.Disabled)
                        }
                    }
                }
            }
        }
        Panel(Modifier.testTag("mouse-buttons")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mask,label) in listOf(1 to "左键",4 to "中键",2 to "右键")) {
                    MouseButton(label,mask,enabled,button,Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MouseButton(label: String, mask: Int, enabled: Boolean, button: (Int,Boolean) -> Unit, modifier: Modifier) {
    val interactions = rememberRemoteKeyInteractions(enabled, "mouse-$mask")
    val currentInteractions by rememberUpdatedState(interactions)
    val send by rememberUpdatedState(button)
    var press by remember { mutableStateOf<PressInteraction.Press?>(null) }
    fun begin(position: Offset) {
        if (press != null) return
        val token = PressInteraction.Press(position)
        press = token
        currentInteractions.tryEmit(token)
        send(mask,true)
    }
    fun finish(cancelled: Boolean) {
        val token = press ?: return
        press = null
        // Stop the local hold immediately, independently of the remote release request.
        currentInteractions.tryEmit(if (cancelled) PressInteraction.Cancel(token) else PressInteraction.Release(token))
        send(mask,false)
    }
    DisposableEffect(enabled,mask) { onDispose { finish(true) } }
    Surface(color = if (press != null && enabled) RemoteColors.ActionContainer else RemoteColors.Fill, shape = MaterialTheme.shapes.small,
        modifier = modifier.heightIn(min = RemoteDimensions.MouseButtonHeight).testTag("mouse-button-$mask")
            .indication(interactions,LocalIndication.current)
            .semantics {
                role = Role.Button
                stateDescription = if (press != null) "按住" else "释放"
                if (!enabled) disabled()
                onClick(label) {
                    if (enabled && press == null) { begin(Offset.Zero); finish(false); true } else false
                }
            }
            .pointerInput(enabled,mask) {
                if (enabled) awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.isConsumed) return@awaitEachGesture
                    var cancelled = true
                    try {
                        down.consume(); begin(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.find { it.id == down.id } ?: break
                            val inside = change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                            if (change.isConsumed || !inside) break
                            if (!change.pressed) { change.consume(); cancelled = false; break }
                        }
                    } finally { finish(cancelled) }
                }
            }) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.bodyLarge, color = if(enabled) RemoteColors.Text else RemoteColors.Disabled) }
    }
}

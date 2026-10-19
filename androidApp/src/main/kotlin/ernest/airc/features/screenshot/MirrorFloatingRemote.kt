package ernest.airc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

internal fun mirrorFloatingRemoteWidth(width: Dp, height: Dp): Dp =
    if(width <= height) width * RemoteDimensions.MirrorRemotePortraitFraction
    else height * RemoteDimensions.MirrorRemoteLandscapeFraction

/** The same overlay survives inline/fullscreen changes; only its viewport bounds change. */
@Composable
internal fun BoxScope.MirrorFloatingRemote(state: RemoteUiState, model: RemoteViewModel, onClose: () -> Unit, onResults: () -> Unit, fullscreen: Boolean = false) {
    val configuration=LocalConfiguration.current
    val screenWidth=configuration.screenWidthDp.dp
    val screenHeight=configuration.screenHeightDp.dp
    val layouts = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var compact by rememberSaveable { mutableStateOf(true) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var interaction by remember { mutableLongStateOf(0L) }
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(interaction, touching, dragging, editing, lifecycleState) {
        idle = false
        if (!touching && !dragging && !editing && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            delay(RemoteDimensions.FloatingRemoteIdleDelayMillis)
            idle = true
        }
    }
    val baseOpacity = 1f - state.floatingRemoteTransparency.coerceIn(RemoteDimensions.FloatingRemoteMinTransparency, RemoteDimensions.FloatingRemoteMaxTransparency) / 100f
    val opacity by animateFloatAsState(if (idle) baseOpacity * RemoteDimensions.FloatingRemoteIdleOpacityFactor else baseOpacity,
        tween(if (idle) RemoteDimensions.FloatingRemoteFadeMillis else 0), label = "floating-remote-opacity")
    val density=LocalDensity.current
    MirrorOverlayTheme(fullscreen) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val panelWidth=minOf(maxWidth,if (compact) RemoteDimensions.CompactRemoteWidth else mirrorFloatingRemoteWidth(screenWidth,screenHeight).coerceAtLeast(RemoteDimensions.MirrorRemoteMinimumWidth))
            val controlsHeight=minOf((maxHeight-RemoteDimensions.MirrorRemoteHeaderHeight).coerceAtLeast(0.dp),if (compact) RemoteDimensions.CompactRemoteHeight else RemoteDimensions.MirrorRemoteSourceHeight*(panelWidth/RemoteDimensions.Width))
            val panelHeight=controlsHeight+RemoteDimensions.MirrorRemoteHeaderHeight
            val limitX=with(density) {(maxWidth-panelWidth).toPx().coerceAtLeast(0f)}
            val limitY=with(density) {(maxHeight-panelHeight).toPx().coerceAtLeast(0f)}
            val headerHeightPx = with(density) { RemoteDimensions.MirrorRemoteHeaderHeight.toPx() }
            val handleWidthPx = with(density) { (panelWidth - RemoteDimensions.MirrorRemoteHeaderHeight * 2).toPx() }
            val snapDistance = with(density) { RemoteDimensions.FloatingRemoteSnapDistance.toPx() }
            val startDrag = { dragging = true; model.remoteKeys.cancelAll() }
            val endDrag = {
                dragging = false
                val snapped = snapMirrorRemoteOffset(Offset(offsetX, offsetY), Offset(limitX, limitY), snapDistance)
                offsetX = snapped.x; offsetY = snapped.y
            }
            val movePanel: (androidx.compose.ui.geometry.Offset) -> Unit = { delta ->
                offsetX=(offsetX.coerceIn(0f,limitX)+delta.x).coerceIn(0f,limitX)
                offsetY=(offsetY.coerceIn(0f,limitY)+delta.y).coerceIn(0f,limitY)
            }
            Surface(Modifier.align(Alignment.TopStart)
                .offset { IntOffset(offsetX.coerceIn(0f,limitX).roundToInt(),offsetY.coerceIn(0f,limitY).roundToInt()) }
                .width(panelWidth).height(panelHeight).testTag("mirror-floating-remote")
                .graphicsLayer { alpha = opacity }
                .semantics { stateDescription = if (idle) "闲置淡化" else "操作中" }
                .onFocusChanged { editing = it.hasFocus }.focusGroup()
                .pointerInput(Unit) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                interaction++
                                idle = false
                                touching = event.changes.any { it.pressed }
                            }
                        }
                    } finally { touching = false }
                }
                .mirrorRemoteDrag(onStart = startDrag, onEnd = endDrag,
                    canStart = { it.y >= headerHeightPx || it.x >= handleWidthPx }, onDrag = movePanel),
                color=if (fullscreen) RemoteColors.Background else RemoteColors.Surface, shape=MaterialTheme.shapes.small,
                border=BorderStroke(RemoteDimensions.MirrorRemoteBorderWidth, RemoteColors.FloatingBorder)) {
                Column {
                    Row(Modifier.fillMaxWidth().height(RemoteDimensions.MirrorRemoteHeaderHeight),
                        horizontalArrangement = Arrangement.End, verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).fillMaxHeight().testTag("mirror-remote-drag")
                            .semantics { contentDescription = "拖动悬浮遥控" }
                            .mirrorRemoteDrag(onStart = startDrag, immediate = true, onEnd = endDrag, onDrag = movePanel),
                            contentAlignment = Alignment.CenterStart) {
                            Box(Modifier.size(RemoteDimensions.MirrorRemoteHeaderHeight).mirrorControlBacking(), contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_drag_remote), null, Modifier.size(24.dp), tint = RemoteColors.Action)
                            }
                        }
                        MirrorToolbarIcon(if (compact) R.drawable.ic_remote_full else R.drawable.ic_remote_compact,
                            if (compact) "切换为完整遥控" else "切换为紧凑遥控", "mirror-remote-layout", onClick = { compact = !compact })
                        MirrorToolbarIcon(R.drawable.ic_close,"收起悬浮遥控","mirror-close-remote",onClick=onClose)
                    }
                    if (compact) CompactMirrorRemote(state, model, Modifier.height(controlsHeight))
                    else layouts.SaveableStateProvider("full") { ScaledMirrorRemote(Modifier.fillMaxWidth().height(controlsHeight)) { RemoteControls(state,model,showResults=onResults) } }
                }
            }
        }
    }
}

/** Scale layout, typography, graphics and hit testing together instead of squeezing the source UI. */
@Composable
internal fun ScaledMirrorRemote(modifier: Modifier, content: @Composable () -> Unit) {
    val density=LocalDensity.current
    val sourceWidth=with(density) {RemoteDimensions.Width.roundToPx()}
    Layout(content=content,modifier=modifier.clipToBounds().testTag("mirror-scaled-remote")) { measurables,constraints ->
        val width=constraints.maxWidth
        val scale=width.toFloat()/sourceWidth
        val height=constraints.maxHeight
        val child=measurables.single().measure(Constraints.fixed(sourceWidth,(height/scale).roundToInt().coerceAtLeast(1)))
        layout(width,height) {
            child.placeWithLayer(0,0) {scaleX=scale;scaleY=scale;transformOrigin=TransformOrigin(0f,0f)}
        }
    }
}

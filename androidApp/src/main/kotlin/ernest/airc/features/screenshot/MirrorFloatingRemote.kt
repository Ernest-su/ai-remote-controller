package ernest.airc

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun mirrorFloatingRemoteWidth(width: Dp, height: Dp): Dp =
    if(width <= height) width * RemoteDimensions.MirrorRemotePortraitFraction
    else height * RemoteDimensions.MirrorRemoteLandscapeFraction

/** The same overlay survives inline/fullscreen changes; only its viewport bounds change. */
@Composable
internal fun BoxScope.MirrorFloatingRemote(state: RemoteUiState, model: RemoteViewModel, onClose: () -> Unit, onResults: () -> Unit) {
    val configuration=LocalConfiguration.current
    val screenWidth=configuration.screenWidthDp.dp
    val screenHeight=configuration.screenHeightDp.dp
    val layouts = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var compact by rememberSaveable { mutableStateOf(true) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth=minOf(maxWidth,if (compact) RemoteDimensions.CompactRemoteWidth else mirrorFloatingRemoteWidth(screenWidth,screenHeight))
        val controlsHeight=minOf((maxHeight-RemoteDimensions.MirrorRemoteHeaderHeight).coerceAtLeast(0.dp),if (compact) RemoteDimensions.CompactRemoteHeight else RemoteDimensions.MirrorRemoteSourceHeight*(panelWidth/RemoteDimensions.Width))
        val panelHeight=controlsHeight+RemoteDimensions.MirrorRemoteHeaderHeight
        val limitX=with(density) {(maxWidth-panelWidth).toPx().coerceAtLeast(0f)}
        val limitY=with(density) {(maxHeight-panelHeight).toPx().coerceAtLeast(0f)}
        Surface(Modifier.align(Alignment.TopStart)
            .offset { IntOffset(offsetX.coerceIn(0f,limitX).roundToInt(),offsetY.coerceIn(0f,limitY).roundToInt()) }
            .width(panelWidth).height(panelHeight).testTag("mirror-floating-remote"),
            color=RemoteColors.Surface,shape=MaterialTheme.shapes.small) {
            Column {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(48.dp).testTag("mirror-remote-drag")
                        .pointerInput(limitX,limitY) {
                            detectDragGestures { change,drag ->
                                change.consume()
                                offsetX=(offsetX.coerceIn(0f,limitX)+drag.x).coerceIn(0f,limitX)
                                offsetY=(offsetY.coerceIn(0f,limitY)+drag.y).coerceIn(0f,limitY)
                            }
                        }, contentAlignment=Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_remote_types),"拖动悬浮遥控",Modifier.size(24.dp),tint=RemoteColors.Secondary)
                    }
                    MirrorToolbarIcon(R.drawable.ic_close,"收起悬浮遥控","mirror-close-remote",onClick=onClose)
                }
                TextButton({ compact = !compact }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (compact) "完整" else "紧凑") }
                if (compact) CompactMirrorRemote(state, model, Modifier.height(controlsHeight))
                else layouts.SaveableStateProvider("full") { ScaledMirrorRemote(Modifier.fillMaxWidth().height(controlsHeight)) { RemoteControls(state,model,showResults=onResults) } }
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

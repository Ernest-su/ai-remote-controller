package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Only the visible control gets a backing; hit targets and glyph alpha stay intact. */
@Composable
internal fun Modifier.mirrorControlBacking(shape: Shape = CircleShape): Modifier =
    if (LocalMirrorOverlay.current) background(RemoteColors.Surface, shape).border(0.5.dp, RemoteColors.Divider, shape)
    else this

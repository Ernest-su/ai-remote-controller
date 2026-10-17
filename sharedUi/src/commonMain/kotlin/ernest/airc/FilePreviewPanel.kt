package ernest.airc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun FileThumbnail(kind: FilePreviewKind, bitmap: ImageBitmap?, text: String?, modifier: Modifier, apk: Boolean = false) {
    Box(modifier.clip(MaterialTheme.shapes.small).background(RemoteColors.Fill), contentAlignment=Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, null, Modifier.fillMaxSize(), contentScale=ContentScale.Crop)
            text != null -> Text(text, Modifier.padding(4.dp), style=MaterialTheme.typography.labelSmall, maxLines=4, overflow=TextOverflow.Clip, color=RemoteColors.Secondary)
            else -> Icon(if(apk) ManagementIcons.Apps else if(kind == FilePreviewKind.IMAGE) ManagementIcons.Image else if(kind == FilePreviewKind.TEXT) ManagementIcons.File else ManagementIcons.Play, null, Modifier.size(24.dp), tint=RemoteColors.Secondary)
        }
    }
}

@Composable
fun FileImagePreview(bitmap: ImageBitmap) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var x by remember(bitmap) { mutableFloatStateOf(0f) }
    var y by remember(bitmap) { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().height(RemoteDimensions.FilePreviewHeight).clip(MaterialTheme.shapes.small).background(RemoteColors.Fill)) {
        Image(bitmap, "图片预览", Modifier.fillMaxSize().testTag("file-image-preview")
            .pointerInput(bitmap) { detectTransformGestures { _, pan, zoom, _ ->
                scale=(scale*zoom).coerceIn(1f,5f)
                x=if(scale==1f) 0f else (x+pan.x).coerceIn(-size.width*(scale-1)/2,size.width*(scale-1)/2)
                y=if(scale==1f) 0f else (y+pan.y).coerceIn(-size.height*(scale-1)/2,size.height*(scale-1)/2)
            } }.graphicsLayer { scaleX=scale;scaleY=scale;translationX=x;translationY=y }, contentScale=ContentScale.Fit)
    }
    Text("双指缩放和拖动查看图片。", style=MaterialTheme.typography.bodySmall, color=RemoteColors.Secondary)
    TextButton({scale=1f;x=0f;y=0f}, Modifier.heightIn(min=48.dp)) { Text("重置缩放") }
}

@Composable
fun FileTextPreview(text: String, encoding: String, truncated: Boolean) {
    Text("$encoding${if(truncated) " · 仅显示前 256 KiB，完整内容请下载查看" else ""}", style=MaterialTheme.typography.bodySmall, color=RemoteColors.Secondary)
    SelectionContainer {
        Text(text.ifEmpty { "（空文件）" }, Modifier.fillMaxWidth().heightIn(max=RemoteDimensions.FilePreviewHeight).verticalScroll(rememberScrollState()).testTag("file-text-preview"), style=MaterialTheme.typography.bodyMedium)
    }
}

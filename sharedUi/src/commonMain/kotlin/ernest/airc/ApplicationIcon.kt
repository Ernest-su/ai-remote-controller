package ernest.airc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException

internal expect fun decodeApplicationIcon(bytes: ByteArray): ImageBitmap

@Composable
fun ApplicationIcon(packageName: String, available: Boolean, load: suspend (String) -> ByteArray) {
    var image by remember(packageName, available) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName, available) {
        if(available) try { image = decodeApplicationIcon(load(packageName)) }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { image = null }
    }
    Box(Modifier.size(RemoteDimensions.ApplicationIcon).testTag("application-icon-$packageName")) {
        image?.let { Image(it, null, Modifier.size(RemoteDimensions.ApplicationIcon)) }
    }
}

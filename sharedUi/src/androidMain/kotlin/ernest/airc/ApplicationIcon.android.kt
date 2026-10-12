package ernest.airc
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
internal actual fun decodeApplicationIcon(bytes: ByteArray): ImageBitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)).asImageBitmap()

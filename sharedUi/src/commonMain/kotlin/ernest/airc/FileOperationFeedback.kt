package ernest.airc

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun FileOperationFeedback(section: String, upload: String?, batch: String?, download: String?) {
    val message = when(section) { "upload" -> upload; "batch" -> batch; "download" -> download; else -> null }
    message?.let { Text(it,color=RemoteColors.Secondary) }
}

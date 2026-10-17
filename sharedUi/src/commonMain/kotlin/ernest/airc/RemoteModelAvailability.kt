package ernest.airc

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The runtime status determines whether the standard remote offers text commands. */
@Composable
fun RemoteModelAvailability(modelConfigured: Boolean, content: @Composable () -> Unit) {
    if (modelConfigured) {
        content()
    } else {
        Text(
            "请先在「功能 → 模型配置」配置目标设备的模型",
            style = MaterialTheme.typography.bodyMedium,
            color = RemoteColors.Secondary,
            modifier = Modifier.testTag("remote-model-required"),
        )
    }
}

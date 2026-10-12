package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ApkInspectionPanel(state: ApkInspectionState, onCancel: (() -> Unit)? = null) {
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取 APK 信息与摘要")
        state.total?.let { Text("${state.bytes} / $it 字节", color = RemoteColors.Secondary) }
        onCancel?.let { OutlinedButton(it, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("取消 APK 解析") } }
    }
    if (state.phase == "cancelled") Text("APK 解析已取消，原文件保留。", color = RemoteColors.Secondary)
    state.error?.let { Text(it, color = RemoteColors.Warning) }
    state.info?.let { info ->
        HorizontalDivider(color = RemoteColors.Divider)
        SectionTitle("APK 信息", "仅解析，不安装或更新应用。")
        InfoRow("目标文件", "${fileRootTitle(info.root)}/${info.path}")
        InfoRow("包名", info.packageName)
        InfoRow("版本", "${info.versionName ?: "未读取名称"} (${info.versionCode})")
        InfoRow("大小", "${info.sizeBytes} 字节")
        InfoRow("SHA-256", info.sha256)
        info.split?.let { InfoRow("拆分包", it) }
        if (info.signatures.isEmpty()) Text("没有读取到已识别的签名记录。", color = RemoteColors.Secondary)
        info.signatures.forEach { signature ->
            InfoRow("签名方案", signature.scheme)
            signature.certificateSha256.forEach { InfoRow("声明证书 SHA-256", it) }
            if (!signature.certificateExtractionSupported) Text("此方案的证书指纹解析尚未支持。", color = RemoteColors.Secondary)
        }
        Text("证书指纹仅从 APK 声明读取，此步骤未验证签名真伪或文件可安装性。", color = RemoteColors.Secondary)
    }
}

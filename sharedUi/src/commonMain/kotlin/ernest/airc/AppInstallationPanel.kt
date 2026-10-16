package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppInstallationPanel(state: AppInstallationState, apk: TargetApkInfo?, available: Boolean, onPrepare: (Boolean) -> Unit, onConfirm: (Boolean) -> Unit, onCancel: () -> Unit, onAdd: (() -> Unit)? = null, onRemove: ((TargetApkInfo) -> Unit)? = null, onPrepareSet: ((Boolean) -> Unit)? = null) {
    var replacement by remember(apk?.sha256) { mutableStateOf(false) }
    apk?.let {
        ToggleSettingRow("允许覆盖安装现有应用", replacement, !state.busy, checkbox = true) { replacement = it }
        ActionButton("准备安装此 APK", available && !state.busy && it.split.isNullOrEmpty()) { onPrepare(replacement) }
    }
    if(apk != null && onAdd != null) ActionButton("加入多包安装", available && !state.busy, onAdd)
    if(state.selectedApks.isNotEmpty() && onPrepareSet != null) {
        SectionTitle("多包安装（${state.selectedApks.size}/32）", "逐个解析并加入 base 与所需 split APK，再统一确认安装。")
        state.selectedApks.forEach { entry ->
            InfoRow(entry.split?.takeIf { it.isNotEmpty() } ?: "base APK", entry.path)
            Text("${entry.sizeBytes} 字节 · ${entry.sha256}", color=RemoteColors.Secondary)
            onRemove?.let { remove -> OutlinedButton({remove(entry)}, enabled=!state.busy, modifier=Modifier.fillMaxWidth().heightIn(min=48.dp), shape = MaterialTheme.shapes.small) { Text("移除此 APK") } }
        }
        val validationError = runCatching { validateApkSet(state.selectedApks) }.exceptionOrNull()?.message
        validationError?.let { Text(it, color=RemoteColors.Warning) }
        ToggleSettingRow("允许覆盖安装现有应用", replacement, !state.busy, checkbox = true) { replacement = it }
        ActionButton("准备安装整组 APK",available && !state.busy && validationError==null) { onPrepareSet(replacement) }
    }
    state.proposal?.let { proposal ->
        AlertDialog(onDismissRequest = { onConfirm(false) }, title = { Text(if (proposal.update) "确认覆盖安装" else "确认安装 APK") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("包名", proposal.apk.packageName)
                InfoRow("待安装版本", "${proposal.apk.versionName ?: "未读取名称"} (${proposal.apk.versionCode})")
                proposal.currentApp?.let { InfoRow("已有版本", "${it.versionName ?: it.versionCode} (${it.versionCode})") }
                InfoRow("Android 用户", proposal.userId.toString())
                proposal.apks.ifEmpty { listOf(proposal.apk) }.forEach { entry ->
                    InfoRow(entry.split?.takeIf { it.isNotEmpty() } ?: "base APK",entry.path)
                    InfoRow("文件大小", "${entry.sizeBytes} 字节")
                    InfoRow("文件 SHA-256", entry.sha256)
                }
                Text("全部 APK 通过同一个 Android 安装会话提交；缺少依赖或签名不一致时由包管理器拒绝。")
                proposal.apk.signatures.forEach { signature -> signature.certificateSha256.forEach { InfoRow("声明证书 SHA-256（${signature.scheme}）", it) } }
                Text("签名有效性由 Android 包管理器在提交安装时验证。确认后不能取消。")
                Text(if (proposal.codeMayAffectAllUsers) "替换 APK 程序代码可能影响该包的所有 Android 用户；应用升级迁移也可能改变原有数据。" else "将在指定用户下安装此 APK。")
            } },
            confirmButton = { TextButton({ onConfirm(true) }, enabled = available, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (proposal.update) "确认覆盖安装" else "确认安装", color = if (proposal.update) RemoteColors.Warning else RemoteColors.Action) } },
            dismissButton = { TextButton({ onConfirm(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (state.phase == "committing") "正在提交安装，不能取消" else if (state.phase == "awaiting_approval") "等待安装确认" else "正在准备 APK 安装")
        state.total?.takeIf { state.phase == "preparing" }?.let { Text("已处理 ${state.bytes} / $it 字节", color = RemoteColors.Secondary) }
        if (state.phase == "preparing") OutlinedButton(onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("取消安装准备") }
    }
    state.message?.let { Text(it, color = RemoteColors.Secondary) }
    state.error?.let { Text(it, color = RemoteColors.Warning) }
}

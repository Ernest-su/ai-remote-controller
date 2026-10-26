package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun DesktopServices(state: DesktopState, controller: DesktopController) = PageContent(Modifier.testTag("service-page")) {
    var confirmStop by remember { mutableStateOf(false) }
    val enabled = state.serial != null && !state.busy
    Panel {
        SectionTitle("管理服务", "通过已授权设备连接，部署和管理设备端服务。")
        InfoRow("服务端版本", state.version ?: "未连接")
        ActionButton("构建 / 部署项目服务", enabled) { controller.deploy(state.serial!!, state.runtimePort) }
        OutlinedButton({ controller.service("status") }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("查询状态") }
        OutlinedButton({ controller.service("restart") }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("重启目标服务") }
        TextButton({ confirmStop = true }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = RemoteColors.Warning)) { Text("停止目标服务") }
    }
    OperationDetails(state.output)
    if (confirmStop) StopServiceConfirmation(enabled, { confirmStop = false }) {
        confirmStop = false
        controller.service("stop")
    }
}

@Composable
fun DesktopPermissions(state: DesktopState, controller: DesktopController) = PageContent(Modifier.testTag("permissions-page")) {
    Panel {
        SectionTitle("运行权限", "下次构建/部署时按设置检测 root，不可用时回退到 shell。")
        ToggleSettingRow("优先使用 root 权限", state.preferRoot, !state.busy, onCheckedChange = controller::setPreferRoot)
        InfoRow("当前 UID", state.uid?.toString() ?: "未知 / 未连接")
    }
}

@Composable
fun DesktopHelp() = PageContent(Modifier.testTag("help-page")) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    ProjectDocumentation { uri.openUri(ProjectLinks.DOCUMENTATION) }
    Panel {
        SectionTitle("桌面测试工具")
        Text("右上角进入设备页，刷新并选择宿主机 adb 已授权设备，或直接连接局域网服务。")
        Text("首次部署需从项目目录启动，并具备项目 Rust/Android NDK 构建环境。桌面不会自动修改目标调试设置。")
        Text("左上角打开三列功能网格。Escape 和顶部返回遵循相同路径，文本草稿切页后保留。")
        Text("模型配置、列表缓存保存在当前桌面用户的私有目录；与手机配置分别保存。只有点击更新按钮才同步到目标。")
        Text("日常遥控直接调用目标工具；文字任务在目标二进制中调用模型。仅用于可信局域网。")
    }
}

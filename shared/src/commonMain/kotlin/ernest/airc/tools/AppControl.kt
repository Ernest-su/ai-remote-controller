package ernest.airc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppPermission(val name: String, val label: String, val granted: Boolean, val runtime: Boolean, val mutable: Boolean, val reason: String = "")
@Serializable
data class AppControlCapabilities(val forceStop: Boolean, val setEnabled: Boolean, val clearData: Boolean)
@Serializable
data class AppControlSnapshot(@SerialName("package") val packageName: String, val userId: Int, val enabled: Boolean,
    val enabledSetting: Int, val stopped: Boolean, val capabilities: AppControlCapabilities, val permissions: List<AppPermission>)
@Serializable
data class AppControlDetails(val app: TargetAppInfo, val state: AppControlSnapshot)
@Serializable
data class AppControlProposal(val id: String, val kind: String, val app: TargetAppInfo, val action: String,
    val permission: String? = null, val approvalRequired: Boolean, val dataLoss: Boolean, val before: AppControlSnapshot)

data class AppControlState(val packageName: String? = null, val busy: Boolean = false, val details: AppControlDetails? = null,
    val proposal: AppControlProposal? = null, val committing: Boolean = false, val message: String? = null, val error: String? = null)

fun appControlLabel(action: String): String = when (action) {
    "force_stop" -> "强行停止"
    "enable" -> "启用应用"
    "disable" -> "停用应用"
    "clear_data" -> "清除全部数据"
    "grant" -> "授予权限"
    "revoke" -> "撤销权限"
    else -> "未知操作"
}
fun appControlImpact(action: String): String = when (action) {
    "force_stop" -> "将停止当前用户的此应用，正在进行的播放、下载和后台任务可能中断。"
    "disable" -> "停用后此应用将无法启动，相关后台功能将停止；可在此重新启用。"
    "enable" -> "重新允许当前用户启动此应用，不会自动打开应用。"
    "clear_data" -> "将删除当前用户的此应用全部数据及缓存，包括登录状态、设置和应用私有文件，无法恢复；应用本身保留。"
    "grant", "revoke" -> "将修改当前用户的此项运行时权限，应用功能可能受影响或被系统重启。特殊访问权限不在本次操作范围内。"
    else -> ""
}

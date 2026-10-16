package ernest.airc

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ManualConnectionTarget(val host: String, val adbPort: Int, val runtimePort: Int)

/** Only an explicit connection action may fall back from the LAN service to ADB deployment. */
internal suspend fun connectLanOrDeploy(
    target: ManualConnectionTarget,
    connectRuntime: suspend () -> Unit,
    deploy: suspend () -> Unit,
    onDeploying: (String) -> Unit,
    onDeploymentFailed: (ManualConnectionTarget) -> Unit,
) {
    try {
        connectRuntime()
        return
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
    }
    onDeploying("遥控服务连接失败，正在尝试通过 ADB ${target.host}:${target.adbPort} 自动部署，请在目标设备上允许授权…")
    try {
        deploy()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        onDeploymentFailed(target)
        throw IllegalStateException("自动部署失败（${target.host}:${target.adbPort}）：${e.message ?: "连接失败"}。已填入目标设备 IP，请检查 ADB 端口与授权后重试，或使用无线调试配对。", e)
    }
}

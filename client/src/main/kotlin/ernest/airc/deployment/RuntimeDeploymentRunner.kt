package ernest.airc

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Keep ADB OPEN requests below legacy devices' 4096-byte packet limit. */
object RuntimeDeploymentRunner {
    suspend fun run(
        directory: String,
        port: Int,
        privilege: RuntimePrivilege,
        push: suspend (ByteArray, String) -> Unit,
        shell: suspend (String) -> String,
    ): String {
        val bytes = RuntimeDeployment.script(directory, port, privilege).toByteArray(Charsets.UTF_8)
        val path = "$directory/deploy-${UUID.randomUUID()}.sh"
        val quoted = RuntimePrivilege.quote(path)
        try {
            push(bytes, path)
            check(shell(privilege.command("cat $quoted")).replace("\r\n", "\n").trimEnd('\n') == bytes.toString(Charsets.UTF_8).trimEnd('\n')) {
                "部署脚本校验失败，目标 ADB 连接可能已中断"
            }
            return shell(privilege.command("sh $quoted")).also {
                check(it.isNotBlank()) { "部署脚本未返回结果，目标 ADB 连接可能已中断；请重新连接并查询服务状态" }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(10_000) { shell(privilege.command("rm -f $quoted")) } }
            }
        }
    }
}

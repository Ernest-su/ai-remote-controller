package ernest.airc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Verified target-side privileges. Commands are quoted as one argument to Android su. */
data class RuntimePrivilege(val manager: Mode, val useRoot: Boolean, val suStyle: SuStyle = SuStyle.COMMAND) {
    init { require(!useRoot || manager != Mode.SHELL) }
    enum class SuStyle { COMMAND, UID_SHELL }
    enum class Mode { SHELL, ROOT_ADBD, SU }
    val description: String get() = when {
        !useRoot -> "ADB shell"
        manager == Mode.ROOT_ADBD -> "root adbd"
        else -> "su root"
    }
    fun command(script: String): String = if (manager == Mode.SU) "${if(suStyle == SuStyle.UID_SHELL) "su 0 sh -c" else "su -c"} ${quote(script)}" else script
    // The deployment transaction itself runs as manager; this prefix selects its child UID.
    val shellPrefix: String get() = if (manager == Mode.SHELL) "" else if(suStyle == SuStyle.UID_SHELL) "su 2000 sh -c" else "su 2000 -c"
    fun launch(script: String): String = command(if (useRoot || manager == Mode.SHELL) script else "$shellPrefix ${quote(script)}")

    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
        private suspend fun suUid(shell: suspend (String) -> String, command: String): String = try {
            withTimeoutOrNull(15_000) { shell(command).trim() }.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ""
        }
        suspend fun detect(preferRoot: Boolean, directory: String, shell: suspend (String) -> String): RuntimePrivilege {
            require(directory.matches(Regex("/[a-zA-Z0-9_./-]+")) && directory.trim('/').isNotEmpty() && directory.split('/').none { it == ".." || it == "." })
            val uid = shell("id -u").trim()
            check(uid == "0" || uid == "2000") { "目标 ADB 用户不是 root 或 shell：$uid" }
            val previousRoot = shell("if [ -e $directory/.root-mode ] || { [ -d $directory/state ] && [ ! -r $directory/state ]; }; then echo root; fi").trim() == "root"
            var style = SuStyle.COMMAND
            val mode = when {
                uid == "0" -> Mode.ROOT_ADBD
                preferRoot || previousRoot -> {
                    if(suUid(shell, "su -c 'id -u' 2>/dev/null") == "0") Mode.SU
                    else if(suUid(shell, "su 0 sh -c 'id -u' 2>/dev/null") == "0") { style = SuStyle.UID_SHELL; Mode.SU }
                    else Mode.SHELL
                }
                else -> Mode.SHELL
            }
            if(mode == Mode.ROOT_ADBD && !preferRoot && suUid(shell,"su 2000 -c 'id -u' 2>/dev/null") != "2000" && suUid(shell,"su 2000 sh -c 'id -u' 2>/dev/null") == "2000") style = SuStyle.UID_SHELL
            check(!previousRoot || mode != Mode.SHELL) { "原服务使用 root，当前 root 不可用，无法安全管理；请恢复目标 su 授权" }
            val useRoot = preferRoot && mode != Mode.SHELL
            return RuntimePrivilege(mode, useRoot, style)
        }
    }
}

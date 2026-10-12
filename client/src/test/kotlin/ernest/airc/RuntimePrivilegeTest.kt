package ernest.airc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimePrivilegeTest {
    private val directory = "/data/local/tmp/airc"
    private fun detect(preferRoot: Boolean, uid: String = "2000", previousRoot: Boolean = false, suUid: String = "", commands: MutableList<String> = mutableListOf()): RuntimePrivilege = runBlocking {
        RuntimePrivilege.detect(preferRoot, directory) { command ->
            commands += command
            when {
                command == "id -u" -> uid
                command.startsWith("if [ -e") -> if (previousRoot) "root" else ""
                command == "su -c 'id -u' 2>/dev/null" -> suUid
                command == "su 0 sh -c 'id -u' 2>/dev/null" -> ""
                else -> error("Unexpected probe: $command")
            }
        }
    }
    @Test fun disabledRootDoesNotPromptForSuOnShellDevice() {
        val commands = mutableListOf<String>()
        val privilege = detect(false, suUid = "0", commands = commands)
        assertEquals(RuntimePrivilege.Mode.SHELL, privilege.manager)
        assertFalse(privilege.useRoot)
        assertFalse(commands.any { it.startsWith("su ") })
    }
    @Test fun rootAdbdHasPriorityWithoutSuProbe() {
        val commands = mutableListOf<String>()
        val privilege = detect(true, uid = "0\r\n", commands = commands)
        assertEquals(RuntimePrivilege.Mode.ROOT_ADBD, privilege.manager)
        assertTrue(privilege.useRoot)
        assertFalse(commands.any { it.startsWith("su ") })
    }
    @Test fun verifiedSuIsUsedForBothManagementAndLaunch() {
        val privilege = detect(true, suUid = "0\n")
        assertEquals(RuntimePrivilege.Mode.SU, privilege.manager)
        assertTrue(privilege.useRoot)
        assertEquals("su -c 'id -u'", privilege.launch("id -u"))
    }
    @Test fun toyboxSuUsesExplicitUidAndShellWithoutChangingDisabledRootBehavior() = runBlocking {
        val privilege=RuntimePrivilege.detect(true,directory) { command -> when {
            command=="id -u" -> "2000"
            command.startsWith("if [ -e") -> ""
            command=="su 0 sh -c 'id -u' 2>/dev/null" -> "0"
            else -> ""
        } }
        assertEquals(RuntimePrivilege.SuStyle.UID_SHELL,privilege.suStyle)
        assertEquals("su 0 sh -c 'id -u'",privilege.command("id -u"))
        assertEquals("su 2000 sh -c",privilege.shellPrefix)
    }
    @Test fun rootAdbdDemotionDetectsNumericShellSyntax() = runBlocking {
        val privilege=RuntimePrivilege.detect(false,directory) { command -> when {
            command=="id -u" -> "0"
            command.startsWith("if [ -e") -> "root"
            command=="su 2000 sh -c 'id -u' 2>/dev/null" -> "2000"
            else -> ""
        } }
        assertEquals(RuntimePrivilege.Mode.ROOT_ADBD,privilege.manager)
        assertFalse(privilege.useRoot)
        assertEquals("su 2000 sh -c 'id -u'",privilege.launch("id -u"))
    }
    @Test fun missingDeniedOrNonRootSuFallsBackToShell() {
        for (reply in listOf("", "Permission denied", "2000")) {
            val privilege = detect(true, suUid = reply)
            assertEquals(RuntimePrivilege.Mode.SHELL, privilege.manager)
            assertFalse(privilege.useRoot)
        }
    }
    @Test fun disablingRootRetainsElevatedManagementOfPreviousService() {
        val privilege = detect(false, previousRoot = true, suUid = "0")
        assertEquals(RuntimePrivilege.Mode.SU, privilege.manager)
        assertFalse(privilege.useRoot)
        assertEquals("su -c 'su 2000 -c '\\''id -u'\\'''", privilege.launch("id -u"))
    }
    @Test fun lostRootAccessDoesNotPretendToManageRootServiceAsShell() {
        try {
            detect(false, previousRoot = true)
            fail("Expected root management failure")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("root 不可用"))
        }
    }
    @Test fun suTransportFailureFallsBackButCancellationPropagates() = runBlocking {
        val fallback = RuntimePrivilege.detect(true, directory) { command ->
            when {
                command == "id -u" -> "2000"
                command.startsWith("if [ -e") -> ""
                else -> throw java.io.IOException("su denied")
            }
        }
        assertFalse(fallback.useRoot)
        try {
            RuntimePrivilege.detect(true, directory) { command ->
                when {
                    command == "id -u" -> "2000"
                    command.startsWith("if [ -e") -> ""
                    else -> throw kotlinx.coroutines.CancellationException("cancelled")
                }
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun shellQuotingPreservesLiteralMultilineScriptAndMetacharacters() {
        val value = "printf '%s\\n' 'literal'; echo \"\${HOME}\"\n# multiline"
        val process = ProcessBuilder("sh", "-c", "printf %s ${RuntimePrivilege.quote(value)}").start()
        assertEquals(value, process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
}

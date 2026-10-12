package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.*
import java.io.File
import java.security.MessageDigest

class SplitApkTargetTest {
    @Test fun atomicSplitInstallUpdateAndBadSignaturePreserveExistingData(): Unit = runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned split APK validation",!serial.isNullOrBlank())
        val adb=HostAdb(); val pkg="ernest.airc.splitfixture"
        suspend fun exists()=adb.command("-s",serial!!,"shell","pm list packages --user all $pkg").lines().any { it.trim()=="package:$pkg" }
        check(!exists()) { "Refusing to alter a pre-existing split fixture" }
        val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection();val transfer=RuntimeTransferClient(runtime)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val controller=AppInstallationController(scope,runtime);val inspection=ApkInspectionController(scope,runtime)
        val json=Json { ignoreUnknownKeys=true }
        var ownsPackage=false
        suspend fun push(file:File):TargetApkInfo {
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val name="${java.util.UUID.randomUUID()}.apk"
            transfer.upload(name,file.length(),hash,file::inputStream)
            inspection.inspect("inbox",name,true)
            val state=withTimeout(30000) { inspection.state.first { !it.busy } }
            assertNull(state.error);return checkNotNull(state.info)
        }
        suspend fun finish()=withTimeout(45000) { controller.state.first { !it.busy } }
        suspend fun prepare(apks:List<TargetApkInfo>,update:Boolean) {
            controller.clear();apks.forEach(controller::add);controller.prepareSelected(update,true)
            val state=withTimeout(30000) { controller.state.first { it.proposal!=null || !it.busy } }
            assertNull(state.error);assertEquals(apks.toSet(),checkNotNull(state.proposal).apks.toSet())
        }
        suspend fun version()=json.decodeFromString<TargetAppInfo>(runtime.request("tool","apps.info",buildJsonObject{put("package",pkg)})).versionCode
        try {
            target.prepare();runtime.connect("127.0.0.1",checkNotNull(target.port))
            val directory=File(checkNotNull(System.getProperty("airc.splitFixtures")))
            val first=listOf(push(File(directory,"config.en-v1.apk")),push(File(directory,"base-v1.apk")))
            prepare(first,false);assertFalse(exists());controller.confirm(false);assertNull(finish().error);assertFalse(exists())
            prepare(first,false);ownsPackage=true;controller.confirm(true);assertNull(finish().error);assertEquals(1L,version())
            assertEquals(2,adb.command("-s",serial,"shell","pm path $pkg").lines().count{it.startsWith("package:")})
            val icon=AppIconController(scope,runtime).load(pkg)
            assertTrue(icon.size in 33..128*1024)
            adb.command("-s",serial,"shell","run-as $pkg sh -c 'mkdir -p files; printf split-data > files/owned'")
            val second=listOf(push(File(directory,"base-v2.apk")),push(File(directory,"config.en-v2.apk")))
            prepare(second,true);controller.confirm(true);assertNull(finish().error);assertEquals(2L,version())
            assertEquals("split-data",adb.command("-s",serial,"shell","run-as $pkg cat files/owned"))
            // Corrupt signed bytes without changing manifest or certificate metadata.
            val bad=File.createTempFile("airc-bad-split-",".apk")
            try {
                val bytes=File(directory,"config.en-v2.apk").readBytes()
                // Local ZIP timestamp is covered by v2 signatures but leaves metadata parseable.
                bytes[10]=(bytes[10].toInt() xor 1).toByte();bad.writeBytes(bytes)
                val corrupt=push(bad)
                prepare(listOf(second[0],corrupt),true);controller.confirm(true)
                assertNotNull(finish().error);assertEquals(2L,version())
                assertEquals("split-data",adb.command("-s",serial,"shell","run-as $pkg cat files/owned"))
            } finally { bad.delete() }
        } finally {
            controller.clear();inspection.clear();scope.cancel();transfer.close();runtime.close()
            try { if(ownsPackage && exists()) check(adb.command("-s",serial,"uninstall",pkg).contains("Success")) }
            finally { target.close() }
        }
    }
}

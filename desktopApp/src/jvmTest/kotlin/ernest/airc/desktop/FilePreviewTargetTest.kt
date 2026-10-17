package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URI
import javax.imageio.ImageIO
import kotlin.test.*

class FilePreviewTargetTest {
    @Test fun rangePreviewAndArchiveIconDoNotLaunchOrInstallOnTarget():Unit=runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for an owned Android target",!serial.isNullOrBlank())
        val adb=HostAdb();val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection();val previews=FilePreviewClient(runtime)
        val transfer=RuntimeTransferClient(runtime)
        val apk=File(checkNotNull(System.getProperty("airc.mediaFixtureApk")))
        val temp=Files.createTempDirectory("airc-preview-target-").toFile()
        try {
            target.prepare();runtime.connect("127.0.0.1",checkNotNull(target.port))
            val before=adb.command("-s",serial,"shell","pm list packages ernest.airc.mediafixture")
            transfer.upload("preview.apk",apk.length(),mediaDigest(apk),apk::inputStream)
            val png=previews.apkIcon("inbox","preview.apk")
            val icon=ImageIO.read(png.inputStream())
            assertEquals(96,icon.width);assertEquals(96,icon.height)
            assertEquals(before,adb.command("-s",serial,"shell","pm list packages ernest.airc.mediafixture"))
            val controllerApk=File(checkNotNull(System.getProperty("airc.runtimeAssets")),"../../../../build/outputs/apk/debug/androidApp-debug.apk").canonicalFile
            check(controllerApk.isFile) {"Build :androidApp:assembleDebug to verify a real archive icon"}
            transfer.upload("controller.apk",controllerApk.length(),mediaDigest(controllerApk),controllerApk::inputStream)
            val controllerIcon=ImageIO.read(previews.apkIcon("inbox","controller.apk").inputStream())
            assertEquals(0x08c979,controllerIcon.getRGB(48,8) and 0xffffff,"Archive resources must provide the original green icon, not the default package icon")
            val text=File(temp,"notes.txt").apply {writeText("中文预览\n".repeat(100000))}
            transfer.upload(text.name,text.length(),mediaDigest(text),text::inputStream)
            val source=previews.open("inbox",text.name)
            try {
                val bytes=source.read(256*1024,true)
                assertEquals(256*1024,bytes.size)
                assertTrue(decodeFilePreviewText(bytes).text.startsWith("中文预览"))
                // Opening the range stream cannot start an APK or media application.
                val stat=runtime.request("tool","files.stat",buildJsonObject {put("root","inbox");put("path",text.name)})
                assertEquals(text.length(),Json.parseToJsonElement(stat).jsonObject["sizeBytes"]!!.jsonPrimitive.long)
            } finally {source.release()}
            val closed=URI(source.url).toURL().openConnection() as HttpURLConnection
            try {assertEquals(404,closed.responseCode)} finally {closed.disconnect()}
            assertFails {previews.open("inbox","../notes.txt")}
        } finally {transfer.close();previews.close();runtime.close();target.close();temp.deleteRecursively()}
    }
}

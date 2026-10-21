package ernest.airc

import android.provider.DocumentsContract
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Owned emulator fixture using the platform's actual external-storage document provider. */
class BatchDocumentSaveTest {
    @Test fun individuallySavedDocumentsKeepOriginalsAndNamesWithoutCreatingZip() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val automation = instrumentation.uiAutomation
        val authority = "com.android.externalstorage.documents"
        val folderName = "airc-batch-${UUID.randomUUID()}"
        automation.executeShellCommand("mkdir /sdcard/Download/$folderName").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val picked = AtomicReference<android.net.Uri>()
        val result = CountDownLatch(1)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.activityResultRegistry.register("batch-folder", ActivityResultContracts.OpenDocumentTree()) { uri ->
                picked.set(uri); result.countDown()
            }.launch(DocumentsContract.buildDocumentUri(authority, "primary:Download/$folderName"))
        }
        fun clickWhenAvailable(vararg labels: String) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (SystemClock.uptimeMillis() < deadline) {
                if ("ALLOW" in labels && result.count == 0L) return
                val root = automation.rootInActiveWindow
                val node = labels.asSequence().flatMap { root?.findAccessibilityNodeInfosByText(it).orEmpty().asSequence() }.firstOrNull { it.isEnabled && it.isClickable }
                if (node != null) {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
                }
                SystemClock.sleep(100)
            }
            error("Folder picker action missing: ${labels.toList()}")
        }
        val cache = File(instrumentation.targetContext.cacheDir, "batch-document-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var folder: android.net.Uri? = null
        try {
            clickWhenAvailable("USE THIS FOLDER", "Use this folder")
            clickWhenAvailable("ALLOW", "Allow")
            check(result.await(10, TimeUnit.SECONDS)) { "Folder grant not returned" }
            val tree = checkNotNull(picked.get()) { "Folder picker cancelled" }
            assertEquals("primary:Download/$folderName", DocumentsContract.getTreeDocumentId(tree))
            folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val ownedParent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val original = checkNotNull(DocumentsContract.createDocument(resolver, ownedParent, "text/plain", "one.txt"))
            resolver.openOutputStream(original)!!.use { it.write("original".toByteArray()) }
            val one = File(cache, "one.cache").apply { writeText("first bytes") }
            val two = File(cache, "two.cache").apply { writeText("中文文件") }
            val saved = mutableListOf<String>()
            saveBatchDocuments(resolver, tree, listOf(BatchFileExport("one.txt", one), BatchFileExport("two.txt", two)), saved::add)
            assertEquals(listOf("one.txt", "two.txt"), saved)
            val values = mutableMapOf<String, String>()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(1))
                    values[cursor.getString(0)] = resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }
            }
            assertEquals(mapOf("one.txt" to "original", "one (1).txt" to "first bytes", "two.txt" to "中文文件"), values)
        } finally {
            folder?.let { DocumentsContract.deleteDocument(resolver, it) }
            cache.deleteRecursively()
            scenario.close()
            automation.executeShellCommand("rmdir /sdcard/Download/$folderName").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        Unit
    }
}

package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class FileManagementTargetTest {
    @Test fun liveFileManagementConfirmsOverwriteDeletionAndFullRoot(): Unit = runBlocking {
        val serial=System.getProperty("airc.targetSerial")
        assumeTrue("Set -PaircTargetSerial for owned file management fixtures",!serial.isNullOrBlank())
        val adb=HostAdb();val target=ShellRuntimeTarget(serial!!)
        val runtime=RuntimeConnection();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val transfers=FileTransferController(scope,runtime)
        val browser=FileBrowserController(scope,runtime)
        val directory=Files.createTempDirectory("airc-management-").toFile()
        val name="airc-management-${java.util.UUID.randomUUID()}.bin"
        val folder="airc-management-${java.util.UUID.randomUUID()}"
        var ownsDownload=false
        try {
            target.prepare()
            val status=runtime.connect("127.0.0.1",checkNotNull(target.port))
            assertTrue("files.delete" in status.tools)
            val full=Json.decodeFromString<TargetFilesPage>(runtime.request("tool","files.list",buildJsonObject {put("root","full");put("limit",10)}))
            assertEquals("full",full.root);assertTrue(full.entries.isNotEmpty())
            val source=java.io.File(directory,"source.bin").apply {writeText("original")}
            transfers.select(UploadSource(name,source.length(),source::inputStream))
            transfers.upload("inbox",name,true)
            withTimeout(15000) {transfers.state.first {it.phase=="completed"&&!it.running}}
            browser.selectRoot("inbox");browser.refresh(true)
            val listing=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(listing.error)
            val entry=listing.entries.single{it.name==name}
            assertEquals("file",entry.kind);assertEquals(8L,entry.sizeBytes)
            browser.inspect(entry,true)
            val stat=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(stat.error);assertEquals(8L,stat.selected?.sizeBytes)
            source.writeText("replacement")
            transfers.select(UploadSource(name,source.length(),source::inputStream))
            transfers.upload("inbox",name,true,true)
            val pending=withTimeout(5000) {transfers.state.first {it.proposal!=null}}
            assertEquals("overwrite",pending.proposal!!.kind)
            assertEquals(8L,pending.proposal!!.sizeBytes)
            transfers.confirm(false)
            withTimeout(5000) {transfers.state.first {!it.running}}
            assertEquals("cancelled",transfers.state.value.phase)
            assertEquals("original",runtime.downloadFile(name,"inbox").second.toString(Charsets.UTF_8))
            transfers.upload("inbox",name,true,true)
            withTimeout(5000) {transfers.state.first {it.proposal!=null}}
            transfers.confirm(true)
            withTimeout(15000) {transfers.state.first {it.phase=="completed"&&!it.running}}
            assertEquals("replacement",runtime.downloadFile(name,"inbox").second.toString(Charsets.UTF_8))
            transfers.delete("inbox",name,false,true)
            withTimeout(5000) {transfers.state.first {it.proposal?.kind=="delete"}}
            assertEquals("replacement",runtime.downloadFile(name,"inbox").second.toString(Charsets.UTF_8))
            transfers.confirm(true)
            withTimeout(5000) {transfers.state.first {it.phase=="completed"&&!it.running}}
            assertEquals("items",transfers.state.value.unit)
            assertFails {runtime.downloadFile(name,"inbox")}
            // Downloads on this Android target cannot use RENAME_NOREPLACE; explicit
            // overwrite approval permits atomic rename for this exclusively owned fixture.
            adb.command("-s",serial,"shell","set -C; printf original > '/storage/emulated/0/Download/$name'")
            ownsDownload=true
            transfers.upload("downloads",name,true,true)
            withTimeout(5000) {transfers.state.first {it.proposal!=null}};transfers.confirm(true)
            withTimeout(15000) {transfers.state.first {it.phase=="completed"&&!it.running}}
            assertEquals("replacement",runtime.downloadFile(name).second.toString(Charsets.UTF_8))
            transfers.delete("downloads",name,false,true)
            withTimeout(5000) {transfers.state.first {it.proposal!=null}};transfers.confirm(true)
            withTimeout(5000) {transfers.state.first {it.phase=="completed"&&!it.running}}
            adb.command("-s",serial,"shell","mkdir '${target.inboxPath}/$folder'; set -C; printf fixture > '${target.inboxPath}/$folder/child'")
            browser.refresh(true)
            val directories=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(directories.error)
            val directoryEntry=directories.entries.single{it.name==folder}
            assertEquals("directory",directoryEntry.kind)
            browser.inspect(directoryEntry,true)
            val directoryStat=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(directoryStat.error);assertEquals("directory",directoryStat.selected?.kind)
            assertNull(directoryStat.selected?.sizeBytes)
            adb.command("-s",serial,"shell","for i in $(seq 1 48); do set -C; printf fixture > '${target.inboxPath}/$folder/page-'\$i; done")
            val unicodeNames=listOf("\uE000","\uD800\uDC00")
            for(unicodeName in unicodeNames)adb.command("-s",serial,"shell","set -C; printf fixture > '${target.inboxPath}/$folder/$unicodeName'")
            browser.enter(directoryEntry,true)
            val firstPage=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(firstPage.error);assertEquals(50,firstPage.entries.size)
            assertEquals(firstPage.entries.last().name,firstPage.nextCursor)
            browser.more(true)
            val allPages=withTimeout(5000){browser.state.first{!it.loading}}
            assertNull(allPages.error);assertNull(allPages.nextCursor)
            assertEquals((listOf("child")+(1..48).map{"page-$it"}+unicodeNames).toSet(),allPages.entries.map{it.name}.toSet())
            assertEquals(51,allPages.entries.size)
            val proposal=Json.decodeFromString<FileActionProposal>(runtime.request("tool","files.delete",buildJsonObject {put("root","inbox");put("path",folder);put("recursive",true)}))
            assertTrue(proposal.directory)
            transfers.reviewPending(true)
            withTimeout(5000) {transfers.state.first {it.proposal?.path==folder}};transfers.confirm(true)
            withTimeout(5000) {transfers.state.first {it.phase=="completed"&&!it.running}}
            assertEquals(52L,transfers.state.value.bytes)
        } finally {
            browser.clear();transfers.close();scope.cancel();runtime.close()
            try { if(ownsDownload)adb.command("-s",serial,"shell","rm -f -- '/storage/emulated/0/Download/$name'") }
            finally {try{target.close()}finally{directory.deleteRecursively()}}
        }
    }

}

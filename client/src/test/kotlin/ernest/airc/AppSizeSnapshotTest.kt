package ernest.airc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class AppSizeSnapshotTest {
    private val task=TransferProgress("sizes-one","app_sizes","app-sizes",0,0,"scanning",root="apps",unit="items",dataPath="/transfers/sizes-one/data")
    private fun output(value:TransferProgress)=buildJsonObject { put("sizeTask",Json.encodeToJsonElement(value)) }.toString()
    @Test fun pollsOnlyTheOriginalTaskAndReturnsTheReportedSizes()=runTest {
        var calls=0
        val result=Json.parseToJsonElement("""{"sizesRequested":true,"apps":[{"package":"a.app","sizeBytes":42}]}""")
        val measured=awaitAppSizes(output(task),request={tool,args->
            calls++;assertEquals("task.progress",tool);assertEquals("sizes-one",args["id"]!!.jsonPrimitive.content)
            Json.encodeToString(task.copy(phase="completed",result=result))
        })
        assertEquals(1,calls);assertEquals(42L,measured!!.single().sizeBytes);assertTrue(measured.single().sizeQueried)
    }
    @Test fun rejectsForeignProgressAndWrongTaskTypes()=runTest {
        assertFailsWith<IllegalStateException> { awaitAppSizes(output(task),request={_,_->Json.encodeToString(task.copy(id="foreign",phase="completed"))}) }
        assertFailsWith<IllegalStateException> { awaitAppSizes(output(task.copy(direction="delete")),request={_,_->error("must not poll") }) }
        assertNull(awaitAppSizes("""{"apps":[]}""",request={_,_->error("must not poll")}))
    }
    @Test fun doesNotApplySizesToReinstalledAppsOrReplaceCurrentMetadata() {
        val current=listOf(InstalledApp("a.app","Current title",cacheKey="a".repeat(64),firstInstallTime=12))
        assertNull(mergeAppSizes(current,listOf(current.single().copy(cacheKey="b".repeat(64),sizeBytes=99))).single().sizeBytes)
        val merged=mergeAppSizes(current,listOf(current.single().copy(label="Old title",sizeBytes=99))).single()
        assertEquals("Current title",merged.label);assertEquals(12L,merged.firstInstallTime);assertEquals(99L,merged.sizeBytes)
        assertFailsWith<IllegalArgumentException> { mergeAppSizes(current,current+current) }
    }

    @Test fun deliversEachPartialBeforeCompletionAndKeepsUnreportedValues()=runTest {
        var calls=0
        val batches=mutableListOf<List<InstalledApp>>()
        val one=Json.parseToJsonElement("""{"sizesRequested":true,"partial":true,"processed":1,"apps":[{"package":"a.app","sizeBytes":42}]}""")
        val both=Json.parseToJsonElement("""{"sizesRequested":true,"partial":true,"processed":2,"apps":[{"package":"a.app","sizeBytes":42},{"package":"b.app","sizeBytes":12}]}""")
        val result=awaitAppSizes(output(task),request={_,_->
            calls++
            if(calls==1) Json.encodeToString(task.copy(result=one))
            else if(calls==2) Json.encodeToString(task.copy(result=both))
            else { assertEquals(listOf(1,2),batches.map { it.size });Json.encodeToString(task.copy(phase="completed",result=both)) }
        },onPartial={batches+=it})
        assertEquals(2,result!!.size)
        val current=listOf(InstalledApp("a.app",sizeBytes=9),InstalledApp("b.app",sizeBytes=8))
        val merged=mergeAppSizes(current,batches.first(),complete=false)
        assertEquals(42L,merged[0].sizeBytes);assertEquals(8L,merged[1].sizeBytes)
    }
}

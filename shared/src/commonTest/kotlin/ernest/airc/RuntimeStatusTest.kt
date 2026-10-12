package ernest.airc

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeStatusTest {
    private val legacy = """{"version":"0.1.0","protocol":1,"deviceId":"fixture","tools":[]}"""
    @Test fun legacyRuntimeWithoutUidRemainsCompatible() {
        assertNull(Json.decodeFromString<RuntimeStatus>(legacy).uid)
        assertFalse(Json.decodeFromString<RuntimeStatus>(legacy).textToolScope)
    }
    @Test fun actualRootAndShellUidsArePreserved() {
        for (uid in listOf(0, 2000)) {
            val status = Json.decodeFromString<RuntimeStatus>(legacy.dropLast(1) + ",\"uid\":$uid}")
            assertEquals(uid, status.uid)
        }
    }
    @Test fun scopedToolCapabilityMustBeExplicit() {
        val status=Json.decodeFromString<RuntimeStatus>(legacy.dropLast(1)+",\"textToolScope\":true,\"uid\":0}")
        assertTrue(status.textToolScope)
        assertEquals(0,status.uid)
    }

}

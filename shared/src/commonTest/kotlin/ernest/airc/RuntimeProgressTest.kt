package ernest.airc

import kotlinx.serialization.json.Json
import kotlin.test.*

class RuntimeProgressTest {
    @Test fun legacyProgressHasNoToolIdentifier(){
        val value=Json.decodeFromString<RuntimeProgress>("""{"id":"fixture","phase":"tool"}""")
        assertNull(value.tool);assertEquals("",value.text)
    }
    @Test fun optionalIdentifierIsDecodedSeparatelyFromVisibleText(){
        val value=Json.decodeFromString<RuntimeProgress>("""{"id":"fixture","phase":"tool","tool":"device.info","text":""}""")
        assertEquals("device.info",value.tool);assertEquals("",value.text)
    }
}

package ernest.airc

import kotlinx.serialization.json.*
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Only structured state and XML attributes are evidence, never raw XML substring matches. */
internal fun routineConditionMet(step: RoutineStep, output: String): Boolean {
    val data = Json.parseToJsonElement(output).jsonObject
    return when (step.type) {
        "wait_app" -> data["packageName"]?.jsonPrimitive?.contentOrNull == step.value
        "wait_playing" -> data["sessions"]?.jsonArray?.any {
            it.jsonObject["active"]?.jsonPrimitive?.booleanOrNull == true && it.jsonObject["state"]?.jsonPrimitive?.intOrNull == 3
        } == true
        "wait_text" -> {
            val xml = data["xml"]?.jsonPrimitive?.content ?: error("未返回可读取的界面树")
            require(xml.length <= 128 * 1024) { "界面树超出上限" }
            // Android and JVM expose different JAXP feature sets. Reject all DTD/entity
            // declarations before parsing, so no external resolution is needed on either.
            require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "界面树不允许文档类型或实体声明" }
            val factory = DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }
            val nodes = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).getElementsByTagName("node")
            (0 until nodes.length).any { index ->
                val attrs = nodes.item(index).attributes
                listOf("text", "content-desc", "resource-id").any { key -> attrs.getNamedItem(key)?.nodeValue?.contains(step.value) == true }
            }
        }
        else -> error("未知等待条件")
    }
}

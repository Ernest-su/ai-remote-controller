package ernest.airc

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/** Explicit opt-in; credentials come only from the process environment and are never printed. */
class LiveModelProviderTest {
    @Test fun directoryAndModernToolResultRoundTrip(): Unit = runBlocking {
        val url = System.getenv("AIRC_TEST_MODEL_URL")
        val key = System.getenv("AIRC_TEST_MODEL_KEY")
        val model = System.getenv("AIRC_TEST_MODEL_NAME")
        assumeTrue("Set AIRC_TEST_MODEL_URL/KEY/NAME to test an authorized provider", !url.isNullOrBlank() && !key.isNullOrBlank() && !model.isNullOrBlank())
        val profile = ModelProfile(url!!, key!!, model!!).validated(requireModel = true)
        val catalog = ModelCatalogClient()
        val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(60, TimeUnit.SECONDS).build()
        try {
            assertTrue("Requested model missing from provider catalog", profile.model in catalog.fetch(profile))
            val messages = mutableListOf<JsonElement>(
                buildJsonObject { put("role", "system"); put("content", "Call device_info for the next request. After receiving its result, answer concisely. Never simulate a tool call in text.") },
                buildJsonObject { put("role", "user"); put("content", "查询测试设备型号和系统版本。") },
            )
            val tools = Json.parseToJsonElement("""[{"type":"function","function":{"name":"device_info","description":"Read Android device information.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}]""")
            suspend fun completion(): JsonObject {
                val payload = buildJsonObject {
                    put("model", profile.model); put("messages", JsonArray(messages)); put("tools", tools)
                    put("tool_choice", "auto"); put("stream", false); put("max_tokens", 4096)
                }
                return execute(http.newCall(Request.Builder().url("${profile.baseUrl}/chat/completions")
                    .header("Authorization", "Bearer ${profile.apiKey}")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build())) { response ->
                    check(response.isSuccessful) { "Model provider HTTP ${response.code}" }
                    Json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("choices").jsonArray.first().jsonObject.getValue("message").jsonObject
                }
            }
            val first = completion()
            val calls = first["tool_calls"]?.jsonArray ?: error("Model did not return modern tool_calls")
            assertTrue(calls.isNotEmpty())
            messages.add(first)
            for (call in calls) {
                assertEquals("device_info", call.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                messages.add(buildJsonObject {
                    put("role", "tool"); put("tool_call_id", call.jsonObject.getValue("id").jsonPrimitive.content)
                    put("content", """{"ok":true,"output":"{\"model\":\"AIRC fixture\",\"sdk\":34}"}""")
                })
            }
            val answer = completion()
            assertTrue("No final content after tool result", answer["content"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true)
        } finally {
            catalog.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}

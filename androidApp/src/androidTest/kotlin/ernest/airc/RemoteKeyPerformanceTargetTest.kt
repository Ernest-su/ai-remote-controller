package ernest.airc

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

/** Opt-in: the host harness installs a disposable receiver and an isolated runtime. */
class RemoteKeyPerformanceTargetTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    @Test fun warmKeysStayFastOrderedAndRecoverWithoutAutomaticReplay() = runBlocking {
        activity.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("inputTargetHost"))
        val port = requireNotNull(args.getString("inputTargetPort")).toInt()
        val runtimePid = requireNotNull(args.getString("inputRuntimePid")).toLong()
        val connection = RuntimeConnection { "en" }
        suspend fun key(name: String): JsonObject = Json.parseToJsonElement(connection.request("tool", "remote.key", buildJsonObject { put("key", name) })).jsonObject
        try {
            connection.connect(host, port)
            val warm = key("down")
            val child = warm.getValue("bridgePid").jsonPrimitive.long
            // A slow ordinary control must not occupy the remote-key execution lane.
            val slow = async { connection.request("tool", "device.shell", buildJsonObject { put("command", "sleep 2; printf control-complete") }) }
            delay(150)
            val separateStarted = SystemClock.elapsedRealtimeNanos()
            val independent = key("left")
            val separateMs = (SystemClock.elapsedRealtimeNanos() - separateStarted) / 1_000_000.0
            assertEquals(child, independent.getValue("bridgePid").jsonPrimitive.long)
            assertEquals("control-complete", slow.await())

            val names = listOf("down", "left", "right", "up", "ok")
            val times = java.util.Collections.synchronizedList(mutableListOf<Double>())
            val injections = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val start = SystemClock.elapsedRealtimeNanos()
            val requests = mutableListOf<Deferred<Unit>>()
            repeat(300) { index ->
                val deadline = start + index * 100_000_000L
                val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                if (remaining > 0) delay(ceil(remaining / 1_000_000.0).toLong())
                requests += async {
                    val active = inFlight.incrementAndGet()
                    peak.updateAndGet { maxOf(it, active) }
                    val sent = SystemClock.elapsedRealtimeNanos()
                    try {
                        val reply = key(names[index % names.size])
                        assertEquals(child, reply.getValue("bridgePid").jsonPrimitive.long)
                        assertTrue(reply.getValue("submitted").jsonPrimitive.boolean)
                        assertTrue(reply.getValue("released").jsonPrimitive.boolean)
                        times += (SystemClock.elapsedRealtimeNanos() - sent) / 1_000_000.0
                        injections += reply.getValue("injectionMicros").jsonPrimitive.long
                    } finally { inFlight.decrementAndGet() }
                }
            }
            requests.awaitAll()
            val sorted = times.sorted()
            val p95 = sorted[ceil(sorted.size * 0.95).toInt() - 1]
            val tailMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            assertTrue(connection.connected.value)

            // Kill only this test runtime's verified child. Do not replay the failed key.
            val kill = "parent=\$(sed -n 's/^PPid:[[:space:]]*//p' /proc/$child/status); [ \"\$parent\" = $runtimePid ] && kill -9 $child && echo child-stopped"
            assertTrue(connection.request("tool", "device.shell", buildJsonObject { put("command", kill) }).contains("child-stopped"))
            val uncertain = runCatching { key("left") }.exceptionOrNull()
            assertNotNull("Lost helper acknowledgement must not cause automatic key replay", uncertain)
            val recovered = key("right")
            assertNotEquals(child, recovered.getValue("bridgePid").jsonPrimitive.long)
            assertTrue(connection.connected.value)
            instrumentation.addResults(Bundle().apply { putString("inputBenchmark", buildJsonObject {
                put("samples", sorted.size); put("rateHz", 10); put("durationSeconds", 30)
                put("p50Ms", sorted[sorted.size / 2]); put("p95Ms", p95); put("maxMs", sorted.last())
                put("peakInFlight", peak.get()); put("isolatedLaneMs", separateMs)
                put("tailMs", tailMs)
                put("injectionP95Ms", injections.sorted()[ceil(injections.size * .95).toInt() - 1] / 1000.0)
                put("recoveredBridgePid", recovered.getValue("bridgePid").jsonPrimitive.long)
            }.toString()) })
            // Preserve all measurements even when a latency threshold fails.
            assertTrue("Key waited for the slow control: $separateMs ms; injection=${independent["injectionMicros"]} us", separateMs <= 100)
            assertTrue("Warm P95 exceeded 100 ms: $p95", p95 <= 100)
            assertTrue("Input continued to accumulate after the last tap: $tailMs ms", tailMs <= 30_200)
        } finally { connection.close() }
    }
}

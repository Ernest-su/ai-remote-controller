package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class RemoteKeyControllerTest {
    private fun reply(args: JsonObject): String = buildJsonObject {
        put("pressId", args["pressId"] ?: JsonNull)
        put("operation", args["action"] ?: JsonPrimitive("key")); put("submitted", true)
        put("released", args["action"]?.jsonPrimitive?.content in listOf(null, "up", "cancel"))
    }.toString()
    private suspend fun until(condition: () -> Boolean) = withTimeout(2500) { while (!condition()) delay(5) }

    @Test fun holdHasOneDownKeepAndUpWithStableGestureId(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = CopyOnWriteArrayList<JsonObject>()
        val controller = RemoteKeyController(scope, { { _, args -> calls += args; reply(args) } })
        try {
            val id = assertNotNull(controller.down("menu"))
            assertEquals("down", calls.single()["action"]!!.jsonPrimitive.content)
            until { calls.any { it["action"]?.jsonPrimitive?.content == "keep" } }
            controller.up(id, false)
            until { calls.last()["action"]?.jsonPrimitive?.content == "up" }
            assertEquals(1, calls.count { it["action"]?.jsonPrimitive?.content == "down" })
            assertTrue(calls.all { it["pressId"]!!.jsonPrimitive.content == id })
            val count = calls.size; delay(300); assertEquals(count, calls.size)
        } finally { controller.cancelAll(); scope.cancel() }
    }

    @Test fun releaseWaitsForAcceptedDownAndCancelledQueuedPressNeverStarts(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<JsonObject>()
        val controller = RemoteKeyController(scope, { { _, args ->
            calls += args
            if (args["action"]?.jsonPrimitive?.content == "down") pending.await()
            reply(args)
        } })
        try {
            val first = assertNotNull(controller.down("right"))
            controller.up(first, false)
            val second = assertNotNull(controller.down("right"))
            controller.up(second, true)
            assertEquals(1, calls.size)
            pending.complete(Unit)
            until { calls.size == 2 }
            assertEquals(listOf("down", "up"), calls.map { it["action"]!!.jsonPrimitive.content })
            controller.up(first, true) // a stale terminal event cannot affect any subsequent press
            val third = assertNotNull(controller.down("right"))
            assertNotEquals(first, third)
            controller.up(third, false)
            until { calls.size == 4 }
            assertEquals(third, calls[2]["pressId"]!!.jsonPrimitive.content)
        } finally { pending.complete(Unit); controller.cancelAll(); scope.cancel() }
    }

    @Test fun queuedShortTapsStayOrderedAndCancelAllPreventsLateDown(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<Unit>()
        val calls = CopyOnWriteArrayList<JsonObject>()
        val controller = RemoteKeyController(scope, { { _, args ->
            calls += args
            if (calls.size == 1) pending.await()
            reply(args)
        } })
        try {
            controller.up(assertNotNull(controller.down("ok")), false)
            repeat(3) { controller.up(assertNotNull(controller.down("ok")), false) }
            pending.complete(Unit)
            until { calls.size == 5 }
            assertEquals(listOf("down", "up", null, null, null), calls.map { it["action"]?.jsonPrimitive?.content })
        } finally { pending.complete(Unit); controller.cancelAll(); scope.cancel() }
    }

    @Test fun failedOrMismatchedDownAttemptsOnlyCancelAndNeverReplays(): Unit = runBlocking {
        for (throwError in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val calls = CopyOnWriteArrayList<JsonObject>(); val errors = CopyOnWriteArrayList<String>()
            val controller = RemoteKeyController(scope, { { _, args ->
                calls += args
                if (args["action"]?.jsonPrimitive?.content == "down") {
                    if (throwError) error("lost reply") else "{}"
                } else reply(args)
            } }, errors::add)
            try {
                controller.down("menu")
                until { errors.isNotEmpty() }
                assertEquals(listOf("down", "cancel"), calls.map { it["action"]!!.jsonPrimitive.content })
            } finally { controller.cancelAll(); scope.cancel() }
        }
    }

    @Test fun cancelAllReleasesEveryHeldKeyOnOriginalWriterAndBoundsQueue(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<Unit>()
        var target = "old"
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val controller = RemoteKeyController(scope, {
            val bound = target
            { _: String, args: JsonObject ->
                calls += bound to args
                if (args["action"]?.jsonPrimitive?.content == "down") pending.await()
                reply(args)
            }
        })
        try {
            repeat(16) { assertNotNull(controller.down(if (it == 0) "menu" else "right")) }
            assertNull(controller.down("left"))
            target = "new"
            controller.cancelAll()
            pending.complete(Unit)
            until { calls.count { it.second["action"]?.jsonPrimitive?.content == "cancel" } == 2 }
            assertTrue(calls.all { it.first == "old" })
            assertEquals(2, calls.count { it.second["action"]?.jsonPrimitive?.content == "down" })
        } finally { pending.complete(Unit); controller.cancelAll(); scope.cancel() }
    }
}

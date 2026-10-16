package ernest.airc

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LanConnectionTest {
    private val target = ManualConnectionTarget("192.0.2.15", 5555, 9876)

    @Test fun healthyServiceDoesNotDeployOrRequestManualConnection() = runBlocking {
        connectLanOrDeploy(target, {}, { fail("unexpected deployment") },
            { fail("unexpected progress") }, { fail("unexpected manual connection") })
    }

    @Test fun serviceFailureDeploysWithAddressAndAuthorizationHint() = runBlocking {
        val events = mutableListOf<String>()
        connectLanOrDeploy(target, { error("service unavailable") }, { events += "deploy" },
            { events += it }, { fail("unexpected manual connection") })
        assertEquals(2, events.size)
        assertTrue(events[0].contains("192.0.2.15:5555"))
        assertTrue(events[0].contains("授权"))
        assertEquals("deploy", events[1])
    }

    @Test fun deploymentTimeoutRequestsManualConnectionAndReportsFailure() = runBlocking {
        var manual: ManualConnectionTarget? = null
        try {
            connectLanOrDeploy(target, { error("offline") }, { withTimeout(1) { awaitCancellation() } }, {}, { manual = it })
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("自动部署失败"))
            assertTrue(e.message!!.contains("192.0.2.15:5555"))
        }
        assertEquals(target, manual)
    }

    @Test fun explicitCancellationDoesNotDeployOrPrefill() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            connectLanOrDeploy(target, { started.complete(Unit); awaitCancellation() },
                { fail("unexpected deployment") }, {}, { fail("unexpected manual connection") })
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun cancellationDuringDeploymentDoesNotRequestManualConnection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch {
            connectLanOrDeploy(target, { error("offline") }, { started.complete(Unit); awaitCancellation() },
                {}, { fail("unexpected manual connection") })
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun sameVersionWithDifferentBytesTriggersUpgradeBeforeConnectionCanBeUsed() = runBlocking {
        val expected = RuntimeRelease("0.1.0", 1, listOf(RuntimeAsset("arm64-v8a", "runtime", "a".repeat(64), 1)))
        var actual = RuntimeStatus("0.1.0", 1, "fixture", emptyList(), binarySha256 = "b".repeat(64))
        val events = mutableListOf<String>()
        connectLanOrDeploy(target,
            { requireRuntimeAlignment(actual, expected) },
            { events += "deploy"; actual = actual.copy(binarySha256 = "a".repeat(64)); requireRuntimeAlignment(actual, expected) },
            { events += it }, { fail("unexpected manual connection") })
        assertTrue(events[0].contains("不一致"))
        assertEquals("deploy", events[1])
        events.clear()
        connectLanOrDeploy(target, { requireRuntimeAlignment(actual, expected) }, { fail("already aligned") }, {}, {})
        assertTrue(events.isEmpty())
    }

    @Test fun failedUpgradeDoesNotAcceptTheOldService() = runBlocking {
        var manual: ManualConnectionTarget? = null
        val old = RuntimeStatus("0.1.0", 1, "fixture", emptyList())
        assertThrows(IllegalStateException::class.java) { runBlocking {
            connectLanOrDeploy(target, { throw RuntimeAlignmentRequired(old, "0.1.0") },
                { error("ADB authorization denied") }, {}, { manual = it })
        } }
        assertEquals(target, manual)
    }

}

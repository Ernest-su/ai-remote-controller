package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ModelProfilesTest {
    @Test fun selectingAndDeletingUseTheSameCanonicalAddressAsSaving(): Unit = runBlocking {
        val store = MemoryStore().apply {
            snapshot = ModelProfileSnapshot(listOf(ModelProfile("https://api.test/v1", "fixture-key", "Model", listOf("Cached"))), "https://api.test/v1")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ModelProfileController(store, ModelCatalog { error("No automatic fetch") }, scope)
            controller.select(" https://api.test/v1/ ")
            assertEquals("Model", controller.state.value.draft.model)
            assertEquals(listOf("Cached"), controller.state.value.draft.models)
            controller.delete("https://api.test/v1/")
            withTimeout(3000) { controller.state.first { it.profiles.isEmpty() } }
            assertTrue(controller.state.value.draft.baseUrl.isEmpty())
            assertNull(store.snapshot.selectedUrl)
        } finally { scope.cancel() }
    }

    @Test fun queuedSaveCannotResurrectAConfigurationDeletedAfterIt(): Unit = runBlocking {
        val queue = java.util.concurrent.ConcurrentLinkedDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.addLast(block) }
        }
        val saved = java.util.concurrent.atomic.AtomicReference(ModelProfileSnapshot())
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val store = object : ModelProfileStore {
            override fun read() = saved.get()
            override fun write(snapshot: ModelProfileSnapshot) { saved.set(snapshot); writes.incrementAndGet() }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val controller = ModelProfileController(store, ModelCatalog { emptyList() }, scope)
            controller.editUrl("https://api.test/v1"); controller.editKey("fixture-key"); controller.editModel("Model")
            controller.save()
            controller.delete("https://api.test/v1")
            withTimeout(5000) {
                while(writes.get()<2 || queue.isNotEmpty()) {
                    queue.pollLast()?.run()
                    delay(10)
                }
            }
            assertTrue(saved.get().profiles.isEmpty())
            assertNull(saved.get().selectedUrl)
            assertTrue(controller.state.value.draft.baseUrl.isEmpty())
        } finally { scope.cancel(); while(queue.isNotEmpty()) queue.pollLast()?.run() }
    }

    @Test fun changingModelWhileSaveIsWritingPreservesNewDraft() = verifyConcurrentModelEdit(false)
    @Test fun changingModelWhileCacheIsWritingPreservesNewDraft() = verifyConcurrentModelEdit(true)
    private fun verifyConcurrentModelEdit(fetch: Boolean): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val store = object : ModelProfileStore {
            var snapshot = ModelProfileSnapshot()
            override fun read() = snapshot
            override fun write(snapshot: ModelProfileSnapshot) {
                entered.complete(Unit)
                release.await()
                this.snapshot = snapshot
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ModelProfileController(store, ModelCatalog { listOf("Fetched") }, scope)
            controller.editUrl("https://api.test/v1"); controller.editKey("fixture-key"); controller.editModel("Old")
            if(fetch) controller.fetch() else controller.save()
            withTimeout(3000) { entered.await() }
            controller.editModel("New")
            release.countDown()
            withTimeout(3000) { controller.state.first { it.profiles.isNotEmpty() && !it.fetching } }
            assertEquals("Old", controller.state.value.profiles.single().model)
            assertEquals("New", controller.state.value.draft.model)
            if(fetch) assertEquals(listOf("Fetched"), controller.state.value.draft.models)
            controller.save()
            withTimeout(3000) { controller.state.first { it.profiles.single().model == "New" } }
        } finally { release.countDown(); scope.cancel() }
    }

    private class MemoryStore : ModelProfileStore {
        var snapshot = ModelProfileSnapshot()
        override fun read() = snapshot
        override fun write(snapshot: ModelProfileSnapshot) { this.snapshot = snapshot }
    }

    @Test fun urlsAndCredentialsAreValidatedWithoutLeakingKeys() {
        assertEquals("https://api.test/v1", canonicalModelUrl(" https://api.test/v1/ "))
        for (url in listOf("oops", "file:///tmp/key", "https://secret@api.test/v1", "https://api.test/v1?token=x")) {
            assertThrows(IllegalArgumentException::class.java) { canonicalModelUrl(url) }
        }
        assertFalse(ModelProfile(apiKey = "fixture-secret").toString().contains("fixture-secret"))
    }

    @Test fun saveSwitchDeleteAndRestartKeepIndependentProfilesAndCaches() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ModelProfileController(store, ModelCatalog { listOf("Model-A", "Model-B") }, scope)
            controller.editUrl("https://one.test/v1/"); controller.editKey("key-one"); controller.editModel("manual-one")
            controller.fetch()
            withTimeout(5000) { controller.state.first { !it.fetching && it.profiles.size == 1 } }
            controller.select(null)
            controller.editUrl("https://two.test/v1"); controller.editKey("key-two"); controller.editModel("manual-two"); controller.save()
            withTimeout(5000) { controller.state.first { it.profiles.size == 2 } }
            controller.select("https://one.test/v1")
            assertEquals("key-one", controller.state.value.draft.apiKey)
            assertEquals(listOf("Model-A", "Model-B"), controller.state.value.draft.models)
            controller.editKey("changed-key")
            assertTrue(controller.state.value.draft.models.isEmpty())
            controller.select("https://two.test/v1")
            controller.delete("https://one.test/v1")
            withTimeout(5000) { controller.state.first { it.profiles.size == 1 } }
            val restarted = ModelProfileController(store, ModelCatalog { error("must not auto-fetch") }, scope)
            assertEquals("key-two", restarted.state.value.draft.apiKey)
            assertEquals("manual-two", restarted.state.value.draft.model)
        } finally { scope.cancel() }
    }

    @Test fun failedRefreshRetainsCachedModelsAndDeletingCancelsPendingRefresh() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failFetch = false
        var pending: CompletableDeferred<List<String>>? = null
        try {
            val controller = ModelProfileController(store, ModelCatalog {
                if (failFetch) error("provider error containing fixture-secret")
                pending?.await() ?: listOf("Cached-Model")
            }, scope)
            controller.editUrl("https://api.test/v1"); controller.editKey("fixture-secret")
            controller.fetch()
            withTimeout(5000) { controller.state.first { !it.fetching && it.profiles.isNotEmpty() } }
            failFetch = true; controller.fetch()
            withTimeout(5000) { controller.state.first { !it.fetching } }
            assertEquals(listOf("Cached-Model"), controller.state.value.draft.models)
            assertFalse(controller.state.value.message.contains("fixture-secret"))
            controller.editUrl("https://api.test/v1/")
            failFetch = false; pending = CompletableDeferred(); controller.fetch()
            controller.delete("https://api.test/v1")
            withTimeout(5000) { controller.state.first { it.profiles.isEmpty() } }
            pending!!.complete(listOf("Stale-Model")); yield()
            assertTrue(store.snapshot.profiles.isEmpty())
            assertTrue(controller.state.value.draft.models.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun modelCatalogUsesExactEndpointAndRejectsRedirectsAndMalformedBodies() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val profile = ModelProfile(server.url("/nested/v1/").toString(), "fixture-key")
            val client = ModelCatalogClient()
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"B"},{"id":"A"},{"id":"B"}]}"""))
            assertEquals(listOf("A", "B"), client.fetch(profile))
            val request = server.takeRequest()
            assertEquals("/nested/v1/models", request.path)
            assertEquals("Bearer fixture-key", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/models"))
            try { client.fetch(profile); fail("redirect accepted") } catch (_: IllegalStateException) { }
            server.enqueue(MockResponse().setBody("""{"data":[{"id":""}]}"""))
            try { client.fetch(profile); fail("invalid model accepted") } catch (_: IllegalStateException) { }
        } finally { server.shutdown() }
    }

    @Test fun cacheFetchAndLocalSaveNeverSyncUntilRequested() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var sent = 0
        try {
            val controller = ModelProfileController(MemoryStore(), ModelCatalog { listOf("Model-A") }, scope)
            controller.editUrl("https://api.test/v1"); controller.editKey("key"); controller.editModel("manual")
            controller.fetch()
            withTimeout(5000) { controller.state.first { !it.fetching && it.profiles.isNotEmpty() } }
            assertEquals(0, sent)
            controller.sync(false) { sent++ }
            assertEquals(0, sent)
            controller.sync(true) { sent++; assertEquals("manual", it.model) }
            withTimeout(5000) { controller.state.first { !it.syncing } }
            assertEquals(1, sent)
        } finally { scope.cancel() }
    }
}

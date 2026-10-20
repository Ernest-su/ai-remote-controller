package ernest.airc

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import okhttp3.*
import okhttp3.mockwebserver.*
import kotlinx.coroutines.flow.first

class FileBrowserControllerTest {
    @Test fun breadcrumbsOnlyNavigateToAncestorsAndPreserveCacheOnFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val paths = mutableListOf<String>()
        var fail = false
        val browser = FileBrowserController(scope) { _, args ->
            val path = args["path"]!!.jsonPrimitive.content
            paths += path
            if (fail) error("fixture unavailable")
            """{"root":"downloads","path":"$path","entries":[{"name":"child","kind":"directory"}]}"""
        }
        try {
            browser.enter(TargetFileEntry("one", "directory"), true)
            browser.enter(TargetFileEntry("two", "directory"), true)
            val before = paths.size
            listOf("../escape", "one/two/child", "on", "other", "one/two").forEach { browser.ancestor(it, true) }
            browser.ancestor("", false)
            assertEquals(before, paths.size)
            fail = true
            browser.ancestor("one", true)
            assertEquals("one/two", browser.state.value.path)
            assertEquals("child", browser.state.value.entries.single().name)
            assertNotNull(browser.state.value.error)
            fail = false
            browser.ancestor("one", true)
            assertEquals("one", browser.state.value.path)
            browser.ancestor("", true)
            assertEquals("", browser.state.value.path)
        } finally { browser.clear(); scope.cancel() }
    }

    @Test fun pagingRejectsOverlapAndUsesUtf8RatherThanUtf16Order()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var payload="""{"root":"downloads","path":"","entries":[{"name":"b","kind":"file"}],"nextCursor":"b"}"""
        val browser=FileBrowserController(scope){_,_->payload}
        try {
            browser.refresh(true)
            payload="""{"root":"downloads","path":"","entries":[{"name":"b","kind":"file"}]}"""
            browser.more(true)
            assertNotNull(browser.state.value.error);assertEquals(listOf("b"),browser.state.value.entries.map{it.name})
            payload="""{"root":"downloads","path":"","entries":[{"name":"z","kind":"file"},{"name":"a","kind":"file"}]}"""
            browser.refresh(true);assertNotNull(browser.state.value.error)
            val bmp="\uE000";val supplementary="\uD800\uDC00"
            payload="""{"root":"downloads","path":"","entries":[{"name":"$bmp","kind":"file"},{"name":"$supplementary","kind":"file"}]}"""
            browser.refresh(true)
            assertNull(browser.state.value.error)
            assertEquals(listOf(bmp,supplementary),browser.state.value.entries.map{it.name})
        }finally{browser.clear();scope.cancel()}
    }

    @Test fun continuationCursorMustMatchTheLastEntry() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var payload="""{"root":"downloads","path":"","entries":[],"nextCursor":"missing"}"""
        val browser=FileBrowserController(scope){_,_->payload}
        try {
            browser.refresh(true)
            assertNotNull(browser.state.value.error);assertFalse(browser.state.value.loaded)
            payload="""{"root":"downloads","path":"","entries":[{"name":"a","kind":"file"}],"nextCursor":"z"}"""
            browser.refresh(true)
            assertNotNull(browser.state.value.error);assertFalse(browser.state.value.loaded)
            payload="""{"root":"downloads","path":"","entries":[{"name":"a","kind":"file"}],"nextCursor":"a"}"""
            browser.refresh(true)
            assertNull(browser.state.value.error);assertTrue(browser.state.value.loaded)
            assertEquals("a",browser.state.value.nextCursor)
        }finally{browser.clear();scope.cancel()}
    }

    @Test fun mismatchedStatKindAndNegativeSizeAreRejected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var payload = """{"root":"downloads","path":"a","kind":"file","sizeBytes":10}"""
        val browser = FileBrowserController(scope) { _, _ -> payload }
        try {
            val entry = TargetFileEntry("a", "file")
            browser.inspect(entry, true)
            assertEquals(10L, browser.state.value.selected?.sizeBytes)
            for (invalid in listOf(
                """{"root":"downloads","path":"a","kind":"directory"}""",
                """{"root":"downloads","path":"a","kind":"file","sizeBytes":-1}"""
            )) {
                payload = invalid
                browser.inspect(entry, true)
                assertFalse(browser.state.value.loading)
                assertNotNull(browser.state.value.error)
                assertNull(browser.state.value.selected)
            }
        } finally { browser.clear(); scope.cancel() }
    }

    @Test fun malformedEntriesRejectWholePageAndPreserveExistingCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var entries = """[{"name":"safe","kind":"file","sizeBytes":1}]"""
        val browser = FileBrowserController(scope) { _, _ ->
            """{"root":"downloads","path":"","entries":$entries}"""
        }
        try {
            browser.refresh(true)
            for (invalid in listOf(
                """[{"name":"../bad","kind":"file"}]""",
                """[{"name":"bad","kind":"unknown"}]""",
                """[{"name":"bad","kind":"file","sizeBytes":-1}]""",
                """[{"name":"same","kind":"file"},{"name":"same","kind":"directory"}]"""
            )) {
                entries = invalid
                browser.refresh(true)
                assertFalse(browser.state.value.loading)
                assertNotNull(browser.state.value.error)
                assertEquals(listOf("safe"), browser.state.value.entries.map { it.name })
            }
            entries = """[{"name":"link","kind":"symlink"},{"name":"special","kind":"other"}]"""
            browser.refresh(true)
            assertNull(browser.state.value.error)
            assertEquals(listOf("link", "special"), browser.state.value.entries.map { it.name })
        } finally { browser.clear(); scope.cancel() }
    }

    @Test fun cancelledListStatAndCreationAllowRetry() = runBlocking {
        for (operation in listOf("files.list", "files.stat", "files.mkdir")) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var cancelled = false
            val browser = FileBrowserController(scope) { tool, _ ->
                if (tool == operation && !cancelled) {
                    cancelled = true
                    throw CancellationException("owned request cancelled")
                }
                when (tool) {
                    "files.stat" -> """{"root":"downloads","path":"a","kind":"file","sizeBytes":10}"""
                    "files.mkdir" -> """{"root":"downloads","path":"a","created":true}"""
                    else -> """{"root":"downloads","path":"","entries":[]}"""
                }
            }
            fun retry() {
                when (operation) {
                    "files.list" -> browser.refresh(true)
                    "files.stat" -> browser.inspect(TargetFileEntry("a", "file"), true)
                    else -> browser.createDirectory("a", true)
                }
            }
            try {
                retry()
                assertTrue(cancelled)
                assertFalse(browser.state.value.loading)
                assertNull(browser.state.value.error)
                retry()
                assertFalse(browser.state.value.loading)
                assertNull(browser.state.value.error)
                if (operation == "files.stat") assertEquals(10L, browser.state.value.selected?.sizeBytes)
                else assertTrue(browser.state.value.loaded)
            } finally { browser.clear(); scope.cancel() }
        }
    }

    @Test fun runtimeBrowserUsesBoundSocketForDirectoryQuery()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val runtime=RuntimeConnection();val server=MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":"fixture","protocol":1,"deviceId":"fixture","tools":[]}"""))
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onMessage(webSocket:WebSocket,text:String){
            val message=Json.parseToJsonElement(text).jsonObject
            val result="""{"root":"downloads","path":"","entries":[],"nextCursor":null}"""
            webSocket.send(buildJsonObject{put("id",message["id"]!!);put("protocol",1);put("ok",true);put("result",result)}.toString())
        }}));server.start()
        var fallback=0
        val browser=FileBrowserController(scope,runtime){_,_->fallback++;error("unbound callback")}
        try {
            runtime.connect(server.hostName,server.port);browser.refresh(true)
            val result=withTimeout(3000){browser.state.first{!it.loading}}
            assertNull(result.error);assertTrue(result.loaded);assertEquals(0,fallback)
        }finally{browser.clear();scope.cancel();runtime.close();server.shutdown()}
    }
    @Test fun clearingAfterCreationCancelsItsAutomaticRefresh()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val refreshing=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val browser=FileBrowserController(scope){tool,_->
            if(tool=="files.mkdir")"""{"root":"downloads","path":"new","created":true}"""
            else {refreshing.complete(Unit);try{awaitCancellation()}finally{cancelled.complete(Unit)}}
        }
        try {
            browser.createDirectory("new",true);withTimeout(1000){refreshing.await()}
            browser.clear();withTimeout(1000){cancelled.await()}
            assertFalse(browser.state.value.loading);assertTrue(browser.state.value.entries.isEmpty())
        }finally{browser.clear();scope.cancel()}
    }
    @Test fun directoryCreationValidatesNameAndRefreshesAfterMatchingSuccess()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val calls=mutableListOf<String>()
        val browser=FileBrowserController(scope){tool,args->calls+=tool
            if(tool=="files.mkdir"){assertEquals("new",args["path"]!!.jsonPrimitive.content);"""{"root":"downloads","path":"new","created":true}"""}
            else """{"root":"downloads","path":"","entries":[{"name":"new","kind":"directory"}],"nextCursor":null}"""
        }
        try {
            browser.createDirectory("new",false);browser.createDirectory("../bad",true);assertTrue(calls.isEmpty())
            browser.createDirectory("new",true);assertEquals(listOf("files.mkdir","files.list"),calls)
            assertEquals("new",browser.state.value.entries.single().name);assertNull(browser.state.value.error)
        }finally{browser.clear();scope.cancel()}
    }
    @Test fun pagingDirectoryNavigationAndStatUseControlledPaths() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val browser = FileBrowserController(scope) { tool, args ->
            calls += tool to args
            val path = args["path"]!!.jsonPrimitive.content
            when {
                tool == "files.stat" -> """{"root":"downloads","path":"a","kind":"file","sizeBytes":10}"""
                path == "b" -> """{"root":"downloads","path":"b","entries":[],"nextCursor":null}"""
                args["cursor"] != null -> """{"root":"downloads","path":"","entries":[{"name":"b","kind":"directory"}],"nextCursor":null}"""
                else -> """{"root":"downloads","path":"","entries":[{"name":"a","kind":"file","sizeBytes":1}],"nextCursor":"a"}"""
            }
        }
        try {
            browser.refresh(false); assertTrue(calls.isEmpty())
            browser.refresh(true); browser.more(true)
            assertEquals(listOf("a", "b"), browser.state.value.entries.map { it.name })
            browser.inspect(browser.state.value.entries.first(), true)
            assertEquals(10L, browser.state.value.selected?.sizeBytes)
            browser.enter(browser.state.value.entries.last(), true)
            assertEquals("b", browser.state.value.path)
            browser.parent(true); assertEquals("", browser.state.value.path)
            val before = calls.size
            browser.enter(TargetFileEntry("..", "directory"), true)
            browser.inspect(TargetFileEntry("..", "symlink"), true)
            assertEquals(before, calls.size)
            browser.clear(); assertFalse(browser.state.value.loaded)
        } finally { scope.cancel() }
    }
    @Test fun selectedFullRootUsesRelativePathsAndClearsOldCache() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val browser=FileBrowserController(scope) { _,args ->
            assertEquals("full",args["root"]!!.jsonPrimitive.content)
            """{"root":"full","path":"","entries":[]}"""
        }
        try {
            browser.selectRoot("full");assertEquals("full",browser.state.value.root)
            browser.refresh(true);assertTrue(browser.state.value.loaded)
            browser.selectRoot("inbox");assertFalse(browser.state.value.loaded)
            browser.selectRoot("invalid");assertEquals("inbox",browser.state.value.root)
        } finally {scope.cancel()}
    }
    @Test fun oldTargetReplyCannotRestoreClearedListing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val pending = CompletableDeferred<String>()
        val browser = FileBrowserController(scope) { _, _ -> withContext(NonCancellable) { pending.await() } }
        try {
            browser.refresh(true); browser.clear()
            pending.complete("""{"root":"downloads","path":"","entries":[]}""")
            yield(); assertFalse(browser.state.value.loaded); assertFalse(browser.state.value.loading)
        } finally { scope.cancel() }
    }
    @Test fun linkDirectoriesKeepLogicalBreadcrumbsAndBlockCreationUntilReturning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val browser = FileBrowserController(scope) { tool, args ->
            calls += tool
            val path = args["path"]!!.jsonPrimitive.content
            if (tool == "files.stat") """{"root":"downloads","path":"alias.txt","kind":"symlink","symlink":true,"linkTarget":"missing","linkError":"link_missing"}"""
            else """{"root":"downloads","path":"$path","readOnly":${path.isNotEmpty()},"entries":[]}"""
        }
        try {
            browser.enter(TargetFileEntry("folder", "directory", symlink = true, linkTarget = "real"), true)
            assertEquals("folder", browser.state.value.path)
            assertTrue(browser.state.value.readOnly)
            val before = calls.size
            browser.createDirectory("blocked", true)
            assertEquals(before, calls.size)
            browser.parent(true)
            assertEquals("", browser.state.value.path)
            assertFalse(browser.state.value.readOnly)
            browser.inspect(TargetFileEntry("alias.txt", "symlink", symlink = true, linkError = "link_missing"), true)
            assertEquals("link_missing", browser.state.value.selected?.linkError)
        } finally { browser.clear(); scope.cancel() }
    }

}

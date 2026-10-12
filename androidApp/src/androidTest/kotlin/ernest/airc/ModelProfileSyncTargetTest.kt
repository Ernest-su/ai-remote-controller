package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Writes a dummy model configuration only to the runner's disposable binary service. */
class ModelProfileSyncTargetTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val rules:org.junit.rules.TestRule=RuleChain.outerRule(EncryptedProfilesIsolation()).around(compose)

    @Test fun manualSyncUpdatesIndependentTargetOnlyAfterExplicitClick() {
        val arguments=InstrumentationRegistry.getArguments()
        val host=arguments.getString("fileTargetHost")
        val port=arguments.getString("fileTargetPort")?.toIntOrNull()
        assumeTrue("Disposable target service required",host!=null && port!=null)
        val probe=RuntimeConnection()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=DeviceStore(context)
        val history=store.read()
        lateinit var model:RemoteViewModel
        var deviceId:String?=null
        var initialized=false
        try {
            val initial=runBlocking { probe.status(host!!,port!!) }
            assertFalse(initial.modelConfigured)
            deviceId=initial.deviceId
            check(history.none { it.id==deviceId }) { "Refusing to modify an existing device history entry" }
            compose.runOnUiThread {
                model=ViewModelProvider(compose.activity)[RemoteViewModel::class.java];initialized=true
                model.discovered(DiscoveredService("Disposable model target",host!!,port!!,emptyMap()))
            }
            compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy }
            compose.onNodeWithTag("open-menu").performClick()
            compose.onNodeWithTag("feature-model").performClick()
            compose.onNodeWithTag("model-api-url").performScrollTo().performTextReplacement("https://model-fixture.invalid/v1")
            compose.onNodeWithTag("model-api-key").performScrollTo().performTextReplacement("fixture-target-sync-key")
            compose.onNodeWithTag("model-name").performScrollTo().performTextReplacement("fixture-target-model")
            compose.onNodeWithText("保存到 App").performScrollTo().performClick()
            compose.waitUntil(10_000) { model.profiles.state.value.profiles.size==1 }
            assertFalse(runBlocking { probe.status(host!!,port!!) }.modelConfigured)
            compose.onNodeWithText("更新到目标设备").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.profiles.state.value.syncing && model.state.value.modelConfigured }
            assertTrue(model.profiles.state.value.message.contains("更新"))
            assertTrue(runBlocking { probe.status(host!!,port!!) }.modelConfigured)
            assertEquals("fixture-target-model",EncryptedModelProfileStore(context).read().profiles.single().model)
        } finally {
            if(initialized) {
                compose.runOnUiThread { model.disconnect() }
                compose.waitUntil(10_000) { !model.state.value.connected }
            }
            deviceId?.takeIf { id->history.none { it.id==id } }?.let(store::remove)
            probe.close()
        }
    }
}

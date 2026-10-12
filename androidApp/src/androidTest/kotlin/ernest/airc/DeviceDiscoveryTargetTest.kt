package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

/** Real Wi-Fi discovery and explicit runtime connection; no pairing, deployment or remote input. */
class DeviceDiscoveryTargetTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun defaultAdbPortAndRuntimeDiscoveryShareOneDeviceRow() {
        val host = InstrumentationRegistry.getArguments().getString("expectedAdbHost")
        assumeNotNull(host)
        lateinit var model: RemoteViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[RemoteViewModel::class.java] }
        compose.onNodeWithTag("device-status").performClick()
        compose.waitUntil(30_000) {
            !model.state.value.adbScanning && host in model.state.value.adbDevices &&
                model.state.value.discovered.any { it.host == host }
        }
        compose.onAllNodesWithTag("device-row-$host").assertCountEquals(1)
        compose.onNodeWithTag("device-row-$host").performScrollTo()
            .assert(hasAnyDescendant(hasText("ADB · 5555")))
            .assert(hasAnyDescendant(hasText("局域网服务")))
        org.junit.Assert.assertFalse(model.state.value.connected)
        try {
            compose.onNode(hasText("连接") and hasAnyAncestor(hasTestTag("device-row-$host"))).performClick()
            compose.waitUntil(10_000) { model.state.value.connected && !model.state.value.busy }
            org.junit.Assert.assertEquals(5555, model.state.value.history.first { it.id == model.state.value.selected }.adbPort)
        } finally { compose.runOnUiThread { model.disconnect() } }
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("remote-page").assertIsDisplayed()
    }
}

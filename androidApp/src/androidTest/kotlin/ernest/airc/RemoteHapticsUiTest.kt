package ernest.airc

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteHapticsUiTest {
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("remote-controls", Context.MODE_PRIVATE)
    private val original = prefs.all["key-haptics"] as? Boolean
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @After fun restore() { prefs.edit().apply { if (original == null) remove("key-haptics") else putBoolean("key-haptics", original) }.commit() }

    @Test fun settingPersistsAcrossRecreationAndViewModelReload() {
        compose.runOnUiThread { ViewModelProvider(compose.activity)[RemoteViewModel::class.java].setKeyHapticsEnabled(true) }
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-settings").performScrollTo().performClick()
        compose.onNodeWithTag("remote-key-haptics").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(prefs.getBoolean("key-haptics", true))
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("remote-key-haptics").performScrollTo().assertIsOff()
        compose.runOnUiThread {
            val app = compose.activity.application
            val store = androidx.lifecycle.ViewModelStore()
            val restored = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(app))
            val model = restored[RemoteViewModel::class.java]
            assertFalse(model.state.value.keyHapticsEnabled)
            model.setKeyHapticsEnabled(true)
            assertTrue(prefs.getBoolean("key-haptics", false))
            store.clear()
        }
    }
}

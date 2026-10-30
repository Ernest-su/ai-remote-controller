package ernest.airc

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class ApplicationSortingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sortReadsSizesOncePreservesChoiceAcrossRefreshAndRestoreAndWorksOffline() {
        var connected by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var apps by mutableStateOf((0..39).map { InstalledApp("fixture.app$it", "App"+it.toString().padStart(2,'0'), false,
            firstInstallTime=1000L+it, sizeBytes=null) })
        var reads=0
        var sizing by mutableStateOf(false)
        val restoration=StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) { RemoteTheme(darkTheme=dark) {
            Box(Modifier.width(320.dp).fillMaxHeight().background(RemoteColors.Background).testTag("apps-sorting-review")) { ApplicationsPanel(apps,connected,false,null,"",{},{},
            sizing=sizing,onReadSizes={ reads++;sizing=true;apps=apps.mapIndexed { i,app->app.copy(sizeBytes=if (i==39) 39L*1024 else null,sizeQueried=true) } }) } } } }
        fun choose(value:String) {
            compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("apps-sort"))
            compose.onNodeWithTag("apps-sort").performClick()
            compose.onNodeWithTag("apps-sort-$value").performClick()
        }
        choose("size")
        compose.waitUntil(5000){reads==1}
        compose.onNodeWithText("正在统计应用大小，已获取 1/40，可继续浏览").assertExists()
        compose.onNodeWithTag("apps-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("app-row-fixture.app39"))
        compose.onNodeWithTag("app-sort-value-fixture.app39", useUnmergedTree = true).assertTextEquals("39 KB")
        compose.onNodeWithTag("app-open-fixture.app39").assertIsEnabled()
        compose.runOnIdle { apps=apps.map { if(it.packageName=="fixture.app38")it.copy(sizeBytes=128L*1024) else it } }
        compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("app-row-fixture.app38"))
        compose.onNodeWithTag("app-sort-value-fixture.app38",useUnmergedTree=true).assertTextEquals("128 KB")
        compose.onNodeWithTag("app-sort-value-fixture.app39",useUnmergedTree=true).assertTextEquals("39 KB")
        compose.runOnIdle { sizing=false;apps=apps.mapIndexed { i,app->app.copy(sizeBytes=if(i==0)null else i.toLong()*1024) } }
        choose("size") // Reading unknown values cannot create an endless query loop.
        compose.runOnIdle { assertEquals(1,reads) }
        compose.onNodeWithTag("apps-sort-direction").performClick().assertTextEquals("升序")
        compose.runOnIdle { apps=apps.map { it.copy(label=it.label+" refreshed") } }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("apps-sort").assertTextEquals("排序：大小")
        compose.onNodeWithTag("apps-sort-direction").assertTextEquals("升序")
        for (mode in listOf(false,true)) {
            compose.runOnIdle { dark=mode }
            compose.onNodeWithTag("apps-page").performScrollToNode(hasTestTag("apps-sort"))
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("app-row-fixture.app1").assertIsDisplayed()
            val image=compose.onNodeWithTag("apps-sorting-review").captureToImage().asAndroidBitmap()
            val directory=InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            File(directory,"apps-sort-${if(mode) "dark" else "light"}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
        }
        compose.runOnIdle { connected=false }
        choose("installed")
        compose.onNodeWithTag("apps-sort-direction").assertTextEquals("降序")
        choose("name")
        compose.onNodeWithTag("apps-sort-direction").assertTextEquals("升序")
        compose.runOnIdle { assertEquals(1,reads) }
    }
}

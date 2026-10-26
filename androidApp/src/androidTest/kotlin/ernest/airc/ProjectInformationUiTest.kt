package ernest.airc

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectInformationUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(DeviceHistoryIsolation()).around(compose)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun aboutShowsBuildVersionAndRepositoryAndReturnsThroughMenu() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-about").performScrollTo().performClick()
        compose.onNodeWithText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        compose.onNodeWithText(ProjectLinks.REPOSITORY).assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("about-page").assertIsDisplayed()
        compose.onNodeWithTag("device-status").performClick()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("about-page").assertIsDisplayed()
        compose.onNodeWithTag("about-repository").performScrollTo().performClick()
        val web = resumedBrowser()
        instrumentation.runOnMainSync {
            assertEquals(ProjectLinks.REPOSITORY, web.intent.getStringExtra("url"))
            web.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithTag("about-page").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }

    @Test fun helpOpensActualPagesInsideAppAndBrowserRestoresHistoryAndRetries() {
        compose.onNodeWithTag("open-menu").performClick()
        compose.onNodeWithTag("feature-help").performScrollTo().performClick()
        compose.onNodeWithTag("help-pages").performScrollTo().performClick()
        val web = resumedBrowser()
        compose.onNodeWithTag("project-web-page").assertIsDisplayed()
        var lastBrowserState = ""
        compose.waitUntil(45_000) {
            var loaded = false
            instrumentation.runOnMainSync {
                val current = "${web.browser?.url} progress=${web.browser?.progress} title=${web.browser?.title}"
                if (current != lastBrowserState) { println("Pages browser: $current"); lastBrowserState = current }
                loaded = web.browser?.progress == 100 && web.browser?.title?.contains("AIRC") == true
            }
            loaded
        }
        instrumentation.runOnMainSync { web.browser!!.loadUrl(ProjectLinks.DOCUMENTATION + "getting-started/") }
        compose.waitUntil(45_000) {
            var loaded = false
            instrumentation.runOnMainSync { loaded = web.browser?.progress == 100 && web.browser?.title?.contains("快速开始") == true }
            loaded
        }
        // Recreate the actual browsed Activity; its saved WebView history must survive.
        instrumentation.runOnMainSync { web.recreate() }
        val restored = resumedBrowser(excluding = web)
        compose.waitUntil(15_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = restored.browser?.canGoBack() == true }
            ready
        }
        compose.onNodeWithTag("web-back").performClick()
        compose.waitUntil(15_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = restored.browser?.url == ProjectLinks.DOCUMENTATION }
            ready
        }
        instrumentation.runOnMainSync { restored.browser!!.loadUrl("https://127.0.0.1:1/unreachable") }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("web-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("web-retry").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("web-retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("web-close").performClick()
        compose.onNodeWithTag("help-page").assertIsDisplayed()
        compose.onNodeWithTag("navigate-back").performClick()
        compose.onNodeWithTag("feature-menu").assertIsDisplayed()
    }

    private fun resumedBrowser(excluding: ProjectWebActivity? = null): ProjectWebActivity {
        var result: ProjectWebActivity? = null
        compose.waitUntil(15_000) {
            instrumentation.runOnMainSync {
                result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ProjectWebActivity>().firstOrNull { it !== excluding }
            }
            result != null
        }
        return checkNotNull(result)
    }
}

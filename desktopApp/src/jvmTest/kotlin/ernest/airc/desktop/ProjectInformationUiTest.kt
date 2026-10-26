package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProjectInformationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aboutShowsPackagedVersionAndOpensExactRepository() {
        val version = checkNotNull(javaClass.getResourceAsStream("/airc-version.txt")).bufferedReader().use { it.readText() }
        assertTrue(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+ \\([0-9]+\\)")))
        var opened = false
        compose.setContent { RemoteTheme { Box(Modifier.width(320.dp).height(600.dp)) { ProjectAbout(version) { opened = true } } } }
        compose.onNodeWithText(version).assertExists()
        compose.onNodeWithText(ProjectLinks.REPOSITORY).assertExists()
        compose.onNodeWithTag("about-repository").performScrollTo().performClick()
        assertTrue(opened)
        val navigation = DesktopNavigation()
        navigation.navigate(RemotePage.ABOUT); navigation.navigate(RemotePage.DEVICES); navigation.back()
        assertEquals(RemotePage.ABOUT, navigation.page)
        navigation.back(); assertEquals(RemotePage.MENU, navigation.page)
    }

    @Test fun helpOpensProjectDocumentation() {
        var opened: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened = uri } }) {
                RemoteTheme { Box(Modifier.width(320.dp).height(600.dp)) { DesktopHelp() } }
            }
        }
        compose.onNodeWithTag("help-pages").performScrollTo().performClick()
        assertEquals(ProjectLinks.DOCUMENTATION, opened)
    }
}

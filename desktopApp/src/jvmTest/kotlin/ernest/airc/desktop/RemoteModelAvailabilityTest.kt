package ernest.airc.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import ernest.airc.*
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class RemoteModelAvailabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modelStatusControlsComposerAndPreservesHiddenDraft() {
        val directory = Files.createTempDirectory("airc-model-ui-")
        val controller = DesktopController(store = DesktopProfileStore(directory))
        var configured by mutableStateOf(false)
        try {
            compose.setContent { RemoteTheme {
                Box(Modifier.size(390.dp, 844.dp)) {
                    DesktopRemoteScreen(DesktopState(modelConfigured = configured), controller, {})
                }
            } }
            compose.onNodeWithTag("remote-command-input").assertDoesNotExist()
            compose.onNodeWithTag("remote-send-command").assertDoesNotExist()
            compose.onNodeWithTag("remote-model-required").assertIsDisplayed().assertHasNoClickAction()
            compose.runOnIdle { configured = true }
            compose.onNodeWithTag("remote-model-required").assertDoesNotExist()
            compose.onNodeWithTag("remote-command-input").performTextInput("保留模型草稿")
            compose.onNodeWithTag("remote-send-command").assertIsNotEnabled()
            compose.runOnIdle { configured = false }
            compose.onNodeWithTag("remote-command-input").assertDoesNotExist()
            compose.runOnIdle { configured = true }
            compose.onNodeWithTag("remote-command-input").assertTextContains("保留模型草稿")
        } finally {
            controller.close()
            directory.toFile().deleteRecursively()
        }
    }
}

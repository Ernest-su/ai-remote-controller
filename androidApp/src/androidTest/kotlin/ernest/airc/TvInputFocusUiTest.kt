package ernest.airc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TvInputFocusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun focusEntryRequiresClickAndFollowsReadinessWithoutDiscardingDraft() {
        var ready by mutableStateOf(false)
        var opened by mutableStateOf(false)
        var input by mutableStateOf(TvInputState(draft = "待发送中文"))
        var sends = 0
        val directions = mutableListOf<String>()
        compose.setContent { RemoteTheme {
            Column(Modifier.width(320.dp).height(640.dp).verticalScroll(rememberScrollState())) {
                TvInputFocusPrompt(ready && !opened, { opened = true })
                if (opened) TvInputContent(input, "测试电视", true, { input = input.copy(draft = it) }, {}, { sends++ },
                    {}, {}, {}, {}, {}, {}, {}, direction = { directions += it }, requestEditorFocus = true)
            }
        } }
        compose.onNodeWithTag("tv-input-focus-prompt").assertDoesNotExist()
        compose.runOnIdle { ready = true }
        compose.onNodeWithTag("tv-input-focus-prompt").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("tv-input-draft").assertDoesNotExist()
        File(compose.activity.getExternalFilesDir(null), "tv-input-focus-entry.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("tv-input-focus-prompt").performClick()
        compose.onNodeWithTag("tv-input-draft").assertIsFocused().assertTextContains("待发送中文")
        compose.runOnIdle { assertEquals(0, sends) }
        compose.onNodeWithText("向右").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("right"), directions); opened = false; ready = false }
        compose.onNodeWithTag("tv-input-focus-prompt").assertDoesNotExist()
        compose.runOnIdle { assertEquals("待发送中文", input.draft) }
    }
}

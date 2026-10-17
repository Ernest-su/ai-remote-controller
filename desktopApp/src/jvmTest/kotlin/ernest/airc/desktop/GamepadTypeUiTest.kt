package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.ui.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import androidx.compose.ui.test.junit4.createComposeRule

class GamepadTypeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingTypeOfflineChangesLayoutAndSelectedMenuItem() {
        var state by mutableStateOf(VirtualGamepadState())
        compose.setContent { RemoteTheme { Column(Modifier.requiredSize(640.dp, 300.dp)) {
            GamepadTypeSelector(state) { state = state.copy(type = it, input = VirtualGamepadInput()) }
            Box(Modifier.weight(1f)) { GamepadPanel(state, false) {} }
        } } }
        compose.onNodeWithTag("gamepad-type-selector").performClick()
        compose.onNodeWithTag("gamepad-type-DUAL_STICK").assertIsSelected()
        compose.onNodeWithTag("gamepad-type-NES").performClick()
        compose.onNodeWithTag("virtual-stick-left").assertDoesNotExist()
        compose.onNodeWithTag("virtual-button-x").assertDoesNotExist()
        compose.onNodeWithTag("virtual-button-dpad-right").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("virtual-button-a").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("gamepad-type-selector").performClick()
        compose.onNodeWithTag("gamepad-type-NES").assertIsSelected()
        compose.onNodeWithTag("gamepad-type-DUAL_STICK").performClick()
        compose.onNodeWithTag("virtual-stick-left").assertIsDisplayed()
        compose.onNodeWithTag("gamepad-dpad").assertDoesNotExist()
    }

    @Test fun nesDiagonalJumpAndOppositeDirectionReleaseRemainIndependent() {
        var state by mutableStateOf(VirtualGamepadState(type = GamepadType.NES, running = true))
        compose.setContent { RemoteTheme { Box(Modifier.requiredSize(640.dp, 240.dp)) {
            GamepadPanel(state, true) { transform -> state = state.copy(input = transform(state.input)) }
        } } }
        val page = compose.onNodeWithTag("gamepad-page")
        val origin = page.fetchSemanticsNode().boundsInRoot.topLeft
        fun position(tag: String) = compose.onNodeWithTag("virtual-button-$tag").fetchSemanticsNode().boundsInRoot.center - origin
        val right = position("dpad-right"); val up = position("dpad-up")
        val left = position("dpad-left"); val jump = position("a")
        page.performTouchInput { down(0, right); down(1, jump); down(2, up) }
        compose.runOnIdle {
            assertEquals(1, state.input.hatX); assertEquals(-1, state.input.hatY)
            assertEquals(listOf("a"), state.input.buttons); assertEquals(0.0, state.input.leftX, .001)
        }
        page.performTouchInput { down(3, left) }
        compose.runOnIdle { assertEquals(0, state.input.hatX) }
        page.performTouchInput { up(3) }
        compose.runOnIdle { assertEquals(1, state.input.hatX) }
        page.performTouchInput { up(0); up(1); up(2) }
        compose.runOnIdle { assertEquals(VirtualGamepadInput(), state.input) }
    }

    @Test fun nesFitsNarrowLargeTextAndShortLandscape() {
        var narrow by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                RemoteTheme(darkTheme = narrow) { Box(Modifier.requiredSize(if (narrow) 320.dp else 640.dp, if (narrow) 480.dp else 240.dp)) {
                    GamepadPanel(VirtualGamepadState(type = GamepadType.NES), false) {}
                } }
            }
        }
        for (value in listOf(false, true)) {
            compose.runOnIdle { narrow = value }
            val page = compose.onNodeWithTag("gamepad-page").fetchSemanticsNode().boundsInRoot
            listOf("dpad-up", "dpad-down", "dpad-left", "dpad-right", "a", "b", "select", "start").forEach {
                val minimumSize = if (it == "a" || it == "b") 72.dp else 64.dp
                val node = compose.onNodeWithTag("virtual-button-$it").assertIsDisplayed()
                    .assertWidthIsAtLeast(minimumSize).assertHeightIsAtLeast(minimumSize).fetchSemanticsNode()
                assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f)
                assertTrue(node.boundsInRoot.left >= page.left && node.boundsInRoot.right <= page.right)
            }
            val image = compose.onRoot().captureToImage()
            val pixels = image.toPixelMap()
            val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
            val folder = File("build/ui-review").apply { mkdirs() }
            ImageIO.write(output, "png", File(folder, if (value) "gamepad-nes-dark-narrow.png" else "gamepad-nes-light-landscape.png"))
        }
    }
}

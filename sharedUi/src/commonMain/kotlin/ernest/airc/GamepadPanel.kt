package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
fun GamepadPanel(state: VirtualGamepadState, available: Boolean,
    onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    Box(Modifier.fillMaxSize().testTag("gamepad-page").padding(RemoteDimensions.GamepadPagePadding)) {
        VirtualGamepadPanel(state, available, onChange)
    }
}

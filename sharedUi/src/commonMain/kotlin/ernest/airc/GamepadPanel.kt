package ernest.airc

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun GamepadPanel(state: VirtualGamepadState, available: Boolean, onStart: () -> Unit,
    onStop: () -> Unit, onChange: ((VirtualGamepadInput) -> VirtualGamepadInput) -> Unit) {
    PageContent(Modifier.testTag("gamepad-page"), contentPadding = 8.dp) {
        VirtualGamepadPanel(state, available, onStart, onStop, onChange)
    }
}

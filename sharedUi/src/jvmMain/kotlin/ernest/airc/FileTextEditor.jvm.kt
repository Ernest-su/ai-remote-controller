package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
internal actual fun PlatformFileTextInput(documentKey: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, modifier: Modifier) {
    // Do not use a TextFieldValue saver: documents must never enter saved state.
    var input by remember(documentKey) { mutableStateOf(TextFieldValue(value)) }
    if (input.text != value) input = input.copy(text = value)
    BasicTextField(input, { input = it; onValueChange(it.text) }, enabled = enabled,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = RemoteColors.Text),
        cursorBrush = SolidColor(RemoteColors.Action),
        modifier = modifier.background(RemoteColors.Surface).verticalScroll(rememberScrollState()).padding(12.dp)
            .semantics { contentDescription = "文本内容" })
}

@Composable
internal actual fun FileTextBackHandler(onBack: () -> Unit) = Unit

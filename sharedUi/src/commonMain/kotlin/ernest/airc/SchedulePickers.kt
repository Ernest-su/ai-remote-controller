package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

expect fun nextScheduleOnceTime(time: String, targetLocalTime: String, elapsedSeconds: Long = 0): String?

@Composable
internal fun ScheduleDropdown(label: String, value: String, choices: List<Pair<String, String>>, tag: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth
            OutlinedButton({ expanded = true }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
                Text(choices.first { it.first == value }.second, Modifier.weight(1f))
                Icon(LibraryIcons.Down, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.width(width).testTag("$tag-menu")) {
                choices.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-$id").semantics { selected = value == id })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTimePicker(initial: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val time = rememberTimePickerState(initialHour = initial.take(2).toIntOrNull()?.coerceIn(0, 23) ?: 0,
        initialMinute = initial.takeLast(2).toIntOrNull()?.coerceIn(0, 59) ?: 0, is24Hour = true)
    LibraryDialog("选择执行时间", "schedule-time-picker", onDismiss, subtitle = "被控端本地时间 · 24 小时制", footer = {
        Button({ onSelect("${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}") },
            shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("schedule-time-confirm")) { Text("确定时间") }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TimePicker(time)
        }
    }
}

package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteQuickMenu(onApps: () -> Unit, onInput: () -> Unit, onRoutines: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }, Modifier.size(48.dp).testTag("remote-more")) {
            Icon(painterResource(R.drawable.ic_more), "遥控器更多操作", Modifier.size(24.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.testTag("remote-quick-menu")) {
            listOf(Triple("常用应用", R.drawable.ic_apps, onApps), Triple("电视输入", R.drawable.ic_text, onInput),
                Triple("快捷操作", R.drawable.ic_remote_types, onRoutines)).forEachIndexed { index, (label, icon, action) ->
                DropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { Icon(painterResource(icon), null, Modifier.size(24.dp), tint = RemoteColors.Action) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("remote-quick-item-$index"),
                    onClick = { expanded = false; action() })
            }
        }
    }
}

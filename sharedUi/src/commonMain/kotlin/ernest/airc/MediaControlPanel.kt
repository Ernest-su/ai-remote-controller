package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaControlPanel(state: MediaControlState, available: Boolean, onRefresh: () -> Unit, onChoose: (MediaSelection) -> Unit, onSeek: (Long) -> Unit, onCustomAction: (String) -> Unit = {}) {
    var target by rememberSaveable { mutableStateOf("") }
    PageContent(Modifier.testTag("media-control-page")) {
        Panel {
            SectionTitle("播放控制", "选择正在播放的应用，查看进度或跳转到指定位置。")
            ActionButton("刷新播放会话", available && !state.busy, onRefresh)
            if (!available) Text("请连接支持播放控制的目标服务。", style = MaterialTheme.typography.bodyMedium, color = RemoteColors.Secondary)
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.sessions.forEach { session -> session.selection()?.let { selection ->
                    FilterChip(state.selected == selection, { onChoose(selection) }, enabled = !state.busy && state.sessions.count { it.selection() == selection } == 1,
                        modifier = Modifier.heightIn(min = 48.dp), label = { Text(selection.packageName + selection.sessionTag?.let { " · $it" }.orEmpty()) })
                } }
            }
            val session = state.sessions.singleOrNull { it.selection() == state.selected }
            session?.let {
                it.description?.let { title -> Text(title) }
                InfoRow("播放器报告位置", it.positionMs?.let { value -> "$value 毫秒" } ?: "未知")
                InfoRow("播放器报告时长", it.durationMs?.let { value -> "$value 毫秒" } ?: "未知")
            }
            if(state.customActionAvailable && session?.customActions?.isNotEmpty() == true) {
                Text("播放器提供的动作", style = MaterialTheme.typography.titleMedium)
                session.customActions.forEach { action ->
                    ActionButton(action.name.ifBlank { action.id }, available && !state.busy && session.customActions.count { it.id == action.id } == 1) { onCustomAction(action.id) }
                }
            }
            if(state.sessions.isEmpty()) Text("当前未显示活动媒体会话，可刷新并检查目标播放器。", color = RemoteColors.Secondary)
            if(!state.seekAvailable) Text("当前定位接口不可用，仍可通过遥控器使用播放键。", color = RemoteColors.Secondary)
            val position = target.toLongOrNull()
            val positionError = when {
                target.isBlank() -> null
                position == null || position < 0 -> "请输入大于或等于 0 的整数毫秒值"
                session?.durationMs?.takeIf { it > 0 }?.let { position > it } == true -> "目标位置不能超过播放器报告时长"
                else -> null
            }
            if (session != null && session.seekSupported != true) Text("此播放器未声明支持定位，可选择其他会话。", color = RemoteColors.Secondary)
            OutlinedTextField(target, { target = it }, label = { Text("目标位置（毫秒）") }, singleLine = true, isError = positionError != null,
                supportingText = { Text(positionError ?: "以播放器报告的时长为准，单位为毫秒") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("media-seek-position"))
            val valid = position != null && position >= 0 && session?.durationMs?.takeIf { it > 0 }?.let { position > it } != true
            ActionButton("提交播放定位", available && !state.busy && state.seekAvailable && session?.seekSupported == true && valid) { position?.let(onSeek) }
            state.message?.let { Text(it, color = RemoteColors.Secondary) }; state.error?.let { Text(it, color = RemoteColors.Warning) }
        }
    }
}

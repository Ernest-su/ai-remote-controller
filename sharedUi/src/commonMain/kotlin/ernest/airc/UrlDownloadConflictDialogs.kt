package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

data class UrlDownloadConflictActions(val rename: (String) -> Unit, val overwrite: () -> Unit,
    val dismiss: () -> Unit, val confirmOverwrite: (Boolean) -> Unit)

@Composable
internal fun UrlDownloadConflictDialogs(state: UrlDownloadState, available: Boolean, actions: UrlDownloadConflictActions?) {
    if (actions == null) return
    state.conflict?.let { conflict ->
        var name by rememberSaveable(state.noticeId, conflict) { mutableStateOf(conflict.suggestedName) }
        AlertDialog(onDismissRequest = actions.dismiss, title = { Text("同名文件已存在") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${fileRootTitle(conflict.root)}/${conflict.path}")
                Text(if (conflict.canOverwrite) "可使用新名称保留原文件，或确认后覆盖。" else "同名项是目录或符号链接，请使用新名称下载。")
                OutlinedTextField(name, { name = it }, label = { Text("新文件名称") }, singleLine = true,
                    isError = !validTargetFileName(name.trim()) || name.trim() == conflict.name,
                    modifier = Modifier.fillMaxWidth().testTag("url-conflict-name"))
            } },
            confirmButton = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ actions.rename(name.trim()) }, enabled = available && !state.busy && validTargetFileName(name.trim()) && name.trim() != conflict.name,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("url-conflict-rename")) { Text("使用新名称下载") }
                if (conflict.canOverwrite) TextButton(actions.overwrite, enabled = available && !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("url-conflict-overwrite")) { Text("覆盖原文件", color = RemoteColors.Warning) }
            } },
            dismissButton = { TextButton(actions.dismiss, Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
    state.overwriteProposal?.let { proposal ->
        AlertDialog(onDismissRequest = { actions.confirmOverwrite(false) }, title = { Text("确认覆盖下载文件") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${fileRootTitle(proposal.root)}/${proposal.path}")
                proposal.sizeBytes?.let { Text("原文件大小：${managementFileSize(it)}") }
                Text("下载完成后将替换此文件，原文件内容不可恢复。取消或下载失败会保留原文件。")
            } },
            confirmButton = { TextButton({ actions.confirmOverwrite(true) }, enabled = available,
                modifier = Modifier.heightIn(min = 48.dp).testTag("url-overwrite-confirm")) { Text("确认覆盖", color = RemoteColors.Warning) } },
            dismissButton = { TextButton({ actions.confirmOverwrite(false) }, Modifier.heightIn(min = 48.dp)) { Text("取消") } })
    }
}

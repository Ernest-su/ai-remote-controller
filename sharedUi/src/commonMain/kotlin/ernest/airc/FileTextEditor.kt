package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@Composable
internal expect fun FileTextEditorWindow()

/** Single-pass bounded tokens; never renders HTML or evaluates source code. */
private val codeKeywords = setOf("true", "false", "null", "None", "True", "False", "if", "else", "for", "while", "return", "fun", "val", "var", "class", "object", "def", "import", "from", "package", "const", "let", "function", "public", "private", "static", "void", "int", "float", "double", "bool", "struct", "enum", "impl", "use", "fn", "async", "await", "try", "catch", "throw", "select", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE")
fun highlightedFileText(text: String, keyword: androidx.compose.ui.graphics.Color, secondary: androidx.compose.ui.graphics.Color): AnnotatedString {
    if (text.length > TEXT_HIGHLIGHT_CHARS) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var index = 0
        while (index < text.length) {
            val start = index
            val first = text[index]
            var comment = false
            var number = false
            var highlight = true
            when {
                text.startsWith("//", index) || first == '#' -> {
                    comment = true
                    while (index < text.length && text[index] != '\n') index++
                }
                text.startsWith("/*", index) -> {
                    comment = true
                    val end = text.indexOf("*/", index + 2)
                    index = if (end < 0) text.length else end + 2
                }
                first == '"' || first == '\'' -> {
                    index++
                    while (index < text.length) {
                        val character = text[index++]
                        if (character == '\\' && index < text.length) index++
                        else if (character == first) break
                    }
                }
                first.isLetter() || first == '_' -> {
                    index++
                    while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) index++
                    highlight = text.substring(start, index) in codeKeywords
                }
                first.isDigit() -> {
                    number = true
                    index++
                    while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
                }
                else -> { index++; highlight = false }
            }
            if (highlight) addStyle(SpanStyle(color = if (comment || number) secondary else keyword,
                fontStyle = if (comment) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (!comment && first.isLetter()) FontWeight.Medium else FontWeight.Normal), start, index)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileTextEditor(state: FileTextState, transfer: FileTransferState, available: Boolean, onEdit: (String) -> Unit,
    onSave: () -> Unit, onContinue: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit, onCancelSave: () -> Unit, onConfirmSave: (Boolean) -> Unit) {
    if (!state.open) return
    var editing by rememberSaveable(state.root, state.path) { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var page by rememberSaveable(state.root, state.path) { mutableIntStateOf(0) }
    fun close() { if (state.dirty || state.saving) discard = true else onClose() }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("关闭文本文件？") },
        text = { Text(if (state.saving) "保存尚未结束，关闭将取消后续保存并丢弃草稿；已经提交的文件不会撤销。" else "未保存的修改将丢失。") },
        confirmButton = { TextButton({ discard = false; onClose() }, Modifier.heightIn(min = 48.dp)) { Text("放弃并关闭", color = RemoteColors.Warning) } },
        dismissButton = { TextButton({ discard = false }, Modifier.heightIn(min = 48.dp)) { Text("继续编辑") } })
    ManagementDialog("文本查看与编辑", "file-text-editor", ::close) {
        FileTextEditorWindow()
        InfoRow("被控文件", "${fileRootTitle(state.root)}/${state.path}")
        if (state.warning) {
            Text("文件较大（${managementFileSize(state.sizeBytes)}），加载可能耗时或失败。", color = RemoteColors.Secondary)
            Text(if (state.sizeBytes > TEXT_EDIT_BYTES) "超过 2 MiB，仅分段读取开头 256 KiB，只读预览；不会编辑或保存截断内容。" else "最多读取 2 MiB 全文，可编辑保存；大文本将关闭高亮以减少等待。", style = MaterialTheme.typography.bodyMedium)
            ActionButton("继续加载", available, onContinue)
        }
        if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取文本…") }
        state.text?.let {
            Text("${state.encoding} · ${managementFileSize(state.sizeBytes)}${if (state.partial) " · 仅显示开头，禁止保存" else ""}", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            if (state.partial || !state.writable) Text("当前文件只读。", color = RemoteColors.Secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!editing, { editing = false }, enabled = !state.saving, label = { Text("查看") }, modifier = Modifier.heightIn(min = 48.dp).testTag("file-text-view"))
                FilterChip(editing, { editing = true }, enabled = state.writable && !state.saving, label = { Text("编辑") }, modifier = Modifier.heightIn(min = 48.dp).testTag("file-text-edit"))
            }
            val keyword = RemoteColors.Action
            val secondary = RemoteColors.Secondary
            if (editing) {
                val transformation = remember(keyword, secondary) { VisualTransformation { input -> TransformedText(highlightedFileText(input.text, keyword, secondary), OffsetMapping.Identity) } }
                OutlinedTextField(state.draft, onEdit, enabled = !state.saving, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = transformation, modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 400.dp).testTag("file-text-input"), maxLines = 18)
                ActionButton("保存回被控设备", available && state.dirty && !state.saving && !state.loading, onSave)
            } else {
                val count = maxOf(1, (state.draft.length + 7_999) / 8_000)
                val current = page.coerceIn(0, count - 1)
                val chunk = state.draft.substring((current * 8_000).coerceAtMost(state.draft.length), ((current + 1) * 8_000).coerceAtMost(state.draft.length))
                val highlighted = remember(chunk, keyword, secondary) { highlightedFileText(chunk, keyword, secondary) }
                SelectionContainer { Text(highlighted, Modifier.fillMaxWidth().testTag("file-text-content"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                if (count > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ page = current - 1 }, enabled = current > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("上一段") }
                    Text("${current + 1} / $count", style = MaterialTheme.typography.bodyMedium)
                    TextButton({ page = current + 1 }, enabled = current + 1 < count, modifier = Modifier.heightIn(min = 48.dp)) { Text("下一段") }
                }
            }
            if (state.draft.length > TEXT_HIGHLIGHT_CHARS) Text("长文本编辑关闭高亮；查看模式按段显示。", style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            if (state.dirty) Text("有未保存的修改", color = RemoteColors.Secondary)
        }
        if (transfer.phase != "idle") FileTransferPanel(transfer, state.root, state.path.substringBeforeLast('/', ""), available, {}, {}, onCancelSave, {}, onConfirm = onConfirmSave, operation = "upload", tasksOnly = true)
        state.message?.let { Text(it, color = RemoteColors.Secondary) }
        state.error?.let { Text(it, color = RemoteColors.Warning) }
        if (state.error != null && state.text == null && !state.loading) OutlinedButton(onRetry, enabled = available, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新加载") }
    }
}

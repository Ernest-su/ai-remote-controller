package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@Composable
internal expect fun PlatformFileTextInput(documentKey: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, modifier: Modifier)

@Composable
internal expect fun FileTextBackHandler(onBack: () -> Unit)

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

/** A child page of the file browser. Only confirmation prompts use dialogs. */
@Composable
fun FileTextEditor(state: FileTextState, transfer: FileTransferState, available: Boolean, onEdit: (String) -> Unit,
    onSave: () -> Unit, onContinue: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit, onCancelSave: () -> Unit,
    onConfirmSave: (Boolean) -> Unit, showHeader: Boolean = true, backRequest: Int = 0) {
    if (!state.open) return
    var editing by rememberSaveable(state.root, state.path) { mutableStateOf(false) }
    var discard by rememberSaveable(state.root, state.path) { mutableStateOf(false) }
    var page by rememberSaveable(state.root, state.path) { mutableIntStateOf(0) }
    var handledBack by rememberSaveable(state.root, state.path) { mutableIntStateOf(backRequest) }
    val editorStates = rememberSaveableStateHolder()
    fun close() { if (state.dirty || state.saving) discard = true else onClose() }
    FileTextBackHandler(::close)
    LaunchedEffect(backRequest) { if (handledBack != backRequest) { handledBack = backRequest; close() } }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("关闭文本文件？") },
        text = { Text(if (state.saving) "保存尚未结束，关闭将取消后续保存并丢弃草稿；已经提交的文件不会撤销。" else "未保存的修改将丢失。") },
        confirmButton = { TextButton({ discard = false; onClose() }, Modifier.heightIn(min = 48.dp)) { Text("放弃并关闭", color = RemoteColors.Warning) } },
        dismissButton = { TextButton({ discard = false }, Modifier.heightIn(min = 48.dp)) { Text("继续编辑") } })
    Column(Modifier.fillMaxSize().background(RemoteColors.Background).testTag("file-text-editor")) {
        if (showHeader) Surface(color = RemoteColors.Header) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(::close, Modifier.size(48.dp).testTag("file-text-editor-close")) { Icon(LibraryIcons.Back, "返回文件管理") }
                Text("文本查看与编辑", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text("${fileRootTitle(state.root)}/${state.path} · ${state.encoding} · ${managementFileSize(state.sizeBytes)}",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        if (state.warning) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文件较大（${managementFileSize(state.sizeBytes)}），加载可能耗时或失败。", color = RemoteColors.Secondary)
            Text(if (state.sizeBytes > TEXT_EDIT_BYTES) "超过 2 MB，只读预览开头 256 KB，不保存截断内容。" else "最多读取 2 MB 全文，编辑使用纯文本以保持流畅。")
            ActionButton("继续加载", available, onContinue)
        }
        if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在读取文本…", Modifier.padding(12.dp)) }
        if (state.text != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!editing, { editing = false }, enabled = !state.saving, label = { Text("查看") }, modifier = Modifier.heightIn(min = 48.dp).testTag("file-text-view"))
                FilterChip(editing, { editing = true }, enabled = state.writable && !state.saving, label = { Text("编辑") }, modifier = Modifier.heightIn(min = 48.dp).testTag("file-text-edit"))
                Spacer(Modifier.weight(1f))
                if (editing) TextButton(onSave, enabled = available && state.dirty && !state.saving && !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("file-text-save")) { Text("保存") }
            }
            if (state.partial || !state.writable) Text(if (state.partial) "仅显示文件开头，禁止编辑和保存。" else "当前文件只读。",
                Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
            if (editing) {
                editorStates.SaveableStateProvider("${state.root}/${state.path}") {
                    PlatformFileTextInput("${state.root}/${state.path}", state.draft, onEdit, state.writable && !state.saving,
                        Modifier.weight(1f).fillMaxWidth().testTag("file-text-input"))
                }
            } else {
                val count = maxOf(1, (state.draft.length + 7_999) / 8_000)
                val current = page.coerceIn(0, count - 1)
                val chunk = remember(state.draft, current) { state.draft.substring((current * 8_000).coerceAtMost(state.draft.length), ((current + 1) * 8_000).coerceAtMost(state.draft.length)) }
                val keyword = RemoteColors.Action
                val secondary = RemoteColors.Secondary
                val highlighted = remember(chunk, keyword, secondary) { highlightedFileText(chunk, keyword, secondary) }
                val scroll = key(current) { rememberScrollState() }
                Box(Modifier.weight(1f).fillMaxWidth().background(RemoteColors.Surface).verticalScroll(scroll).padding(12.dp)) {
                    SelectionContainer { Text(highlighted, Modifier.fillMaxWidth().testTag("file-text-content"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
                if (count > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ page = current - 1 }, enabled = current > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("上一段") }
                    Text("${current + 1} / $count", style = MaterialTheme.typography.bodySmall)
                    TextButton({ page = current + 1 }, enabled = current + 1 < count, modifier = Modifier.heightIn(min = 48.dp)) { Text("下一段") }
                }
            }
        } else Spacer(Modifier.weight(1f))
        if (state.dirty) Text("有未保存的修改", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary)
        if (transfer.phase != "idle") Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            FileTransferPanel(transfer, state.root, state.path.substringBeforeLast('/', ""), available, {}, {}, onCancelSave, {}, onConfirm = onConfirmSave, operation = "upload", tasksOnly = true)
        }
        state.message?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Secondary) }
        state.error?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = RemoteColors.Warning) }
        if (state.error != null && state.text == null && !state.loading) TextButton(onRetry, enabled = available, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新加载") }
    }
}

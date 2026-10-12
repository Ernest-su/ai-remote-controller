package ernest.airc

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StorageAnalysisPanel(state: StorageAnalysisState, available: Boolean, onAnalyze: () -> Unit, onCancel: () -> Unit) {
    SectionTitle("存储分析", "分析当前目录，按后缀统计文件类型。")
    ActionButton("分析此目录", available && !state.busy, onAnalyze)
    state.root?.let { InfoRow("分析范围", "${fileRootTitle(it)}/${state.path}") }
    if(state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("已扫描 ${state.visited} 项，最多 ${state.limit} 项", color = RemoteColors.Secondary)
        OutlinedButton(onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("取消存储分析") }
    }
    state.error?.let { Text(it, color = RemoteColors.Warning) }
    if(state.phase == "cancelled") Text("分析已取消，原文件保留。", color = RemoteColors.Secondary)
    state.report?.let { report ->
        InfoRow("去重文件数", report.totals.files.toString())
        InfoRow("逻辑大小", "${report.totals.logicalBytes} 字节")
        InfoRow("已分配文件块", "${report.totals.allocatedBytes} 字节")
        Text("统计来自文件系统元数据，不是可释放空间，也不是应用缓存大小；报告不是实时快照。", color = RemoteColors.Secondary)
        if(!report.complete) Text("报告不完整：达到扫描上限、存在访问错误或目录发生变化。", color = RemoteColors.Warning)
        if(report.skippedFilesystems > 0 || report.skippedLinks > 0 || report.skippedSpecial > 0) Text("跳过 ${report.skippedLinks} 个链接、${report.skippedFilesystems} 个其他文件系统入口、${report.skippedSpecial} 个特殊文件。", color = RemoteColors.Secondary)
        if(report.duplicateFiles > 0) Text("${report.duplicateFiles} 个硬链接别名已去重。", color = RemoteColors.Secondary)
        report.categories.entries.sortedByDescending { it.value.logicalBytes }.forEach { (category, totals) ->
            InfoRow(storageCategoryTitle(category), "${totals.files} 个文件 · 逻辑 ${totals.logicalBytes} 字节 · 分配块 ${totals.allocatedBytes} 字节")
        }
        if(report.largestFiles.isNotEmpty()) SectionTitle("逻辑大小最大的文件", "最多显示 20 项；可在文件列表核对属性并按原流程确认删除。")
        report.largestFiles.forEach { file -> InfoRow(file.path, "逻辑 ${file.logicalBytes} 字节 · 分配块 ${file.allocatedBytes} 字节") }
        if(report.errorCount > 0) Text("${report.errorCount} 项访问或名称错误；仅显示前八项。", color = RemoteColors.Secondary)
        report.errors.forEach { Text("${it.path}: ${it.message}", color = RemoteColors.Secondary) }
    }
}

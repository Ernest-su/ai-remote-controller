package ernest.airc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun StorageAnalysisPanel(state: StorageAnalysisState, available: Boolean, onAnalyze: () -> Unit, onCancel: () -> Unit,
    tasksOnly: Boolean = false, onDelete: ((StorageLargeFile) -> Unit)? = null, deleteEnabled: Boolean = available) {
    var details by rememberSaveable { mutableStateOf(false) }
    SectionTitle("存储分析", "看看哪些文件最占空间")
    state.root?.let { InfoRow("查看位置", "${fileRootTitle(it)}/${state.path}") }
    if (!tasksOnly) ActionButton(if (state.report == null) "开始分析" else "重新分析", available && !state.busy, onAnalyze)
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("正在查看文件，已检查 ${state.visited} 项…", color=RemoteColors.Secondary)
        OutlinedButton(onCancel,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("停止分析") }
    }
    state.error?.let { Text("未能完成分析：$it",color=RemoteColors.Warning) }
    if (state.phase == "cancelled") Text("已停止分析，文件未被改动。",color=RemoteColors.Secondary)
    if (state.report == null && !state.busy && state.error == null && state.phase != "cancelled")
        Text("分析只查看文件，不会自动删除。",color=RemoteColors.Secondary)
    state.report?.let { report ->
        Column(Modifier.fillMaxWidth().background(RemoteColors.Fill,MaterialTheme.shapes.small).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("文件总大小",style=MaterialTheme.typography.bodyMedium,color=RemoteColors.Secondary)
            Text(managementFileSize(report.totals.logicalBytes),style=MaterialTheme.typography.titleLarge)
            Text("${report.totals.files} 个文件 · ${report.directories} 个文件夹")
        }
        Text("文件大小不等于可腾出的空间。",color=RemoteColors.Secondary)
        if (state.outdated) Text("文件已删除，请重新分析以更新统计。",color=RemoteColors.Secondary,modifier=Modifier.testTag("storage-outdated"))
        if (!report.complete) Text("有些文件未能查看，以下只显示已找到的内容。",color=RemoteColors.Warning)
        if (report.totals.files == 0L) Text("没有找到可统计的文件。",color=RemoteColors.Secondary)
        else {
            SectionTitle("哪些文件占空间")
            report.categories.entries.sortedByDescending { it.value.logicalBytes }.forEach { (category,totals) ->
                val share=if(report.totals.logicalBytes>0) (totals.logicalBytes.toDouble()/report.totals.logicalBytes).toFloat().coerceIn(0f,1f) else 0f
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(storageCategoryTitle(category),Modifier.weight(1f))
                        Text("${managementFileSize(totals.logicalBytes)} · ${(share*100).toInt()}%",color=RemoteColors.Secondary)
                    }
                    LinearProgressIndicator(progress={share},modifier=Modifier.fillMaxWidth())
                    Text("${totals.files} 个文件",style=MaterialTheme.typography.bodySmall,color=RemoteColors.Secondary)
                }
            }
        }
        if (report.largestFiles.isNotEmpty()) {
            SectionTitle("较大的文件", "按大小排列，最多20个文件")
            report.largestFiles.forEach { file ->
                Row(Modifier.fillMaxWidth().heightIn(min=64.dp).testTag("storage-file-${file.path}"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(RemoteDimensions.ManagementFileIcon).background(RemoteColors.Fill,MaterialTheme.shapes.small),contentAlignment=Alignment.Center) {
                        Icon(when(file.category) {"images"->ManagementIcons.Image;"video","audio"->ManagementIcons.Play;"apk"->ManagementIcons.Apps;else->ManagementIcons.File},null,Modifier.size(24.dp),tint=RemoteColors.Secondary)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(file.path.substringAfterLast('/'),style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("${storageCategoryTitle(file.category)} · ${managementFileSize(file.logicalBytes)}",color=RemoteColors.Secondary,style=MaterialTheme.typography.bodyMedium)
                        Text("${fileRootTitle(report.root)}/${file.path}",color=RemoteColors.Secondary,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                    onDelete?.let { remove -> IconButton({remove(file)},enabled=deleteEnabled && !state.busy,modifier=Modifier.size(48.dp).testTag("storage-delete-${file.path}")) {
                        Icon(ManagementIcons.Trash,"删除 ${file.path.substringAfterLast('/')}",tint=if(deleteEnabled && !state.busy) RemoteColors.Warning else RemoteColors.Disabled)
                    } }
                }
                HorizontalDivider(color=RemoteColors.Divider,thickness=0.5.dp)
            }
        }
        TextButton({details=!details},modifier=Modifier.heightIn(min=48.dp).testTag("storage-details")) { Text(if(details) "收起扫描详情" else "查看扫描详情") }
        if (details) {
            InfoRow("检查过的项目",report.entriesVisited.toString())
            InfoRow("实际占用空间",managementFileSize(report.totals.allocatedBytes))
            Text("设备保存文件时会额外分配空间，因此实际占用可能与文件大小不同；这里也不是保证能腾出的空间。",color=RemoteColors.Secondary)
            val skipped=report.skippedLinks+report.skippedSpecial+report.skippedFilesystems
            if(skipped>0) Text("有 $skipped 项快捷链接、特殊文件或其他存储位置未计入。",color=RemoteColors.Secondary)
            if(report.duplicateFiles>0) Text("同一文件的 ${report.duplicateFiles} 个其他入口未重复计算。",color=RemoteColors.Secondary)
            if(report.errorCount>0) Text("${report.errorCount} 项未能读取，下面最多显示8项：",color=RemoteColors.Secondary)
            report.errors.forEach { Text("${it.path}：${it.message}",color=RemoteColors.Secondary) }
        }
    }
}

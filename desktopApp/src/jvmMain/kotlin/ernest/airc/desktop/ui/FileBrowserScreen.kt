package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import javax.swing.JFileChooser
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Composable
fun DesktopFileBrowser(state: DesktopState, controller: DesktopController) {
    val download by controller.urlDownloads.state.collectAsState()
    val downloadDirectory by controller.downloadDirectories.state.collectAsState()
    val storage by controller.storageAnalysis.state.collectAsState()
    val files by controller.files.state.collectAsState()
    val installation by controller.appInstallation.state.collectAsState()
    val apk by controller.apkInspection.state.collectAsState()
    val media by controller.fileMedia.state.collectAsState()
    val batch by controller.batchTransfers.state.collectAsState()
    val uploadDraft by controller.uploadDraft.state.collectAsState()
    val transfer by controller.transfers.state.collectAsState()
    val previewSession by controller.filePreviews.session.collectAsState()
    val thumbnails=remember(previewSession,files.root,files.path,files.contentRevision) {DesktopFileThumbnails(controller.filePreviews)}
    val scope=rememberCoroutineScope()
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    var batchMessage by remember(batch.noticeId) { mutableStateOf<String?>(null) }
    var downloadMessage by remember(transfer.noticeId) { mutableStateOf<String?>(null) }
    val available = state.connected && !state.busy && "files.list" in state.tools && "files.stat" in state.tools
    LaunchedEffect(available, files.root) {
        if (available && !files.loaded && !files.loading && files.error == null) controller.files.refresh(true)
    }
    val canTransfer=available && listOf("task.start","task.progress","task.cancel").all { it in state.tools }
    val cache=remember { File(DesktopController.defaultStateDirectory().toFile(),"downloads") }
    val uploadLocked = transfer.running || transfer.proposal != null || transfer.readyToSave || batch.running || batch.paused || batch.readyToSave || batch.saving
    fun path(name: String)=listOf(files.path,name).filter{it.isNotEmpty()}.joinToString("/")
    FileBrowserPanel(files, available, { controller.files.refresh(available) }, { controller.files.parent(available) },
        { controller.files.more(available) }, { controller.files.enter(it, available) }, { controller.files.inspect(it, available) },
        taskGroups=fileTaskGroups(batch, transfer, download, storage, apk, installation, media),
        selectionDeviceKey=state.deviceId,
        onDownloadSelected={ names, mode -> controller.batchTransfers.download(files.root, files.path, names, mode) },
        batchDownloadEnabled=canTransfer && !transfer.running && transfer.proposal == null && !batch.running && !batch.paused && !batch.readyToSave && !batch.saving,
        directoryAvailable=available&&!transfer.running,
        onCreateDirectory=if("files.mkdir" in state.tools) ({name -> controller.files.createDirectory(name,available&&!transfer.running)})else null,
        onRoot=controller.files::selectRoot,
        onNavigate={ controller.files.ancestor(it, available) },
        unavailableMessage=if (!state.connected) "设备未连接，连接后即可浏览和管理文件。" else if (state.busy) "设备正在执行操作，请稍后重试。" else "当前服务不支持文件浏览，请更新目标服务。",
        operationActive=batch.running || batch.paused || batch.readyToSave || transfer.running || transfer.proposal != null || transfer.readyToSave || installation.busy || installation.proposal != null || apk.busy || download.busy || media.busy || storage.busy,
        operationSection=when {
            batch.running || batch.paused || batch.readyToSave -> "batch"
            transfer.running || transfer.proposal != null || transfer.readyToSave -> transfer.operation
            installation.busy || installation.proposal != null || apk.busy -> "apk"
            download.busy -> "url"
            media.busy -> "media"
            storage.busy -> "storage"
            download.phase != "idle" -> "url"
            transfer.phase != "idle" -> transfer.operation
            installation.message != null || apk.info != null || apk.error != null -> "apk"
            media.message != null || media.error != null -> "media"
            storage.report != null || storage.error != null -> "storage"
            else -> "upload"
        },
        operationNoticeIds=listOfNotNull(batch.noticeId, transfer.noticeId, installation.noticeId, apk.noticeId, download.noticeId, media.noticeId, storage.noticeId),
        operationMessage=when {
            batch.running || batch.paused || batch.readyToSave -> "批量传输任务可查看"
            transfer.proposal != null || installation.proposal != null -> "有文件操作等待确认"
            download.busy || transfer.running || apk.busy || installation.busy || media.busy || storage.busy -> "文件任务正在进行，可查看进度"
            transfer.readyToSave -> "文件已下载，可保存到本机"
            download.error != null || transfer.error != null || apk.error != null || installation.error != null || media.error != null || storage.error != null -> "文件任务遇到问题，请查看详情"
            download.phase != "idle" || transfer.phase != "idle" || installation.message != null || media.message != null || storage.report != null -> "有文件任务结果可查看"
            batch.noticeId != null -> "有文件任务结果可查看"
            else -> null
        },
        onApk=if(available && !apk.busy && "apps.apk_info" in state.tools) ({entry -> controller.apkInspection.inspect(files.root,path(entry.name),available)}) else null,
        onMedia=if(available && "media.players" in state.tools && "media.open" in state.tools) ({entry -> controller.fileMedia.inspect(files.root,path(entry.name),available,"media.publish" in state.tools && files.root!="downloads")}) else null,
        onDelete=if(available && !files.readOnly && !transfer.running && "files.delete" in state.tools) ({entry -> controller.transfers.delete(files.root,path(entry.name),entry.kind=="directory" && !entry.isSymbolicLink,available && !files.readOnly) { controller.files.refresh(available) } }) else null,
        onDownload=if(canTransfer&&!transfer.running) ({ entry ->
            downloadMessage=null
            cache.mkdirs()
            runCatching { Files.setPosixFilePermissions(cache.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
            controller.transfers.download(files.root,path(entry.name),cache,canTransfer)
        }) else null,
        thumbnail={entry,modifier -> DesktopFileThumbnail(entry,modifier,thumbnails,files.root,path(entry.name),available && (if(entry.name.endsWith(".apk",true)) "apps.apk_icon" else "files.preview") in state.tools)},
        previewContent=if("files.preview" in state.tools) ({entry -> DesktopFilePreview(entry,controller.filePreviews,files.root,path(entry.name))}) else null,
        transfer={ requested ->
            val tasksOnly = requested.startsWith("task:")
            val section = requested.removePrefix("task:")
            val selectUpload: () -> Unit = {
                val dialog=FileDialog(null as Frame?,"选择本地文件",FileDialog.LOAD);dialog.isMultipleMode=true;dialog.isVisible=true
                val selected=dialog.files.toList();dialog.dispose()
                if(selected.isNotEmpty()) runCatching {
                    require(selected.size <= 32) { "最多选择32个文件，请重新选择" }
                    val sources = selected.map { file -> UploadSource(file.name,file.length()){file.inputStream()} }
                    controller.uploadDraft.select(sources)
                    uploadMessage=null
                }.onFailure { uploadMessage=it.message }
            }
            if (section == "upload" && !tasksOnly) {
                UploadFilesPanel(uploadDraft.map { it.name },uploadDraft.singleOrNull()?.size,files,canTransfer && !files.readOnly,uploadLocked,selectUpload,
                    { name, overwrite ->
                        if (!uploadLocked && canTransfer && !files.readOnly) uploadDraft.singleOrNull()?.let { source ->
                            uploadMessage=null
                            controller.transfers.select(source)
                            controller.transfers.upload(files.root,path(name),true,overwrite)
                        }
                    }, {
                        if (!uploadLocked && canTransfer && !files.readOnly && uploadDraft.size > 1) {
                            uploadMessage=null; batchMessage=null
                            controller.batchTransfers.select(uploadDraft)
                            controller.batchTransfers.upload(files.root,files.path)
                        }
                    })
            }
            if (section == "upload" && !tasksOnly) FileOperationFeedback("upload",uploadMessage,batchMessage,downloadMessage)
            if(section == "batch" || section == "upload" && !tasksOnly && batch.uploadSelection && batch.noticeId != null) {
                BatchTransferPanel(batch,canTransfer,controller.batchTransfers::pause,controller.batchTransfers::resume,controller.batchTransfers::cancel,{
                    val file=controller.batchTransfers.exportFile()
                    if(file!=null) {
                        val dialog=FileDialog(null as Frame?,"保存 ZIP",FileDialog.SAVE);dialog.file="下载文件.zip";dialog.isVisible=true
                        val destination=dialog.file?.let {File(dialog.directory,it)};dialog.dispose()
                        if(destination!=null)scope.launch {try {withContext(Dispatchers.IO){Files.newOutputStream(destination.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {out->file.inputStream().use {it.copyTo(out,64*1024)}}};batchMessage="ZIP 已保存"}catch(e:CancellationException){throw e}catch(e:Exception){batchMessage=e.message ?: "保存失败"}}
                    }
                }, saveIndividual = {
                    val notice = batch.noticeId
                    scope.launch {
                        val folder = withContext(Dispatchers.Swing) {
                            val chooser = JFileChooser().apply { dialogTitle = "选择保存文件夹"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                        }
                        if (folder != null) {
                            val pending = controller.batchTransfers.beginFileExport(notice)
                            if (pending.isNotEmpty()) {
                                var failure: String? = null
                                try {
                                    withContext(Dispatchers.IO) { saveBatchFiles(folder.toPath(), pending) { controller.batchTransfers.fileSaved(notice, it) } }
                                    batchMessage= "文件已分别保存，同名文件已自动改名"
                                } catch (e: CancellationException) { failure = "保存已中断，可继续保存剩余文件"; throw e }
                                catch (e: Exception) { failure = e.message ?: "保存失败，可重试剩余文件" }
                                finally { controller.batchTransfers.finishFileExport(notice, failure) }
                            }
                        }
                    }
                })
                FileOperationFeedback("batch",uploadMessage,batchMessage,downloadMessage)
            }

            if(section == "url") {
                LaunchedEffect(download.url, available, tasksOnly) { if (!tasksOnly) controller.urlDownloads.inspect(available && "files.url_info" in state.tools) }
                UrlDownloadPanel(download, available && listOf("files.download_url", "task.progress", "task.cancel").all { it in state.tools },
                    validDownloadUrl(download.url), controller.urlDownloads::editUrl, controller.urlDownloads::editName,
                    { controller.urlDownloads.start(available) }, controller.urlDownloads::cancel,
                    { controller.urlDownloads.directory("downloads", "") }, conflictActions = UrlDownloadConflictActions(
            { controller.urlDownloads.renameConflict(it, available) }, { controller.urlDownloads.start(available, overwrite = true) },
            controller.urlDownloads::dismissConflict, controller.urlDownloads::confirmOverwrite), tasksOnly = tasksOnly) { selected ->
                    LaunchedEffect(available, downloadDirectory.root) {
                        if(available && !downloadDirectory.loaded && !downloadDirectory.loading && downloadDirectory.error == null) controller.downloadDirectories.refresh(true)
                    }
                    FileBrowserPanel(downloadDirectory, available, { controller.downloadDirectories.refresh(available) },
                        { controller.downloadDirectories.parent(available) }, { controller.downloadDirectories.more(available) },
                        { controller.downloadDirectories.enter(it, available) }, {},
                        onRoot=controller.downloadDirectories::selectRoot,
                        onNavigate={ controller.downloadDirectories.ancestor(it, available) },
                        onSelectDirectory={ controller.urlDownloads.directory(downloadDirectory.root, downloadDirectory.path); selected() })
                }
            }
            if (section == "storage") {
            if("storage.analyze" in state.tools) StorageAnalysisPanel(storage, available && !files.readOnly && "task.progress" in state.tools && "task.cancel" in state.tools,
                { controller.storageAnalysis.analyze(files.root,files.path,available && !files.readOnly) }, controller.storageAnalysis::cancel,tasksOnly=tasksOnly,
                onDelete=if ("files.delete" in state.tools) ({ file ->
                    storage.report?.let { report ->
                        controller.transfers.delete(report.root,file.path,false,available && !uploadLocked && !storage.busy) {
                            val result=controller.transfers.state.value
                            if (result.operation=="delete" && result.phase=="completed" && result.root==report.root && result.path==file.path)
                                controller.storageAnalysis.fileDeleted(report.root,file.path)
                            controller.files.refresh(available)
                        }
                    }
                }) else null,deleteEnabled=available && !uploadLocked && !storage.busy)
            if ("storage.analyze" !in state.tools) Text("当前服务不支持存储分析。", color=RemoteColors.Secondary)
            if (transfer.operation=="delete" && storage.report?.let {report -> report.root==transfer.root && report.largestFiles.any {it.path==transfer.path}}==true)
                FileTransferPanel(transfer,transfer.root,"",available,{},{}, controller.transfers::cancel,{},onConfirm=controller.transfers::confirm,operation="delete",tasksOnly=true)
            }
            if (section == "apk") {
            apk.info?.takeIf {it.root==files.root}?.let {info ->
                val entry=TargetFileEntry(info.path.substringAfterLast('/'),"file",info.sizeBytes)
                DesktopFileThumbnail(entry,androidx.compose.ui.Modifier.size(RemoteDimensions.FileGridThumbnail),thumbnails,info.root,info.path,available && "apps.apk_icon" in state.tools)
            }
            Text("APK 预览与被控设备安装",style=androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("安装将在当前连接的被控设备执行。",color=RemoteColors.Secondary)
            ApkInspectionPanel(apk, controller.apkInspection::cancel)
            if("apps.install" in state.tools) AppInstallationPanel(installation, apk.info, available,
                { update -> apk.info?.let { controller.appInstallation.prepare(it,update,available) } },
                controller.appInstallation::confirm, controller.appInstallation::cancel,
                onAdd = { apk.info?.let(controller.appInstallation::add) }, onRemove = controller.appInstallation::remove,
                tasksOnly=tasksOnly,installLabel = "在被控设备安装此 APK", onPrepareSet = { update -> controller.appInstallation.prepareSelected(update, available) })
            if (apk.info == null && !apk.busy && apk.error == null) Text("从 APK 文件的更多菜单选择 APK 信息，读取后可准备安装。", color=RemoteColors.Secondary)
            }
            if (section == "media") {
            FileMediaPanel(media,available,controller.fileMedia::choose,{controller.fileMedia.open(available)},{controller.fileMedia.release(available)},onMode={local -> media.players?.let{controller.fileMedia.inspect(it.root,it.path,available,local)}},onCancel=controller.fileMedia::cancel,onRecover=if("media.publications" in state.tools) ({controller.fileMedia.recover(available)})else null,onReleaseCopy={id -> controller.fileMedia.releaseCopy(id,available)},tasksOnly=tasksOnly)
            if (media.players == null && !media.busy) Text("从媒体文件的更多菜单选择在被控设备播放，选择播放器后再执行。", color=RemoteColors.Secondary)
            }
            if (section in listOf("upload", "download", "delete") && (tasksOnly || section != "upload" || transfer.operation == "upload" && transfer.phase != "idle")) {
            FileTransferPanel(transfer,files.root,files.path,canTransfer && (section != "upload" || !files.readOnly && !batch.running && !batch.paused && !batch.readyToSave && !batch.saving),onSelect=selectUpload,
                onUpload={ name -> downloadMessage=null;controller.transfers.upload(files.root,path(name),canTransfer && !files.readOnly) },
                onCancel=controller.transfers::cancel,
                onSave={
                    val name=controller.transfers.prepareExport()
                    if(name!=null) scope.launch {
                        val dialog=FileDialog(null as Frame?,"保存下载文件",FileDialog.SAVE)
                        var exported: FileExport?=null
                        var completed=false
                        try {
                            dialog.file=name;dialog.isVisible=true
                            exported=controller.transfers.takeExport()
                            val snapshot=exported
                            val destination=dialog.file?.let { File(dialog.directory,it) }
                            if(snapshot!=null && destination!=null) {
                                withContext(Dispatchers.IO) {
                                    val output=Files.newOutputStream(destination.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
                                    try { output.use { sink -> snapshot.file.inputStream().use { input ->
                                        val buffer=ByteArray(64*1024)
                                        while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;sink.write(buffer,0,count) }
                                    } } } catch(error:Exception) { Files.deleteIfExists(destination.toPath());throw error }
                                }
                                completed=true;downloadMessage="文件已保存：${destination.name}"
                            }
                        } catch(e:CancellationException) { throw e }
                        catch(_:java.nio.file.FileAlreadyExistsException) { downloadMessage="文件已存在，请选择新名称。" }
                        catch(_:Exception) { downloadMessage="保存失败，可重试。" }
                        finally { dialog.dispose();exported?.let { if(completed)it.file.delete()else controller.transfers.restoreExport(it) } }
                    }
                }, onOverwrite={name -> controller.transfers.upload(files.root,path(name),canTransfer && !files.readOnly,true)},onConfirm=controller.transfers::confirm,operation=section,tasksOnly=true)
            if (section == "download") FileOperationFeedback(section,uploadMessage,batchMessage,downloadMessage)
            }
        })
}

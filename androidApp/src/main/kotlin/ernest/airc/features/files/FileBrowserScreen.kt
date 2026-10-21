package ernest.airc

import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

@Composable
internal fun FileBrowserScreen(state: RemoteUiState, model: RemoteViewModel) {
    val download by model.urlDownloads.state.collectAsState()
    val storage by model.storageAnalysis.state.collectAsState()
    val files by model.files.state.collectAsState()
    val installation by model.appInstallation.state.collectAsState()
    val apk by model.apkInspection.state.collectAsState()
    val media by model.fileMedia.state.collectAsState()
    val batch by model.batchTransfers.state.collectAsState()
    val uploadDraft by model.uploadDraft.state.collectAsState()
    val transfer by model.transfers.state.collectAsState()
    val previewSession by model.filePreviews.session.collectAsState()
    val thumbnails=remember(previewSession,files.root,files.path,files.contentRevision) {AndroidFileThumbnails(model.filePreviews)}
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    var batchMessage by remember(batch.noticeId) { mutableStateOf<String?>(null) }
    var downloadMessage by remember(transfer.noticeId) { mutableStateOf<String?>(null) }
    val available = state.connected && !state.busy && "files.list" in state.tools && "files.stat" in state.tools
    LaunchedEffect(available, files.root) {
        if (available && !files.loaded && !files.loading && files.error == null) model.files.refresh(true)
    }
    val canTransfer=available && listOf("task.start","task.progress","task.cancel").all { it in state.tools }
    fun path(name: String)=listOf(files.path,name).filter{it.isNotEmpty()}.joinToString("/")
    val uploadLocked = transfer.running || transfer.proposal != null || transfer.readyToSave || batch.running || batch.paused || batch.readyToSave || batch.saving
    val select=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) scope.launch {
            try {
                val sources=withContext(Dispatchers.IO) { uris.also { require(it.size <= 32) { "最多选择32个文件，请重新选择" } }.map { uri ->
                    var name="upload.bin";var size:Long?=null
                    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor -> if(cursor.moveToFirst()) {
                        val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n)
                        val s=cursor.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s)
                    } }
                    UploadSource(name,size) { checkNotNull(context.contentResolver.openInputStream(uri)) }
                } }
                model.uploadDraft.select(sources)
                uploadMessage=null
            } catch(e:CancellationException){throw e} catch(e:Exception){uploadMessage=e.message ?: "无法读取文件"}
        }
    }
    var batchSaveNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val saveBatchFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val notice = batchSaveNotice
        if (uri != null) {
            scope.launch {
                val pending = model.batchTransfers.beginFileExport(notice)
                if (pending.isEmpty()) return@launch
                var failure: String? = null
                try {
                    withContext(Dispatchers.IO) { saveBatchDocuments(context.contentResolver, uri, pending) { model.batchTransfers.fileSaved(notice, it) } }
                    batchMessage= "文件已分别保存，同名文件已自动改名"
                } catch (e: CancellationException) { failure = "保存已中断，可继续保存剩余文件"; throw e }
                catch (e: Exception) { failure = e.message ?: "保存失败，可重试剩余文件" }
                finally { model.batchTransfers.finishFileExport(notice, failure) }
            }
        }
    }
    val saveBatch=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file=model.batchTransfers.exportFile()
        if(uri!=null && file!=null) scope.launch { try {withContext(Dispatchers.IO) {checkNotNull(context.contentResolver.openOutputStream(uri)).use {out->file.inputStream().use {it.copyTo(out,64*1024)}}};batchMessage="ZIP 已保存"}
            catch(e:CancellationException){throw e}catch(_:Exception){batchMessage="保存失败，可重试"} }
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val exported=model.transfers.takeExport()
        if(exported!=null) {
            if(uri==null) model.transfers.restoreExport(exported)
            else scope.launch {
                var completed=false
                try {
                    withContext(Dispatchers.IO) {
                        val output=checkNotNull(context.contentResolver.openOutputStream(uri))
                        try { output.use { sink -> exported.file.inputStream().use { input ->
                            val buffer=ByteArray(64*1024)
                            while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0)break;sink.write(buffer,0,count) }
                        } } } catch(error:Exception) { runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) };throw error }
                    }
                    completed=true;downloadMessage="文件已保存"
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { downloadMessage="保存失败，可重试。" }
                finally { if(completed)exported.file.delete()else model.transfers.restoreExport(exported) }
            }
        }
    }
    FileBrowserPanel(files, available, { model.files.refresh(available) }, { model.files.parent(available) },
        { model.files.more(available) }, { model.files.enter(it, available) }, { model.files.inspect(it, available) },
        selectionBackHandler={ active, cancel -> androidx.activity.compose.BackHandler(active, onBack = cancel) },
        taskGroups=fileTaskGroups(batch, transfer, download, storage, apk, installation, media),
        selectionDeviceKey=state.selected,
        onDownloadSelected={ names, mode -> model.batchTransfers.download(files.root, files.path, names, mode) },
        batchDownloadEnabled=canTransfer && !transfer.running && transfer.proposal == null && !batch.running && !batch.paused && !batch.readyToSave && !batch.saving,
        directoryAvailable=available&&!transfer.running,
        onCreateDirectory=if("files.mkdir" in state.tools) ({name -> model.files.createDirectory(name,available&&!transfer.running)})else null,
        onRoot=model.files::selectRoot,
        onNavigate={ model.files.ancestor(it, available) },
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
        onApk=if(available && !apk.busy && "apps.apk_info" in state.tools) ({entry -> model.apkInspection.inspect(files.root,path(entry.name),available)}) else null,
        onMedia=if(available && "media.players" in state.tools && "media.open" in state.tools) ({entry -> model.fileMedia.inspect(files.root,path(entry.name),available,"media.publish" in state.tools && files.root!="downloads")}) else null,
        onDelete=if(available && !files.readOnly && !transfer.running && "files.delete" in state.tools) ({entry -> model.transfers.delete(files.root,path(entry.name),entry.kind=="directory" && !entry.isSymbolicLink,available && !files.readOnly) { model.files.refresh(available) } }) else null,
        onDownload=if(canTransfer&&!transfer.running) ({ entry ->
            downloadMessage=null
            model.transfers.download(files.root,path(entry.name),context.externalCacheDir ?: context.cacheDir,canTransfer)
        })else null,
        thumbnail={entry,modifier -> AndroidFileThumbnail(entry,modifier,thumbnails,files.root,path(entry.name),available && (if(entry.name.endsWith(".apk",true)) "apps.apk_icon" else "files.preview") in state.tools)},
        previewContent=if("files.preview" in state.tools) ({entry -> AndroidFilePreview(entry,model.filePreviews,files.root,path(entry.name))}) else null,
        transfer={ requested ->
            val tasksOnly = requested.startsWith("task:")
            val section = requested.removePrefix("task:")
            if (section == "upload" && !tasksOnly) {
                UploadFilesPanel(uploadDraft.map { it.name },uploadDraft.singleOrNull()?.size,files,canTransfer && !files.readOnly,uploadLocked,{ select.launch(arrayOf("*/*")) },
                    { name, overwrite ->
                        if (!uploadLocked && canTransfer && !files.readOnly) uploadDraft.singleOrNull()?.let { source ->
                            uploadMessage=null
                            model.transfers.select(source)
                            model.transfers.upload(files.root,path(name),true,overwrite)
                        }
                    }, {
                        if (!uploadLocked && canTransfer && !files.readOnly && uploadDraft.size > 1) {
                            uploadMessage=null; batchMessage=null
                            model.batchTransfers.select(uploadDraft)
                            model.batchTransfers.upload(files.root,files.path)
                        }
                    })
            }
            if (section == "upload" && !tasksOnly) FileOperationFeedback("upload",uploadMessage,batchMessage,downloadMessage)
            if(section == "batch" || section == "upload" && !tasksOnly && batch.uploadSelection && batch.noticeId != null) {
                BatchTransferPanel(batch,canTransfer,
                    model.batchTransfers::pause,model.batchTransfers::resume,model.batchTransfers::cancel,{saveBatch.launch("下载文件.zip")}, saveIndividual = {
                        batchSaveNotice = batch.noticeId; saveBatchFiles.launch(null)
                    })
                FileOperationFeedback("batch",uploadMessage,batchMessage,downloadMessage)
            }

            if(section == "url") {
                DownloadPanel(state, model, tasksOnly)
            }
            if (section == "storage") {
            if("storage.analyze" in state.tools) StorageAnalysisPanel(storage, available && !files.readOnly && "task.progress" in state.tools && "task.cancel" in state.tools,
                { model.storageAnalysis.analyze(files.root,files.path,available && !files.readOnly) }, model.storageAnalysis::cancel, tasksOnly=tasksOnly,
                onDelete=if ("files.delete" in state.tools) ({ file ->
                    storage.report?.let { report ->
                        model.transfers.delete(report.root,file.path,false,available && !uploadLocked && !storage.busy) {
                            val result=model.transfers.state.value
                            if (result.operation=="delete" && result.phase=="completed" && result.root==report.root && result.path==file.path)
                                model.storageAnalysis.fileDeleted(report.root,file.path)
                            model.files.refresh(available)
                        }
                    }
                }) else null,deleteEnabled=available && !uploadLocked && !storage.busy)
            if ("storage.analyze" !in state.tools) Text("当前服务不支持存储分析。", color=RemoteColors.Secondary)
            if (transfer.operation=="delete" && storage.report?.let {report -> report.root==transfer.root && report.largestFiles.any {it.path==transfer.path}}==true)
                FileTransferPanel(transfer,transfer.root,"",available,{},{}, model.transfers::cancel,{},onConfirm=model.transfers::confirm,operation="delete",tasksOnly=true)
            }
            if (section == "apk") {
            apk.info?.takeIf {it.root==files.root}?.let {info ->
                val entry=TargetFileEntry(info.path.substringAfterLast('/'),"file",info.sizeBytes)
                AndroidFileThumbnail(entry,androidx.compose.ui.Modifier.size(RemoteDimensions.FileGridThumbnail),thumbnails,info.root,info.path,available && "apps.apk_icon" in state.tools)
            }
            Text("APK 预览与被控设备安装",style=androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("安装将在当前连接的被控设备执行。",color=RemoteColors.Secondary)
            ApkInspectionPanel(apk, model.apkInspection::cancel)
            if("apps.install" in state.tools) AppInstallationPanel(installation, apk.info, available,
                { update -> apk.info?.let { model.appInstallation.prepare(it,update,available) } },
                model.appInstallation::confirm, model.appInstallation::cancel,
                onAdd = { apk.info?.let(model.appInstallation::add) }, onRemove = model.appInstallation::remove,
                tasksOnly = tasksOnly, installLabel = "在被控设备安装此 APK", onPrepareSet = { update -> model.appInstallation.prepareSelected(update, available) })
            if (apk.info == null && !apk.busy && apk.error == null) Text("从 APK 文件的更多菜单选择 APK 信息，读取后可准备安装。", color=RemoteColors.Secondary)
            }
            if (section == "media") {
            FileMediaPanel(media,available,model.fileMedia::choose,{model.fileMedia.open(available)},{model.fileMedia.release(available)},onMode={local -> media.players?.let{model.fileMedia.inspect(it.root,it.path,available,local)}},onCancel=model.fileMedia::cancel,onRecover=if("media.publications" in state.tools) ({model.fileMedia.recover(available)})else null,onReleaseCopy={id -> model.fileMedia.releaseCopy(id,available)},tasksOnly=tasksOnly)
            if (media.players == null && !media.busy) Text("从媒体文件的更多菜单选择在被控设备播放，选择播放器后再执行。", color=RemoteColors.Secondary)
            }
            if (section in listOf("upload", "download", "delete") && (tasksOnly || section != "upload" || transfer.operation == "upload" && transfer.phase != "idle")) {
            FileTransferPanel(transfer,files.root,files.path,canTransfer && (section != "upload" || !files.readOnly && !batch.running && !batch.paused && !batch.readyToSave && !batch.saving),{select.launch(arrayOf("*/*"))},
                {name -> downloadMessage=null;model.transfers.upload(files.root,path(name),canTransfer && !files.readOnly)},model.transfers::cancel,
                {model.transfers.prepareExport()?.let { save.launch(it) }},onOverwrite={name -> model.transfers.upload(files.root,path(name),canTransfer && !files.readOnly,true)},onConfirm=model.transfers::confirm,operation=section,tasksOnly=true)
            if (section == "download") FileOperationFeedback(section,uploadMessage,batchMessage,downloadMessage)
            }
        })
}

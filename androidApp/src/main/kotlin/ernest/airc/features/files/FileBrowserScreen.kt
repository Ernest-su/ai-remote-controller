package ernest.airc

import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

@Composable
internal fun FileBrowserScreen(state: RemoteUiState, model: RemoteViewModel) {
    val storage by model.storageAnalysis.state.collectAsState()
    val files by model.files.state.collectAsState()
    val installation by model.appInstallation.state.collectAsState()
    val apk by model.apkInspection.state.collectAsState()
    val media by model.fileMedia.state.collectAsState()
    val transfer by model.transfers.state.collectAsState()
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val available = state.connected && !state.busy && "files.list" in state.tools && "files.stat" in state.tools
    LaunchedEffect(available, files.root) {
        if (available && !files.loaded && !files.loading && files.error == null) model.files.refresh(true)
    }
    val canTransfer=available && listOf("task.start","task.progress","task.cancel").all { it in state.tools }
    fun path(name: String)=listOf(files.path,name).filter{it.isNotEmpty()}.joinToString("/")
    val select=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            try {
                val source=withContext(Dispatchers.IO) {
                    var name="upload.bin";var size:Long?=null
                    context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                        if(cursor.moveToFirst()) {
                            val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n)
                            val s=cursor.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!cursor.isNull(s))size=cursor.getLong(s)
                        }
                    }
                    UploadSource(name,size) { checkNotNull(context.contentResolver.openInputStream(uri)){"无法读取所选文件"} }
                }
                model.transfers.select(source)
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { message="无法读取所选文件信息" }
        }
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
                    completed=true;message="文件已保存"
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { message="保存失败，可重试。" }
                finally { if(completed)exported.file.delete()else model.transfers.restoreExport(exported) }
            }
        }
    }
    FileBrowserPanel(files, available, { model.files.refresh(available) }, { model.files.parent(available) },
        { model.files.more(available) }, { model.files.enter(it, available) }, { model.files.inspect(it, available) },
        directoryAvailable=available&&!transfer.running,
        onCreateDirectory=if("files.mkdir" in state.tools) ({name -> model.files.createDirectory(name,available&&!transfer.running)})else null,
        onRoot=model.files::selectRoot,
        onNavigate={ model.files.ancestor(it, available) },
        unavailableMessage=if (!state.connected) "设备未连接，连接后即可浏览和管理文件。" else if (state.busy) "设备正在执行操作，请稍后重试。" else "当前服务不支持文件浏览，请更新目标服务。",
        operationMessage=when {
            transfer.proposal != null || installation.proposal != null -> "有文件操作等待确认"
            transfer.running || apk.busy || installation.busy || media.busy || storage.busy -> "文件任务正在进行，可查看进度"
            transfer.readyToSave -> "文件已下载，可保存到本机"
            transfer.error != null || apk.error != null || installation.error != null || media.error != null || storage.error != null -> "文件任务遇到问题，请查看详情"
            transfer.phase != "idle" || installation.message != null || media.message != null || storage.report != null -> "有文件任务结果可查看"
            else -> null
        },
        onApk=if(available && !apk.busy && "apps.apk_info" in state.tools) ({entry -> model.apkInspection.inspect(files.root,path(entry.name),available)}) else null,
        onMedia=if(available && "media.players" in state.tools && "media.open" in state.tools) ({entry -> model.fileMedia.inspect(files.root,path(entry.name),available,"media.publish" in state.tools && files.root!="downloads")}) else null,
        onDelete=if(available && !transfer.running && "files.delete" in state.tools) ({entry -> model.transfers.delete(files.root,path(entry.name),entry.kind=="directory",available) { model.files.refresh(available) } }) else null,
        onDownload=if(canTransfer&&!transfer.running) ({ entry ->
            message=null
            model.transfers.download(files.root,path(entry.name),context.externalCacheDir ?: context.cacheDir,canTransfer)
        })else null,
        transfer={ section ->
            if (section == "storage") {
            if("storage.analyze" in state.tools) StorageAnalysisPanel(storage, available && "task.progress" in state.tools && "task.cancel" in state.tools,
                { model.storageAnalysis.analyze(files.root,files.path,available) }, model.storageAnalysis::cancel)
            if ("storage.analyze" !in state.tools) Text("当前服务不支持存储分析。", color=RemoteColors.Secondary)
            }
            if (section == "apk") {
            ApkInspectionPanel(apk, model.apkInspection::cancel)
            if("apps.install" in state.tools) AppInstallationPanel(installation, apk.info, available,
                { update -> apk.info?.let { model.appInstallation.prepare(it,update,available) } },
                model.appInstallation::confirm, model.appInstallation::cancel,
                if("apps.install_pending" in state.tools) ({ model.appInstallation.pending(available) }) else null,
                onAdd = { apk.info?.let(model.appInstallation::add) }, onRemove = model.appInstallation::remove,
                onPrepareSet = { update -> model.appInstallation.prepareSelected(update, available) })
            if (apk.info == null && !apk.busy && apk.error == null) Text("从 APK 文件的更多菜单选择 APK 信息，读取后可准备安装。", color=RemoteColors.Secondary)
            }
            if (section == "media") {
            FileMediaPanel(media,available,model.fileMedia::choose,{model.fileMedia.open(available)},{model.fileMedia.release(available)},onMode={local -> media.players?.let{model.fileMedia.inspect(it.root,it.path,available,local)}},onCancel=model.fileMedia::cancel,onRecover=if("media.publications" in state.tools) ({model.fileMedia.recover(available)})else null,onReleaseCopy={id -> model.fileMedia.releaseCopy(id,available)})
            if (media.players == null && !media.busy) Text("从媒体文件的更多菜单选择媒体打开，选择播放器后再执行。", color=RemoteColors.Secondary)
            }
            if (section == "transfer") {
            if("files.pending" in state.tools) ActionButton("查看模型待确认的删除",available&&!transfer.running) { model.transfers.reviewPending(available) }
            FileTransferPanel(transfer,files.root,files.path,canTransfer,{select.launch(arrayOf("*/*"))},
                {name -> message=null;model.transfers.upload(files.root,path(name),canTransfer)},model.transfers::cancel,
                {model.transfers.prepareExport()?.let { save.launch(it) }},onOverwrite={name -> model.transfers.upload(files.root,path(name),canTransfer,true)},onConfirm=model.transfers::confirm)
            message?.let { androidx.compose.material3.Text(it,color=RemoteColors.Secondary) }
            }
        })
}

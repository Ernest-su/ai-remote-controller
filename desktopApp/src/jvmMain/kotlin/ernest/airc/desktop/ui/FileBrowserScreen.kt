package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

@Composable
fun DesktopFileBrowser(state: DesktopState, controller: DesktopController) {
    val storage by controller.storageAnalysis.state.collectAsState()
    val files by controller.files.state.collectAsState()
    val installation by controller.appInstallation.state.collectAsState()
    val apk by controller.apkInspection.state.collectAsState()
    val media by controller.fileMedia.state.collectAsState()
    val transfer by controller.transfers.state.collectAsState()
    val scope=rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val available = state.connected && !state.busy && "files.list" in state.tools && "files.stat" in state.tools
    LaunchedEffect(available, files.root) {
        if (available && !files.loaded && !files.loading && files.error == null) controller.files.refresh(true)
    }
    val canTransfer=available && listOf("task.start","task.progress","task.cancel").all { it in state.tools }
    val cache=remember { File(DesktopController.defaultStateDirectory().toFile(),"downloads") }
    fun path(name: String)=listOf(files.path,name).filter{it.isNotEmpty()}.joinToString("/")
    FileBrowserPanel(files, available, { controller.files.refresh(available) }, { controller.files.parent(available) },
        { controller.files.more(available) }, { controller.files.enter(it, available) }, { controller.files.inspect(it, available) },
        directoryAvailable=available&&!transfer.running,
        onCreateDirectory=if("files.mkdir" in state.tools) ({name -> controller.files.createDirectory(name,available&&!transfer.running)})else null,
        onRoot=controller.files::selectRoot,
        onNavigate={ controller.files.ancestor(it, available) },
        unavailableMessage=if (!state.connected) "设备未连接，连接后即可浏览和管理文件。" else if (state.busy) "设备正在执行操作，请稍后重试。" else "当前服务不支持文件浏览，请更新目标服务。",
        operationMessage=when {
            transfer.proposal != null || installation.proposal != null -> "有文件操作等待确认"
            transfer.running || apk.busy || installation.busy || media.busy || storage.busy -> "文件任务正在进行，可查看进度"
            transfer.readyToSave -> "文件已下载，可保存到本机"
            transfer.error != null || apk.error != null || installation.error != null || media.error != null || storage.error != null -> "文件任务遇到问题，请查看详情"
            transfer.phase != "idle" || installation.message != null || media.message != null || storage.report != null -> "有文件任务结果可查看"
            else -> null
        },
        onApk=if(available && !apk.busy && "apps.apk_info" in state.tools) ({entry -> controller.apkInspection.inspect(files.root,path(entry.name),available)}) else null,
        onMedia=if(available && "media.players" in state.tools && "media.open" in state.tools) ({entry -> controller.fileMedia.inspect(files.root,path(entry.name),available,"media.publish" in state.tools && files.root!="downloads")}) else null,
        onDelete=if(available && !transfer.running && "files.delete" in state.tools) ({entry -> controller.transfers.delete(files.root,path(entry.name),entry.kind=="directory",available) { controller.files.refresh(available) } }) else null,
        onDownload=if(canTransfer&&!transfer.running) ({ entry ->
            message=null
            cache.mkdirs()
            runCatching { Files.setPosixFilePermissions(cache.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
            controller.transfers.download(files.root,path(entry.name),cache,canTransfer)
        }) else null,
        transfer={ section ->
            if (section == "storage") {
            if("storage.analyze" in state.tools) StorageAnalysisPanel(storage, available && "task.progress" in state.tools && "task.cancel" in state.tools,
                { controller.storageAnalysis.analyze(files.root,files.path,available) }, controller.storageAnalysis::cancel)
            if ("storage.analyze" !in state.tools) Text("当前服务不支持存储分析。", color=RemoteColors.Secondary)
            }
            if (section == "apk") {
            ApkInspectionPanel(apk, controller.apkInspection::cancel)
            if("apps.install" in state.tools) AppInstallationPanel(installation, apk.info, available,
                { update -> apk.info?.let { controller.appInstallation.prepare(it,update,available) } },
                controller.appInstallation::confirm, controller.appInstallation::cancel,
                if("apps.install_pending" in state.tools) ({ controller.appInstallation.pending(available) }) else null,
                onAdd = { apk.info?.let(controller.appInstallation::add) }, onRemove = controller.appInstallation::remove,
                onPrepareSet = { update -> controller.appInstallation.prepareSelected(update, available) })
            if (apk.info == null && !apk.busy && apk.error == null) Text("从 APK 文件的更多菜单选择 APK 信息，读取后可准备安装。", color=RemoteColors.Secondary)
            }
            if (section == "media") {
            FileMediaPanel(media,available,controller.fileMedia::choose,{controller.fileMedia.open(available)},{controller.fileMedia.release(available)},onMode={local -> media.players?.let{controller.fileMedia.inspect(it.root,it.path,available,local)}},onCancel=controller.fileMedia::cancel,onRecover=if("media.publications" in state.tools) ({controller.fileMedia.recover(available)})else null,onReleaseCopy={id -> controller.fileMedia.releaseCopy(id,available)})
            if (media.players == null && !media.busy) Text("从媒体文件的更多菜单选择媒体打开，选择播放器后再执行。", color=RemoteColors.Secondary)
            }
            if (section == "transfer") {
            if("files.pending" in state.tools) ActionButton("查看模型待确认的删除",available&&!transfer.running) { controller.transfers.reviewPending(available) }
            FileTransferPanel(transfer,files.root,files.path,canTransfer,
                onSelect={
                    val dialog=FileDialog(null as Frame?,"选择推送文件",FileDialog.LOAD)
                    try { dialog.isVisible=true;dialog.file?.let { name ->
                        val file=File(dialog.directory,name)
                        if(file.isFile) controller.transfers.select(UploadSource(file.name,file.length(),file::inputStream))
                    } } finally { dialog.dispose() }
                },
                onUpload={ name -> message=null;controller.transfers.upload(files.root,path(name),canTransfer) },
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
                                completed=true;message="文件已保存：${destination.name}"
                            }
                        } catch(e:CancellationException) { throw e }
                        catch(_:java.nio.file.FileAlreadyExistsException) { message="文件已存在，请选择新名称。" }
                        catch(_:Exception) { message="保存失败，可重试。" }
                        finally { dialog.dispose();exported?.let { if(completed)it.file.delete()else controller.transfers.restoreExport(it) } }
                    }
                }, onOverwrite={name -> controller.transfers.upload(files.root,path(name),canTransfer,true)},onConfirm=controller.transfers::confirm)
            message?.let { androidx.compose.material3.Text(it,color=RemoteColors.Secondary) }
            }
        })
}

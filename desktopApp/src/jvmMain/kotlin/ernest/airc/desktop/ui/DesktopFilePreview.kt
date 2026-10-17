package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO

internal fun desktopPreviewBitmap(bytes: ByteArray, maximum: Int): ImageBitmap {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers=ImageIO.getImageReaders(input)
        check(readers.hasNext()) {"图片格式不受支持，请下载后查看。"}
        val reader=readers.next()
        try {
            reader.input=input
            val width=reader.getWidth(0);val height=reader.getHeight(0)
            val sample=maxOf(1,(maxOf(width,height)+maximum-1)/maximum)
            val params=reader.defaultReadParam.apply {setSourceSubsampling(sample,sample,0,0)}
            val bitmap=reader.read(0,params)
            return ByteArrayOutputStream().use {output -> ImageIO.write(bitmap,"png",output);Image.makeFromEncoded(output.toByteArray()).toComposeImageBitmap()}
        } finally {reader.dispose()}
    }
}

internal class DesktopFileThumbnails(private val client: FilePreviewClient) {
    private val cache=object:LinkedHashMap<String,Pair<ImageBitmap?,String?>>(32,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Pair<ImageBitmap?,String?>>?)=size>64
    }
    fun cached(path:String)=synchronized(cache){cache[path]}
    suspend fun load(root:String,path:String,kind:FilePreviewKind,apk:Boolean=false):Pair<ImageBitmap?,String?> = client.withThumbnailPermit {
        cached(path)?.let {return@withThumbnailPermit it}
        if(apk) return@withThumbnailPermit withContext(Dispatchers.IO) {
            (desktopPreviewBitmap(client.apkIcon(root,path),160) to null).also {synchronized(cache){cache[path]=it}}
        }
        val resource=client.open(root,path)
        try {
            val result=withContext(Dispatchers.IO) {
                try {
                    when(kind) {
                        FilePreviewKind.IMAGE -> desktopPreviewBitmap(resource.read(16*1024*1024),160) to null
                        FilePreviewKind.TEXT -> null to decodeFilePreviewText(resource.read(2048,true)).text.take(300)
                        else -> null to null
                    }
                } catch(e:CancellationException){throw e} catch(_:Exception){null to null}
            }
            currentCoroutineContext().ensureActive();resource.checkCurrent()
            synchronized(cache){cache[path]=result}
            result
        } finally {resource.release()}
    }
}

@Composable
internal fun DesktopFileThumbnail(entry:TargetFileEntry,modifier:Modifier,loader:DesktopFileThumbnails,root:String,path:String,available:Boolean) {
    val apk=entry.kind=="file" && entry.name.endsWith(".apk",true)
    val kind=filePreviewKind(entry) ?: if(apk) FilePreviewKind.IMAGE else return
    var result by remember(loader,path) {mutableStateOf(loader.cached(path))}
    LaunchedEffect(loader,path,available) {
        if(available && result==null && kind in listOf(FilePreviewKind.IMAGE,FilePreviewKind.TEXT)) try {result=loader.load(root,path,kind,apk)} catch(e:CancellationException){throw e} catch(_:Exception) { }
    }
    FileThumbnail(kind,result?.first,result?.second,modifier,apk)
}

@Composable
internal fun DesktopFilePreview(entry:TargetFileEntry,client:FilePreviewClient,root:String,path:String) {
    val session by client.session.collectAsState()
    var resource by remember(session,root,path) {mutableStateOf<FilePreviewClient.Resource?>(null)}
    var bitmap by remember(session,root,path) {mutableStateOf<ImageBitmap?>(null)}
    var text by remember(session,root,path) {mutableStateOf<FilePreviewText?>(null)}
    var error by remember(session,root,path) {mutableStateOf<String?>(null)}
    var loading by remember(session,root,path) {mutableStateOf(true)}
    var retry by remember {mutableIntStateOf(0)}
    val scope=rememberCoroutineScope()
    val kind=filePreviewKind(entry)
    LaunchedEffect(session,root,path,retry) {
        loading=true;error=null;bitmap=null;text=null
        var opened:FilePreviewClient.Resource?=null
        try {
            opened=client.open(root,path);resource=opened
            when(kind) {
                FilePreviewKind.IMAGE -> bitmap=withContext(Dispatchers.IO) {desktopPreviewBitmap(opened.read(16*1024*1024),2048)}
                FilePreviewKind.TEXT -> text=withContext(Dispatchers.IO) {decodeFilePreviewText(opened.read(256*1024,true))}
                else -> Unit
            }
            loading=false
            awaitCancellation()
        } catch(e:CancellationException){throw e} catch(e:Exception){loading=false;error=e.message ?: "预览失败"}
        finally {resource=null;opened?.release()}
    }
    if(loading) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在读取预览…")}
    error?.let {Text(it,color=RemoteColors.Warning);ActionButton("重新读取",!loading){retry++}}
    bitmap?.let {FileImagePreview(it)}
    text?.let {FileTextPreview(it.text,it.encoding,(resource?.descriptor?.sizeBytes ?: 0)>256*1024)}
    resource?.takeIf {!loading && kind in listOf(FilePreviewKind.VIDEO,FilePreviewKind.AUDIO)}?.let {source ->
        Text("使用本机系统播放器打开媒体流。播放期间请保持此预览窗口打开，关闭后资源失效。")
        ActionButton("在主控设备打开播放",Desktop.isDesktopSupported()) {scope.launch {
            try {withContext(Dispatchers.IO) {Desktop.getDesktop().browse(URI(source.url))}}
            catch(e:CancellationException){throw e} catch(_:Exception){error="无法打开系统播放器，请下载后播放。"}
        }}
    }
}

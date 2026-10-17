package ernest.airc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

internal data class AndroidFileThumbnail(val bitmap: ImageBitmap? = null, val text: String? = null)

internal class AndroidFileThumbnails(private val client: FilePreviewClient) {
    private val cache = object : LruCache<String, AndroidFileThumbnail>(8*1024*1024) {
        override fun sizeOf(key: String, value: AndroidFileThumbnail) = value.bitmap?.let { it.width*it.height*4 } ?: (value.text?.length?.times(2) ?: 1)
    }
    fun cached(path: String) = cache.get(path)
    suspend fun load(root: String, path: String, kind: FilePreviewKind, apk: Boolean = false): AndroidFileThumbnail = client.withThumbnailPermit {
        cache.get(path)?.let { return@withThumbnailPermit it }
        if(apk) return@withThumbnailPermit withContext(Dispatchers.IO) {
            AndroidFileThumbnail(bitmap=decodePreviewBitmap(client.apkIcon(root,path),160)).also {cache.put(path,it)}
        }
        val resource = client.open(root,path)
        try {
            val result = withContext(Dispatchers.IO) {
                try {
                    when(kind) {
                        FilePreviewKind.IMAGE -> AndroidFileThumbnail(bitmap=decodePreviewBitmap(resource.read(16*1024*1024),160))
                        FilePreviewKind.TEXT -> AndroidFileThumbnail(text=decodeFilePreviewText(resource.read(2048,true)).text.take(300))
                        FilePreviewKind.VIDEO, FilePreviewKind.AUDIO -> {
                            val retriever=MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(resource.url, emptyMap())
                                if(kind==FilePreviewKind.VIDEO) AndroidFileThumbnail(bitmap=retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,160,160)?.asImageBitmap())
                                else AndroidFileThumbnail(bitmap=retriever.embeddedPicture?.takeIf {it.size<=16*1024*1024}?.let {decodePreviewBitmap(it,160)})
                            } finally { retriever.release() }
                        }
                    }
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) { AndroidFileThumbnail() }
            }
            currentCoroutineContext().ensureActive()
            resource.checkCurrent()
            cache.put(path,result)
            result
        } finally { resource.release() }
    }
}

internal fun decodePreviewBitmap(bytes: ByteArray, maximum: Int): ImageBitmap? {
    val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
    var sample=1
    while(bounds.outWidth/sample>maximum || bounds.outHeight/sample>maximum || bounds.outWidth.toLong()*bounds.outHeight/sample/sample>4_000_000) sample*=2
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?.asImageBitmap()
}

@Composable
internal fun AndroidFileThumbnail(entry: TargetFileEntry, modifier: Modifier, thumbnails: AndroidFileThumbnails, root: String, path: String, available: Boolean) {
    val apk=entry.kind=="file" && entry.name.endsWith(".apk",true)
    val kind=filePreviewKind(entry) ?: if(apk) FilePreviewKind.IMAGE else return
    var result by remember(thumbnails,path) {mutableStateOf(thumbnails.cached(path))}
    LaunchedEffect(thumbnails,path,available) {
        if(available && result==null) try {result=thumbnails.load(root,path,kind,apk)} catch(e:CancellationException){throw e} catch(_:Exception) { }
    }
    FileThumbnail(kind,result?.bitmap,result?.text,modifier,apk)
}

@Composable
internal fun AndroidFilePreview(entry: TargetFileEntry, client: FilePreviewClient, root: String, path: String) {
    val session by client.session.collectAsState()
    var resource by remember(session,root,path) { mutableStateOf<FilePreviewClient.Resource?>(null) }
    var bitmap by remember(session,root,path) {mutableStateOf<ImageBitmap?>(null)}
    var text by remember(session,root,path) {mutableStateOf<FilePreviewText?>(null)}
    var error by remember(session,root,path) {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    var loading by remember(session,root,path) {mutableStateOf(true)}
    val kind=checkNotNull(filePreviewKind(entry))
    LaunchedEffect(session,root,path,retry) {
        loading=true;error=null;bitmap=null;text=null
        var opened:FilePreviewClient.Resource?=null
        try {
            opened=client.open(root,path)
            resource=opened
            when(kind) {
                FilePreviewKind.IMAGE -> bitmap=withContext(Dispatchers.IO) {checkNotNull(decodePreviewBitmap(opened.read(16*1024*1024),2048)){"图片格式不受支持或文件已损坏，请下载后查看。"}}
                FilePreviewKind.TEXT -> text=withContext(Dispatchers.IO) {decodeFilePreviewText(opened.read(256*1024,true))}
                else -> Unit
            }
            loading=false
            awaitCancellation()
        } catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message ?: "预览失败";loading=false}
        finally {resource=null;opened?.release()}
    }
    if(loading) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在读取预览…")}
    error?.let {Text(it,color=RemoteColors.Warning);ActionButton("重新读取",!loading) {retry++}}
    bitmap?.let {FileImagePreview(it)}
    text?.let {FileTextPreview(it.text,it.encoding,(resource?.descriptor?.sizeBytes ?: 0)>256*1024)}
    resource?.takeIf {!loading && error==null && kind in listOf(FilePreviewKind.AUDIO,FilePreviewKind.VIDEO)}?.let {
        AndroidFilePlayer(it,kind==FilePreviewKind.VIDEO,entry.name)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun AndroidFilePlayer(resource: FilePreviewClient.Resource, video: Boolean, name: String) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val player=remember(resource) { ExoPlayer.Builder(context).setAudioAttributes(AudioAttributes.DEFAULT,true).setHandleAudioBecomingNoisy(true).build() }
    var error by remember(resource) {mutableStateOf<String?>(null)}
    var ready by remember(resource) {mutableStateOf(false)}
    var playing by remember(resource) {mutableStateOf(false)}
    var position by remember(resource) {mutableLongStateOf(0L)}
    var duration by remember(resource) {mutableLongStateOf(0L)}
    var seek by remember(resource) {mutableStateOf<Float?>(null)}
    DisposableEffect(player,owner) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean) {playing=isPlaying}
            override fun onPlaybackStateChanged(state:Int) {ready=state==Player.STATE_READY || state==Player.STATE_ENDED}
            override fun onPlayerError(failure:PlaybackException) {error="无法播放此文件（${failure.errorCodeName}），可下载后用其他播放器打开。"}
        }
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener);owner.lifecycle.addObserver(observer)
        val mime=when(name.substringAfterLast('.').lowercase()) {"mkv"->MimeTypes.VIDEO_MATROSKA;"mp4","m4v","mov"->MimeTypes.VIDEO_MP4;"webm"->MimeTypes.VIDEO_WEBM;"mp3"->MimeTypes.AUDIO_MPEG;"m4a"->MimeTypes.AUDIO_MP4;"aac"->MimeTypes.AUDIO_AAC;"flac"->MimeTypes.AUDIO_FLAC;"wav"->MimeTypes.AUDIO_WAV;"ogg","opus"->MimeTypes.AUDIO_OGG;else->null}
        player.setMediaItem(MediaItem.Builder().setUri(resource.url).setMimeType(mime).build());player.prepare()
        onDispose {owner.lifecycle.removeObserver(observer);player.removeListener(listener);player.release()}
    }
    LaunchedEffect(player) {
        while(true) {
            if(runCatching {resource.checkCurrent()}.isFailure) {player.pause();error="目标连接已改变，请重新打开预览。";break}
            position=player.currentPosition.coerceAtLeast(0);duration=player.duration.coerceAtLeast(0)
            delay(400)
        }
    }
    if(video) AndroidView(factory={PlayerView(it).apply {this.player=player;useController=false}},update={it.keepScreenOn=playing},modifier=Modifier.fillMaxWidth().height(RemoteDimensions.FilePreviewHeight).testTag("file-video-preview"),onRelease={it.player=null})
    Text(if(video) "在主控设备播放视频" else "在主控设备播放音频",style=MaterialTheme.typography.bodyMedium)
    error?.let {Text(it,color=RemoteColors.Warning)}
    Slider(value=seek ?: if(duration>0) position.toFloat()/duration else 0f,onValueChange={seek=it},onValueChangeFinished={seek?.let {player.seekTo((duration*it).toLong())};seek=null},enabled=ready && duration>0,modifier=Modifier.heightIn(min=48.dp).testTag("file-player-seek"))
    Text("${previewTime(position)} / ${previewTime(duration)}",style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton({if(player.isPlaying) player.pause() else {if(player.playbackState==Player.STATE_ENDED)player.seekTo(0);player.play()}},enabled=ready && error==null,modifier=Modifier.heightIn(min=48.dp).testTag("file-player-toggle")) {Text(if(playing) "暂停" else "播放")}
        TextButton({player.pause();player.seekTo(0)},enabled=ready,modifier=Modifier.heightIn(min=48.dp)) {Text("停止")}
    }
}
private fun previewTime(ms:Long)="${ms/60000}:${(ms/1000%60).toString().padStart(2,'0')}"

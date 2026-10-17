package ernest.airc

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private val screenshotShareMutex = Mutex()

@Composable
internal fun ScreenshotScreen(state: RemoteUiState, model: RemoteViewModel, fullscreen:Boolean=false, onFullscreen:(Boolean)->Unit={}) {
    val activity=LocalContext.current as? android.app.Activity
    DisposableEffect(fullscreen,activity) {
        val window=activity?.window
        val controller=window?.let { androidx.core.view.WindowCompat.getInsetsController(it,it.decorView) }
        val wasVisible=window?.decorView?.let { androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) } ?: true
        if(fullscreen) {
            controller?.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if(fullscreen && wasVisible) controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    val screenshot by model.screenshots.state.collectAsState()
    val dynamic by model.ascrcpyPreview.state.collectAsState()
    val dynamicActive=dynamic !is ernest.ascrcpy.scrcpy.ScrcpyState.Idle && dynamic !is ernest.ascrcpy.scrcpy.ScrcpyState.Failed
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val image by produceState<ImageBitmap?>(null, screenshot.png) {
        value = null
        val png = screenshot.png ?: return@produceState
        try { value = withContext(Dispatchers.Default) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, options)
            options.inSampleSize = 1
            while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
            options.inJustDecodeBounds = false
            checkNotNull(BitmapFactory.decodeByteArray(png, 0, png.size, options)) { "截图无法解码" }.asImageBitmap()
        } } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "截图无法解码，请重新获取。" }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val png = model.screenshots.takeExport()
        if (uri != null && png != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(png) } }
                message = "截图已保存"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "无法保存截图" }
        }
    }
    var remoteVisible by rememberSaveable { mutableStateOf(false) }
    var showResults by rememberSaveable { mutableStateOf(false) }
    val remoteState = rememberSaveableStateHolder()
    androidx.activity.compose.BackHandler(fullscreen) { onFullscreen(false) }
    if(showResults) AlertDialog(onDismissRequest={showResults=false}, title={Text("执行结果")},
        text={ androidx.compose.foundation.text.selection.SelectionContainer {
            Text(state.output, Modifier.heightIn(max=RemoteDimensions.ManagementDialogHeight).then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())))
        } }, confirmButton={TextButton({showResults=false}) {Text("关闭")}})
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
    ScreenshotPanel(image, screenshot.loading, state.connected && !state.busy && !dynamicActive && "device.screenshot" in state.tools,
        screenshot.error ?: message,
        onCapture = { message = null; model.screenshots.refresh(state.connected && !state.busy && !dynamicActive && "device.screenshot" in state.tools) },
        onSave = { model.screenshots.prepareExport(); save.launch("airc-screenshot-${System.currentTimeMillis()}.png") },
        onShare = {
            val png = screenshot.png
            if (png != null) scope.launch {
                try {
                    val uri = withContext(Dispatchers.IO) { screenshotShareMutex.withLock {
                        val directory = File(context.cacheDir, "screenshots").apply { mkdirs() }
                        // Keep each granted URI stable; bound the app-private share cache.
                        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { check(it.delete()) { "无法清理截图分享缓存" } }
                        val file = File.createTempFile("screenshot-", ".png", directory); file.writeBytes(png)
                        FileProvider.getUriForFile(context, "${context.packageName}.screenshots", file)
                    } }
                    val intent = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    context.startActivity(Intent.createChooser(intent, "分享截图"))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "无法分享截图" }
            }
        }, preview = {
            // Keep the same composition and native view ancestors across fullscreen changes.
            Surface(color=RemoteColors.Surface, shape=MaterialTheme.shapes.small) {
                Box(Modifier.padding(if(fullscreen) 0.dp else 16.dp)) {
                    AndroidScreenMirror(state, model, fullscreen, onFullscreen,
                        modifier=if(fullscreen) Modifier.height(viewportHeight) else Modifier,
                        onRemote={remoteVisible=!remoteVisible}, remoteVisible=remoteVisible)
                }
            }
        }, unavailableMessage = if(dynamicActive) "动态预览期间暂不获取单张截图。" else "请连接支持截图工具的服务。", fullscreen=fullscreen)
        if(remoteVisible) remoteState.SaveableStateProvider("floating-remote") {
            MirrorFloatingRemote(state,model,onClose={remoteVisible=false},onResults={showResults=true})
        }
    }
}

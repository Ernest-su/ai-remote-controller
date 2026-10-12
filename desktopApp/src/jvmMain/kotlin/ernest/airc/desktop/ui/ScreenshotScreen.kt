package ernest.airc.desktop

import ernest.airc.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

@Composable
fun DesktopScreenshot(state: DesktopState, controller: DesktopController) {
    val screenshot by controller.screenshots.state.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val image by produceState<ImageBitmap?>(null, screenshot.png) {
        value = null
        val png = screenshot.png ?: return@produceState
        try { value = withContext(Dispatchers.Default) { Image.makeFromEncoded(png).toComposeImageBitmap() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "截图无法解码，请重新获取。" }
    }
    ScreenshotPanel(image, screenshot.loading, state.connected && !state.busy && "device.screenshot" in state.tools,
        screenshot.error ?: message,
        onCapture = { message = null; controller.screenshots.refresh(state.connected && !state.busy && "device.screenshot" in state.tools) },
        onSave = {
            val png = screenshot.png
            if (png != null) scope.launch {
                val dialog = FileDialog(null as Frame?, "保存截图 PNG", FileDialog.SAVE)
                try {
                    dialog.file = "airc-screenshot-${System.currentTimeMillis()}.png"
                    dialog.isVisible = true
                    val file = dialog.file
                    if (file != null) {
                        val destination = Path.of(dialog.directory, file)
                        withContext(Dispatchers.IO) { DesktopScreenshotFiles.save(destination, png) }
                        message = "截图已保存：$destination"
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: java.nio.file.FileAlreadyExistsException) { message = "文件已存在，请选择新名称。" }
                catch (_: Exception) { message = "无法保存截图" }
                finally { dialog.dispose() }
            }
        }, unavailableMessage = "请连接支持截图工具的服务。")
}

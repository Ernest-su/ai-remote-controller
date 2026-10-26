package ernest.airc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** App-owned browser without JavaScript bridges or access to local files. */
class ProjectWebActivity : ComponentActivity() {
    internal var browser: WebView? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")?.takeIf {
            it == ProjectLinks.DOCUMENTATION || it == ProjectLinks.REPOSITORY
        } ?: run { finish(); return }
        val title = if (url == ProjectLinks.DOCUMENTATION) "项目文档" else "项目仓库"
        setContent {
            val stored = getSharedPreferences("appearance", MODE_PRIVATE).getString("theme", null)
            val dark = ThemeMode.fromStored(stored).isDark(isSystemInDarkTheme())
            val palette = if (dark) DarkRemotePalette else LightRemotePalette
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(palette.Header.toArgb()) else SystemBarStyle.light(palette.Header.toArgb(), palette.Header.toArgb()),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(palette.Background.toArgb()) else SystemBarStyle.light(palette.Background.toArgb(), palette.Background.toArgb()),
                )
            }
            var progress by remember { mutableIntStateOf(0) }
            var failed by remember { mutableStateOf(false) }
            fun back() { browser?.let { if (it.canGoBack()) it.goBack() else finish() } ?: finish() }
            BackHandler { back() }
            RemoteTheme(darkTheme = dark) {
                Surface(color = RemoteColors.Background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("project-web-page")) {
                        Surface(color = RemoteColors.Header) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ back() }, Modifier.size(48.dp).testTag("web-back")) {
                                    Icon(painterResource(R.drawable.ic_back), "返回", Modifier.size(24.dp))
                                }
                                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                TextButton({ finish() }, Modifier.heightIn(min = 48.dp).testTag("web-close")) { Text("关闭") }
                            }
                        }
                        if (progress < 100 && !failed) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        if (failed) Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("网页加载失败，请检查网络后重试。", color = RemoteColors.Secondary)
                            TextButton({ failed = false; browser?.reload() }, Modifier.heightIn(min = 48.dp).testTag("web-retry")) { Text("重试") }
                        }
                        AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).testTag("project-web-view"), factory = { context ->
                            WebView(context).apply {
                                browser = this
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, value: Int) { progress = value }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"
                                    override fun onPageStarted(view: WebView, target: String, favicon: android.graphics.Bitmap?) { failed = false }
                                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); failed = true }
                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) failed = true }
                                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame) failed = true }
                                }
                                val saved = savedInstanceState?.getBundle("web-state")
                                if (saved == null || restoreState(saved) == null) loadUrl(url)
                            }
                        })
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        browser?.let { view -> outState.putBundle("web-state", Bundle().also { view.saveState(it) }) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        browser?.apply { stopLoading(); destroy() }
        browser = null
        super.onDestroy()
    }

    companion object {
        fun open(context: Context, url: String) {
            context.startActivity(Intent(context, ProjectWebActivity::class.java).putExtra("url", url))
        }
    }
}

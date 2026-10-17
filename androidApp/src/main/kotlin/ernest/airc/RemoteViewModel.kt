package ernest.airc

import kotlinx.coroutines.flow.first

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

data class RemoteUiState(
    val status: String = "请选择或连接目标设备",
    val output: String = "",
    val busy: Boolean = false,
    val submitting: Boolean = false,
    val connected: Boolean = false,
    val tools: List<String> = emptyList(),
    val touchBusy: Boolean = false,
    val feedback: RemoteFeedback = RemoteFeedback(),
    val mediaStatus: String = "未连接设备",
    val history: List<RememberedDevice> = emptyList(),
    val discovered: List<DiscoveredService> = emptyList(),
    val adbDevices: List<String> = emptyList(),
    val adbScanning: Boolean = false,
    val adbScanStatus: String = "打开设备页后扫描同网段 ADB",
    internal val manualConnectionTarget: ManualConnectionTarget? = null,
    val selected: String? = null,
    val version: String? = null,
    val modelConfigured: Boolean = false,
    val qr: PairingQr? = null,
    val preferRoot: Boolean = false,
    val keyHapticsEnabled: Boolean = true,
    val runtimeUid: Int? = null,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = false,
    val appsError: String? = null,
    val deviceInfo: DeviceInfo? = null,
    val deviceInfoLoading: Boolean = false,
    val deviceInfoError: String? = null,
)

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DeviceStore(application)
    private val connector = AdbConnector(application)
    private val installer = RuntimeInstaller(application)
    private val runtime = RuntimeConnection { application.resources.configuration.locales[0].toLanguageTag() }
    val screenshots = ScreenshotController(viewModelScope, runtime::screenshot)
    val ascrcpyPreview = AscrcpyPreviewController(getApplication(),viewModelScope) {
        val record=state.value.history.find{it.id==state.value.selected} ?: error("请先选择目标设备")
        val host=record.host ?: error("没有保存目标地址")
        val port=record.adbPort ?: error("请先在设备页通过 ADB 连接此设备")
        record.wirelessGuid?.let{connector.wireless(it,host,port)} ?: connector.tcp(host,port)
    }
    val transfers = FileTransferController(viewModelScope, runtime)
    val files = FileBrowserController(viewModelScope,runtime)
    val mediaControl = MediaControlController(viewModelScope, runtime)
    val virtualGamepad = VirtualGamepadController(viewModelScope, runtime)
    val gamepad = GamepadController(viewModelScope, runtime)
    val storageAnalysis = StorageAnalysisController(viewModelScope, runtime)
    val appIcons = AppIconController(viewModelScope, runtime)
    val appInstallation = AppInstallationController(viewModelScope, runtime)
    val apkInspection = ApkInspectionController(viewModelScope, runtime)
    val filePreviews = FilePreviewClient(runtime)
    val fileMedia = FileMediaController(viewModelScope,runtime)
    val appControl = AppControlController(viewModelScope,runtime)
    val appManagement = AppManagementController(viewModelScope,runtime)
    val systemObservation = SystemObservationController(viewModelScope,runtime)
    val systemActions = SystemActionController(viewModelScope,runtime,systemObservation::clear)
    val appCache = AppCacheController(viewModelScope,runtime)
    val diagnostics = NetworkDiagnostics(viewModelScope)
    internal val profiles = ModelProfileController(EncryptedModelProfileStore(application), ModelCatalogClient(), viewModelScope)
    private var adb: AdbSession? = null
    private var automaticReconnectEnabled = true
    private var connectionJob: Job? = null
    private val automaticConnection = AutomaticConnection(viewModelScope) { device ->
        if (!state.value.busy && !state.value.connected) {
            reconnectDevice(device, automatic = true)
            connectionJob?.join()
        }
        state.value.connected
    }
    private var adbScanJob: Job? = null
    private var adbScanGeneration = 0L
    private var deviceInfoJob: Job? = null
    private var deviceInfoGeneration = 0L
    private var appsJob: Job? = null
    private var appsGeneration = 0L
    private var gestureGeneration = 0L
    private val feedbackGeneration = AtomicLong()
    private val releasePreferences = application.getSharedPreferences("runtime-release", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(RemoteUiState(history = store.read(), preferRoot = releasePreferences.getBoolean("prefer-root", false),
        keyHapticsEnabled = application.getSharedPreferences("remote-controls", android.content.Context.MODE_PRIVATE).getBoolean("key-haptics", true)))
    val state = mutable.asStateFlow()
    private val discovery = ServiceDiscovery(application, RUNTIME_SERVICE_TYPE,
        onFound = { service ->
            if (service.attributes["protocol"] == PROTOCOL_VERSION.toString()) mutable.update {
                it.copy(discovered = it.discovered.filterNot { old -> old.name == service.name } + service)
            }
        },
        onLost = { name -> mutable.update { it.copy(discovered = it.discovered.filterNot { service -> service.name == name }) } },
        onError = { error -> mutable.update { it.copy(status = "局域网发现：$error") } },
    )
    init {
        discovery.start()
        viewModelScope.launch {
            runtime.connected.collect { connected ->
                val lostConnection = state.value.connected && !connected
                if (!connected) { deviceInfoGeneration++; deviceInfoJob?.cancel(); appsGeneration++; appsJob?.cancel(); gestureGeneration++ }
                mutable.update { if (connected) it.copy(connected = true)
                    else it.copy(connected = false, tools = emptyList(), touchBusy = false, deviceInfo = null, deviceInfoLoading = false, deviceInfoError = null, apps = emptyList(), appsLoading = false, appsError = null) }
                if (lostConnection && automaticReconnectEnabled) {
                    state.value.history.find { it.id == state.value.selected }?.let(automaticConnection::start)
                }
            }
        }
        val lastId = releasePreferences.getString("last-connected-device", null)
        (state.value.history.find { it.id == lastId } ?: state.value.history.filter { it.runtimePort != null }.maxByOrNull { it.lastConnectedAt })
            ?.let(automaticConnection::start)
    }
    // Prefer structured media snapshots; older runtimes retain shell compatibility.
    private val mediaMonitor = viewModelScope.launch {
        runtime.session.collectLatest { session ->
            if (session == null) {
                appIcons.clear(); screenshots.clear(); ascrcpyPreview.stop(); files.clear(); transfers.clear(); fileMedia.clear(); appManagement.clear(); appControl.clear(); appCache.clear(); systemActions.clear(); systemObservation.clear(); apkInspection.clear(); appInstallation.clear(); storageAnalysis.clear(); mediaControl.clear(); gamepad.clear(); virtualGamepad.clear()
                mutable.update { it.copy(mediaStatus = "未连接设备") }
            } else while (true) {
                if (runtime.session.value != session) return@collectLatest
                val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return@collectLatest
                val status = try {
                    if ("media.status" in state.value.tools) structuredMediaStatusLabel(send("media.status", buildJsonObject {}))
                    else mediaStatusLabel(send("device.shell", buildJsonObject { put("command", "dumpsys media_session") }))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { "暂时无法获取播放状态" }
                if (runtime.session.value == session) mutable.update { it.copy(mediaStatus = status) }
                delay(3_000)
            }
        }
    }

    private fun feedback(phase: FeedbackPhase, text: String = "", token: Long? = null): Long {
        val generation = token ?: feedbackGeneration.incrementAndGet()
        mutable.update {
            if (generation != feedbackGeneration.get()) it
            else {
                val previous = if (token == null) RemoteFeedback(revision = it.feedback.revision) else it.feedback
                it.copy(feedback = previous.updated(phase, text))
            }
        }
        return generation
    }
    private fun progress(token: Long, event: RuntimeProgress) {
        progressPhase(event.phase)?.let { feedback(it, event.text, token) }
    }
    fun setKeyHapticsEnabled(enabled: Boolean) {
        getApplication<Application>().getSharedPreferences("remote-controls", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("key-haptics", enabled).apply()
        mutable.update { it.copy(keyHapticsEnabled = enabled) }
    }
    fun showMessage(message: String) {
        mutable.update { it.copy(output = message) }
        feedback(FeedbackPhase.ERROR, message)
    }
    fun scanAdbDevices() {
        if (adbScanJob?.isActive == true) return
        val generation = ++adbScanGeneration
        adbScanJob = viewModelScope.launch {
            val range = wifiScanRange(getApplication())
            if (range == null) {
                mutable.update { it.copy(adbDevices = emptyList(), adbScanning = false, adbScanStatus = "请连接 Wi-Fi 后扫描同网段 ADB") }
                return@launch
            }
            mutable.update { it.copy(adbDevices = emptyList(), adbScanning = true, adbScanStatus = "正在扫描 ${range.label} · ADB 5555") }
            try {
                AdbSubnetProbe.scan(range.hosts) { host ->
                    mutable.update { if (generation == adbScanGeneration) it.copy(adbDevices = (it.adbDevices + host).distinct().sorted()) else it }
                }
                mutable.update { it.copy(adbScanStatus = "扫描完成 · ${range.label} · ${it.adbDevices.size} 台 ADB 设备") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(adbScanStatus = "ADB 扫描失败：${e.message}") } }
            finally { mutable.update { if (generation == adbScanGeneration) it.copy(adbScanning = false) else it } }
        }
    }
    fun cancelAdbScan() {
        adbScanGeneration++
        adbScanJob?.cancel(); adbScanJob = null
        mutable.update { if (it.adbScanning) it.copy(adbScanning = false, adbScanStatus = "扫描已停止，返回页面后重新扫描") else it }
    }
    fun startDiscovery() = discovery.start()
    fun stopDiscovery() { cancelAdbScan(); discovery.close(); mutable.update { it.copy(discovered = emptyList()) } }

    private fun connection(disconnectRuntime: Boolean = true, automatic: Boolean = false, action: suspend () -> Unit) {
        if (state.value.busy) return
        if (!automatic && disconnectRuntime) { automaticReconnectEnabled = false; automaticConnection.stop() }
        connectionJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, status = "正在连接…") }
            try {
                if (disconnectRuntime) {
                    feedbackGeneration.incrementAndGet()
                    mutable.update { it.copy(feedback = RemoteFeedback(revision = it.feedback.revision + 1L)) }
                    runtime.disconnect()
                }
                action()
            }
            catch (e: CancellationException) { mutable.update { it.copy(status = "已取消") }; throw e }
            catch (e: Exception) {
                val message = e.message ?: "连接失败"
                mutable.update { it.copy(status = message) }
                feedback(FeedbackPhase.ERROR, message)
            }
            finally { mutable.update { it.copy(busy = false, qr = null) } }
        }
    }
    fun diagnose(host: String, port: Int) {
        val session = adb?.takeIf { it.host == host.trim() }
        val probe: (suspend () -> String)? = session?.let { selected -> { withContext(Dispatchers.IO) { selected.client.shell("id -u").text().trim().let { uid -> check(uid.toIntOrNull() != null) { "ADB 身份查询失败，请检查授权。" }; "已授权会话，UID $uid" } } } }
        diagnostics.run(host, port, probe) {
            val found = state.first { current -> current.discovered.any { it.host == host.trim() && it.port == port } }
                .discovered.first { it.host == host.trim() && it.port == port }
            DiagnosticStage("mDNS", DiagnosticOutcome.PASSED, null, "已观察到匹配公告：${found.name}")
        }
    }

    fun tcp(host: String, port: Int, runtimePort: Int) = connection {
        attach(connector.tcp(host.trim(), port), ConnectionMethod.TCP, runtimePort)
    }
    fun pair(host: String, pairingPort: Int, code: String, runtimePort: Int, connectionPort: Int? = null) = connection {
        mutable.update { it.copy(status = "配对并发现目标连接端口…") }
        attach(connector.pair(host.trim(), pairingPort, code, connectionPort), ConnectionMethod.WIRELESS_CODE, runtimePort)
    }
    fun qr(runtimePort: Int) = connection {
        val qr = PairingQr()
        mutable.update { it.copy(qr = qr, status = "请用目标设备无线调试页面扫描二维码") }
        attach(connector.qr(qr), ConnectionMethod.WIRELESS_QR, runtimePort)
    }
    fun cancelConnection() { automaticReconnectEnabled = false; automaticConnection.stop(); connectionJob?.cancel() }

    private suspend fun attach(session: AdbSession, method: ConnectionMethod, runtimePort: Int, releaseUrl: String? = null) {
        if (adb !== session) adb?.close()
        adb = session
        try {
            val model = withContext(Dispatchers.IO) { session.client.shell("getprop ro.product.model").text().trim() }
            val known = state.value.history.find {
                (session.guid != null && it.wirelessGuid == session.guid) || (it.host == session.host && it.adbPort == session.port)
            }
            val adbRecord = RememberedDevice(known?.id ?: "adb:${session.guid ?: "${session.host}:${session.port}"}",
                model.ifBlank { known?.name ?: "AI 设备" }, method, session.host, session.port, session.guid,
                known?.runtimePort, System.currentTimeMillis())
            // A successful ADB session survives an interrupted or failed deployment.
            val history = withContext(Dispatchers.IO) { store.remember(adbRecord) }
            mutable.update { it.copy(history = history) }
            val release = installer.install(session.client, releaseUrl, runtimePort, state.value.preferRoot) { message -> mutable.update { it.copy(status = message) } }
            mutable.update { it.copy(status = "启动目标服务…") }
            val actualPort = installer.start(session.client, runtimePort, state.value.preferRoot)
            try {
                attachRuntime(session.host, actualPort, method, session.port, session.guid, model, previousId = adbRecord.id, expectedRelease = release)
            } catch (e: RuntimeAlignmentRequired) {
                // The file may already be current while an older executable is still running.
                mutable.update { it.copy(status = "目标文件已对齐，正在重启旧服务…") }
                val restartedPort = installer.restart(session.client, actualPort, state.value.preferRoot)
                attachRuntime(session.host, restartedPort, method, session.port, session.guid, model, previousId = adbRecord.id, expectedRelease = release)
            }
        } catch (e: Exception) { session.close(); if (adb === session) adb = null; throw e }
    }

    private suspend fun attachRuntime(host: String, port: Int, method: ConnectionMethod?, adbPort: Int?, guid: String?, model: String?, previousId: String? = null, expectedRelease: RuntimeRelease? = null) {
        appIcons.clear(); screenshots.clear(); ascrcpyPreview.stop(); files.clear(); transfers.clear(); fileMedia.clear(); appManagement.clear(); appControl.clear(); appCache.clear(); systemActions.clear(); systemObservation.clear(); apkInspection.clear(); appInstallation.clear(); storageAnalysis.clear(); mediaControl.clear(); gamepad.clear(); virtualGamepad.clear()
        val status = runtime.connect(host, port, expectedRelease ?: withContext(Dispatchers.IO) { installer.bundledRelease() })
        val previous = state.value.history.find { it.id == status.deviceId }
        val record = RememberedDevice(status.deviceId, model?.ifBlank { null } ?: previous?.name ?: status.name ?: "AI 设备",
            method ?: previous?.method ?: ConnectionMethod.TCP, host, adbPort ?: previous?.adbPort,
            guid ?: previous?.wirelessGuid, port, System.currentTimeMillis())
        val history = withContext(Dispatchers.IO) { store.remember(record, previousId) }
        releasePreferences.edit().putString("last-connected-device", record.id).apply()
        automaticReconnectEnabled = true
        mutable.update { it.copy(status = "已连接 ${history.first { it.id == record.id }.visibleName}", selected = record.id, version = status.version, tools = status.tools, runtimeUid = status.uid, modelConfigured = status.modelConfigured, history = history) }
    }

    fun discovered(service: DiscoveredService, adbPort: Int? = null) = connection {
        adb?.close(); adb = null
        val known = state.value.history.firstOrNull { it.id == service.attributes["id"] }
        connectWithDeploymentFallback(service.host, service.port, adbPort ?: known?.adbPort, known?.wirelessGuid,
            known?.method ?: ConnectionMethod.TCP, service.attributes["name"])
    }

    fun reconnect(device: RememberedDevice) = reconnectDevice(device, automatic = false)

    private fun reconnectDevice(device: RememberedDevice, automatic: Boolean) = connection(automatic = automatic) {
        adb?.close(); adb = null
        // Reuse LAN only after its running bytes match the bundled release.
        val live = state.value.discovered.find { it.attributes["id"] == device.id }
        val host = live?.host ?: device.host ?: error("没有保存目标地址")
        val port = live?.port ?: device.runtimePort ?: 9876
        connectWithDeploymentFallback(host, port, device.adbPort, device.wirelessGuid, device.method, device.name)
    }

    private suspend fun connectWithDeploymentFallback(host: String, port: Int, adbPort: Int?, guid: String?, method: ConnectionMethod, name: String?) {
        val target = ManualConnectionTarget(host, adbPort ?: 5555, port)
        connectLanOrDeploy(target,
            connectRuntime = { attachRuntime(host, port, method, adbPort, guid, name) },
            deploy = {
                val session = if (guid != null) connector.wireless(guid, host, adbPort)
                    else connector.tcp(host, target.adbPort)
                attach(session, if (guid == null) ConnectionMethod.TCP else method, port)
            },
            onDeploying = { message -> mutable.update { it.copy(status = message) } },
            onDeploymentFailed = { failed -> mutable.update { it.copy(manualConnectionTarget = failed) } },
        )
    }

    internal fun consumeManualConnectionTarget(target: ManualConnectionTarget) {
        mutable.update { if (it.manualConnectionTarget == target) it.copy(manualConnectionTarget = null) else it }
    }

    fun rename(id: String, name: String) { viewModelScope.launch {
        try {
            val history = withContext(Dispatchers.IO) { store.rename(id, name) }
            mutable.update { it.copy(history = history) }
        } catch (e: Exception) { mutable.update { it.copy(status = e.message ?: "无法保存设备显示名") } }
    } }

    fun forget(id: String) { viewModelScope.launch(Dispatchers.IO) { val history = store.remove(id); mutable.update { it.copy(history = history) } } }
    private fun execute(feedbackToken: Long? = null, action: suspend () -> String) {
        val session = runtime.session.value
        viewModelScope.launch {
            try {
                val result = action()
                if (session == null || runtime.session.value != session) return@launch
                if (result.isNotBlank()) mutable.update { it.copy(output = result) }
                if (feedbackToken != null) feedback(FeedbackPhase.COMPLETE, result, feedbackToken)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (session == null || runtime.session.value != session) return@launch
                val message = e.message ?: "执行失败"
                mutable.update { it.copy(output = message) }
                if (feedbackToken != null) feedback(FeedbackPhase.ERROR, message, feedbackToken)
                else feedback(FeedbackPhase.ERROR, message)
            }
        }
    }
    fun key(key: String) {
        if (!state.value.connected) return
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return
        execute { send("remote.key", buildJsonObject { put("key", key) }) }
    }
    fun refreshDeviceInfo() {
        if (!state.value.connected || state.value.deviceInfoLoading) return
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return
        val deviceId = state.value.selected
        val generation = ++deviceInfoGeneration
        mutable.update { it.copy(deviceInfoLoading = true, deviceInfoError = null) }
        deviceInfoJob = viewModelScope.launch {
            try {
                val result = send("device.info", buildJsonObject {})
                val info = Json { ignoreUnknownKeys = true }.decodeFromString<DeviceInfo>(result)
                mutable.update { if (generation == deviceInfoGeneration && it.connected && it.selected == deviceId) it.copy(deviceInfo = info) else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { if (generation == deviceInfoGeneration && it.connected && it.selected == deviceId) it.copy(deviceInfo = null,
                    deviceInfoError = e.message ?: "无法查询设备信息") else it }
            } finally {
                mutable.update { if (generation == deviceInfoGeneration) it.copy(deviceInfoLoading = false) else it }
            }
        }
    }
    fun gesture(tool: String, arguments: kotlinx.serialization.json.JsonObject): Boolean {
        if (!state.value.connected || state.value.touchBusy || tool !in state.value.tools) return false
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return false
        val generation = ++gestureGeneration
        mutable.update { it.copy(touchBusy = true) }
        val token = feedback(FeedbackPhase.TOOL)
        execute(token) {
            try { send(tool, arguments) }
            finally { mutable.update { if (generation == gestureGeneration) it.copy(touchBusy = false) else it } }
        }
        return true
    }
    fun listApps() {
        if (!state.value.connected || state.value.appsLoading) return
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return
        appIcons.clear()
        val generation = ++appsGeneration
        val deviceId = state.value.selected
        mutable.update { it.copy(appsLoading = true, appsError = null) }
        appsJob = viewModelScope.launch {
            try {
                val apps = decodeInstalledApps(send("apps.list", buildJsonObject {}))
                mutable.update { if (generation == appsGeneration && it.connected && it.selected == deviceId) it.copy(apps = apps) else it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { if (generation == appsGeneration) it.copy(appsError = e.message ?: "无法查询应用列表") else it }
            } finally {
                mutable.update { if (generation == appsGeneration) it.copy(appsLoading = false) else it }
            }
        }
    }
    fun openApp(packageName: String) {
        if (!state.value.connected) return
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return
        execute { send("apps.open", buildJsonObject { put("package", packageName.trim()) }) }
    }
    private var textSubmissionGeneration = 0L
    fun text(text: String): Boolean {
        if (text.isBlank() || state.value.submitting) return false
        if (!state.value.connected) { showMessage("请先连接设备"); return false }
        if (!state.value.modelConfigured) { showMessage("请先在「功能 → 模型配置」配置目标设备的模型"); return false }
        val send = runCatching { runtime.textWriter() }.getOrNull() ?: return false
        val submission = ++textSubmissionGeneration
        mutable.update { it.copy(output = "正在思考…", submitting = true) }
        val token = feedback(FeedbackPhase.THINKING)
        execute(token) {
            try { send(text.trim()) { progress(token, it) } }
            finally { if (submission == textSubmissionGeneration) mutable.update { it.copy(submitting = false) } }
        }
        return true
    }

    fun syncModelProfile() {
        if (!state.value.connected || state.value.busy) return
        val write = runCatching { runtime.configurationWriter() }.getOrNull() ?: return
        val target = state.value.selected
        profiles.sync(state.value.connected && !state.value.busy) { profile ->
            check(state.value.connected && !state.value.busy && target == state.value.selected) { "目标连接已改变" }
            write(profile)
            mutable.update { if (it.selected == target && it.connected && !it.busy) it.copy(modelConfigured = true) else it }
        }
    }

    fun configure(baseUrl: String, key: String, model: String) = execute {
        val result = runtime.request("configure", arguments = buildJsonObject {
            put("baseUrl", baseUrl.trim()); put("apiKey", key); put("model", model.trim())
        })
        mutable.update { it.copy(modelConfigured = true) }
        result
    }

    private suspend fun selectedAdb(): AdbSession {
        adb?.let { return it }
        val record = state.value.history.find { it.id == state.value.selected } ?: error("请先选择目标设备")
        val port = record.adbPort ?: error("请先在设备页通过 ADB 连接此设备")
        val host = record.host ?: error("没有保存目标地址")
        val session = record.wirelessGuid?.let { connector.wireless(it, host, port) } ?: connector.tcp(host, port)
        adb = session
        val history = withContext(Dispatchers.IO) { store.remember(record.copy(host = session.host, adbPort = session.port)) }
        mutable.update { it.copy(history = history) }
        return session
    }
    fun serviceStatus() = connection(disconnectRuntime = false) {
        val output = installer.status(selectedAdb().client, state.value.preferRoot)
        mutable.update { it.copy(output = output, status = "已查询目标服务状态") }
    }
    fun setPreferRoot(value: Boolean) = connection(disconnectRuntime = false) {
        withContext(Dispatchers.IO) {
            check(releasePreferences.edit().putBoolean("prefer-root", value).commit()) { "无法保存 root 设置" }
        }
        mutable.update { it.copy(preferRoot = value) }
        if (state.value.selected != null) restartSelectedService()
        else mutable.update { it.copy(status = "已保存运行权限设置", output = if (value) "下次 ADB 部署时自动检测 root" else "下次 ADB 部署时使用 shell 权限") }
    }
    private suspend fun applyRuntime() {
        val session = selectedAdb()
        val port = state.value.history.find { it.id == state.value.selected }?.runtimePort ?: 9876
        attach(session, state.value.history.find { it.id == state.value.selected }?.method ?: ConnectionMethod.TCP, port, RuntimeReleaseRepository.githubManifest().toString())
    }
    fun updateRuntime() = connection(disconnectRuntime = false) { applyRuntime() }
    private suspend fun restartSelectedService() {
        val session = selectedAdb()
        val record = state.value.history.find { it.id == state.value.selected } ?: error("请先选择目标设备")
        installer.install(session.client, runtimePort = record.runtimePort ?: 9876, preferRoot = state.value.preferRoot) { message -> mutable.update { it.copy(status = message) } }
        val port = installer.restart(session.client, record.runtimePort ?: 9876, state.value.preferRoot)
        attachRuntime(session.host, port, record.method, session.port, session.guid, record.name)
    }
    fun restartService() = connection { restartSelectedService() }
    fun stopService() = connection(disconnectRuntime = false) {
        automaticReconnectEnabled = false
        automaticConnection.stop()
        val result = installer.stop(selectedAdb().client, state.value.preferRoot)
        runtime.disconnect()
        mutable.update { it.copy(output = result, status = "目标服务已停止") }
    }
    fun disconnect() {
        automaticReconnectEnabled = false
        automaticConnection.stop()
        connectionJob?.cancel()
        ascrcpyPreview.stop()
        viewModelScope.launch {
            runtime.disconnect(); adb?.close(); adb = null
            mutable.update { it.copy(status = "已断开", runtimeUid = null) }
        }
    }

    override fun onCleared() {
        automaticConnection.stop()
        ascrcpyPreview.stop()
        discovery.close(); transfers.close(); filePreviews.close(); runtime.close(); profiles.close(); adb?.close()
        super.onCleared()
    }
}

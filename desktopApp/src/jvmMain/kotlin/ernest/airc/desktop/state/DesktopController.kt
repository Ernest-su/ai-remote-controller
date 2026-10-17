package ernest.airc.desktop

import ernest.airc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

class DesktopController(
    private val adb: HostAdb = HostAdb(),
    store: ModelProfileStore = DesktopProfileStore(defaultStateDirectory()),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val runtime: RuntimeConnection = RuntimeConnection(),
    private val project: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { it.resolve("android-build-run.sh").toFile().isFile } ?: Path.of("").toAbsolutePath(),
    catalog: ModelCatalog = ModelCatalogClient(),
    private val deviceNames: DesktopDeviceNames = DesktopDeviceNames(defaultStateDirectory()),
) : AutoCloseable {
    private val mutable = MutableStateFlow(DesktopState())
    val state = mutable.asStateFlow()
    val profiles = ModelProfileController(store, catalog, scope)
    val screenshots = ScreenshotController(scope, runtime::screenshot)
    val transfers = FileTransferController(scope, runtime)
    val files = FileBrowserController(scope,runtime)
    val mediaControl = MediaControlController(scope, runtime)
    val virtualGamepad = VirtualGamepadController(scope, runtime)
    val gamepad = GamepadController(scope, runtime)
    val storageAnalysis = StorageAnalysisController(scope, runtime)
    val appIcons = AppIconController(scope, runtime)
    val appInstallation = AppInstallationController(scope, runtime)
    val apkInspection = ApkInspectionController(scope, runtime)
    val filePreviews = FilePreviewClient(runtime)
    val fileMedia = FileMediaController(scope,runtime)
    val appControl = AppControlController(scope,runtime)
    val appManagement = AppManagementController(scope,runtime)
    val systemObservation = SystemObservationController(scope,runtime)
    val systemActions = SystemActionController(scope,runtime,systemObservation::clear)
    val appCache = AppCacheController(scope,runtime)
    val diagnostics = NetworkDiagnostics(scope)
    private val json = Json { ignoreUnknownKeys = true }
    private var generation = 0L
    private var feedbackGeneration = 0L
    private var forward: Pair<String, Int>? = null
    private var connectionJob: Job? = null
    init {
        scope.launch {
            runtime.session.collectLatest { session ->
                if (session == null) { appIcons.clear(); screenshots.clear(); files.clear(); transfers.clear(); fileMedia.clear(); appManagement.clear(); appControl.clear(); appCache.clear(); systemActions.clear(); systemObservation.clear(); apkInspection.clear(); appInstallation.clear(); storageAnalysis.clear(); mediaControl.clear(); gamepad.clear(); virtualGamepad.clear(); mutable.update { it.copy(connected = false, apps = emptyList(), appsLoading = false, appsError = null, deviceInfo = null, tools = emptyList(), media = "未连接设备") } }
                else while (true) {
                    if (runtime.session.value != session) return@collectLatest
                    val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return@collectLatest
                    val media = try {
                        if ("media.status" in state.value.tools) structuredMediaStatusLabel(send("media.status", buildJsonObject {}))
                        else mediaStatusLabel(send("device.shell", buildJsonObject { put("command", "dumpsys media_session") }))
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "暂时无法获取播放状态" }
                    if (runtime.session.value == session) mutable.update { it.copy(media = media) }
                    delay(3000)
                }
            }
        }
    }
    private fun operation(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        connectionJob = scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(e.message ?: "操作失败") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun diagnose(host: String, port: Int) {
        val serial = state.value.serial?.takeIf { state.value.host == host.trim() && state.value.port == port }
        val probe: (suspend () -> String)? = serial?.let { selected -> { adb.command("-s", selected, "shell", "id -u").trim().let { uid -> check(uid.toIntOrNull() != null) { "ADB 身份查询失败，请检查授权。" }; "已授权会话，UID $uid" } } }
        val discoveryHost = if (serial != null) DesktopDiscovery.tcpAdbHost(serial) else host.trim()
        val discoveryPort = if (serial != null) state.value.runtimePort else port
        diagnostics.run(host, port, probe, discoveryTimeoutMs = 12000) {
            if (discoveryHost == null) DiagnosticStage("mDNS", DiagnosticOutcome.SKIPPED, null, "USB ADB 会话没有设备局域网地址；请填写设备 IP 进行发现诊断。")
            else DesktopDiscovery().probe(discoveryHost, discoveryPort)
        }
    }
    fun refreshAdb() = operation { mutable.update { it.copy(adbDevices = adb.devices()) } }
    fun connectAdb(endpoint: String) = operation { adb.connect(endpoint.trim()); mutable.update { it.copy(adbDevices = adb.devices()) } }
    private suspend fun releaseForward() {
        val previous = forward; forward = null
        if (previous != null) runCatching { adb.removeForward(previous.first, previous.second) }
    }
    private suspend fun bundledRelease(): RuntimeRelease = withContext(Dispatchers.IO) {
        val file = project.resolve("androidApp/src/main/assets/runtime/manifest.json").toFile()
        check(file.isFile) { "缺少配套设备端程序清单，请从项目目录启动并先构建设备端程序" }
        json.decodeFromString<RuntimeRelease>(file.readText()).also { it.validate(1) }
    }
    private suspend fun attach(host: String, port: Int, serial: String?, runtimePort: Int = port) {
        generation++; feedbackGeneration++
        appIcons.clear(); screenshots.clear(); files.clear(); transfers.clear(); fileMedia.clear(); appManagement.clear(); appControl.clear(); appCache.clear(); systemActions.clear(); systemObservation.clear(); apkInspection.clear(); appInstallation.clear(); storageAnalysis.clear(); mediaControl.clear(); gamepad.clear(); virtualGamepad.clear()
        runtime.disconnect()
        mutable.update { it.copy(connected = false, apps = emptyList(), appsLoading = false, appsError = null, deviceInfo = null, status = "正在连接服务…", touchBusy = false) }
        val release = bundledRelease()
        val status = try { runtime.connect(host, port, release) }
        catch (e: RuntimeAlignmentRequired) {
            mutable.update { it.copy(status = "被控端程序与当前控制端不一致，正在升级配套版本…") }
            val target = serial ?: "$host:5555"
            if (serial == null) adb.connect(target)
            val output = adb.deploy(target, runtimePort, state.value.preferRoot, project)
            mutable.update { it.copy(output = output) }
            runtime.connect(host, port, bundledRelease())
        }
        val name = withContext(Dispatchers.IO) { deviceNames.read()[status.deviceId] } ?: status.name ?: "Android 设备"
        mutable.update { it.copy(connected = true, deviceId = status.deviceId, name = name, host = host,
            port = port, runtimePort = runtimePort, serial = serial, version = status.version, uid = status.uid, tools = status.tools,
            modelConfigured = status.modelConfigured, status = "已连接 $name") }
    }
    fun connectLan(host: String, port: Int) = operation { require(port in 1..65535); releaseForward(); attach(host.trim(), port, null) }
    fun selectAdb(serial: String, runtimePort: Int = 9876) = operation {
        require(runtimePort in 1..65535)
        releaseForward()
        val local = adb.forward(serial, runtimePort)
        forward = serial to local
        try { attach("127.0.0.1", local, serial, runtimePort) }
        catch (e: Exception) { releaseForward(); throw e }
    }
    fun deploy(serial: String, port: Int = 9876) = operation {
        mutable.update { it.copy(status = "正在构建并部署目标二进制…") }
        val output = adb.deploy(serial, port, state.value.preferRoot, project)
        releaseForward()
        val local = adb.forward(serial, port); forward = serial to local
        attach("127.0.0.1", local, serial, port)
        mutable.update { it.copy(output = output) }
    }
    fun renameDevice(value: String) = operation {
        val id = state.value.deviceId ?: error("请先连接目标设备")
        val name = validatedDeviceName(value)
        withContext(Dispatchers.IO) { deviceNames.rename(id, name) }
        mutable.update { it.copy(name = name, status = "已保存设备显示名") }
    }
    fun setPreferRoot(value: Boolean) { mutable.update { it.copy(preferRoot = value) } }
    fun cancelConnection() { connectionJob?.cancel(); mutable.update { it.copy(status = "已取消当前操作") } }
    private fun feedback(phase: FeedbackPhase, text: String, token: Long) {
        mutable.update { if (token == feedbackGeneration) it.copy(feedback = it.feedback.updated(phase, text)) else it }
    }
    fun message(text: String) { val token = ++feedbackGeneration; mutable.update { it.copy(output = text) }; feedback(FeedbackPhase.ERROR, text, token) }
    private fun execute(tool: String, arguments: JsonObject = buildJsonObject {}, action: ((String) -> Unit)? = null) {
        if (!state.value.connected) return
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return
        val session = generation
        val token = ++feedbackGeneration
        feedback(FeedbackPhase.TOOL, "", token)
        scope.launch {
            try {
                val output = send(tool, arguments)
                if (session == generation) {
                    action?.invoke(output)
                    mutable.update { it.copy(output = output) }
                    feedback(FeedbackPhase.COMPLETE, if (tool == "remote.key") "已发送" else output.ifBlank { "已发送" }, token)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session == generation) { val error = e.message ?: "执行失败"; mutable.update { it.copy(output = error) }; feedback(FeedbackPhase.ERROR, error, token) } }
        }
    }
    fun key(value: String) = execute("remote.key", buildJsonObject { put("key", value) })
    fun listApps() {
        if (!state.value.connected || state.value.appsLoading) return
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return
        val session = generation
        appIcons.clear()
        mutable.update { it.copy(appsLoading = true, appsError = null) }
        scope.launch {
            try {
                val apps = decodeInstalledApps(send("apps.list", buildJsonObject {}))
                if (session == generation) mutable.update { it.copy(apps = apps) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session == generation) mutable.update { it.copy(appsError = e.message ?: "无法查询应用列表") } }
            finally { if (session == generation) mutable.update { it.copy(appsLoading = false) } }
        }
    }
    fun openApp(packageName: String) = execute("apps.open", buildJsonObject { put("package", packageName.trim()) })
    fun deviceInfo() = execute("device.info") { output -> mutable.update { it.copy(deviceInfo = json.decodeFromString<DeviceInfo>(output)) } }
    fun gesture(tool: String, arguments: JsonObject): Boolean {
        if (!state.value.connected || state.value.touchBusy || tool !in state.value.tools) return false
        val send = runCatching { runtime.toolWriter() }.getOrNull() ?: return false
        val session = generation
        val token = ++feedbackGeneration
        feedback(FeedbackPhase.TOOL, "", token)
        mutable.update { it.copy(touchBusy = true) }
        scope.launch {
            try { val output = send(tool, arguments); if (session == generation) { mutable.update { it.copy(output = output) }; feedback(FeedbackPhase.COMPLETE, output.ifBlank { "已发送" }, token) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session == generation) { val error = e.message ?: "手势失败"; mutable.update { it.copy(output = error) }; feedback(FeedbackPhase.ERROR, error, token) } }
            finally { if (session == generation) mutable.update { it.copy(touchBusy = false) } }
        }
        return true
    }
    private var textSubmissionGeneration = 0L
    fun text(text: String): Boolean {
        if (text.isBlank() || !state.value.connected || !state.value.modelConfigured || state.value.submitting) return false
        val send = runCatching { runtime.textWriter() }.getOrNull() ?: return false
        val submission = ++textSubmissionGeneration
        val session = generation; val token = ++feedbackGeneration
        mutable.update { it.copy(submitting = true) }; feedback(FeedbackPhase.THINKING, "", token)
        scope.launch {
            try {
                val output = send(text.trim()) { progress -> progressPhase(progress.phase)?.let { feedback(it, progress.text, token) } }
                if (session == generation) { mutable.update { it.copy(output = output) }; feedback(FeedbackPhase.COMPLETE, output, token) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (session == generation) { val error = e.message ?: "文字任务失败"; mutable.update { it.copy(output = error) }; feedback(FeedbackPhase.ERROR, error, token) } }
            finally { if (submission == textSubmissionGeneration) mutable.update { it.copy(submitting = false) } }
        }
        return true
    }
    fun syncProfile() {
        if (!state.value.connected || state.value.busy) return
        val write = runCatching { runtime.configurationWriter() }.getOrNull() ?: return
        val session = generation
        profiles.sync(state.value.connected && !state.value.busy) { profile ->
            check(session == generation && state.value.connected && !state.value.busy) { "目标连接已改变" }
            write(profile)
            if (session == generation) mutable.update { it.copy(modelConfigured = true) }
        }
    }
    fun service(action: String) = operation {
        require(action in listOf("status", "restart", "stop"))
        val serial = state.value.serial ?: error("请先通过 ADB 设备连接")
        val privilege = RuntimePrivilege.detect(state.value.preferRoot, "/data/local/tmp/airc") { command -> adb.command("-s", serial, "shell", command) }
        val script = "/data/local/tmp/airc/airc-runtime service $action --state-dir /data/local/tmp/airc/state --port ${state.value.runtimePort}"
        val output = adb.command("-s", serial, "shell", privilege.command(script))
        mutable.update { it.copy(output = output) }
        if (action == "stop") { generation++; runtime.disconnect() }
        if (action == "restart") {
            val local = forward?.second ?: adb.forward(serial, state.value.runtimePort).also { forward = serial to it }
            attach("127.0.0.1", local, serial, state.value.runtimePort)
        }
    }
    fun disconnect() = operation { generation++; feedbackGeneration++; runtime.disconnect(); releaseForward(); mutable.update { it.copy(status = "已断开", touchBusy = false) } }
    override fun close() {
        transfers.close(); filePreviews.close()
        runtime.close()
        profiles.close()
        val previous = forward; forward = null
        if (previous != null) runBlocking { withTimeoutOrNull(5000) { runCatching { adb.removeForward(previous.first, previous.second) } } }
        scope.cancel()
    }
    companion object {
        fun defaultStateDirectory(): Path = Path.of(System.getenv("AIRC_DESKTOP_STATE_DIR") ?: Path.of(System.getProperty("user.home"), ".local", "share", "airc-desktop").toString())
    }
}

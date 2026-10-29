package ernest.airc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal data class VoiceState(
    val settings: VoiceSettings = VoiceSettings(),
    val preparing: Boolean = false,
    val recording: Boolean = false,
    val draining: Boolean = false,
    val message: String = "",
)

/** Audio and recording state stay in memory, with a hard 60 second limit. */
internal class AndroidVoiceController(private val context: Context, private val scope: CoroutineScope, private val runtime: RuntimeConnection,
    private val capture: () -> VoiceCapture = { AndroidVoiceCapture() }) {
    private val preferences = context.getSharedPreferences("voice-input", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(VoiceState(VoiceSettings(
        preferences.getString("package", "com.konka.bestlauncher")!!, preferences.getString("key", "1017")!!)))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var released = AtomicBoolean(false)
    private var captureStopped = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val generation = AtomicLong()
    init { scope.launch { runtime.session.drop(1).collect { stop() } } }
    fun settings(value: VoiceSettings) {
        stop()
        mutable.update { it.copy(settings = value, message = "") }
        preferences.edit().putString("package", value.packageName).putString("key", value.keyCode).apply()
    }
    fun permissionDenied() { mutable.update { it.copy(message = "需要麦克风权限；授权后请重新按住说话") } }
    fun start(): Long? {
        val previous = job
        if (previous?.isActive == true) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionDenied(); return null }
        val session = runtime.session.value ?: return null
        val settings = state.value.settings
        try { settings.validate() } catch (error: Exception) { mutable.update { it.copy(message = error.message.orEmpty()) }; return null }
        val token = generation.incrementAndGet()
        val release = AtomicBoolean(false).also { released = it }
        val previousCapture = captureStopped
        val captureDone = CompletableDeferred<Unit>().also { captureStopped = it }
        fun update(change: (VoiceState) -> VoiceState) { mutable.update { if (generation.get() == token) change(it) else it } }
        update { it.copy(preparing = true, recording = false, draining = false, message = "正在准备，录音将自动缓存…") }
        job = scope.launch(Dispatchers.IO) {
            val stream = RuntimeVoiceStream(session) { runtime.session.value }
            var delivered = false
            try {
                coroutineScope {
                    val buffer = BufferedVoicePcm()
                    val producer = launch(Dispatchers.IO) {
                        var recorder: VoiceCapture? = null
                        try {
                            previousCapture.await()
                            ensureActive()
                            if (!release.get()) {
                                recorder = capture()
                                recorder.start()
                                update { it.copy(recording = !release.get()) }
                                val pcm = ByteArray(640)
                                val until = SystemClock.elapsedRealtime() + 60_000
                                var lastAudio = SystemClock.elapsedRealtime()
                                while (!release.get() && SystemClock.elapsedRealtime() < until) {
                                    ensureActive()
                                    val count = recorder.read(pcm)
                                    check(count >= 0) { "手机录音失败：$count" }
                                    if (count > 0) { buffer.append(pcm, count); lastAudio = SystemClock.elapsedRealtime() }
                                    else { check(SystemClock.elapsedRealtime() - lastAudio < 1000) { "手机麦克风没有返回音频" }; delay(5) }
                                }
                                // Keep the already captured tail, including a partial 20ms frame.
                                var tail = 0
                                while (tail < 3200) {
                                    val count = recorder.read(pcm)
                                    check(count >= 0) { "手机录音失败：$count" }
                                    if (count == 0) break
                                    buffer.append(pcm, count); tail += count
                                }
                            }
                            buffer.finish()
                            update { it.copy(recording = false, draining = true, message = "正在发送剩余语音…") }
                        } finally {
                            try { recorder?.close() } finally { captureDone.complete(Unit) }
                        }
                    }
                    // Capturing is independent of the old connection's cleanup and new handshake.
                    previous?.join()
                    if (buffer.available.await()) {
                        stream.open(settings)
                        update { it.copy(preparing = false, message = if (release.get()) "正在发送剩余语音…" else "正在发送语音，松开结束") }
                        while (true) {
                            val pcm = buffer.read() ?: break
                            stream.send(pcm, pcm.size)
                        }
                        producer.join()
                        update { it.copy(preparing = false, recording = false, draining = true, message = "正在发送剩余语音…") }
                        stream.finish()
                        delivered = true
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                update { it.copy(message = "语音失败：等待目标准备或音频送达超时，请重试") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { update { it.copy(message = "语音失败：${voiceErrorMessage(error)}") } }
            finally {
                captureDone.complete(Unit)
                withContext(NonCancellable) {
                    try { if (!delivered) stream.stop() }
                    catch (error: Exception) { update { it.copy(message = "语音失败：未收到取消确认，${voiceErrorMessage(error)}") } }
                    finally { stream.close() }
                }
                update { it.copy(preparing = false, recording = false, draining = false, message = if (it.message.startsWith("语音失败")) it.message else "语音已结束") }
            }
        }.also { recordingJob ->
            recordingJob.invokeOnCompletion {
                captureDone.complete(Unit)
                update { if (it.preparing || it.recording || it.draining) it.copy(preparing = false, recording = false, draining = false, message = "语音已结束") else it }
            }
        }
        return token
    }
    fun finish(token: Long? = null) {
        if (token != null && generation.get() != token) return
        if (job?.isActive == true) {
            released.set(true)
            mutable.update { if (it.message.startsWith("语音失败")) it else it.copy(recording = false, draining = true, message = "正在发送剩余语音…") }
        }
    }
    fun stop(token: Long? = null) { if (token == null || generation.get() == token) job?.cancel() }

    private fun voiceErrorMessage(error: Exception) = when {
        error.message?.contains("Microphone is already in use") == true -> "麦克风正在被其他会话使用，请结束后重试"
        else -> error.message ?: "请重试"
    }
}

internal interface VoiceCapture : AutoCloseable {
    fun start()
    fun read(buffer: ByteArray): Int
}

private class AndroidVoiceCapture : VoiceCapture {
    private var recorder: AudioRecord? = null
    override fun start() {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "此手机不支持 16 kHz 录音" }
        val input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum, 3200)).build()
        recorder = input
        check(input.state == AudioRecord.STATE_INITIALIZED) { "无法初始化手机麦克风" }
        input.startRecording()
        check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法启动手机麦克风" }
    }
    override fun read(buffer: ByteArray) = checkNotNull(recorder).read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
    override fun close() {
        recorder?.let { input ->
            try { if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.stop() }
            finally { input.release(); recorder = null }
        }
    }
}

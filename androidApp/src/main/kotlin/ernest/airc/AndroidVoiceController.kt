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

internal data class VoiceState(
    val settings: VoiceSettings = VoiceSettings(),
    val preparing: Boolean = false,
    val recording: Boolean = false,
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
        mutable.update { it.copy(preparing = true, message = "正在连接麦克风…") }
        job = scope.launch(Dispatchers.IO) {
            var recorder: VoiceCapture? = null
            val stream = RuntimeVoiceStream(session) { runtime.session.value }
            try {
                val until = SystemClock.elapsedRealtime() + 60_000
                previous?.join()
                currentCoroutineContext().ensureActive()
                // Validate local capture before sending the optional key to the target.
                recorder = capture()
                recorder.start()
                stream.open(settings)
                // Drop startup audio; only live audio after target readiness is transmitted.
                val pcm = ByteArray(640)
                var drained = 0
                while (drained < 16000 && recorder.read(pcm) > 0) drained += pcm.size
                currentCoroutineContext().ensureActive()
                mutable.update { if (generation.get() == token) it.copy(preparing = false, recording = true, message = "正在发送语音，松开结束") else it }
                var lastAudio = SystemClock.elapsedRealtime()
                while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < until) {
                    check(runtime.session.value == session) { "目标连接已改变" }
                    val count = recorder.read(pcm)
                    check(count >= 0) { "手机录音失败：$count" }
                    if (count > 0) { stream.send(pcm, count); lastAudio = SystemClock.elapsedRealtime() }
                    else { check(SystemClock.elapsedRealtime() - lastAudio < 1000) { "手机麦克风没有返回音频" }; delay(5) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { if (generation.get() == token) it.copy(message = "语音失败：${voiceErrorMessage(error)}") else it } }
            finally {
                try { recorder?.close() } catch (_: Exception) {}
                withContext(NonCancellable) {
                    try { stream.stop() }
                    catch (error: Exception) { mutable.update { if (generation.get() == token) it.copy(message = "语音失败：未收到结束确认，${voiceErrorMessage(error)}") else it } }
                    finally { stream.close() }
                }
                mutable.update { if (generation.get() == token) it.copy(preparing = false, recording = false, message = if (it.message.startsWith("语音失败")) it.message else "语音已结束") else it }
            }
        }.also { recordingJob ->
            recordingJob.invokeOnCompletion {
                mutable.update { state ->
                    if (generation.get() == token && (state.preparing || state.recording)) state.copy(preparing = false, recording = false, message = "语音已结束") else state
                }
            }
        }
        return token
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

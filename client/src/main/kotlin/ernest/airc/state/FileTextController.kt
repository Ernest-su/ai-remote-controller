package ernest.airc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Bounded text drafts stay in memory and retain their original connection and encoding. */
class FileTextController(private val scope: CoroutineScope, private val runtime: RuntimeConnection) : AutoCloseable {
    private val preview = FilePreviewClient(runtime)
    val transfers = FileTransferController(scope, runtime)
    private val mutable = MutableStateFlow(FileTextState())
    val state = mutable.asStateFlow()
    private var generation = 0
    private var job: Job? = null
    private var target: RuntimeTransferTarget? = null
    private var originalDigest: String? = null
    private var bom = ByteArray(0)

    fun open(root: String, path: String, entry: TargetFileEntry, available: Boolean, writable: Boolean) {
        if (!available || entry.kind != "file") return
        closeDocument()
        mutable.value = FileTextState(open = true, root = root, path = path, writable = writable && !entry.isSymbolicLink)
        load(false)
    }
    fun continueLoading() { if (state.value.warning) load(true) }
    fun retry() { if (state.value.open && !state.value.saving) load(false) }
    private fun load(approved: Boolean) {
        val token = generation
        val current = state.value
        mutable.update { it.copy(loading = true, warning = false, error = null) }
        job = scope.launch {
            try {
                withTimeout(30_000) {
                    val destination = runtime.transferTarget()
                    target?.let { runtime.checkTransferTarget(it) }
                    target = destination
                    val resource = preview.open(current.root, current.path)
                    try {
                        val size = resource.descriptor.sizeBytes
                        if (size > TEXT_WARNING_BYTES && !approved) {
                            if (token == generation) mutable.update { it.copy(sizeBytes = size, loading = false, warning = true) }
                            return@withTimeout
                        }
                        val partial = size > TEXT_EDIT_BYTES
                        val bytes = resource.read(if (partial) TEXT_WARNING_BYTES else TEXT_EDIT_BYTES, partial)
                        val decoded = withContext(Dispatchers.Default) { if (partial) decodeFilePreviewText(bytes) else decodeEditableText(bytes) }
                        runtime.checkTransferTarget(destination)
                        if (token == generation) {
                            bom = textBom(bytes)
                            originalDigest = if (partial) null else digest(bytes)
                            mutable.update { it.copy(sizeBytes = size, loading = false, partial = partial, warning = false, text = decoded.text, draft = decoded.text, encoding = decoded.encoding, writable = it.writable && !partial, error = null) }
                        }
                    } finally { resource.release() }
                }
            } catch (e: TimeoutCancellationException) {
                if (token == generation) mutable.update { it.copy(loading = false, error = "文本加载超时，请重试或下载到本地查看。") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = e.message ?: "文本加载失败") } }
        }
    }
    fun edit(value: String) {
        if (!state.value.writable || state.value.saving) return
        if (value.length > TEXT_EDIT_BYTES) { mutable.update { it.copy(error = "编辑内容过大，草稿保留在修改前状态。") }; return }
        mutable.update { it.copy(draft = value, message = null, error = null) }
    }
    fun save(available: Boolean) {
        val current = state.value
        if (!available || !current.writable || !current.dirty || current.saving || current.loading) return
        val token = generation
        mutable.update { it.copy(saving = true, message = null, error = null) }
        job = scope.launch {
            try {
                val destination = checkNotNull(target)
                runtime.checkTransferTarget(destination)
                val bytes = withContext(Dispatchers.Default) { encodeEditableText(current.draft, current.encoding, bom) }
                transfers.uploadEdited(current.root, current.path, bytes, checkNotNull(originalDigest), destination)
                val result = transfers.state.first { !it.running }
                check(result.phase == "completed") { result.error ?: "保存未完成，草稿已保留。" }
                runtime.checkTransferTarget(destination)
                if (token == generation) {
                    originalDigest = digest(bytes)
                    mutable.update { it.copy(text = current.draft, sizeBytes = bytes.size.toLong(), message = "已保存回被控设备，摘要校验通过。") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = e.message ?: "保存失败，草稿已保留。") } }
            finally { if (token == generation) mutable.update { it.copy(saving = false) } }
        }
    }
    fun closeDocument() { generation++; job?.cancel(); transfers.clear(); target = null; originalDigest = null; bom = ByteArray(0); mutable.value = FileTextState() }
    override fun close() { closeDocument(); transfers.close(); preview.close() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal fun textBom(bytes: ByteArray): ByteArray = when {
    bytes.size >= 3 && bytes.take(3) == listOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) -> bytes.copyOf(3)
    bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> bytes.copyOf(2)
    bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> bytes.copyOf(2)
    else -> ByteArray(0)
}
internal fun decodeEditableText(bytes: ByteArray): FilePreviewText {
    val decoded = decodeFilePreviewText(bytes)
    val strict = Charset.forName(decoded.encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, textBom(bytes).size, bytes.size - textBom(bytes).size)).toString()
    return decoded.copy(text = strict)
}
internal fun encodeEditableText(text: String, encoding: String, bom: ByteArray): ByteArray {
    val encoded = Charset.forName(encoding).newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
    check(encoded.remaining() + bom.size <= TEXT_EDIT_BYTES) { "编码后的文件超过 2 MiB 编辑上限，草稿已保留。" }
    return bom + ByteArray(encoded.remaining()).also { encoded.get(it) }
}

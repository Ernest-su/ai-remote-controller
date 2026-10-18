package ernest.airc

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.content.Context

/** External content is retained only while the receiving Activity's URI grant is valid. */
data class IncomingShare(val id: Long, val text: String? = null, val uri: Uri? = null, val error: String? = null)

internal fun incomingShare(intent: Intent, id: Long): IncomingShare? {
    if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
    if (intent.action == Intent.ACTION_SEND_MULTIPLE) return IncomingShare(id, error = "请每次分享一个文件。")
    @Suppress("DEPRECATION")
    val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    if (stream != null) return if (stream.scheme == "content") IncomingShare(id, uri = stream) else IncomingShare(id, error = "请选择可读取的内容文件。")
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    if (text != null && text.toByteArray(Charsets.UTF_8).size > 65536) return IncomingShare(id, error = "分享文字超过 64 KiB，请缩短后重试。")
    return if (text.isNullOrBlank()) IncomingShare(id, error = "未收到可用的文字或文件。") else IncomingShare(id, text = text)
}

internal suspend fun sharedUploadSource(context: Context, uri: Uri): UploadSource = withContext(Dispatchers.IO) {
    var name = "shared-file"; var size: Long? = null
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).takeIf { it >= 0 }
        }
    }
    check(validTargetFileName(name)) { "文件名称无效，请在上传面板重新选择文件。" }
    UploadSource(name, size) { checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取分享文件，请重新分享。" } }
}

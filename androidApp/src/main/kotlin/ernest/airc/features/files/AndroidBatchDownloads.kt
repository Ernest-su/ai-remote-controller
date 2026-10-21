package ernest.airc

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Uses the user-selected document tree; every output is a newly created document. */
internal suspend fun saveBatchDocuments(resolver: ContentResolver, tree: Uri, files: List<BatchFileExport>, saved: (String) -> Unit) {
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
    val names = mutableSetOf<String>()
    val ids = mutableSetOf<String>()
    checkNotNull(resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)) { "无法读取保存文件夹" }.use { cursor ->
        while (cursor.moveToNext()) { names += cursor.getString(0); ids += cursor.getString(1) }
    }
    for (entry in files) {
        currentCoroutineContext().ensureActive()
        val name = suggestedDownloadName(entry.name) { it in names }
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)) { "无法创建文件：$name" }
        check(DocumentsContract.getDocumentId(uri) !in ids) { "文件提供方返回了已有文件，已停止保存" }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")) { "无法写入文件：$name" }.use { out ->
                entry.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; out.write(buffer, 0, count) }
                }
            }
            names += name; ids += DocumentsContract.getDocumentId(uri)
            saved(entry.name)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw error
        }
    }
}

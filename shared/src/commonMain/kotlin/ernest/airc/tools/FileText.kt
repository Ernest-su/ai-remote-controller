package ernest.airc

const val TEXT_WARNING_BYTES = 256 * 1024
const val TEXT_EDIT_BYTES = 2 * 1024 * 1024
const val TEXT_HIGHLIGHT_CHARS = 64 * 1024

data class FileTextState(
    val open: Boolean = false, val root: String = "downloads", val path: String = "", val sizeBytes: Long = 0,
    val loading: Boolean = false, val warning: Boolean = false, val partial: Boolean = false,
    val text: String? = null, val draft: String = "", val encoding: String = "UTF-8", val writable: Boolean = false,
    val saving: Boolean = false, val message: String? = null, val error: String? = null,
) { val dirty: Boolean get() = text != null && draft != text }

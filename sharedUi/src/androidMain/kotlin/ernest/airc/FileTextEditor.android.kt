package ernest.airc

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView

/** Only viewport metadata is saved; file contents remain in the controller's memory. */
private class FileTextViewport(var start: Int = 0, var end: Int = 0, var x: Int = 0, var y: Int = 0)
private val viewportSaver = listSaver<FileTextViewport, Int>(
    save = { listOf(it.start, it.end, it.x, it.y) },
    restore = { FileTextViewport(it[0], it[1], it[2], it[3]) },
)

private class FileDocumentEditText(context: Context) : EditText(context) {
    var position: FileTextViewport? = null
    var applyingModel = false
    var boundValue: String? = null
    private var restoringScroll: Pair<Int, Int>? = null

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        if (!applyingModel && restoringScroll == null) position?.let { it.start = start; it.end = end }
    }
    override fun onScrollChanged(x: Int, y: Int, oldX: Int, oldY: Int) {
        super.onScrollChanged(x, y, oldX, oldY)
        if (!applyingModel && restoringScroll == null) position?.let { it.x = x; it.y = y }
    }
    override fun bringPointIntoView(offset: Int): Boolean =
        if (restoringScroll != null) false else super.bringPointIntoView(offset)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        restoringScroll?.let { saved ->
            scrollTo(saved.first, saved.second)
            post {
                if (restoringScroll === saved) {
                    scrollTo(saved.first, saved.second)
                    restoringScroll = null
                    position?.let { it.x = scrollX; it.y = scrollY }
                }
            }
        }
    }
    fun render(value: String) {
        // A user edit already has the exact buffer. Progress/theme recompositions must not
        // call setText, reset the caret, re-layout the document or regenerate syntax spans.
        if (boundValue == value) return
        val location = checkNotNull(position)
        val x = location.x; val y = location.y
        restoringScroll = x to y
        applyingModel = true
        setText(value)
        setSelection(location.start.coerceIn(0, value.length), location.end.coerceIn(0, value.length))
        boundValue = value
        applyingModel = false
        requestLayout()
    }
}

@Composable
internal actual fun PlatformFileTextInput(documentKey: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, modifier: Modifier) {
    val viewport = rememberSaveable(documentKey, saver = viewportSaver) { FileTextViewport() }
    val change by rememberUpdatedState(onValueChange)
    val nativeView = remember(documentKey) { arrayOfNulls<FileDocumentEditText>(1) }
    var hasFocus by remember(documentKey) { mutableStateOf(false) }
    val foreground = RemoteColors.Text.toArgb()
    val background = RemoteColors.Surface.toArgb()
    val selection = RemoteColors.ActionContainer.toArgb()
    val textSize = MaterialTheme.typography.bodySmall.fontSize
    val fontSize = with(LocalDensity.current) { textSize.toPx() }
    AndroidView(
        modifier = modifier.semantics {
            editableText = AnnotatedString(value)
            focused = hasFocus
            requestFocus { nativeView[0]?.requestFocus() == true }
            setText { if (enabled) { change(it.text); true } else false }
            insertTextAtCursor { inserted ->
                val view = nativeView[0]
                if (enabled && view != null) {
                    val start = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
                    val end = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(start)
                    view.text.replace(start, end, inserted.text)
                    view.setSelection(start + inserted.length)
                    true
                } else false
            }
        },
        factory = { context ->
            FileDocumentEditText(context).apply {
                nativeView[0] = this
                setOnFocusChangeListener { _, focused -> hasFocus = focused }
                position = viewport
                // AndroidView hierarchy snapshots must not persist the plaintext draft.
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                setHorizontallyScrolling(true)
                isVerticalScrollBarEnabled = true
                val padding = (12 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(text: Editable?) {
                        if (!applyingModel) {
                            val updated = text?.toString().orEmpty()
                            boundValue = updated
                            change(updated)
                        }
                    }
                })
            }
        },
        update = { view ->
            view.isEnabled = enabled
            view.setTextColor(foreground)
            view.setBackgroundColor(background)
            view.highlightColor = selection
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
            view.render(value)
        },
    )
}

@Composable
internal actual fun FileTextBackHandler(onBack: () -> Unit) = BackHandler(onBack = onBack)

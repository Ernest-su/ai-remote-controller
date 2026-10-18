package ernest.airc.ime;

import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** A remote-only IME. Text is held only for the duration of commitText, never persisted. */
public final class RemoteInputMethod extends InputMethodService {
    static RemoteInputMethod current;
    private boolean editing;
    @Override public void onCreate() { super.onCreate(); current = this; }
    @Override public void onDestroy() { if (current == this) current = null; super.onDestroy(); }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        editing = info != null && info.inputType != InputType.TYPE_NULL;
    }
    @Override public void onUnbindInput() { editing = false; super.onUnbindInput(); }
    @Override public void onFinishInput() { editing = false; super.onFinishInput(); }
    @Override public boolean onEvaluateInputViewShown() { return false; }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    boolean ready() { return editing && getCurrentInputConnection() != null; }
    boolean commit(String text) {
        InputConnection connection = getCurrentInputConnection();
        return editing && connection != null && connection.commitText(text, 1);
    }
}

package ernest.airc.mediafixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Records only synthetic input in this disposable fixture's private directory. */
public final class TextActivity extends Activity {
    static volatile android.content.ClipData originalClipboard;
    static volatile boolean clipboardChanged;
    private boolean initializedClipboard;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setRawInputType((getIntent().getBooleanExtra("paste", false) || getIntent().getBooleanExtra("ime", false)) ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : android.text.InputType.TYPE_NULL);
        field.setShowSoftInputOnFocus(getIntent().getBooleanExtra("ime", false));
        field.setTextSize(24);
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {}
            public void afterTextChanged(Editable value) {
                try (FileOutputStream output = openFileOutput("remote-text.txt",MODE_PRIVATE)) {
                    output.write(value.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception error) { throw new IllegalStateException(error); }
            }
        });
        setContentView(field);
        field.requestFocus();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        String text = getIntent().getStringExtra("clipboard");
        if (focused && text != null && !initializedClipboard) {
            initializedClipboard = true;
            android.content.ClipboardManager manager = getSystemService(android.content.ClipboardManager.class);
            originalClipboard = manager.getPrimaryClip();
            clipboardChanged = true;
            manager.setPrimaryClip(android.content.ClipData.newPlainText("fixture", text));
        }
    }
}

package ernest.airc.mediafixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Only synthetic fixture text, readable by shell DUMP permission on run-as-less images. */
public final class TextJournalProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only fixture journal");
        String name;
        if ("/text".equals(uri.getPath())) name = "remote-text.txt";
        else if ("/color-input".equals(uri.getPath())) name = "color-input.jsonl";
        else throw new FileNotFoundException("unknown fixture journal");
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), name), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        int uid = android.os.Binder.getCallingUid();
        if (uid != 2000 && uid != 0) throw new SecurityException("ADB shell required");
        if ("restoreClipboard".equals(method)) {
            android.content.ClipboardManager manager = getContext().getSystemService(android.content.ClipboardManager.class);
            if (TextActivity.clipboardChanged) {
                if (TextActivity.originalClipboard != null) manager.setPrimaryClip(TextActivity.originalClipboard);
                else if (android.os.Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip();
                TextActivity.originalClipboard = null;
                TextActivity.clipboardChanged = false;
            }
            return new android.os.Bundle();
        }
        if (!"clipboard".equals(method)) throw new UnsupportedOperationException();
        android.content.ClipData clip = getContext().getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
        android.os.Bundle result = new android.os.Bundle();
        result.putString("clipboard", clip == null ? "" : clip.getItemAt(0).coerceToText(getContext()).toString());
        return result;
    }
    public String getType(Uri uri) { return "text/plain"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read only"); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
}

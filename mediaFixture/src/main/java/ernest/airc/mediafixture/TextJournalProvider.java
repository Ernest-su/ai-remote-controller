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
        if (!"r".equals(mode) || !"/text".equals(uri.getPath())) throw new FileNotFoundException("read only text journal");
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(), "remote-text.txt"), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public String getType(Uri uri) { return "text/plain"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read only"); }
    public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
    public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read only"); }
}

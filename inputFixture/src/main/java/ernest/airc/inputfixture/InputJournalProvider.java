package ernest.airc.inputfixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only shell diagnostic access to this disposable receiver, including on run-as-less images. */
public final class InputJournalProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        String name;
        if ("/events".equals(uri.getPath())) name="events.jsonl";
        else if ("/ready".equals(uri.getPath())) name="ready";
        else throw new FileNotFoundException("unknown journal");
        return ParcelFileDescriptor.open(new File(getContext().getFilesDir(),name),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public String getType(Uri uri) { return "application/x-ndjson"; }
    public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) { return null; }
    public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException("read only"); }
    public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException("read only"); }
    public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException("read only"); }
}

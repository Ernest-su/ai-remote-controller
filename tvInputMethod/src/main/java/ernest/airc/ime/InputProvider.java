package ernest.airc.ime;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Only the authorized ADB shell/root can call this endpoint, including ContentProvider.call(). */
public final class InputProvider extends ContentProvider {
    static final String IME = "ernest.airc.ime/.RemoteInputMethod";
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != 2000 && uid != 0) throw new SecurityException("ADB shell required");
        FutureTask<Bundle> task = new FutureTask<>(() -> handle(method, extras));
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(task);
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (Exception error) {
            task.cancel(false); handler.removeCallbacks(task);
            return result("uncertain");
        }
    }
    private Bundle handle(String method, Bundle extras) {
        String selected = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("input", 0);
        if ("remember".equals(method)) {
            if (selected != null && selected.contains("/") && !isOurs(selected) && selected.matches("[A-Za-z0-9_.$/]+")) {
                if (!prefs.edit().putString("previous", selected).commit()) return result("storage_error");
            }
            return result("remembered");
        }
        if ("status".equals(method)) {
            Bundle value = result(isOurs(selected) ? "selected" : "inactive");
            value.putString("previous", prefs.getString("previous", ""));
            value.putBoolean("ready", RemoteInputMethod.current != null && RemoteInputMethod.current.ready());
            return value;
        }
        if (!"commit".equals(method)) return result("invalid");
        if (!isOurs(selected) || RemoteInputMethod.current == null || !RemoteInputMethod.current.ready()) return result("no_editor");
        try {
            String encoded = extras == null ? null : extras.getString("text");
            if (encoded == null || encoded.length() > 87384) return result("invalid");
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            if (bytes.length == 0 || bytes.length > 65536) return result("invalid");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            return result(RemoteInputMethod.current.commit(text) ? "committed" : "rejected");
        } catch (Exception error) { return result("invalid"); }
    }
    private static boolean isOurs(String id) {
        return IME.equals(id) || "ernest.airc.ime/ernest.airc.ime.RemoteInputMethod".equals(id);
    }
    private static Bundle result(String status) { Bundle value = new Bundle(); value.putString("status", status); return value; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}

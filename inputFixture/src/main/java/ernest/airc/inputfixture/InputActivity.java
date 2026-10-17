package ernest.airc.inputfixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Disposable receiver: counts actual framework delivery, consumes test keys without navigation. */
public final class InputActivity extends Activity {
    private File journal;
    private int count;
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        journal=new File(getFilesDir(),"events.jsonl");
        try(FileOutputStream out=new FileOutputStream(journal,false)) {} catch(Exception error) { throw new IllegalStateException(error); }
        TextView view=new TextView(this);view.setText("AIRC input delivery fixture");view.setFocusableInTouchMode(true);setContentView(view);view.requestFocus();
    }
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        File ready=new File(getFilesDir(),"ready");
        try { if(focused) { try(FileOutputStream out=new FileOutputStream(ready,false)) {} } else ready.delete(); }
        catch(Exception error) { throw new IllegalStateException(error); }
    }
    public boolean dispatchKeyEvent(KeyEvent event) {
        if(journal==null) return super.dispatchKeyEvent(event);
        if(count++>=2048) throw new IllegalStateException("input fixture event limit exceeded");
        try(FileOutputStream out=new FileOutputStream(journal,true)) {
            JSONObject data=new JSONObject().put("code",event.getKeyCode()).put("action",event.getAction())
                .put("source",event.getSource()).put("repeat",event.getRepeatCount())
                .put("flags",event.getFlags()).put("longPress",event.isLongPress()).put("canceled",event.isCanceled())
                .put("downTime",event.getDownTime()).put("eventTime",event.getEventTime()).put("receivedTime",SystemClock.uptimeMillis());
            out.write((data.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        } catch(Exception error) { throw new IllegalStateException(error); }
        return true;
    }
}

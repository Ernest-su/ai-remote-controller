package ernest.airc.inputfixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
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
    private boolean recordMouse(MotionEvent event) {
        if(journal==null || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        if(count++>=2048) throw new IllegalStateException("input fixture event limit exceeded");
        try(FileOutputStream out=new FileOutputStream(journal,true)) {
            JSONObject data=new JSONObject().put("kind","mouse").put("code",-1).put("action",event.getActionMasked())
                .put("source",event.getSource()).put("buttons",event.getButtonState()).put("button",event.getActionButton())
                .put("x",event.getX()).put("y",event.getY()).put("wheel",event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                .put("deviceId",event.getDeviceId()).put("deviceName",event.getDevice()==null?"":event.getDevice().getName())
                .put("eventTime",event.getEventTime()).put("receivedTime",SystemClock.uptimeMillis());
            out.write((data.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        } catch(Exception error) { throw new IllegalStateException(error); }
        return true;
    }
    public boolean dispatchGenericMotionEvent(MotionEvent event) { return recordMouse(event) || super.dispatchGenericMotionEvent(event); }
    public boolean dispatchTouchEvent(MotionEvent event) { return recordMouse(event) || super.dispatchTouchEvent(event); }
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

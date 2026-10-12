package ernest.airc.mediafixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

/** Owned event receiver; journal contains only test input, never user application data. */
public final class GamepadActivity extends Activity {
    private File journal;private TextView label;private int count;
    public void onCreate(Bundle saved){
        super.onCreate(saved);journal=new File(getFilesDir(),"gamepad-events.jsonl");
        try(FileOutputStream out=new FileOutputStream(journal,false)){}catch(Exception error){throw new IllegalStateException(error);}
        label=new TextView(this);label.setText("AIRC gamepad event fixture");label.setFocusableInTouchMode(true);setContentView(label);label.requestFocus();
    }
    public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);File ready=new File(getFilesDir(),"gamepad-ready");
        try{if(focused){try(FileOutputStream out=new FileOutputStream(ready,false)){}}else{ready.delete();}}catch(Exception error){throw new IllegalStateException(error);}
    }
    private void append(JSONObject event){
        if(count++>=256)throw new IllegalStateException("fixture event limit exceeded");
        try(FileOutputStream out=new FileOutputStream(journal,true)){out.write((event.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));label.setText(event.toString());}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private String deviceName(int id){
        android.view.InputDevice device=android.view.InputDevice.getDevice(id);
        return device==null?"":device.getName();
    }
    public boolean dispatchKeyEvent(KeyEvent event){
        if((event.getSource()&android.view.InputDevice.SOURCE_GAMEPAD)==android.view.InputDevice.SOURCE_GAMEPAD){
            try{append(new JSONObject().put("kind","key").put("code",event.getKeyCode()).put("action",event.getAction()).put("source",event.getSource()).put("deviceId",event.getDeviceId()).put("deviceName",deviceName(event.getDeviceId())).put("downTime",event.getDownTime()).put("eventTime",event.getEventTime()).put("repeat",event.getRepeatCount()));}catch(Exception error){throw new IllegalStateException(error);}return true;
        }
        return super.dispatchKeyEvent(event);
    }
    public boolean dispatchGenericMotionEvent(MotionEvent event){
        if((event.getSource()&android.view.InputDevice.SOURCE_JOYSTICK)==android.view.InputDevice.SOURCE_JOYSTICK){
            try{append(new JSONObject().put("kind","motion").put("action",event.getAction()).put("source",event.getSource()).put("deviceId",event.getDeviceId()).put("deviceName",deviceName(event.getDeviceId())).put("downTime",event.getDownTime()).put("eventTime",event.getEventTime()).put("x",event.getAxisValue(MotionEvent.AXIS_X)).put("y",event.getAxisValue(MotionEvent.AXIS_Y)).put("z",event.getAxisValue(MotionEvent.AXIS_Z)).put("rz",event.getAxisValue(MotionEvent.AXIS_RZ)).put("hatX",event.getAxisValue(MotionEvent.AXIS_HAT_X)).put("hatY",event.getAxisValue(MotionEvent.AXIS_HAT_Y)));}catch(Exception error){throw new IllegalStateException(error);}return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
}

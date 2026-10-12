package ernest.airc.mediafixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Bounded journal of injected test gestures; no user application data. */
public final class TouchActivity extends Activity {
    private File journal;private GestureDetector detector;private int count;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);journal=new File(getFilesDir(),"touch-events.jsonl");
        try(FileOutputStream out=new FileOutputStream(journal,false)){}catch(Exception error){throw new IllegalStateException(error);}
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        TextView label=new TextView(this);label.setText("AIRC touch event fixture");label.setBackgroundColor(0xff1565c0);setContentView(label);
        detector=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public boolean onDoubleTap(MotionEvent event){append("double_tap",event);return true;}
            @Override public void onLongPress(MotionEvent event){append("long_press",event);}
        });
    }
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);File ready=new File(getFilesDir(),"touch-ready");
        try{if(focused){try(FileOutputStream out=new FileOutputStream(ready,false)){}}else ready.delete();}catch(Exception error){throw new IllegalStateException(error);}
    }
    private void append(String kind,MotionEvent event){
        if(count++>=4096)throw new IllegalStateException("fixture journal limit exceeded");
        try{
            android.graphics.Point size=new android.graphics.Point();getWindowManager().getDefaultDisplay().getRealSize(size);
            JSONObject value=new JSONObject().put("kind",kind).put("action",event.getActionMasked()).put("x",event.getRawX()).put("y",event.getRawY())
                .put("downTime",event.getDownTime()).put("eventTime",event.getEventTime()).put("width",size.x).put("height",size.y);
            try(FileOutputStream out=new FileOutputStream(journal,true)){out.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){append("motion",event);detector.onTouchEvent(event);return true;}
}

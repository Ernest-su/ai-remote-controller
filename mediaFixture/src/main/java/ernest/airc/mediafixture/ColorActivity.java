package ernest.airc.mediafixture;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.graphics.Canvas;
import android.graphics.Paint;
/** Deterministic screenrecord source, avoiding capture of user applications. */
public final class ColorActivity extends Activity {
    public void onCreate(Bundle state){super.onCreate(state);
        applyOrientation(getIntent());
        setContentView(new View(this){
        protected void onDraw(Canvas canvas){Paint p=new Paint();p.setColor(0xff00ffff);canvas.drawRect(0,0,getWidth(),getHeight()/2f,p);p.setColor(0xffff00ff);canvas.drawRect(0,getHeight()/2f,getWidth(),getHeight(),p);p.setColor(0xffffffff);float x=(android.os.SystemClock.uptimeMillis()/10)%Math.max(1,getWidth());canvas.drawRect(x,0,x+20,20,p);long ticks=(android.os.SystemClock.elapsedRealtime()/10)&0xffffffffL;float cell=getWidth()/36f;float top=getHeight()/2f-60;
            for(int bit=0;bit<36;bit++){boolean white=bit<4?(bit%2==0):((ticks>>(35-bit))&1)!=0;p.setColor(white?0xffffffff:0xff000000);canvas.drawRect(bit*cell,top,(bit+1)*cell,top+48,p);}postInvalidateDelayed(33);}
    });}
    protected void onNewIntent(android.content.Intent intent){
        super.onNewIntent(intent);setIntent(intent);applyOrientation(intent);
    }
    private void applyOrientation(android.content.Intent intent){
        String orientation=intent.getStringExtra("orientation");
        if("portrait".equals(orientation))setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else if("landscape".equals(orientation))setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"color-orientation.json"),false)){
            out.write(new org.json.JSONObject().put("intentOrientation",orientation==null?"none":orientation).put("requestedOrientation",getRequestedOrientation()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception error){throw new IllegalStateException(error);}
    }

}

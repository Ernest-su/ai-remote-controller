package ernest.airc.mediafixture;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

/** Owned high-entropy animation; creates no files and reads no target content. */
public final class NoiseActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new NoiseView(this));
    }
    private static final class NoiseView extends View {
        private final Bitmap bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);
        private final int[] pixels=new int[640*360];
        private int random=0x12345678;
        NoiseView(Context context){super(context);}
        protected void onDraw(Canvas canvas){
            for(int i=0;i<pixels.length;i++){
                random^=random<<13;random^=random>>>17;random^=random<<5;
                pixels[i]=0xff000000|(random&0x00ffffff);
            }
            bitmap.setPixels(pixels,0,640,0,0,640,360);
            canvas.drawBitmap(bitmap,null,new Rect(0,0,getWidth(),getHeight()),null);
            postInvalidateDelayed(33);
        }
    }
}

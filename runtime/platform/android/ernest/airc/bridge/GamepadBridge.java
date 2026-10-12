package ernest.airc.bridge;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Finite synthetic input transaction; no installed APK or persistent Java service. */
public final class GamepadBridge {
    private final Object manager;
    private final Method inject;
    private GamepadBridge() throws Exception {
        Class<?> type;
        try { type=Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch(ClassNotFoundException older){type=Class.forName("android.hardware.input.InputManager");}
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
    }
    private void send(InputEvent event) throws Exception {
        // Async submission avoids blocking on a game's event loop. Fixture verifies actual delivery.
        if(!Boolean.TRUE.equals(inject.invoke(manager,event,0)))throw new IllegalStateException("input submission rejected");
    }
    private void key(int code,int action,long down) throws Exception {
        send(new KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,-1,0,0,InputDevice.SOURCE_GAMEPAD));
    }
    private void axes(float x,float y,long down) throws Exception {
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;properties.toolType=MotionEvent.TOOL_TYPE_UNKNOWN;
        MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();coordinates.setAxisValue(MotionEvent.AXIS_X,x);coordinates.setAxisValue(MotionEvent.AXIS_Y,y);
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coordinates},0,0,1,1,-1,0,InputDevice.SOURCE_JOYSTICK,0);
        try {send(event);} finally {event.recycle();}
    }
    private void release(int[] codes,long down) throws Exception {
        Exception failure=null;
        for(int i=codes.length-1;i>=0;i--)try{key(codes[i],KeyEvent.ACTION_UP,down);}catch(Exception error){failure=error;}
        try {axes(0,0,down);}catch(Exception error){failure=error;}
        if(failure!=null)throw failure;
    }
    private static boolean allowed(int code) {
        return code==19||code==20||code==21||code==22||code==96||code==97||code==99||code==100||(code>=102&&code<=109);
    }
    public static void main(String[] args) {
        try {
            if(args.length!=5||(!args[0].equals("pulse")&&!args[0].equals("release")))throw new IllegalArgumentException("invalid gamepad bridge operation");
            String[] values=args[1].isEmpty()?new String[0]:args[1].split(",");
            if(values.length>16)throw new IllegalArgumentException("too many buttons");
            int[] codes=new int[values.length];java.util.HashSet<Integer> seen=new java.util.HashSet<>();
            for(int i=0;i<values.length;i++){codes[i]=Integer.parseInt(values[i]);if(!allowed(codes[i])||!seen.add(codes[i]))throw new IllegalArgumentException("invalid button code");}
            float x=Float.parseFloat(args[2]),y=Float.parseFloat(args[3]);int hold=Integer.parseInt(args[4]);
            if(!Float.isFinite(x)||!Float.isFinite(y)||Math.abs(x)>1||Math.abs(y)>1||hold<10||hold>2000)throw new IllegalArgumentException("invalid axes or hold duration");
            GamepadBridge bridge=new GamepadBridge();long down=SystemClock.uptimeMillis();
            if(args[0].equals("release")){bridge.release(codes,down);}
            else {
                if(codes.length>4)throw new IllegalArgumentException("too many pulse buttons");
                try {bridge.axes(x,y,down);for(int code:codes)bridge.key(code,KeyEvent.ACTION_DOWN,down);SystemClock.sleep(hold);}
                finally {bridge.release(codes,down);}
            }
            System.out.println(new JSONObject().put("submitted",true).put("released",true).put("gameVerified",false).put("sourceGamepad",InputDevice.SOURCE_GAMEPAD).put("sourceJoystick",InputDevice.SOURCE_JOYSTICK).put("elapsedMs",SystemClock.uptimeMillis()-down).toString());
        } catch(Throwable error) {
            while(error.getCause()!=null)error=error.getCause();System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);
        }
    }
}

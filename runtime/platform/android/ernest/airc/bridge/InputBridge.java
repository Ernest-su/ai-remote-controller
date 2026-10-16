package ernest.airc.bridge;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** One runtime-owned process, private stdio only; every key is a finite down/up transaction. */
public final class InputBridge {
    private final Object manager;
    private final Method inject;
    private final Map<Integer,Long> pressed = new HashMap<>();
    private InputBridge() throws Exception {
        Class<?> type;
        try { type=Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch(ClassNotFoundException older) { type=Class.forName("android.hardware.input.InputManager"); }
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
    }
    private static boolean allowed(int code) {
        return (code>=7&&code<=16)||(code>=19&&code<=26)||code==67||code==82||code==85||code==87||code==88||code==164||code==166||code==167||code==3||code==4;
    }
    private void send(int code,int action,long down) throws Exception {
        KeyEvent event=new KeyEvent(down,SystemClock.uptimeMillis(),action,code,0,0,-1,0,0,InputDevice.SOURCE_KEYBOARD);
        // Submission confirmation; receiving application consumption is verified separately.
        if(!Boolean.TRUE.equals(inject.invoke(manager,event,0))) throw new IllegalStateException("input submission rejected");
    }
    private synchronized void release(int code) throws Exception {
        long down=pressed.containsKey(code)?pressed.get(code):SystemClock.uptimeMillis();
        send(code,KeyEvent.ACTION_UP,down);
        pressed.remove(code);
    }
    private synchronized void tap(int code) throws Exception {
        long down=SystemClock.uptimeMillis();
        pressed.put(code,down);
        try { send(code,KeyEvent.ACTION_DOWN,down); }
        finally { release(code); }
    }
    private synchronized void releaseAll() {
        for(int code:new java.util.ArrayList<>(pressed.keySet())) {
            try { release(code); } catch(Exception error) { System.err.println("input release failed: "+error); }
        }
    }
    public static void main(String[] args) {
        InputBridge bridge=null;
        try {
            bridge=new InputBridge();
            final InputBridge owned=bridge;
            Runtime.getRuntime().addShutdownHook(new Thread(owned::releaseAll));
            System.out.println(new JSONObject().put("ready",true).put("pid",Process.myPid()).put("uid",Os.getuid()));
            BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while((line=reader.readLine())!=null) {
                long id=-1;
                try {
                    if(line.length()>1024) throw new IllegalArgumentException("input request too large");
                    JSONObject request=new JSONObject(line);id=request.getLong("id");
                    int code=request.getInt("code");String operation=request.getString("operation");
                    if(!allowed(code)) throw new IllegalArgumentException("unsupported input code");
                    if(operation.equals("key")) bridge.tap(code);
                    else if(operation.equals("release")) bridge.release(code);
                    else throw new IllegalArgumentException("unsupported input operation");
                    System.out.println(new JSONObject().put("id",id).put("ok",true).put("released",true));
                } catch(Throwable error) {
                    while(error.getCause()!=null)error=error.getCause();
                    System.out.println(new JSONObject().put("id",id).put("ok",false).put("error",error.toString()));
                }
            }
        } catch(Throwable error) {
            try { System.out.println(new JSONObject().put("ready",false).put("error",error.toString())); }
            catch(Exception ignored) { System.err.println(error); }
        } finally { if(bridge!=null)bridge.releaseAll(); }
    }
}

package ernest.airc.bridge;

import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

/** Runtime-owned key edges and repeats; an independent timer releases abandoned holds. */
public final class InputBridge {
    private final Object manager;
    private final Method inject;
    private final Map<Integer,Long> pressed = new HashMap<>();
    private static final class Hold {
        final String id; final long down;
        long heartbeat, nextRepeat; int repeats;
        Hold(String id, long now) { this.id=id; down=now; heartbeat=now; nextRepeat=now+ViewConfiguration.getLongPressTimeout(); }
    }
    private final Map<Integer,Hold> holds = new HashMap<>();
    private final java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"airc-key-repeat");thread.setDaemon(true);return thread;
    });
    private InputBridge() throws Exception {
        Class<?> type;
        try { type=Class.forName("android.hardware.input.InputManagerGlobal"); }
        catch(ClassNotFoundException older) { type=Class.forName("android.hardware.input.InputManager"); }
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        timer.scheduleAtFixedRate(this::tick,10,10,java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    private static boolean allowed(int code) {
        return (code>=7&&code<=16)||(code>=19&&code<=26)||code==66||code==67||code==82||code==85||code==87||code==88||code==164||code==166||code==167||code==3||code==4;
    }
    private void send(int code,int action,long down) throws Exception {
        send(code,action,down,0,0);
    }
    private void send(int code,int action,long down,int repeat,int flags) throws Exception {
        KeyEvent event=new KeyEvent(down,SystemClock.uptimeMillis(),action,code,repeat,0,-1,0,flags,InputDevice.SOURCE_KEYBOARD);
        // Submission confirmation; receiving application consumption is verified separately.
        if(!Boolean.TRUE.equals(inject.invoke(manager,event,0))) throw new IllegalStateException("input submission rejected");
    }
    private synchronized void release(int code) throws Exception {
        long down=pressed.containsKey(code)?pressed.get(code):SystemClock.uptimeMillis();
        send(code,KeyEvent.ACTION_UP,down);
        pressed.remove(code);
        holds.remove(code);
    }
    private synchronized void edge(int code,String action,String id) throws Exception {
        long now=SystemClock.uptimeMillis();
        Hold hold=holds.get(code);
        if(action.equals("down")) {
            if(pressed.containsKey(code)) throw new IllegalArgumentException("key already held");
            hold=new Hold(id,now);holds.put(code,hold);pressed.put(code,now);
            try {send(code,KeyEvent.ACTION_DOWN,now);} catch(Exception error) {release(code);throw error;}
            return;
        }
        if(hold==null) {
            if(action.equals("up")||action.equals("cancel")) return;
            throw new IllegalArgumentException("key hold expired");
        }
        if(!hold.id.equals(id)) throw new IllegalArgumentException("stale key gesture");
        if(now-hold.heartbeat>=1500||now-hold.down>=120000) {
            cancel(code,hold);throw new IllegalArgumentException("key hold expired");
        }
        if(action.equals("keep")) hold.heartbeat=now;
        else if(action.equals("cancel")) cancel(code,hold);
        else if(action.equals("up")) release(code);
        else throw new IllegalArgumentException("unsupported input operation");
    }
    private void cancel(int code,Hold hold) throws Exception {
        send(code,KeyEvent.ACTION_UP,hold.down,0,KeyEvent.FLAG_CANCELED);
        holds.remove(code);pressed.remove(code);
    }
    private synchronized void tick() {
        long now=SystemClock.uptimeMillis();
        for(int code:new java.util.ArrayList<>(holds.keySet())) {
            Hold hold=holds.get(code);
            try {
                if(now-hold.heartbeat>=1500||now-hold.down>=120000) cancel(code,hold);
                else if(now>=hold.nextRepeat) {
                    hold.repeats++;
                    send(code,KeyEvent.ACTION_DOWN,hold.down,hold.repeats,hold.repeats==1?KeyEvent.FLAG_LONG_PRESS:0);
                    hold.nextRepeat=now+ViewConfiguration.getKeyRepeatDelay();
                }
            } catch(Exception error) {
                System.err.println("key repeat/release failed: "+error);
                try {cancel(code,hold);} catch(Exception ignored) { /* retry on next tick */ }
            }
        }
    }
    private synchronized void tap(int code) throws Exception {
        if(pressed.containsKey(code)) throw new IllegalArgumentException("key already held");
        long down=SystemClock.uptimeMillis();
        pressed.put(code,down);
        try { send(code,KeyEvent.ACTION_DOWN,down); }
        finally { release(code); }
    }
    private synchronized void releaseAll() {
        for(int code:new java.util.ArrayList<>(pressed.keySet())) {
            try {
                Hold hold=holds.get(code);
                if(hold!=null) cancel(code,hold); else release(code);
            } catch(Exception error) { System.err.println("input release failed: "+error); }
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
                    boolean released=operation.equals("key")||operation.equals("release")||operation.equals("up")||operation.equals("cancel");
                    if(operation.equals("key")) bridge.tap(code);
                    else if(operation.equals("release")) bridge.release(code);
                    else if(operation.equals("down")||operation.equals("keep")||operation.equals("up")||operation.equals("cancel")) {
                        String pressId=request.getString("pressId");java.util.UUID.fromString(pressId);
                        bridge.edge(code,operation,pressId);
                    } else throw new IllegalArgumentException("unsupported input operation");
                    System.out.println(new JSONObject().put("id",id).put("ok",true).put("released",released));
                } catch(Throwable error) {
                    while(error.getCause()!=null)error=error.getCause();
                    System.out.println(new JSONObject().put("id",id).put("ok",false).put("error",error.toString()));
                }
            }
        } catch(Throwable error) {
            try { System.out.println(new JSONObject().put("ready",false).put("error",error.toString())); }
            catch(Exception ignored) { System.err.println(error); }
        } finally { if(bridge!=null) {bridge.timer.shutdownNow();bridge.releaseAll();} }
    }
}

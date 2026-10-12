package ernest.airc.bridge;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;

/** Observations only; never changes power, keyguard or credentials. */
public final class SystemStateBridge {
    private interface Query { boolean read() throws Exception; }
    private static Object read(JSONObject errors,String name,Query query) throws Exception {
        try { return query.read(); }
        catch(Throwable error) {
            while(error.getCause()!=null)error=error.getCause();
            errors.put(name,error.getClass().getSimpleName());
            return JSONObject.NULL;
        }
    }
    public static void main(String[] args) {
        try {
            if(args.length!=1||!args[0].equals("state"))throw new IllegalArgumentException("invalid state query");
            Context context=FrameworkContext.forUser(0);
            PowerManager power=context.getSystemService(PowerManager.class);
            KeyguardManager guard=context.getSystemService(KeyguardManager.class);
            JSONObject errors=new JSONObject();
            JSONObject result=new JSONObject().put("uid",Process.myUid()).put("userId",0)
                .put("elapsedRealtimeMs",SystemClock.elapsedRealtime())
                .put("interactive",read(errors,"interactive",()->power.isInteractive()))
                .put("keyguardLocked",read(errors,"keyguardLocked",()->guard.isKeyguardLocked()))
                .put("deviceLocked",read(errors,"deviceLocked",()->guard.isDeviceLocked()))
                .put("deviceSecure",read(errors,"deviceSecure",()->guard.isDeviceSecure()))
                .put("errors",errors);
            System.out.println(result.toString());
        }catch(Throwable error){while(error.getCause()!=null)error=error.getCause();System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);}
    }
}

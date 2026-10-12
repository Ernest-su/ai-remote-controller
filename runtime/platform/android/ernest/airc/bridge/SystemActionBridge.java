package ernest.airc.bridge;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONObject;

/** Fixed framework operations; called only after the binary consumes manual approval. */
public final class SystemActionBridge {
    public static void main(String[] args) {
        try {
            if(args.length!=1 || !(args[0].equals("lock")||args[0].equals("reboot")||args[0].equals("shutdown")))throw new IllegalArgumentException("invalid system action");
            if(Process.myUid()!=0)throw new SecurityException("root required");
            String action=args[0];
            Context context=FrameworkContext.forUser(0);
            String permission=action.equals("lock")?"android.permission.DEVICE_POWER":"android.permission.REBOOT";
            if(context.checkPermission(permission,Process.myPid(),Process.myUid())!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("permission denied");
            boolean lock=action.equals("lock");
            String type=lock?"android.view.IWindowManager":"android.os.IPowerManager";
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,lock?"window":"power");
            if(binder==null)throw new IllegalStateException("framework service unavailable");
            Object service=Class.forName(type+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            if(lock)Class.forName(type).getMethod("lockNow",Bundle.class).invoke(service,new Object[]{null});
            else Class.forName(type).getMethod(action,boolean.class,String.class,boolean.class).invoke(service,false,"airc",false);
            System.out.println(new JSONObject().put("action",action).put("submitted",true).toString());
        } catch(Throwable error) { while(error.getCause()!=null)error=error.getCause();System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1); }
    }
}

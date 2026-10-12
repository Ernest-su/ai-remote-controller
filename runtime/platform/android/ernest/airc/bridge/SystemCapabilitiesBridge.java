package ernest.airc.bridge;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.Bundle;
import org.json.JSONObject;

/** Read-only interface/permission discovery. Never invokes a power or lock action. */
public final class SystemCapabilitiesBridge {
    private static boolean method(String type,String name,Class<?>... parameters){
        try{Class.forName(type).getMethod(name,parameters);return true;}catch(Throwable unavailable){return false;}
    }
    public static void main(String[] args){
        try{
            if(args.length!=1||!"capabilities".equals(args[0]))throw new IllegalArgumentException("invalid capability probe");
            Context caller=FrameworkContext.forUser(0);
            boolean power=caller.checkPermission("android.permission.DEVICE_POWER",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED;
            boolean reboot=caller.checkPermission("android.permission.REBOOT",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED;
            System.out.println(new JSONObject().put("uid",Process.myUid()).put("devicePowerGranted",power).put("rebootGranted",reboot)
                .put("lockInterfacePresent",method("android.view.IWindowManager","lockNow",Bundle.class))
                .put("rebootInterfacePresent",method("android.os.IPowerManager","reboot",boolean.class,String.class,boolean.class))
                .put("shutdownInterfacePresent",method("android.os.IPowerManager","shutdown",boolean.class,String.class,boolean.class)).toString());
        }catch(Throwable error){while(error.getCause()!=null)error=error.getCause();System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);}
    }
}

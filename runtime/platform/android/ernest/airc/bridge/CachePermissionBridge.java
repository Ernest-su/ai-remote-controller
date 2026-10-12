package ernest.airc.bridge;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import org.json.JSONObject;

/** Read-only effective caller permission probe; never grants permission or clears data. */
public final class CachePermissionBridge {
    public static void main(String[] args) {
        try {
            if(args.length!=2||!args[0].equals("permission"))throw new IllegalArgumentException("invalid permission probe");
            Context caller=FrameworkContext.forUser(Integer.parseInt(args[1]));
            boolean granted=caller.checkPermission("android.permission.INTERNAL_DELETE_CACHE_FILES",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED;
            System.out.println(new JSONObject().put("internalDeleteCacheGranted",granted).put("uid",Process.myUid()).toString());
        } catch(Throwable error){while(error.getCause()!=null)error=error.getCause();System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);}
    }
}

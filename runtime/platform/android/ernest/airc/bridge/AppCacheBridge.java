package ernest.airc.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Cache-only PackageManager API; never calls clearApplicationUserData or deletes paths. */
public final class AppCacheBridge {
    private static final String OBSERVER="android.content.pm.IPackageDataObserver";
    private static Method operation() throws Exception {
        return Class.forName("android.content.pm.IPackageManager").getMethod("deleteApplicationCacheFilesAsUser",String.class,int.class,Class.forName(OBSERVER));
    }
    private static final class Observer extends Binder {
        Observer() { attachInterface(null,OBSERVER); }
        final CountDownLatch done=new CountDownLatch(1);
        volatile String packageName;
        volatile boolean succeeded;
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
            if(code==IBinder.INTERFACE_TRANSACTION) { if(reply!=null)reply.writeString(OBSERVER);return true; }
            if(code==IBinder.FIRST_CALL_TRANSACTION) {
                data.enforceInterface(OBSERVER);
                packageName=data.readString();succeeded=data.readInt()!=0;done.countDown();
                return true;
            }
            return super.onTransact(code,data,reply,flags);
        }
    }
    public static void main(String[] args) {
        try {
            if(args.length<2)throw new IllegalArgumentException("invalid cache arguments");
            int user=Integer.parseInt(args[1]);if(user<0)throw new IllegalArgumentException("invalid user");
            Context caller=FrameworkContext.forUser(user);
            String permission=android.os.Build.VERSION.SDK_INT<28?"android.permission.DELETE_CACHE_FILES":"android.permission.INTERNAL_DELETE_CACHE_FILES";
            boolean granted=caller.checkPermission(permission,Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED;
            boolean available;
            try { operation();available=true; } catch(ReflectiveOperationException absent) { available=false; }
            if(args[0].equals("capabilities")&&args.length==2) {
                System.out.println(new JSONObject().put("cacheOnlyApiAvailable",available).put("cacheDeleteGranted",granted).put("cacheDeletePermission",permission).put("uid",Process.myUid()).toString());
            } else if(args[0].equals("clear")&&args.length==3) {
                if(!granted||(Process.myUid()!=0&&Process.myUid()!=2000))throw new SecurityException("cache deletion permission unavailable");
                String pkg=args[2];
                if(!pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))throw new IllegalArgumentException("invalid package");
                android.content.pm.ApplicationInfo app=caller.getPackageManager().getApplicationInfo(pkg,0);
                if((app.flags&android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0||app.uid%100000<10000)throw new SecurityException("system app cache deletion is not allowed");
                Observer observer=new Observer();
                Object callback=Class.forName(OBSERVER+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,observer);
                Object manager=Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
                operation().invoke(manager,pkg,user,callback);
                if(!observer.done.await(20,TimeUnit.SECONDS))throw new IllegalStateException("cache cleanup completion timed out; operation may still finish");
                if(!pkg.equals(observer.packageName)||!observer.succeeded)throw new IllegalStateException("package manager did not confirm cache cleanup");
                System.out.println(new JSONObject().put("package",pkg).put("userId",user).put("cacheOnly",true).put("completed",true).toString());
            } else throw new IllegalArgumentException("invalid cache operation");
        } catch(Throwable error) { while(error.getCause()!=null)error=error.getCause();System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1); }
    }
}

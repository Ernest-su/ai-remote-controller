package ernest.airc.bridge;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Read-only, bounded current-user storage snapshot; never estimates unknown values as zero. */
final class AppStorage {
    static Map<String,Long> read(Context context,int user,List<PackageInfo> apps,java.util.function.BiConsumer<String,Long> onResult) throws Exception {
        Map<String,Long> result=new ConcurrentHashMap<>();
        long deadline=SystemClock.elapsedRealtime()+300000;
        ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"airc-app-storage");t.setDaemon(true);return t;});
        Future<?> work=executor.submit(()->{
            if(android.os.Build.VERSION.SDK_INT>=26) {
                for(PackageInfo app:apps) {
                    if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)break;
                    Long size=null;
                    try {
                        android.app.usage.StorageStatsManager manager=(android.app.usage.StorageStatsManager)context.getSystemService("storagestats");
                        android.os.UserHandle handle=(android.os.UserHandle)android.os.UserHandle.class.getMethod("of",int.class).invoke(null,user);
                        android.app.usage.StorageStats stats=manager.queryStatsForPackage(app.applicationInfo.storageUuid==null?android.os.storage.StorageManager.UUID_DEFAULT:app.applicationInfo.storageUuid,app.packageName,handle);
                        long code=stats.getAppBytes(), data=stats.getDataBytes(); // Data already includes cache.
                        if(code>=0&&data>=0)size=Math.addExact(code,data);
                    } catch(Exception unavailable) { /* Missing authorization, removed package or storage unavailable. */ }
                    if(size!=null)result.put(app.packageName,size);
                    onResult.accept(app.packageName,size);
                }
            } else {
                try {
                    Class<?> observerType=Class.forName("android.content.pm.IPackageStatsObserver");
                    java.lang.reflect.Method query=Class.forName("android.content.pm.IPackageManager").getMethod("getPackageSizeInfo",String.class,int.class,observerType);
                    Object manager=Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
                    for(PackageInfo app:apps) {
                        if(Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()>=deadline)break;
                        CountDownLatch done=new CountDownLatch(1);
                        try {
                            StatsObserver binder=new StatsObserver(app.packageName,user,result,done);
                            Object observer=Class.forName("android.content.pm.IPackageStatsObserver$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
                            query.invoke(manager,app.packageName,user,observer);
                            done.await(Math.max(0,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
                        } catch(InterruptedException stopped) { Thread.currentThread().interrupt();break; }
                        catch(Exception unavailable) { /* Keep the remaining packages eligible. */ }
                        onResult.accept(app.packageName,result.get(app.packageName));
                    }
                } catch(RuntimeException failed) { throw failed; }
                catch(Exception unavailable) { /* Keep basic app metadata available. */ }
            }
        });
        try { work.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS); }
        catch(TimeoutException expired) { work.cancel(true); }
        catch(ExecutionException failed) { throw new IllegalStateException("storage stream failed",failed.getCause()); }
        catch(InterruptedException stopped) { work.cancel(true);Thread.currentThread().interrupt();throw stopped; }
        finally { executor.shutdownNow(); }
        return new java.util.HashMap<>(result);
    }
    private static final class StatsObserver extends Binder {
        static final String DESCRIPTOR="android.content.pm.IPackageStatsObserver";
        final String name;final int user;final Map<String,Long> result;final CountDownLatch done;
        StatsObserver(String name,int user,Map<String,Long> result,CountDownLatch done) {
            attachInterface(null,DESCRIPTOR);this.name=name;this.user=user;this.result=result;this.done=done;
        }
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws android.os.RemoteException {
            if(code==IBinder.INTERFACE_TRANSACTION) { if(reply!=null)reply.writeString(DESCRIPTOR);return true; }
            if(code!=IBinder.FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(DESCRIPTOR);
            try {
                Class<?> type=Class.forName("android.content.pm.PackageStats");
                Parcelable.Creator<?> creator=(Parcelable.Creator<?>)type.getField("CREATOR").get(null);
                Object stats=data.readInt()!=0?creator.createFromParcel(data):null;
                boolean success=data.readInt()!=0;
                if(success&&stats!=null&&name.equals(type.getField("packageName").get(stats))&&type.getField("userHandle").getInt(stats)==user) {
                    long total=0;
                    for(String field:new String[]{"codeSize","dataSize","cacheSize","externalCodeSize","externalDataSize","externalCacheSize","externalMediaSize","externalObbSize"}) {
                        long value=type.getField(field).getLong(stats);if(value<0)throw new IllegalStateException("unknown size");total=Math.addExact(total,value);
                    }
                    result.put(name,total);
                }
            } catch(Exception unavailable) { /* Unknown stays absent. */ }
            finally { done.countDown(); }
            return true;
        }
    }
}

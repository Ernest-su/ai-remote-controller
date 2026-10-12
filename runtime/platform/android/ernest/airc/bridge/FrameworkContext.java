package ernest.airc.bridge;

import android.content.Context;
import android.os.Looper;

/** Caller identity is the existing shell package within the explicitly selected user. */
final class FrameworkContext {
    static Context forUser(int user) throws Exception {
        if(user<0) throw new IllegalArgumentException("invalid user");
        if(Looper.myLooper()==null) Looper.prepare();
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        Object thread=threadClass.getMethod("systemMain").invoke(null);
        Context system=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
        Class<?> handleClass=Class.forName("android.os.UserHandle");
        Object handle=handleClass.getMethod("of",int.class).invoke(null,user);
        return (Context)Context.class.getMethod("createPackageContextAsUser",String.class,int.class,handleClass).invoke(system,"com.android.shell",0,handle);
    }
}

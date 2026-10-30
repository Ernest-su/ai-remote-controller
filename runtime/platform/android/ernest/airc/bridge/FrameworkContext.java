package ernest.airc.bridge;

import android.content.Context;
import android.os.Looper;

/** Caller identity is the existing shell package within the explicitly selected user. */
final class FrameworkContext {
    private static Context system;
    private static Context caller;
    private static int callerUser = -1;
    static synchronized Context forUser(int user) throws Exception {
        if(user<0) throw new IllegalArgumentException("invalid user");
        if(Looper.myLooper()==null) Looper.prepare();
        Class<?> threadClass=Class.forName("android.app.ActivityThread");
        if(system==null) {
            Object thread=threadClass.getMethod("systemMain").invoke(null);
            system=(Context)threadClass.getMethod("getSystemContext").invoke(thread);
        }
        if(caller!=null&&callerUser==user)return caller;
        Class<?> handleClass=Class.forName("android.os.UserHandle");
        Object handle=handleClass.getMethod("of",int.class).invoke(null,user);
        caller=(Context)Context.class.getMethod("createPackageContextAsUser",String.class,int.class,handleClass).invoke(system,"com.android.shell",0,handle);
        callerUser=user;
        return caller;
    }
}

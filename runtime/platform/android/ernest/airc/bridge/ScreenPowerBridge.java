package ernest.airc.bridge;

import android.os.Build;
import android.os.IBinder;
import android.system.Os;
import java.lang.reflect.Method;

/** Controls physical display power without putting Android to sleep. */
public final class ScreenPowerBridge {
    private static Class<?> displayControl() throws Exception {
        Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        ClassLoader loader = (ClassLoader) factory.getDeclaredMethod("createClassLoader", String.class,
            String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class)
            .invoke(null, System.getenv("SYSTEMSERVERCLASSPATH"), null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> type = loader.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), type, "android_servers");
        return type;
    }
    public static void main(String[] args) {
        int status = 0;
        try {
            if (args.length != 1 || !(args[0].equals("on") || args[0].equals("off")))
                throw new IllegalArgumentException("expected on or off");
            Class<?> surface = Class.forName("android.view.SurfaceControl");
            Class<?> displays = Build.VERSION.SDK_INT >= 34
                ? displayControl() : surface;
            int mode = args[0].equals("on") ? 2 : 0;
            Method set = surface.getMethod("setDisplayPowerMode", IBinder.class, int.class);
            if (Build.VERSION.SDK_INT >= 29) {
                long[] ids = (long[]) displays.getMethod("getPhysicalDisplayIds").invoke(null);
                if (ids == null || ids.length == 0) throw new IllegalStateException("no physical displays");
                Method token = displays.getMethod("getPhysicalDisplayToken", long.class);
                for (long id : ids) set.invoke(null, token.invoke(null, id), mode);
            } else {
                Object token = surface.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
                if (token == null) throw new IllegalStateException("no built-in display");
                set.invoke(null, token, mode);
            }
            System.out.println("{}");
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            System.err.println(error.getClass().getSimpleName() + ": " + error.getMessage());
            status = 1;
        }
        // Loading android_servers can register vendor native destructors that crash during
        // normal app_process shutdown. This one-shot bridge has no shutdown work: all Binder
        // calls have returned. Flush the result and replace this process with a minimal shell
        // exit, bypassing loaded native destructors while preserving the actual status.
        System.out.flush();
        System.err.flush();
        try {
            Os.execv("/system/bin/sh", new String[] {"sh", "-c", status == 0 ? "exit 0" : "exit 1"});
        } catch (Exception error) {
            System.err.println("Could not finish screen power bridge: " + error);
            System.err.flush();
            System.exit(1);
        }
    }
}

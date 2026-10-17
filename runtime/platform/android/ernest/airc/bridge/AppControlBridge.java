package ernest.airc.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Process;
import android.os.UserHandle;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads actual per-user state and effective caller capabilities. Mutations use fixed pm/am commands. */
public final class AppControlBridge {
    private static boolean allowed(Context context, String permission) {
        return context.checkPermission("android.permission." + permission, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
    }
    public static void main(String[] args) {
        try {
            if (args.length != 3 || !args[0].equals("details")) throw new IllegalArgumentException("invalid app control query");
            int user = Integer.parseInt(args[1]);
            String name = args[2];
            Context context = FrameworkContext.forUser(user);
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(name, PackageManager.GET_PERMISSIONS);
            ApplicationInfo app = info.applicationInfo;
            if (app == null) throw new IllegalStateException("application info unavailable");
            boolean userApp = (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 && app.uid % 100000 >= 10000;
            boolean caller = Process.myUid() == 0 || Process.myUid() == 2000;
            boolean grant = caller && allowed(context, "GRANT_RUNTIME_PERMISSIONS");
            boolean revoke = caller && allowed(context, "REVOKE_RUNTIME_PERMISSIONS");
            JSONArray permissions = new JSONArray();
            String[] requested = info.requestedPermissions == null ? new String[0] : info.requestedPermissions;
            if (requested.length > 1024) throw new IllegalStateException("permission count exceeds limit");
            for (String permission : requested) {
                String label = permission;
                boolean runtime = false;
                boolean fixed = true;
                String reason = "特殊访问或安装时权限，不能作为普通权限切换";
                try {
                    PermissionInfo definition = manager.getPermissionInfo(permission, 0);
                    runtime = (definition.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
                    CharSequence title = definition.loadLabel(manager);
                    if (title != null) label = title.toString().substring(0, Math.min(title.length(), 256));
                    if (runtime) {
                        try {
                            UserHandle handle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, user);
                            int flags = (Integer) PackageManager.class.getMethod("getPermissionFlags", String.class, String.class, UserHandle.class)
                                .invoke(manager, permission, name, handle);
                            // POLICY_FIXED, SYSTEM_FIXED and GRANTED_BY_DEFAULT are handled conservatively.
                            fixed = (flags & (4 | 16 | 32)) != 0;
                            reason = fixed ? "权限受系统或策略管理" : "";
                        } catch (ReflectiveOperationException unavailable) { reason = "无法读取权限策略，暂不支持修改"; }
                    }
                } catch (PackageManager.NameNotFoundException unknown) { reason = "权限定义不可用"; }
                boolean granted = manager.checkPermission(permission, name) == PackageManager.PERMISSION_GRANTED;
                boolean mutable = userApp && app.targetSdkVersion > 22 && runtime && !fixed && (granted ? revoke : grant);
                if (!mutable && reason.isEmpty()) reason = userApp ? "服务缺少修改此权限的授权" : "系统及特权应用仅供查看";
                permissions.put(new JSONObject().put("name", permission).put("label", label).put("granted", granted)
                    .put("runtime", runtime).put("mutable", mutable).put("reason", reason));
            }
            JSONObject capabilities = new JSONObject()
                .put("forceStop", userApp && caller && allowed(context, "FORCE_STOP_PACKAGES"))
                .put("setEnabled", userApp && caller && allowed(context, "CHANGE_COMPONENT_ENABLED_STATE"))
                .put("clearData", userApp && caller && allowed(context, "CLEAR_APP_USER_DATA"));
            System.out.println(new JSONObject().put("package", name).put("userId", user)
                .put("enabled", app.enabled).put("enabledSetting", manager.getApplicationEnabledSetting(name))
                .put("stopped", (app.flags & ApplicationInfo.FLAG_STOPPED) != 0)
                .put("capabilities", capabilities).put("permissions", permissions).toString());
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            System.err.println(error.getClass().getSimpleName() + ": " + error.getMessage());
            System.exit(1);
        }
    }
}

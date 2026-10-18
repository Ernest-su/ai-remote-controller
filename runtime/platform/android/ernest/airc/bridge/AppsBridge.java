package ernest.airc.bridge;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Reads package labels only; does not launch, enable, install or remove applications. */
public final class AppsBridge {
    private static long version(android.content.pm.PackageInfo info) {
        return android.os.Build.VERSION.SDK_INT>=28 ? info.getLongVersionCode() : info.versionCode;
    }
    private static String key(int user, android.content.pm.PackageInfo info, android.content.res.Configuration configuration) throws Exception {
        ApplicationInfo app=info.applicationInfo;
        String identity="1\n"+user+"\n"+info.packageName+"\n"+info.firstInstallTime+"\n"+info.lastUpdateTime+"\n"+version(info)+"\n"+app.uid+"\n"+(app.flags&ApplicationInfo.FLAG_SYSTEM)+"\n"+app.sourceDir+"\n"+java.util.Arrays.toString(app.splitSourceDirs)+"\n"+configuration.toString()+"\n"+java.util.TimeZone.getDefault().getID();
        // Overlay resource paths can change without replacing this application's APK.
        for(String field:new String[]{"resourceDirs","overlayPaths"}) {
            try { identity+="\n"+java.util.Arrays.toString((String[])ApplicationInfo.class.getField(field).get(app)); }
            catch(ReflectiveOperationException unavailable) { identity+="\n"; }
        }
        return AppMetadataCache.digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String id(int user,String name) {
        if(user<0 || name.length()>255 || !name.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)*")) throw new IllegalArgumentException("invalid application identity");
        return user+"-"+name;
    }
    private static JSONObject metadata(AppMetadataCache cache, PackageManager manager, int user, android.content.pm.PackageInfo info, android.content.res.Configuration configuration) throws Exception {
        String id=id(user,info.packageName), key=key(user,info,configuration);
        JSONObject previous=cache.metadata(id,key);
        if(previous!=null && previous.opt("label") instanceof String && previous.getString("label").length()<=256 && previous.opt("app") instanceof JSONObject) return previous;
        ApplicationInfo app=info.applicationInfo;
        if(app==null) throw new IllegalStateException("application info unavailable");
        CharSequence title=null;
        try { title=manager.getApplicationLabel(app); } catch(RuntimeException unavailable) { /* Retry an unavailable label on the next query. */ }
        String label=title==null ? null : title.toString();
        if(label!=null && label.length()>256) label=label.substring(0,256);
        JSONObject details=new JSONObject().put("package",info.packageName).put("userId",user).put("appId",app.uid%100000)
            .put("firstInstallTime",info.firstInstallTime).put("lastUpdateTime",new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.ROOT).format(new java.util.Date(info.lastUpdateTime))).put("versionCode",version(info))
            .put("versionName",info.versionName==null?JSONObject.NULL:info.versionName).put("codePath",new java.io.File(app.sourceDir).getParent())
            .put("system",(app.flags&ApplicationInfo.FLAG_SYSTEM)!=0);
        JSONObject value=new JSONObject().put("cacheKey",key).put("label",label==null?JSONObject.NULL:label).put("app",details);
        if(label!=null) cache.put(id,value,null);
        return value;
    }
    private static void installed(String operation,int user,String name,String directory) throws Exception {
        id(user,name);
        android.content.Context context=FrameworkContext.forUser(user);
        PackageManager manager=context.getPackageManager();
        try(AppMetadataCache cache=new AppMetadataCache(directory)) {
            android.content.pm.PackageInfo info;
            try { info=manager.getPackageInfo(name,0); }
            catch(PackageManager.NameNotFoundException removed) { cache.remove(user,name);throw removed; }
            JSONObject value=metadata(cache,manager,user,info,context.getResources().getConfiguration());
            if(operation.equals("info")) { System.out.println(value.getJSONObject("app"));return; }
            byte[] bytes=cache.icon(id(user,name),value);
            if(bytes==null) {
                bytes=renderIcon(info.applicationInfo.loadIcon(manager));
                // Do not publish pixels from an application replaced during rendering.
                android.content.pm.PackageInfo current=manager.getPackageInfo(name,0);
                if(!key(user,current,context.getResources().getConfiguration()).equals(value.getString("cacheKey"))) throw new IllegalStateException("application changed while reading icon");
                cache.put(id(user,name),value,bytes);
            }
            System.out.println(new JSONObject().put("cacheKey",value.getString("cacheKey")).put("icon",hex(bytes)));
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder hex=new StringBuilder(bytes.length*2);
        for(byte b:bytes) { hex.append(Character.forDigit((b>>4)&15,16));hex.append(Character.forDigit(b&15,16)); }
        return hex.toString();
    }
    private static void archiveIcon(int user, String path) throws Exception {
        if(!path.matches("/proc/[0-9]+/fd/[0-9]+")) throw new IllegalArgumentException("pinned APK descriptor required");
        PackageManager manager=FrameworkContext.forUser(user).getPackageManager();
        android.content.pm.PackageInfo info=manager.getPackageArchiveInfo(path,0);
        if(info==null || info.applicationInfo==null) throw new IllegalArgumentException("cannot read APK application");
        info.applicationInfo.sourceDir=path;info.applicationInfo.publicSourceDir=path;
        System.out.println(hex(renderIcon(info.applicationInfo.loadIcon(manager))));
    }
    private static byte[] renderIcon(android.graphics.drawable.Drawable drawable) throws Exception {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0,0,96,96);
            drawable.draw(new android.graphics.Canvas(bitmap));
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)) throw new IllegalStateException("icon encoding failed");
            byte[] bytes=output.toByteArray();
            if(bytes.length>128*1024) throw new IllegalStateException("icon exceeds limit");
            return bytes;
        } finally { bitmap.recycle(); }
    }
    public static void main(String[] args) {
        try {
            if(args.length==3 && args[0].equals("archive_icon")) { archiveIcon(Integer.parseInt(args[1]),args[2]); return; }
            if(args.length==4 && (args[0].equals("icon") || args[0].equals("info"))) { installed(args[0],Integer.parseInt(args[1]),args[2],args[3]);return; }
            if(args.length!=3 || !args[0].equals("list")) throw new IllegalArgumentException("invalid app bridge operation");
            int user=Integer.parseInt(args[1]);
            android.content.Context context=FrameworkContext.forUser(user);
            PackageManager manager=context.getPackageManager();
            List<android.content.pm.PackageInfo> apps=new ArrayList<>(manager.getInstalledPackages(0));
            if(apps.size()>4096) throw new IllegalStateException("installed application count exceeds 4096");
            Collections.sort(apps,Comparator.comparing(info->info.packageName));
            JSONArray values=new JSONArray();int labelErrors=0;
            try(AppMetadataCache cache=new AppMetadataCache(args[2])) {
                java.util.Set<String> installed=new java.util.HashSet<>();
                for(android.content.pm.PackageInfo info:apps) {
                    String name=info.packageName;installed.add(name);
                    JSONObject value;
                    try { value=metadata(cache,manager,user,info,context.getResources().getConfiguration()); }
                    catch(PackageManager.NameNotFoundException removedDuringList) { continue; }
                    if(value.isNull("label")) labelErrors++;
                    values.put(new JSONObject().put("package",name).put("label",value.opt("label"))
                        .put("system",(info.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)!=0).put("cacheKey",value.getString("cacheKey")));
                }
                cache.retain(user,installed);
            }
            String result=new JSONObject().put("apps",values).put("source","package_manager").put("userId",user).put("labelErrors",labelErrors).toString();
            if(result.getBytes("UTF-8").length>1024*1024)throw new IllegalStateException("app list exceeds 1 MiB");
            System.out.println(result);
        } catch(Throwable error) {
            while(error.getCause()!=null)error=error.getCause();
            System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);
        }
    }
}

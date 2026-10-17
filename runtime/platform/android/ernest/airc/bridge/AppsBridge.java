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
    private static void icon(int user, String name) throws Exception {
        PackageManager manager=FrameworkContext.forUser(user).getPackageManager();
        android.graphics.drawable.Drawable drawable=manager.getApplicationIcon(name);
        renderIcon(drawable);
    }
    private static void archiveIcon(int user, String path) throws Exception {
        if(!path.matches("/proc/[0-9]+/fd/[0-9]+")) throw new IllegalArgumentException("pinned APK descriptor required");
        PackageManager manager=FrameworkContext.forUser(user).getPackageManager();
        android.content.pm.PackageInfo info=manager.getPackageArchiveInfo(path,0);
        if(info==null || info.applicationInfo==null) throw new IllegalArgumentException("cannot read APK application");
        info.applicationInfo.sourceDir=path;info.applicationInfo.publicSourceDir=path;
        renderIcon(info.applicationInfo.loadIcon(manager));
    }
    private static void renderIcon(android.graphics.drawable.Drawable drawable) throws Exception {
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0,0,96,96);
            drawable.draw(new android.graphics.Canvas(bitmap));
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)) throw new IllegalStateException("icon encoding failed");
            byte[] bytes=output.toByteArray();
            if(bytes.length>128*1024) throw new IllegalStateException("icon exceeds limit");
            StringBuilder hex=new StringBuilder(bytes.length*2);
            for(byte b:bytes) { hex.append(Character.forDigit((b>>4)&15,16));hex.append(Character.forDigit(b&15,16)); }
            System.out.println(hex);
        } finally { bitmap.recycle(); }
    }
    public static void main(String[] args) {
        try {
            if(args.length==3 && args[0].equals("archive_icon")) { archiveIcon(Integer.parseInt(args[1]),args[2]); return; }
            if(args.length==3 && args[0].equals("icon")) { icon(Integer.parseInt(args[1]),args[2]); return; }
            if(args.length!=2 || !args[0].equals("list")) throw new IllegalArgumentException("invalid app bridge operation");
            int user=Integer.parseInt(args[1]);
            PackageManager manager=FrameworkContext.forUser(user).getPackageManager();
            List<ApplicationInfo> apps=new ArrayList<>(manager.getInstalledApplications(0));
            if(apps.size()>4096) throw new IllegalStateException("installed application count exceeds 4096");
            Collections.sort(apps,Comparator.comparing(info->info.packageName));
            JSONArray values=new JSONArray();int labelErrors=0;
            for(ApplicationInfo app:apps) {
                String label=null;
                try {
                    CharSequence value=manager.getApplicationLabel(app);
                    if(value!=null && value.length()>0) {
                        String text=value.toString();
                        if(text.length()>256) text=text.substring(0,256);
                        label=text;
                    }
                } catch(RuntimeException unavailableLabel) {labelErrors++;}
                values.put(new JSONObject().put("package",app.packageName).put("label",label==null?JSONObject.NULL:label)
                    .put("system",(app.flags&ApplicationInfo.FLAG_SYSTEM)!=0));
            }
            String result=new JSONObject().put("apps",values).put("source","package_manager").put("userId",user).put("labelErrors",labelErrors).toString();
            if(result.getBytes("UTF-8").length>256*1024)throw new IllegalStateException("app list exceeds 256 KiB");
            System.out.println(result);
        } catch(Throwable error) {
            while(error.getCause()!=null)error=error.getCause();
            System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);
        }
    }
}

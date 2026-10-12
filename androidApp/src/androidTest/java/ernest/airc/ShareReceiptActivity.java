package ernest.airc;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Standalone test APK UID; Android/Java only because target Kotlin dependencies are not packaged here. */
public final class ShareReceiptActivity extends Activity {
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        JSONObject receipt=new JSONObject();
        try {
            receipt.put("uid",android.os.Process.myUid());
            Intent intent=getIntent();
            if(!Intent.ACTION_SEND.equals(intent.getAction())||!"image/png".equals(intent.getType()))throw new IllegalArgumentException();
            Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(uri==null||!"content".equals(uri.getScheme())||!"ernest.airc.screenshots".equals(uri.getAuthority()))throw new IllegalArgumentException();
            if((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new SecurityException();
            if(checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream input=getContentResolver().openInputStream(uri)){
                if(input==null)throw new IllegalStateException();
                byte[] buffer=new byte[64*1024];int n;
                while((n=input.read(buffer))!=-1){count+=n;if(count>16L*1024*1024)throw new IllegalStateException();digest.update(buffer,0,n);}
            }
            StringBuilder sha=new StringBuilder();
            for(byte value:digest.digest())sha.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
            receipt.put("bytes",count).put("sha256",sha.toString());
        }catch(Exception error){try{receipt.put("error",error.getClass().getSimpleName());}catch(Exception ignored){}}
        try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"share-receipt.json"))){out.write(receipt.toString().getBytes(StandardCharsets.UTF_8));}
        catch(Exception error){throw new IllegalStateException(error);}
        TextView view=new TextView(this);view.setText("AIRC 截图验收完成");setContentView(view);finish();
    }
}

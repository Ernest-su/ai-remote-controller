package ernest.airc.bridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** One runtime-owned process for fast media and MUSIC volume requests; EOF ends the worker. */
public final class PlaybackBridge {
    public static void main(String[] ignored) throws Exception {
        Method currentUser=Class.forName("android.app.ActivityManager").getMethod("getCurrentUser");
        BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        System.out.println(new JSONObject().put("ready",true));
        String line;
        while((line=input.readLine())!=null) {
            long id=-1;
            try {
                if(line.length()>8192)throw new IllegalArgumentException("playback request too large");
                JSONObject request=new JSONObject(line);id=request.getLong("id");
                JSONArray values=request.getJSONArray("arguments");
                if(values.length()>3)throw new IllegalArgumentException("invalid playback arguments");
                String[] args=new String[values.length()+2];
                args[0]=request.getString("operation");args[1]=String.valueOf(currentUser.invoke(null));
                for(int i=0;i<values.length();i++)args[i+2]=values.getString(i);
                String kind=request.getString("kind");
                JSONObject result;
                if(kind.equals("media"))result=MediaBridge.execute(args);
                else if(kind.equals("audio"))result=AudioBridge.execute(args);
                else throw new IllegalArgumentException("invalid playback request kind");
                System.out.println(new JSONObject().put("id",id).put("ok",true).put("result",result));
            } catch(Throwable error) {
                while(error.getCause()!=null)error=error.getCause();
                System.out.println(new JSONObject().put("id",id).put("ok",false).put("error",error.getClass().getSimpleName()+": "+error.getMessage()));
            }
        }
    }
}

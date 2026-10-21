package ernest.airc.bridge;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import org.json.JSONObject;

/** Explicit media-stream operations for remote controls and target-owned schedules; never uses mute-toggle keys. */
public final class AudioBridge {
    public static void main(String[] args) {
        try {
            if(args.length<2)throw new IllegalArgumentException("invalid audio arguments");
            int user=Integer.parseInt(args[1]);
            Context context=FrameworkContext.forUser(user);
            AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            int stream=AudioManager.STREAM_MUSIC;
            String action=args[0];
            int requested=-1, index=-1;
            boolean muted=false;
            if(action.equals("status")&&args.length==2) {
                // Read-only snapshot; no adjustment is performed.
            } else if(action.equals("set_volume")&&args.length==3) {
                requested=Integer.parseInt(args[2]);
                if(requested<0||requested>100)throw new IllegalArgumentException("volume must be 0 to 100");
                if(audio.isVolumeFixed())throw new IllegalStateException("设备使用固定音量，无法设置媒体音量");
                int min=Build.VERSION.SDK_INT>=28?audio.getStreamMinVolume(stream):0;
                int max=audio.getStreamMaxVolume(stream);
                if(max<=min)throw new IllegalStateException("设备不提供可调节的媒体音量");
                index=min+Math.round((max-min)*requested/100f);
                audio.setStreamVolume(stream,index,0);
            } else if((action.equals("mute")||action.equals("unmute"))&&args.length==2) {
                muted=action.equals("mute");
                audio.adjustStreamVolume(stream,muted?AudioManager.ADJUST_MUTE:AudioManager.ADJUST_UNMUTE,0);
            } else throw new IllegalArgumentException("unsupported audio operation");
            // Framework application may be asynchronous. Verify the resulting stream state.
            boolean verified=action.equals("status");
            for(int attempt=0;!verified&&attempt<40;attempt++) {
                verified=index>=0?audio.getStreamVolume(stream)==index:audio.isStreamMute(stream)==muted;
                if(verified)break;
                Thread.sleep(50);
            }
            if(!verified)throw new IllegalStateException("媒体音量或静音状态未达到设定值，请检查设备音量限制");
            JSONObject result=new JSONObject().put("operation",action).put("stream","media")
                .put("volumeIndex",audio.getStreamVolume(stream)).put("minVolumeIndex",Build.VERSION.SDK_INT>=28?audio.getStreamMinVolume(stream):0)
                .put("maxVolumeIndex",audio.getStreamMaxVolume(stream)).put("fixed",audio.isVolumeFixed())
                .put("muted",audio.isStreamMute(stream)).put("verified",true);
            if(requested>=0)result.put("requestedPercent",requested);
            System.out.println(result.toString());
        } catch(Throwable error) {
            while(error.getCause()!=null)error=error.getCause();
            System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);
        }
    }
}

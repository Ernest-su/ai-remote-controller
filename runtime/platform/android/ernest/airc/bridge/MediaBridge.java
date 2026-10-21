package ernest.airc.bridge;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;

/** Short-lived framework adapter, invoked by the native runtime; no service or APK. */
public final class MediaBridge {
    private static List<MediaController> sessions(int user) throws Exception {
        Context caller=FrameworkContext.forUser(user);
        try {
            Class<?> initializer=Class.forName("android.media.MediaFrameworkPlatformInitializer");
            if(initializer.getMethod("getMediaServiceManager").invoke(null)==null) {
                Class<?> services=Class.forName("android.media.MediaServiceManager");
                initializer.getMethod("setMediaServiceManager",services).invoke(null,services.getConstructor().newInstance());
            }
        } catch(ClassNotFoundException olderPlatform) { /* Before media framework module initialization. */ }
        MediaSessionManager manager=(MediaSessionManager)caller.getSystemService(Context.MEDIA_SESSION_SERVICE);
        return manager.getActiveSessions(null);
    }
    private static JSONObject snapshot(MediaController controller) throws Exception {
        JSONObject result=new JSONObject();
        PlaybackState state=controller.getPlaybackState();MediaMetadata metadata=controller.getMetadata();
        result.put("packageName",controller.getPackageName());result.put("active",true);String tag=controller.getTag();result.put("sessionTag",tag==null||tag.length()>128?JSONObject.NULL:tag);
        result.put("state",state==null?JSONObject.NULL:state.getState());
        result.put("positionMs",state==null||state.getPosition()<0?JSONObject.NULL:state.getPosition());
        result.put("updatedAtElapsedMs",state==null?JSONObject.NULL:state.getLastPositionUpdateTime());
        result.put("seekSupported",state==null?JSONObject.NULL:(state.getActions()&PlaybackState.ACTION_SEEK_TO)!=0);
        result.put("durationMs",metadata==null||!metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)?JSONObject.NULL:metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        String title=metadata==null?null:metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        result.put("description",title==null?JSONObject.NULL:title.substring(0,Math.min(title.length(),256)));
        result.put("customActions",MediaCustomActions.snapshot(state));
        return result;
    }
    public static void main(String[] args) {
        try {
            if(args.length!=2&&args.length!=5) throw new IllegalArgumentException("invalid media bridge arguments");
            int user=Integer.parseInt(args[1]);if(user<0)throw new IllegalArgumentException("invalid user");
            List<MediaController> controllers=sessions(user);
            if(controllers.size()>64)throw new IllegalStateException("too many active media sessions");
            if(args[0].equals("status")&&args.length==2) {
                JSONArray values=new JSONArray();for(MediaController controller:controllers)values.put(snapshot(controller));
                System.out.println(new JSONObject().put("sessions",values).put("seekAvailable",true).put("customActionAvailable",true).put("source","media_controller").put("userId",user).toString());
            } else if(args[0].equals("pause_current")&&args.length==2) {
                MediaController selected=null;
                // Android returns active sessions in priority order. Pause one playing session,
                // never send a toggle that could resume an already paused player.
                for(MediaController controller:controllers) {
                    PlaybackState state=controller.getPlaybackState();
                    if(state!=null&&(state.getState()==PlaybackState.STATE_PLAYING||state.getState()==PlaybackState.STATE_BUFFERING||state.getState()==PlaybackState.STATE_FAST_FORWARDING||state.getState()==PlaybackState.STATE_REWINDING)
                        &&(state.getActions()&PlaybackState.ACTION_PAUSE)!=0) { selected=controller;break; }
                }
                if(selected==null)throw new IllegalStateException("no playing media session with pause support");
                selected.getTransportControls().pause();
                System.out.println(new JSONObject().put("operation","pause").put("packageName",selected.getPackageName()).put("submitted",true).put("playbackVerified",false).toString());
            } else if(args[0].equals("custom_action")&&args.length==5) {
                System.out.println(MediaCustomActions.send(controllers,args[2],args[3],args[4]).toString());
            } else if(args[0].equals("seek")&&args.length==5) {
                String pkg=args[2];String tag=args[3];long position=Long.parseLong(args[4]);if(position<0)throw new IllegalArgumentException("position must be nonnegative");
                MediaController selected=null;
                for(MediaController controller:controllers)if(pkg.equals(controller.getPackageName())&&(tag.isEmpty()||tag.equals(controller.getTag()))) {
                    if(selected!=null)throw new IllegalStateException("multiple active sessions for package; session selection required");selected=controller;
                }
                if(selected==null)throw new IllegalStateException("active media session missing");
                PlaybackState before=selected.getPlaybackState();
                if(before==null||(before.getActions()&PlaybackState.ACTION_SEEK_TO)==0)throw new IllegalStateException("session does not support seek");
                MediaMetadata metadata=selected.getMetadata();
                if(metadata!=null&&metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)&&metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)>0&&position>metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))throw new IllegalArgumentException("position exceeds duration");
                selected.getTransportControls().seekTo(position);
                System.out.println(new JSONObject().put("packageName",pkg).put("requestedMs",position).put("submitted",true).put("playbackVerified",false).put("session",snapshot(selected)).toString());
            } else throw new IllegalArgumentException("unsupported media bridge operation");
        } catch(Throwable error) {
            while(error.getCause()!=null)error=error.getCause();
            System.err.println(error.getClass().getSimpleName()+": "+error.getMessage());System.exit(1);
        }
    }
}

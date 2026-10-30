package ernest.airc.bridge;

import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.view.KeyEvent;
import android.os.SystemClock;
import java.util.List;
import org.json.JSONObject;

/** Transport commands target one declared, unambiguous session rather than global media keys. */
final class MediaCommands {
    static JSONObject send(List<MediaController> controllers, String pkg, String tag, String command) throws Exception {
        MediaController selected=null;
        for(MediaController controller:controllers) if(pkg.equals(controller.getPackageName())&&(tag.isEmpty()||tag.equals(controller.getTag()))) {
            if(selected!=null)throw new IllegalStateException("multiple active sessions for package; session selection required");
            selected=controller;
        }
        if(selected==null)throw new IllegalStateException("active media session missing");
        long action;
        switch(command) {
            case "play": action=PlaybackState.ACTION_PLAY;break;
            case "pause": action=PlaybackState.ACTION_PAUSE;break;
            case "previous": action=PlaybackState.ACTION_SKIP_TO_PREVIOUS;break;
            case "next": action=PlaybackState.ACTION_SKIP_TO_NEXT;break;
            default: throw new IllegalArgumentException("unsupported media command");
        }
        PlaybackState before=selected.getPlaybackState();
        if(before==null)throw new IllegalStateException("player does not support this command");
        boolean explicit=(before.getActions()&action)!=0;
        boolean toggle=(command.equals("play")||command.equals("pause"))&&(before.getActions()&PlaybackState.ACTION_PLAY_PAUSE)!=0;
        if(!explicit&&!toggle)throw new IllegalStateException("player does not support this command");
        MediaController.TransportControls controls=selected.getTransportControls();
        if(!explicit) {
            int state=before.getState();
            boolean playing=state==PlaybackState.STATE_PLAYING;
            boolean paused=state==PlaybackState.STATE_PAUSED||state==PlaybackState.STATE_STOPPED||state==PlaybackState.STATE_NONE;
            if(!playing&&!paused)throw new IllegalStateException("player state does not allow a safe play/pause toggle");
            // A stale pause request must not resume a session that is already paused.
            if(command.equals("pause")?playing:paused) {
                long now=SystemClock.uptimeMillis();
                if(!selected.dispatchMediaButtonEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0)))
                    throw new IllegalStateException("player rejected play/pause key");
                selected.dispatchMediaButtonEvent(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,0));
            }
        } else switch(command) {
            case "play": controls.play();break;
            case "pause": controls.pause();break;
            case "previous": controls.skipToPrevious();break;
            case "next": controls.skipToNext();break;
        }
        String actualTag=selected.getTag();
        return new JSONObject().put("packageName",pkg).put("sessionTag",actualTag==null||actualTag.length()>128?JSONObject.NULL:actualTag)
            .put("command",command).put("submitted",true).put("playbackVerified",false);
    }
}

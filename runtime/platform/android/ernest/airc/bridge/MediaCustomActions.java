package ernest.airc.bridge;

import android.media.session.MediaController;
import android.media.session.PlaybackState;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

/** Player-defined actions: no private protocol or inferred parameter bundle. */
final class MediaCustomActions {
    private static boolean valid(String id) {
        if(id==null || id.isEmpty() || id.getBytes(StandardCharsets.UTF_8).length>512)return false;
        for(int i=0;i<id.length();i++)if(Character.isISOControl(id.charAt(i)))return false;
        return true;
    }
    static JSONArray snapshot(PlaybackState state) throws Exception {
        JSONArray result=new JSONArray();
        if(state==null)return result;
        List<PlaybackState.CustomAction> actions=state.getCustomActions();
        if(actions.size()>32)throw new IllegalStateException("too many custom media actions");
        HashSet<String> identifiers=new HashSet<>();
        for(PlaybackState.CustomAction action:actions) {
            String id=action.getAction();
            if(!valid(id)||!identifiers.add(id))throw new IllegalStateException("invalid or duplicate custom media action");
            String name=action.getName()==null?"":action.getName().toString();
            result.put(new JSONObject().put("id",id).put("name",name.substring(0,Math.min(128,name.length()))));
        }
        return result;
    }
    static JSONObject send(List<MediaController> controllers,String pkg,String tag,String id) throws Exception {
        if(!valid(id))throw new IllegalArgumentException("invalid custom action identifier");
        MediaController selected=null;
        for(MediaController controller:controllers)if(pkg.equals(controller.getPackageName())&&(tag.isEmpty()||tag.equals(controller.getTag()))) {
            if(selected!=null)throw new IllegalStateException("multiple active sessions; session selection required");
            selected=controller;
        }
        if(selected==null)throw new IllegalStateException("active media session missing");
        JSONArray actions=snapshot(selected.getPlaybackState());
        boolean declared=false;
        for(int i=0;i<actions.length();i++)if(id.equals(actions.getJSONObject(i).getString("id")))declared=true;
        if(!declared)throw new IllegalStateException("custom action not currently advertised by session");
        selected.getTransportControls().sendCustomAction(id,null);
        return new JSONObject().put("packageName",pkg).put("action",id).put("submitted",true).put("playbackVerified",false);
    }
}

use serde_json::{json, Value};

pub(super) fn controls() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"media_volume","description":"Read the actual media stream volume, limits and mute state. Optional percent sets media volume from 0 to 100 through AudioManager and verifies the applied index. Does not toggle mute.","parameters":{"type":"object","properties":{"percent":{"type":"integer","minimum":0,"maximum":100}},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"media_custom_action","description":"Send exactly one custom action currently advertised by the selected active standard MediaSession in media_status. Use its package, sessionTag and action id; do not guess private actions or extras. No parameter bundle is sent. Request submission does not prove application; refresh media_status to observe the player.","parameters":{"type":"object","properties":{"package":{"type":"string"},"sessionTag":{"type":"string"},"action":{"type":"string","minLength":1,"maxLength":512}},"required":["package","action"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"media_seek","description":"Request absolute playback position in milliseconds through an active standard MediaSession. Requires package, optional sessionTag from media_status and seek support. No directional-key approximation. A submitted request is not proof of applied playback; query media_status to verify.","parameters":{"type":"object","properties":{"package":{"type":"string"},"sessionTag":{"type":"string"},"positionMs":{"type":"integer","minimum":0}},"required":["package","positionMs"],"additionalProperties":false}}}),
    ]
}

pub(super) fn publications() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"media_publications","description":"List retained local playback copies, including copies recovered after runtime restart. Their ids can be released without deleting the originals.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"media_publish","description":"Create a readable playback copy in a service-owned Downloads folder, keeping the original. Use only when the user wants local-player playback. Returns a cancellable task; poll task_progress for completed result, then media_open that result path. Release its id when finished.","parameters":{"type":"object","properties":{"root":{"type":"string","enum":["downloads","inbox","full"]},"path":{"type":"string"}},"required":["path"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"media_players","description":"Query installed players for a target media file. Downloads uses file URI; private inbox/full uses HTTP. Stub handlers are excluded.","parameters":{"type":"object","properties":{"root":{"type":"string","enum":["downloads","inbox","full"]},"path":{"type":"string"},"mimeType":{"type":"string"},"transport":{"type":"string","enum":["file","http","local-copy"]}},"required":["path"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"media_open","description":"Open a target image/audio/video with an installed player after file upload. Optional package must be one returned by media_players. Launch success does not prove playback.","parameters":{"type":"object","properties":{"root":{"type":"string","enum":["downloads","inbox","full"]},"path":{"type":"string"},"mimeType":{"type":"string"},"package":{"type":"string"}},"required":["path"],"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"media_release","description":"Release a private HTTP resource or owned local playback copy. Local copy release deletes only the owned copy, keeping the original. This does not stop a player.","parameters":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}}}),
    ]
}

pub(super) fn status() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"media_status","description":"Read MediaSession packages, states, reported positions, nullable duration and seek capability. This tool does not seek or change playback.","parameters":{"type":"object","properties":{},"additionalProperties":false}}}),
    ]
}

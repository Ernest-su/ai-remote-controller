use serde_json::{json, Value};

pub(super) fn mirror() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"screen_mirror_start","description":"Prepare a one-reader AVC/H.264 live screen stream for an explicit user request. Starts capture only when the client reads the separate binary HTTP path. No audio. Limits: even width/height 160..1280 / 90..1280, at most 921600 pixels, bitrate 250000..8000000, durationSeconds 10..120. Actual encoder/decoder support and FPS require observation; protected content can be black.","parameters":{"type":"object","properties":{"width":{"type":"integer"},"height":{"type":"integer"},"bitrate":{"type":"integer"},"durationSeconds":{"type":"integer"}},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"screen_mirror_stop","description":"Stop the selected live encoded screen stream owned by this connection. Disconnect also cancels its ticket/stream.","parameters":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}}}),
    ]
}

pub(super) fn preview() -> Vec<Value> {
    vec![
        json!({"type":"function","function":{"name":"screen_preview_start","description":"Prepare a low-frame-rate screen preview stream for an explicit user request. Captures start only when the app reads the returned binary HTTP path. No audio, high-FPS video or private casting protocol. One preview lease, 30-second claim window; stop after use. intervalMs 500..5000, durationSeconds 10..120. Frames are not returned as text.","parameters":{"type":"object","properties":{"intervalMs":{"type":"integer","minimum":500,"maximum":5000},"durationSeconds":{"type":"integer","minimum":10,"maximum":120}},"additionalProperties":false}}}),
        json!({"type":"function","function":{"name":"screen_preview_stop","description":"Stop the explicitly selected screen preview by its id; does not affect other media resources.","parameters":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}}}),
    ]
}

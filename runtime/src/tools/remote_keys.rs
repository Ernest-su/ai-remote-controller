use anyhow::{bail, Result};

pub(super) fn code(key: &str) -> Result<u32> {
    Ok(match key {
        "up" => 19,
        "down" => 20,
        "left" => 21,
        "right" => 22,
        "ok" => 23,
        "back" => 4,
        "home" => 3,
        "volume_up" => 24,
        "volume_down" => 25,
        "mute" => 164,
        "play_pause" => 85,
        "power" => 26,
        "menu" => 82,
        "media_previous" => 88,
        "media_next" => 87,
        "0" => 7,
        "1" => 8,
        "2" => 9,
        "3" => 10,
        "4" => 11,
        "5" => 12,
        "6" => 13,
        "7" => 14,
        "8" => 15,
        "9" => 16,
        "channel_up" => 166,
        "channel_down" => 167,
        "delete" => 67,
        key => bail!("unsupported remote key: {key}"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn keypad_keys_are_fixed_android_codes() {
        for digit in 0..10 {
            assert_eq!(code(&digit.to_string()).unwrap(), 7 + digit);
        }
        assert_eq!(code("channel_up").unwrap(), 166);
        assert_eq!(code("channel_down").unwrap(), 167);
        assert_eq!(code("delete").unwrap(), 67);
        assert!(code("7;reboot").is_err());
    }
}

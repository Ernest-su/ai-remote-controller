use anyhow::{bail, Result};
use std::time::{SystemTime, UNIX_EPOCH};
pub(super) fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}
pub(super) fn local(timestamp: i64) -> libc::tm {
    let value = timestamp as libc::time_t;
    let mut tm = unsafe { std::mem::zeroed() };
    unsafe {
        libc::localtime_r(&value, &mut tm);
    }
    tm
}
pub(super) fn formatted(timestamp: i64) -> String {
    let tm = local(timestamp);
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}",
        tm.tm_year + 1900,
        tm.tm_mon + 1,
        tm.tm_mday,
        tm.tm_hour,
        tm.tm_min
    )
}
pub(super) fn clock(value: &str) -> Result<(i32, i32)> {
    if value.len() != 5 || value.as_bytes()[2] != b':' {
        bail!("time must be HH:mm");
    }
    let (h, m) = value.split_once(':').unwrap();
    let h: i32 = h.parse()?;
    let m: i32 = m.parse()?;
    if !(0..24).contains(&h) || !(0..60).contains(&m) {
        bail!("invalid time");
    }
    Ok((h, m))
}
pub(super) fn daily(value: &str, after: i64) -> Result<i64> {
    let (h, m) = clock(value)?;
    let mut tm = local(after);
    tm.tm_hour = h;
    tm.tm_min = m;
    tm.tm_sec = 0;
    tm.tm_isdst = -1;
    let mut result = unsafe { libc::mktime(&mut tm) } as i64;
    if result <= after {
        tm.tm_mday += 1;
        tm.tm_hour = h;
        tm.tm_min = m;
        tm.tm_sec = 0;
        tm.tm_isdst = -1;
        result = unsafe { libc::mktime(&mut tm) } as i64;
    }
    if result <= after {
        bail!("unable to determine next daily execution");
    }
    Ok(result)
}
pub(super) fn once(value: &str, after: i64) -> Result<i64> {
    if value.len() != 16 || !value.is_ascii() {
        bail!("use yyyy-MM-dd HH:mm in target local time");
    }
    let (date, time) = value
        .split_once(' ')
        .ok_or_else(|| anyhow::anyhow!("invalid date/time"))?;
    let parts: Vec<_> = date.split('-').collect();
    if parts.len() != 3 {
        bail!("invalid date");
    }
    let (hour, minute) = clock(time)?;
    let year: i32 = parts[0].parse()?;
    let month: i32 = parts[1].parse()?;
    let day: i32 = parts[2].parse()?;
    if !(2020..=2100).contains(&year) || !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        bail!("invalid date");
    }
    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    tm.tm_year = year - 1900;
    tm.tm_mon = month - 1;
    tm.tm_mday = day;
    tm.tm_hour = hour;
    tm.tm_min = minute;
    tm.tm_isdst = -1;
    let timestamp = unsafe { libc::mktime(&mut tm) } as i64;
    if timestamp <= after || formatted(timestamp) != value {
        bail!("time must be a future valid target local date/time");
    }
    Ok(timestamp)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dates_and_daily_rollover_are_checked() {
        let now = now();
        assert!(clock("24:00").is_err());
        assert!(once("2026-02-30 10:00", 0).is_err());
        let next = daily("12:00", now).unwrap();
        assert!(next > now && next <= now + 26 * 3600);
        assert_eq!(&formatted(next)[11..], "12:00");
        assert!(once(&formatted(now - 120), now).is_err());
    }
}

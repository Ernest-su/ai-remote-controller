use anyhow::{bail, Context, Result};
use serde_json::Value;
use std::collections::BTreeSet;
pub(super) const BUTTONS: &[(&str, u16)] = &[
    ("a", 304),
    ("b", 305),
    ("x", 307),
    ("y", 308),
    ("l1", 310),
    ("r1", 311),
    ("l2", 312),
    ("r2", 313),
    ("select", 314),
    ("start", 315),
    ("thumb_l", 317),
    ("thumb_r", 318),
];
pub(super) const AXES: &[u16] = &[0, 1, 2, 5, 16, 17];
#[derive(Clone, Debug, Default, PartialEq)]
pub(super) struct State {
    pub keys: BTreeSet<u16>,
    pub axes: [i32; 6],
}
pub(super) fn parse(args: &Value) -> Result<State> {
    let object = args.as_object().context("state must be object")?;
    if object.keys().any(|key| {
        ![
            "buttons", "leftX", "leftY", "rightX", "rightY", "hatX", "hatY",
        ]
        .contains(&key.as_str())
    }) {
        bail!("unsupported virtual gamepad state field");
    }
    let mut state = State::default();
    if let Some(buttons) = args.get("buttons") {
        let buttons = buttons.as_array().context("buttons must be array")?;
        if buttons.len() > 12 {
            bail!("too many buttons");
        }
        for value in buttons {
            let name = value.as_str().context("button must be string")?;
            let code = BUTTONS
                .iter()
                .find(|(key, _)| *key == name)
                .context("unsupported virtual button")?
                .1;
            if !state.keys.insert(code) {
                bail!("duplicate button");
            }
        }
    }
    for (index, key) in ["leftX", "leftY", "rightX", "rightY"].iter().enumerate() {
        let value = args
            .get(*key)
            .map(|v| {
                v.as_f64()
                    .filter(|v| v.is_finite() && (-1.0..=1.0).contains(v))
                    .context("stick axes must be finite -1..1")
            })
            .transpose()?
            .unwrap_or(0.0);
        state.axes[index] = (value * 32767.0).round() as i32;
    }
    for (index, key) in ["hatX", "hatY"].iter().enumerate() {
        state.axes[index + 4] = args
            .get(*key)
            .map(|v| {
                v.as_i64()
                    .filter(|v| (-1..=1).contains(v))
                    .context("hat must be -1, 0 or 1")
            })
            .transpose()?
            .unwrap_or(0) as i32;
    }
    Ok(state)
}
pub(super) fn delta(old: &State, new: &State) -> Vec<(u16, u16, i32)> {
    let mut events = vec![];
    for key in old.keys.difference(&new.keys) {
        events.push((1, *key, 0));
    }
    for key in new.keys.difference(&old.keys) {
        events.push((1, *key, 1));
    }
    for (index, code) in AXES.iter().enumerate() {
        if old.axes[index] != new.axes[index] {
            events.push((3, *code, new.axes[index]));
        }
    }
    if !events.is_empty() {
        events.push((0, 0, 0));
    }
    events
}

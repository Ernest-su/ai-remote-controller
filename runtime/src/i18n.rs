use serde_json::Value;

/// Language belongs to a WebSocket connection, never to shared device state.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) enum Language {
    Chinese,
    #[default]
    English,
}

impl Language {
    pub(crate) fn from_accept_language(header: &str) -> Self {
        let mut best = (0.0_f32, Self::English);
        for range in header.split(',').take(16) {
            let mut parts = range.trim().split(';');
            let tag = parts.next().unwrap_or("").trim().to_ascii_lowercase();
            let language = match tag.split('-').next().unwrap_or("") {
                "zh" => Self::Chinese,
                "en" => Self::English,
                _ => continue,
            };
            let mut quality = 1.0_f32;
            for parameter in parts {
                if let Some(value) = parameter.trim().strip_prefix("q=") {
                    quality = value.parse().unwrap_or(0.0);
                }
            }
            if quality.is_finite() && quality <= 1.0 && quality > best.0 {
                best = (quality, language);
            }
        }
        best.1
    }

    pub(crate) fn error(self, message: &str) -> String {
        if self == Self::English {
            return message.into();
        }
        if let Some((_, translated)) = MESSAGES.iter().find(|(english, _)| *english == message) {
            return (*translated).into();
        }
        for (prefix, translated) in [
            ("uinput ioctl failed: ", "uinput 设备操作失败："),
            ("virtual gamepad write failed: ", "虚拟手柄输入写入失败："),
            ("unsupported request type: ", "不支持的请求类型："),
            ("unknown tool: ", "不支持的工具："),
            ("invalid request: ", "请求格式无效："),
            ("model API HTTP ", "模型接口返回 HTTP "),
            ("gamepad release also failed: ", "手柄按键释放也失败："),
        ] {
            if let Some(detail) = message.strip_prefix(prefix) {
                return format!("{translated}{detail}");
            }
        }
        // Unknown platform/provider diagnostics remain intact after a localized explanation.
        format!("设备操作失败：{message}")
    }

    /// Only structured diagnostic fields are translated, never file paths, content or model answers.
    pub(crate) fn tool_result(self, tool: &str, original: String) -> String {
        if self == Self::English
            || !matches!(
                tool,
                "gamepad.virtual_capabilities"
                    | "device.system_capabilities"
                    | "device.system_state"
            )
        {
            return original;
        }
        let Ok(mut value) = serde_json::from_str::<Value>(&original) else {
            return original;
        };
        fn visit(value: &mut Value, language: Language) -> bool {
            let mut changed = false;
            match value {
                Value::Object(object) => {
                    for (key, value) in object {
                        if matches!(key.as_str(), "reason" | "error") {
                            if let Some(message) = value.as_str().filter(|text| !text.is_empty()) {
                                *value = Value::String(language.error(message));
                                changed = true;
                            }
                        } else if value.is_object() || value.is_array() {
                            changed |= visit(value, language);
                        }
                    }
                }
                Value::Array(array) => {
                    for value in array {
                        changed |= visit(value, language);
                    }
                }
                _ => {}
            }
            changed
        }
        if visit(&mut value, self) {
            value.to_string()
        } else {
            original
        }
    }
}

const MESSAGES: &[(&str, &str)] = &[
    (
        "uinput version 5 or newer required",
        "目标设备需要 uinput 5 或更高版本，当前内核不支持持续虚拟手柄",
    ),
    (
        "virtual gamepad requires a verified root runtime",
        "持续虚拟手柄需要设备端服务以已验证的 root 权限运行",
    ),
    (
        "uinput unavailable or permission denied",
        "无法访问 /dev/uinput，请检查目标内核支持与 root 权限",
    ),
    (
        "uinput is not a character device",
        "/dev/uinput 不是有效的字符设备",
    ),
    (
        "virtual gamepad already active",
        "已有持续虚拟手柄正在运行，请先停止",
    ),
    (
        "virtual session missing or owned by another connection",
        "虚拟手柄会话不存在或属于其他连接，请重新启动",
    ),
    ("virtual device name too long", "虚拟设备名称过长"),
    ("connection owner required", "此操作需要有效的控制连接"),
    ("unknown virtual gamepad tool", "不支持的虚拟手柄工具"),
    ("empty argument object required", "此操作不接受额外参数"),
    (
        "virtual gamepad arguments must be object",
        "虚拟手柄参数必须是对象",
    ),
    (
        "unsupported virtual gamepad arguments",
        "包含不支持的虚拟手柄参数",
    ),
    (
        "sequence must strictly increase",
        "输入序号必须递增，请重新启动手柄",
    ),
    ("state must be object", "手柄状态必须是对象"),
    (
        "unsupported virtual gamepad state field",
        "包含不支持的虚拟手柄状态字段",
    ),
    ("buttons must be array", "按键列表必须是数组"),
    ("too many buttons", "同时按下的按键过多"),
    ("button must be string", "按键名称必须是文字"),
    ("unsupported virtual button", "不支持此虚拟手柄按键"),
    ("duplicate button", "按键列表包含重复项"),
    (
        "stick axes must be finite -1..1",
        "摇杆轴值必须在 -1 到 1 之间",
    ),
    ("hat must be -1, 0 or 1", "方向帽轴值必须为 -1、0 或 1"),
    ("gamepad arguments must be object", "手柄参数必须是对象"),
    (
        "only buttons, x, y, rightX, rightY and holdMs accepted",
        "手柄仅接受 buttons、x、y、rightX、rightY 和 holdMs 参数",
    ),
    ("at most six buttons accepted", "最多支持同时按下六个按键"),
    ("unsupported gamepad button", "不支持此手柄按键"),
    (
        "opposite directions cannot be combined",
        "不能同时按下相反方向",
    ),
    (
        "axes must be finite numbers in -1..1",
        "轴值必须在 -1 到 1 之间",
    ),
    ("holdMs must be integer", "按住时长必须是整数毫秒"),
    (
        "holdMs must be 10..2000",
        "按住时长必须在 10 到 2000 毫秒之间",
    ),
    (
        "pulse must include buttons or nonzero axes; use gamepad.reset",
        "请提供按键或非零轴值；释放输入请使用手柄重置",
    ),
    ("gamepad transaction worker failed", "手柄输入任务执行失败"),
    (
        "ongoing gamepad transaction did not finish; retry reset",
        "上一条手柄输入尚未结束，请稍后重试重置",
    ),
    ("gamepad transaction already active", "已有手柄输入正在执行"),
    (
        "gamepad input failed; release retried",
        "手柄输入失败，已尝试释放按键",
    ),
    (
        "gamepad.reset accepts an empty object",
        "手柄重置不接受额外参数",
    ),
    ("id required", "缺少操作会话 ID"),
    ("unsupported protocol", "连接协议不匹配，请更新设备端服务"),
    (
        "request execution timed out",
        "设备操作超时，请检查目标状态后重试",
    ),
    ("tool execution timed out", "设备工具执行超时"),
    (
        "persistent input bridge unavailable",
        "常驻输入桥接不可用，请检查设备端服务与输入权限",
    ),
    ("input service stopped", "输入服务已停止，请重新连接设备"),
    (
        "input key outcome uncertain; no automatic replay; release recovery attempted",
        "按键执行结果不确定，已尝试释放按键；不会自动重复执行",
    ),
    (
        "input key outcome uncertain; release recovery pending; no automatic retry",
        "按键执行结果不确定，释放恢复尚未完成；不会自动重复执行",
    ),
    (
        "request queue full; wait for pending operations and retry",
        "操作队列已满，请等待已有操作完成后重试",
    ),
    ("gesture timed out", "触控手势执行超时"),
    ("apps.list timed out", "应用列表读取超时"),
    ("device.info timed out", "设备信息读取超时"),
    ("invalid Android package name", "Android 应用包名无效"),
    (
        "Android framework bridge unavailable",
        "Android 系统接口不可用，请检查设备端服务与权限",
    ),
    (
        "screencap did not return a PNG image",
        "目标系统未返回有效的 PNG 截图",
    ),
    ("invalid screenshot dimensions", "目标截图尺寸无效"),
    (
        "screen capture already in progress",
        "正在获取截图，请稍后再试",
    ),
    ("mirror settings outside limits", "屏幕镜像设置超出允许范围"),
    (
        "mirror stream belongs to another connection",
        "此屏幕镜像属于其他连接",
    ),
    (
        "mirror stream already reserved or active",
        "已有屏幕镜像正在准备或运行，请先停止",
    ),
    (
        "screen preview already reserved or streaming; close the previous reader",
        "已有动态预览正在运行，请先关闭",
    ),
    (
        "preview interval/duration outside limits",
        "预览间隔或时长超出允许范围",
    ),
    (
        "system permission caller identity mismatch",
        "系统权限查询的调用身份不一致",
    ),
    (
        "incomplete or invalid system permission probe",
        "系统权限查询返回的数据不完整或无效",
    ),
    (
        "invalid system permission probe",
        "系统权限查询返回了无效数据",
    ),
    ("invalid model API address", "模型接口地址无效"),
    (
        "model API address must be an HTTP(S) base URL without credentials or query",
        "模型接口必须是 HTTP(S) 根地址，不能包含凭据或查询参数",
    ),
    (
        "configure API key and model first",
        "请先配置模型和 API Key",
    ),
    (
        "text must contain 1..32768 bytes",
        "文字指令必须包含 1 到 32768 字节",
    ),
    ("invalid model API JSON", "模型接口返回了无效的 JSON"),
    ("model API returned no message", "模型接口未返回消息"),
    (
        "model returned no answer or tool call",
        "模型未返回回答或工具调用",
    ),
    (
        "model returned too many tool calls",
        "模型返回的工具调用过多",
    ),
    (
        "model exceeded 16 tool decision rounds",
        "模型已达到 16 轮工具调用上限",
    ),
];

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn negotiates_supported_language_and_quality() {
        for tag in ["zh", "zh-CN", "zh-TW", "ZH-hans", "fr,zh;q=0.9,en;q=0.8"] {
            assert_eq!(Language::from_accept_language(tag), Language::Chinese);
        }
        for tag in [
            "",
            "en-US",
            "fr-FR",
            "zh;q=0,en;q=1",
            "zh;q=invalid",
            "zh;q=NaN",
            "zh;q=2",
        ] {
            assert_eq!(Language::from_accept_language(tag), Language::English);
        }
    }
    #[test]
    fn known_errors_are_translated_and_english_is_unchanged() {
        for (english, chinese) in MESSAGES {
            assert_eq!(Language::Chinese.error(english), *chinese);
            assert_eq!(Language::English.error(english), *english);
        }
        assert_eq!(
            Language::Chinese.error("uinput ioctl failed: Permission denied (os error 13)"),
            "uinput 设备操作失败：Permission denied (os error 13)"
        );
        assert_eq!(
            Language::Chinese.error("vendor driver failure 42"),
            "设备操作失败：vendor driver failure 42"
        );
    }
    #[test]
    fn translates_nested_diagnostics_without_rewriting_content_or_paths() {
        let source = json!({"supported":false,"reason":"uinput version 5 or newer required", "path":"/dev/uinput", "content":"uinput version 5 or newer required", "nested":[{"error":"uinput unavailable or permission denied"}]}).to_string();
        let translated: Value = serde_json::from_str(
            &Language::Chinese.tool_result("gamepad.virtual_capabilities", source.clone()),
        )
        .unwrap();
        assert!(translated["reason"].as_str().unwrap().contains("uinput 5"));
        assert!(translated["nested"][0]["error"]
            .as_str()
            .unwrap()
            .contains("root"));
        assert_eq!(translated["path"], "/dev/uinput");
        assert_eq!(translated["content"], "uinput version 5 or newer required");
        assert_eq!(
            Language::Chinese.tool_result("device.shell", source.clone()),
            source
        );
        assert_eq!(
            Language::English.tool_result("gamepad.virtual_capabilities", source.clone()),
            source
        );
        assert_eq!(
            Language::Chinese.tool_result("gamepad.virtual_capabilities", "a model answer".into()),
            "a model answer"
        );
    }
}

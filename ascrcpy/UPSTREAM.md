Adapted from https://github.com/Ernest-su/ascrcpy at 4c51086fb4df629915821af417ebc79f4c48c9f4.
Apache-2.0; original package namespaces and LICENSE retained.
Matched scrcpy server version: 4.0. Source: https://github.com/Genymobile/scrcpy/tree/v4.0
Server SHA-256: 84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a
Server artifact is generated into build assets, not committed.

AIRC 的控制扩展增加独立的无视频文字会话，按 scrcpy 4.0 `SET_CLIPBOARD`（UTF-8 长度、序号、paste 标志）和 `ACK_CLIPBOARD` 编解码；关闭自动剪贴板同步，不引入目标 APK。协议参考 [scrcpy 4.0 control_msg.c](https://github.com/Genymobile/scrcpy/blob/v4.0/app/src/control_msg.c) 与 [Controller.java](https://github.com/Genymobile/scrcpy/blob/v4.0/server/src/main/java/com/genymobile/scrcpy/control/Controller.java)。该回执不证明当前输入框已接收文字。

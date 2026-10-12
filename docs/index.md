# AI 遥控

AIRC 将 Android 手机变成既能按键操作、也能执行自然语言任务的遥控器。手机通过 ADB 安装目标端程序，随后通过局域网 WebSocket 通信；目标端单独发布 mDNS 服务供手机发现。

![遥控器界面](assets/remote-screen.png){ width="320" }

## 从哪里开始

- 第一次使用：阅读[快速开始](getting-started.md)，然后[连接目标设备](guide/connections.md)。
- 使用 AI：阅读[遥控与文字](guide/remote.md)与[模型配置](guide/models.md)。
- 管理目标程序：阅读[服务与更新](guide/services.md)。
- 集成或贡献：阅读[架构](development/architecture.md)、[协议](protocol.md)与[开发构建](development/build.md)。

## 当前支持

| 项目 | 范围 |
| --- | --- |
| 控制端 | Android 8.0 / API 26 及以上，包名 `ernest.airc` |
| 目标程序 | Android ARM64、ARMv7、x86_64，最低 API 26 |
| 连接 | 目标 TCP ADB、Android 无线配对码、二维码；后续可直接连接健康 runtime |
| 发现与通信 | IPv4 局域网、独立 `_airc._tcp.` mDNS、WebSocket |
| AI | 兼容 chat completions 工具调用的服务 |
| 未来平台 | KMP 共享协议；iOS、鸿蒙尚未实现 |

当前需从源码构建，步骤见[快速开始](getting-started.md)；功能与使用限制见对应使用指南。

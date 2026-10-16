# 贡献指南

欢迎提交问题报告、文档修正与代码改进。先阅读[架构](docs/development/architecture.md)、[构建](docs/development/build.md)和[测试](docs/development/testing.md)。

界面改动必须遵守[视觉与交互约束](docs/development/visual-guidelines.md)，包括导航入口、九宫格与录音取消规则。

## 问题报告

请描述预期行为、实际行为和最小复现步骤，附应用/runtime 版本、Android 版本、目标 ABI、连接方式和网络情况。日志请删除 API 密钥、配对码、二维码密码和个人信息。区分真机结果、模拟器结果与协议 fixture 结果。

## 提交改动

1. 将改动聚焦在一个问题，说明触发条件及改动后的行为。
2. 修改协议、工具、界面或发布流程时，同步更新 `docs/` 对应页面。
3. 按测试指南的影响范围矩阵选择必要验证，发布前再执行完整门禁；MkDocs 严格构建只在提交前执行，日常开发不重复运行。
4. 在提交说明中写明测试命令、结果和未覆盖的验证条件。

界面改动优先在 JVM 和 Android 模拟器验证。文档只维护当前行为与稳定约束，不维护开发流水账。

不提交构建输出、模型凭据、发布私钥、个人 `local.properties` 或临时测试环境。文档按[Docs as Code 约定](docs/development/documentation.md)维护。

项目原创代码使用 [MIT License](LICENSE)；第三方代码保留各自许可证，见 [第三方声明](THIRD_PARTY_NOTICES.md)。

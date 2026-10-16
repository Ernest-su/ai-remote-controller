# 测试与验证

界面修改优先使用 JVM 与 Android 模拟器：共享组件和业务逻辑先在 JVM 验证，系统返回、旋转、软键盘、系统栏和 Keystore 等行为再由模拟器覆盖。真机用于厂商系统、摄像头、输入注入、自然网络故障和第三方应用兼容等无法替代的专项。

文档维护验证方法和可复用约束，执行结果写在回复或提交说明，不追加逐轮开发记录。

## 按影响范围选择验证

| 改动 | 日常验证 | 交付或专项补充 |
| --- | --- | --- |
| 纯文档、文案且不改变布局 | 检查相关链接与术语 | 提交前执行一次 MkDocs 严格构建 |
| 颜色、尺寸、共享组件 | 受影响 JVM Compose 回归；亮暗截图核对 | 模拟器检查窄屏、大字体和相关平台布局 |
| 导航、提交、持久化、生命周期 | 相关 JVM 行为回归和模拟器 instrumentation | Android 交付构建 App 与测试 APK |
| client 协议、连接、安全边界 | 对应 client/shared JVM 与本地主机 fixture | 目标行为改变时选择隔离目标专项 |
| runtime 工具、权限、资源生命周期 | Rust 测试、Clippy 与对应协议 fixture | 匹配 ABI 的模拟器或专用目标验证 |
| 发布或跨层大改 | 完整本地门禁、APK 与三 ABI 摘要 | 签名、真实设备和提供商按发布范围验证 |

选择能验证改动风险的必要集合，通过后不重复执行无关测试。变更扩大、出现失败或发现新风险时再扩展。不得通过删除断言、吞异常或把失败改为跳过来获得通过。

## 聚焦开发入口

```sh
# 共享 UI 和 Android JVM；桌面 UI 需要显示环境
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE python3 scripts/verify-local.py --scope ui
# Android 交付时附带构建应用和测试包
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE python3 scripts/verify-local.py --scope ui --build-apk
python3 scripts/verify-local.py --scope client
python3 scripts/verify-local.py --scope runtime
```

`ui` 选择桌面 UI/主题回归和 Android JVM，不连接外部设备或执行无关 Rust 测试；Gradle 为平台测试准备的 runtime 构建依赖仍可能触发。`client` 执行共享/client/Android JVM。`runtime` 执行 Rust、Clippy 与主机协议测试，需要 Python 的 `websockets`、`zeroconf`。

桌面测试需要显示环境；无显示器时使用 Xvfb。`xvfb-run` 还需要 xauth；缺少时可手动运行 `Xvfb -displayfd 1 -screen 0 1280x1024x24 -nolisten tcp`，将输出编号用于 `DISPLAY=:编号`，结束只停止自己创建的进程。

可直接选择测试：

```sh
./gradlew :client:test --tests ernest.airc.ModelProfilesTest --no-daemon
./gradlew :desktopApp:jvmTest --tests ernest.airc.desktop.AppearanceTest --no-daemon
./gradlew :androidApp:testDebugUnitTest --tests ernest.airc.RemoteNavigationTest --no-daemon
```


## Android 模拟器 {#emulator-controller}

使用独立 AVD，先构建 App 与测试 APK：

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --no-daemon
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite product-pages
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite lan-connection-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite appearance
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite controller-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite management-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite app-packages-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite models
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite profile-isolation
```

这些专项不要求外部目标。脚本会安装 App/测试 APK 并在结束后移除测试 APK；应使用自有模拟器，不能覆盖用户现有应用或删除数据以绕过签名冲突。专项选择与预期用例数量由脚本维护，结果校验要求全部用例终态成功，跳过不计作通过。

| 专项 | 主要验证内容 |
| --- | --- |
| `product-pages` | 320dp / 1.5 倍字体亮暗主题下逐页浏览、设备页返回来源、等高网格及离线截图；不操作外部目标 |
| `lan-connection-ui` | 仅访问控制端回环地址，验证服务连接失败后尝试 ADB 5555、失败展开并填入目标地址、草稿重建恢复 |
| `appearance` | 三种外观模式、本地持久化、重建、设置返回、草稿、系统栏与未知值回退 |
| `controller-ui` | 页面导航、提交与执行反馈、键盘间距、离线、大字体、旋转与输入工具 |
| `management-ui` | 文件/应用列表、搜索筛选、按需面板、离线与草稿恢复 |
| `app-packages-ui` | 图标显示、多包安装清单与显式提交 |
| `models` | API 配置、模型选择、真实 Keystore 持久化与手动同步边界 |
| `profile-isolation` | 测试配置备份隔离、恢复与重复隔离拒绝 |

视觉检查覆盖亮色/暗色、遥控器、功能网格、设置、表单和确认框，以及窄屏、1.5 倍字体、软键盘展开。截图、应用图标和镜像保留原色。Android 面板 fixture 使用 debug 专用 ComponentActivity；不要在已有 Compose 测试宿主上重复调用测试规则的 `setContent`。

Compose 桌面测试的取消事件实现与 Android 不同：越界/松开可在 JVM 验证，实际取消、后台与窗口失焦由 Android 专项补充。不能以单纯编译测试 APK 代替执行。

## 完整本地门禁

准备安装 `websockets`、`zeroconf` 的 Python 环境，并提供与当前信任根匹配的既有签名清单和相邻 `.sig`：

```sh
python3 scripts/verify-local.py --python /path/to/test-python --release-fixture /path/to/manifest.json
```

默认 `full` 执行共享/client/桌面/Android JVM、Rust 测试与 Clippy、主机协议和生命周期 fixture、App/测试 APK 构建，以及 APK 内三 ABI 大小/摘要核对。它不自动连接真实设备或调用真实提供商，也不运行文档严格构建。

聚焦开发检查不要求维护者签名清单；缺少签名 fixture 时，相关用例按原规则跳过，报告必须注明。无需生成或索取维护者私钥。

## 提交前文档检查

MkDocs 严格构建只在准备提交时执行一次，日常编辑、UI 测试和完整业务门禁不自动触发：

```sh
python3 -m pip install -r requirements-docs.txt
python3 scripts/verify-local.py --scope commit
```

提交后的文档 CI 复核严格构建并生成站点。生成站点不进入源码提交。

## 设备与提供商专项

只有改动涉及真实目标或硬件行为时才选择对应专项，方法见[设备验证](../device-validation.md)。不得不带类或专项选择运行整套 instrumentation，其中包含部署、配对和系统动作测试。

真实目标测试须显式配置设备；使用 UUID 服务目录、临时端口和自建 fixture，核对目标服务身份及源文件/数据保留，结束清理自己的资源。需要目标时优先用独立模拟器，再按硬件兼容需求使用专用物理设备。

模型提供商测试显式启用，凭据只交给所需进程，不能写入源码、命令历史、测试日志或文档。确定性模型 fixture 验证工具调用协议与边界，不能证明真实提供商兼容。

## 结果判定

回复或提交说明写明命令、环境、实际执行或缓存复用、失败/跳过及未执行范围。相同输入的 Gradle 缓存可用于纯代码检查；依赖外部设备状态的专项不能拿缓存代替当前执行。

JVM fixture、模拟器和真实设备分别说明。UI fixture 不证明目标实际输入、文件/应用操作或游戏消费；主机工具不证明 Android 系统效果；模拟器不能替代厂商系统、摄像头和自然 WLAN 故障。保留失败证据与有效断言，不扩大通过结论。

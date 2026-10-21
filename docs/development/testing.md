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
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite remote-haptics-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite remote-haptics-input-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite lan-connection-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite appearance
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite controller-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite gamepad-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite management-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite file-preview-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite mirror-toolbar-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite app-packages-ui
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite models
python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite profile-isolation
```

这些专项不要求外部目标。脚本会安装 App/测试 APK 并在结束后移除测试 APK；应使用自有模拟器，不能覆盖用户现有应用或删除数据以绕过签名冲突。专项选择与预期用例数量由脚本维护，结果校验要求全部用例终态成功，跳过不计作通过。

| 专项 | 主要验证内容 |
| --- | --- |
| `launcher-shortcut-ui` | 桌面入口默认名称、修改与校验、图标选择、亮暗大字体、草稿恢复、取消与系统快捷方式元数据；不操作外部目标 |
| `product-pages` | 320dp / 1.5 倍字体亮暗主题下逐页浏览、设备页返回来源、等高网格及离线截图；不操作外部目标 |
| `remote-haptics-input-ui` | 标准遥控器下键快速短按、真实按下/松开与滑出取消；请求仅发往控制端本地模拟服务，不操作外部目标。真实振动器的停止与厂商日志需在指定手机补查 |
| `remote-haptics-ui` | 遥控震动开关的切换、旋转与重新加载持久化；实际震感需有振动器的设备核对 |
| `lan-connection-ui` | 仅访问控制端回环地址，验证服务连接失败后尝试 ADB 5555、失败展开并填入目标地址、草稿重建恢复 |
| `appearance` | 三种外观模式、本地持久化、重建、设置返回、草稿、系统栏与未知值回退 |
| `gamepad-ui` | NES/双摇杆类型选择、方向与跳跃多指组合、类型旋转保留、标题启停/重连图标、重连可停止且游戏按键禁用、固定无滚动视口、多指操作与取消释放、横屏方向及离页恢复；只验证控制端界面 |
| `controller-ui` | 页面导航、提交与执行反馈、键盘间距、离线、大字体、旋转与输入工具 |
| `management-ui` | 文件/应用列表、搜索筛选、按需面板、离线与草稿恢复 |
| `mirror-toolbar-ui` | 动态预览标题图标、画质档位保存、紧凑按键至少 48dp、全屏原生视图复用、布局切换及横竖屏草稿保留；不连接目标 |
| `url-conflict-ui` | 网址下载同名提示、新名称编辑与校验、覆盖前独立确认、目录与离线禁用，320dp/1.5倍字体亮暗主题 |
| `upload-local-picker-ui` | 上传打开实际系统文件选择器、取消保留草稿、待保存任务不禁用本地选择、下载保存反馈不串入上传 |
| `storage-task-tabs-ui` | 普通用户存储概览、折叠扫描详情、列表删除确认及离线禁用；任务分类计数、0项禁用、旋转恢复与任务变化 |
| `file-task-navigation-ui` | 文件任务统一入口、空态、任务汇总不出现创建表单、旋转恢复与窄屏亮暗截图；上传共用一个选择器入口 |
| `batch-download-mode-ui` | 下载前 ZIP/逐个选择、取消不提交与旋转恢复、独立文件保存状态；通过模拟器系统 DocumentsProvider 验证逐个保存及同名改名不覆盖 |
| `file-multiselect-ui` | 列表/网格多选下载、可见项选择、32项限制、筛选与恢复、目录切换和刷新、返回退出、离线/批量任务互斥，亮暗大字体 |
| `file-page-ui` | 文件布局与链接、任务提示同一行布局、关闭后导航/恢复/进度更新保持隐藏，新任务重新显示；使用隔离界面 fixture |
| `file-preview-ui` | 主控端图片受限解码、文本范围读取及关闭释放、音频播放/后台暂停；仅使用本机回环媒体 fixture |
| `app-packages-ui` | 图标显示、多包安装清单与显式提交 |
| `models` | API 配置、模型选择、真实 Keystore 持久化与手动同步边界 |
| `profile-isolation` | 测试配置备份隔离、恢复与重复隔离拒绝 |

视觉检查覆盖亮色/暗色、遥控器、功能网格、设置、表单和确认框，以及窄屏、1.5 倍字体、软键盘展开。截图、应用图标和镜像保留原色。Android 面板 fixture 使用 debug 专用 ComponentActivity；不要在已有 Compose 测试宿主上重复调用测试规则的 `setContent`。

Compose 桌面测试的取消事件实现与 Android 不同：越界/松开可在 JVM 验证，实际取消、后台与窗口失焦由 Android 专项补充。不能以单纯编译测试 APK 代替执行。

遥控震动诊断可在 debug App 使用 `adb -s <手机序列号> logcat -v threadtime -s AircHaptics:I`，结合 `adb -s <手机序列号> shell dumpsys vibrator_manager` 核对硬件状态。日志包含系统触摸时序、方向键名称、交互源和按下 token、派发完成/取消、计时开始/触发及停止；不记录文字内容或凭据。快速短按需核对同一按钮的多个 token：延迟派发可能正常完成后又出现合成按下/松开对，旧 token 必须在被替代时清理。`remote-haptics-input-ui` 覆盖 Compose 短按、不等待界面空闲的系统输入队列连按，以及按下约 100ms、靠近 Android 延迟按压反馈阈值的连按。

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

文件预览的隔离目标回归：

```sh
./gradlew :desktopApp:jvmTest --tests ernest.airc.desktop.FilePreviewTargetTest -PaircTargetSerial=<自有模拟器序列号> --no-daemon
```

该测试使用 UUID 服务目录，读取未安装 APK 的图标并核对 App 包内的原色图标，验证文本范围读取、释放及路径边界；不安装目标 APK。显式指定目标的桌面测试先构建配套 App/runtime 与 fixture，避免使用过期二进制。不会修改已有目标服务。

## 设备与提供商专项

普通按键提速专项使用独立的 `inputFixture` APK 和 UUID runtime 目录，安装前拒绝覆盖已有接收 fixture，结束后移除自建资源并恢复目标原 Activity：

```sh
./gradlew :inputFixture:assembleDebug :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --no-daemon
uv run --with websockets python scripts/test-remote-input.py --phone <控制手机序列号> --target <被控端序列号> --host <被控端IP>
```

专项让控制手机和接收 Activity 保持前台亮屏，测量手机经局域网的热态提交确认延迟，目标 P95≤100ms、10Hz 持续30秒无积压；另行记录桥接注入耗时，区分执行与网络抖动。被控接收 Activity 独立核对按下/抬起数量、顺序、来源和重复计数。额外覆盖慢工具期间按键独立执行、桥接被杀后的仅抬起恢复、正常停止回收和父进程异常退出后的 EOF 清理。RPC 成功不替代接收端实收验证，记录实际指标与失败范围。手机安装器要求正常安装确认时先完成确认；`--use-installed-phone` 校验已有 App/测试 APK 摘要后可省去重复安装。

只有改动涉及真实目标或硬件行为时才选择对应专项，方法见[设备验证](../device-validation.md)。不得不带类或专项选择运行整套 instrumentation，其中包含部署、配对和系统动作测试。

真实目标测试须显式配置设备；使用 UUID 服务目录、临时端口和自建 fixture，核对目标服务身份及源文件/数据保留，结束清理自己的资源。需要目标时优先用独立模拟器，再按硬件兼容需求使用专用物理设备。

模型提供商测试显式启用，凭据只交给所需进程，不能写入源码、命令历史、测试日志或文档。确定性模型 fixture 验证工具调用协议与边界，不能证明真实提供商兼容。

## 结果判定

回复或提交说明写明命令、环境、实际执行或缓存复用、失败/跳过及未执行范围。相同输入的 Gradle 缓存可用于纯代码检查；依赖外部设备状态的专项不能拿缓存代替当前执行。

JVM fixture、模拟器和真实设备分别说明。UI fixture 不证明目标实际输入、文件/应用操作或游戏消费；主机工具不证明 Android 系统效果；模拟器不能替代厂商系统、摄像头和自然 WLAN 故障。保留失败证据与有效断言，不扩大通过结论。

手柄恢复使用 `VirtualGamepadControllerTest` 和 `VirtualGamepadRecoveryTransportTest` 验证更新超时、清理无回复、打开回复丢失、晚到回复、取消恢复、连接绑定和重试次数边界；WebSocket fixture 不证明目标游戏消费输入。服务端重开归属及旧设备释放可在自有 root 模拟器运行：

```sh
uv run --with websockets python runtime/virtual_gamepad_target_test.py --serial <自有模拟器序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-x86_64 --receiver-apk mediaFixture/build/outputs/apk/debug/mediaFixture-debug.apk
```

运行前构建匹配 ABI 的 runtime 和 `:mediaFixture:assembleDebug`，不要向其他 ABI 的设备推送 x86_64 文件。专项只创建 UUID 服务和原先未安装的接收 fixture，验证同连接重开、外连接拒绝、内核设备回收、按键边沿及回中，结束清理自建资源。

应用详情 UI 使用控制端回环协议 fixture，验证页面返回、旋转、权限确认与离线禁用，以及 320dp、1.5 倍字体的亮暗布局：

```sh
python3 scripts/test-phone-target.py --phone <自有模拟器序列号> --suite app-details-ui
```

被控端应用操作使用 UUID 包名、临时签名 APK 与独立 runtime 状态目录；脚本创建相机运行时权限、普通网络权限和私有标记数据，验证拒绝/重复确认、权限授予撤销、状态改变后确认拒绝、强停、启停用及数据清除。只在显式选择的自有模拟器或专用目标执行，不操作已有应用的数据，结束卸载自建 APK、停止自建服务并移除自己的转发和目录。先构建配套 ABI：

```sh
uv run --with websockets python runtime/app_control_target_test.py --serial <自有模拟器序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-x86_64
```

标准遥控长按使用 `RemoteKeyControllerTest` 与 Rust `remote_input::tests` 验证串行边沿、手势归属、快速短按、无自动重放、断线与超时释放。`remote-haptics-input-ui` 同时检查真实界面的按下先于松开、菜单保活、取消和后台释放，服务为控制端回环 fixture。独立 Android 接收端可验证实际 KeyEvent：

```sh
./gradlew :inputFixture:assembleDebug :androidApp:assembleDebug --no-daemon
uv run --with websockets python runtime/remote_key_hold_target_test.py --serial <自有目标序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-<匹配ABI> --receiver-apk inputFixture/build/outputs/apk/debug/inputFixture-debug.apk
```

专项只安装原先不存在的接收 fixture，并创建 UUID runtime 目录与临时 ADB 转发；校验方向、菜单、确认、返回、数字及删除的 downTime、重复次数、长按标记、取消与断线释放。短暂暂停经路径验证的自建 runtime，核对 Java 桥接的独立超时释放，随后恢复并清理。接收 fixture 通过只读、要求 shell DUMP 权限的 provider 暴露自身日志，不依赖目标支持 run-as。测试不操作电源/音量，也不证明厂商系统键或第三方应用的最终长按动作。

应用元数据缓存的隔离目标回归：

```sh
./gradlew :desktopApp:jvmTest --tests ernest.airc.desktop.AppMetadataCacheTargetTest --tests ernest.airc.desktop.AppLabelsTargetTest -PaircTargetSerial=<自有模拟器序列号> --no-daemon
```

专项创建 UUID 服务目录，拒绝覆盖已有媒体 fixture；验证名称与图标的重复读取、跨服务重启复用、同版本重装/更新失效、损坏缓存重建、卸载清理及实时权限/启用状态。资源配置检查临时切换目标亮暗模式并恢复原设置，结束卸载自建 fixture、停止自建服务并清理转发和目录。控制端版本标识、未变化图标复用和晚到响应边界由 `InstalledAppsTest`、`AppIconControllerTest` 验证。

手机快捷功能回归：先构建 App 与测试 APK，执行 `python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite mobile-convenience-ui`。覆盖独立电视输入的离线禁用与旋转草稿、系统分享不自动执行、内容 URI 元数据与拒绝文件 URI、收藏切换与恢复、音量键页面范围、组合分步编辑与排序、等待秒数编辑、旋转恢复、列表直接执行、暂停/继续不重放、最后一次详情及旋转恢复、未保存修改及删除确认。`--suite library-layout-ui` 检查 320dp / 1.5 倍字号下的收藏筛选与星标、步骤类型图标和菜单、按键选择和等待输入，并保存亮暗主题截图；桌面 `ApplicationLibraryUiTest` 覆盖收藏优先、筛选、上限与离线管理，`LibraryEditingTest` 覆盖排序及秒数解析边界。`QuickRoutineControllerTest` 覆盖组合顺序、暂停与剩余等待、按键释放校验、按电视保存执行记录、失败/取消中断与重连隔离；`ClipboardPasteMessageTest` 覆盖 UTF-8 包格式与长度边界。

中文粘贴专项使用 `ScrcpyTextInputTargetTest`，需显式传入 `textTargetHost` / `textTargetPort`，在自有目标预先安装原本不存在的 mediaFixture，并在测试后卸载。专项打开 TextActivity 的 paste 模式，核对中文、表情和 Shell 字符按字面写入，以及临时 server 文件被清理；不证明厂商播放器或密码输入框允许粘贴。

文字接收 fixture 通过仅允许 shell DUMP 权限读取的 `content://ernest.airc.mediafixture.text/text` 返回合成测试文字，不依赖目标 `run-as` 能力，不暴露其他应用文件。

主界面 UI fixture 在启动 Activity 前用 DeviceHistoryIsolation 持久备份设备历史和上次连接项，测试期间清空可自动重连目标，Activity 销毁后恢复；不把原设备当作离线 fixture。中断留下 active 备份时拒绝覆盖，应先调用该类的 restore 恢复原历史。此隔离不修改模型密钥或目标服务。

文件页布局回归使用 `python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite file-page-ui`，检查无重复标题、320dp / 1.5 倍字号的底栏溢出菜单及亮暗截图、按操作打开弹窗、自动识别信息的显示与文件名编辑。`FileWorkflowUiTest` 验证上传表单与下载/删除状态隔离；`UrlDownloadControllerTest` 验证自动探测不启动下载、手动名称保护、快速换址及迟到响应隔离；`UrlMetadataProtocolTest` 使用主机 runtime 和本地 HTTP fixture 验证重定向、响应头识别及识别后下载，`UrlDownloadTargetTest` 在隔离 Android 目录验证相同协议。

标准遥控器顶栏菜单回归使用 `python3 scripts/test-phone-target.py --phone <模拟器序列号> --suite remote-menu-ui`：验证三项带图标竖排菜单、离线可浏览、点击后关闭菜单、旋转不自动打开菜单、设备和类型入口可用，并复用窄屏、大字体及真实软键盘布局回归。`mobile-convenience-ui` 从该菜单进入收藏、电视输入与快捷操作。

电视输入双通道验证：`TvInputControllerTest` 覆盖按电视保存方式、确认后清草稿、拒绝重复提交、失败不回退、取消和换目标隔离，以及中文/Shell 字符编码边界。构建 App/测试 APK 后，`python3 scripts/test-phone-target.py --phone <自有模拟器> --suite tv-input-ui` 检查 320dp / 1.5 倍字号的亮暗单选、安装与发送禁用、恢复入口、错误与处理中反馈并保存截图；`mobile-convenience-ui` 覆盖真实入口、旋转、草稿保留和软键盘展开/全局状态更新后的标题与关闭按钮可见性。

执行 `python3 scripts/test-tv-input-method.py --serial <自有模拟器>` 可安装 fixture、运行真实输入法专项并恢复原 Activity、清理自建包。专项 `TvInputMethodTargetTest` 仅在显式传入 `ownedImeTarget=true` 的自有 API 31+ 模拟器运行。先确认 `ernest.airc.ime` 和 `ernest.airc.mediafixture` 均未安装，再安装自建 mediaFixture。专项使用模拟器 shell 权限运行生产输入法安装、摘要检查、启用、中文/表情/字面 Shell 字符提交、剪贴板不变、普通应用越权拒绝、无输入框拒绝和原输入法恢复，结束卸载自建输入法；外层调用者须卸载自建 mediaFixture 并恢复原 Activity。该专项不证明厂商电视或第三方自绘输入框兼容。

全屏透明叠层回归使用 `AppearanceTest` 核对半透明衬底在纯白/纯黑视频上的正常文字、图标与危险前景对比度，并核对切换全屏后主题恢复及内容实例保留。`mirror-toolbar-ui` 同时运行 `MirrorOverlayUiTest`：以纯白、纯黑、棋盘格模拟视频，在亮暗主题下检查外框空隙透明、48dp 触控、图标入口、透明空隙阻断目标触控、完整遥控草稿保留并保存截图；原 `MirrorToolbarUiTest` 继续核对原生 Surface 身份、方向缩放和普通布局。`MirrorQualityUiTest` 通过内存 ADB 传入真实视频尺寸头并配置平台解码器，检查连续切换档位后的解码尺寸、分辨率/码率/帧率参数与旧会话清理；该 fixture 不提供视频帧，不证明真实目标编码和画面渲染。

悬浮遥控头部与拖动继续使用 `mirror-toolbar-ui` 专项：`MirrorFloatingDragUiTest` 检查图标顺序和触控尺寸、无文字切换行、手柄快速/慢速连续滑动与长按后移动（不重复位移）、空白/按键/切换/关闭/输入区长按拖动及连续多次位移、边界限制、快速滑动不拖动、静止按住保持按键语义、拖动取消按键及多指介入不再发送 DOWN。桌面 `RemoteKeyInputUiTest` 检查父级手势取消当前按键、已消费 DOWN 不生成新按键；`remote-haptics-input-ui` 回归原有真实按下/抬起、快速点按与震动清理。以上均使用本地 fixture，不证明厂商电视的最终按键消费。

电视输入焦点联动：`TvInputControllerTest` 使用只读 backend fixture 验证就绪/失焦、查询失败后隐藏与恢复、监测取消释放连接，以及换电视拒绝迟到状态，不触发安装或发送。`tv-input-ui` 包含 `TvInputFocusUiTest`，验证显式点击才打开面板和手机焦点、保留草稿及方向操作。`TvInputMethodTargetTest` 同时读取真实输入连接的轻量焦点接口；由 `scripts/test-tv-input-method.py --serial <自有模拟器>` 执行，不证明厂商输入框兼容。

悬浮吸附与淡化：`MirrorRemoteBehaviorTest` 核对四边吸附、自由位置、越界与窄视口；`mirror-toolbar-ui` 包含 `FloatingRemoteBehaviorUiTest`，检查离线透明度设置及重新加载、连续拖动后吸附、5秒闲置淡化与按住恢复后的持续可见。测试使用本地画面 fixture，不连接电视。

真实电视预览与浮层联测使用 `python3 scripts/test-phone-target.py --phone <控制端ADB> --target <电视ADB> --host <电视IP> --suite mirror-floating-target`。先构建 App、AndroidTest 与 mediaFixture；专项创建配套隔离 runtime，在临时 ColorActivity 中核对实际解码像素变化、原生 Surface 保留、连续拖动与吸附、设置透明度、闲置淡化与唤醒、浮层不透传触控、电视实收方向键 DOWN/UP、完整/紧凑切换及后台恢复。退出后核对自建 scrcpy 文件/进程释放，恢复控制端历史和透明度、电视原前台 Activity，并卸载自建 fixture。控制端若是模拟器，应明确报告，不能称为实体手机实测。

首次在新控制端进入全屏时，先关闭 Android 的全屏手势教学提示，避免系统遮罩干扰叠层像素检查。`mirror-floating-target` 支持 `--preview-quality 流畅|均衡|清晰` 选择初始档位；若测试进程被中断，驱动器通过 `DeviceHistoryRecoveryTest` 恢复设备历史与已记录的透明度，避免把临时目标留给自动重连。

符号链接专项：`FileBrowserControllerTest` 检查链接目录的逻辑路径、返回与只读创建拦截；`FileLinkUiTest`（桌面与 Android，Android 归入 `file-page-ui`）验证链接目标/错误、预览与下载入口、链接删除确认和只读禁用，适配亮暗、320dp 与1.5倍字号。Rust `tools::files` 检查范围内相对/绝对/多级链接、越界、失效、循环、无权限、特殊文件、procfs链接和并发替换后的读取隔离；文件动作及传输测试检查只删除链接、下载可读而写入父目录仍拒绝链接。

Android 文件系统专项只操作明确选择的自有模拟器，在 UUID 目录启动匹配 ABI 的独立服务，验证真实 HTTP 范围预览、固定来源、流式下载摘要、写入/扫描拒绝及链接删除，结束清理自建服务、文件与转发：

```sh
uv run --with websockets python runtime/file_links_target_test.py --serial <自有模拟器序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-x86_64
```

按目标 ABI 替换二进制；此专项不安装 App、不修改已有文件或系统设置，也不代替厂商共享存储兼容性测试。

定时与批量功能的聚焦主机回归使用`ScheduleControllerTest`、`BatchTransferProtocolTest`、`RoutineConditionsTest`和`RemoteLayoutTest`，分别验证重连确认失效与音量参数传输、真实主机协议的多文件摘要/ZIP、条件等待与输入边界。Rust schedules测试使用可注入动作验证独立调度、单次不重放、每天顺延、音量参数边界与持久化恢复；chunks测试使用独立任务实例验证分块回退、偏移拒绝、摘要提交与范围读取，不占用其他测试共享注册表的任务名额。

构建App和测试APK后，执行`python3 scripts/test-phone-target.py --phone <自有模拟器> --suite scheduled-features-ui`，验证窄屏大字体亮暗主题的定时运行说明、时间与动作下拉选择、单次未来时间推算、时间选择器确认/取消和恢复、离线草稿与提交禁用、媒体音量输入校验与旋转恢复、静音/解除静音提交、批量文件选择。`product-pages`同时覆盖定时入口和设备页返回。固定桌面快捷方式、小组件、厂商休眠限制、实际关机及真实视觉提供商兼容需按交付范围另行验证，主机和面板fixture不能替代这些行为。

被控端定时执行专项可在自有模拟器运行：

```sh
uv run --with websockets python runtime/scheduled_actions_target_test.py --serial <自有目标序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-<匹配ABI>
```

专项构建并安装UUID包名的临时MediaSession/启动器接收应用，创建UUID服务目录，保存60/70/80秒后的暂停、打开应用与强停任务后断开WebSocket。同时保存90秒后的媒体音量40%，以及100/110秒静音和120/130秒解除静音任务。分别核对暂停回调、前台包、stopped标志、系统媒体音量与静音状态及被控端执行记录；重复静音和解除静音不会切换为相反状态。结束清理自己的APK、服务和ADB转发，并恢复原音量、静音状态和Activity。不会执行关机，也不证明厂商休眠或关机接口行为。

Downloads 上传平台验证使用独立 Android 模拟器：`python3 runtime/upload_copy_target_test.py --serial <模拟器序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-x86_64`（需要 Python websockets）。覆盖单文件、空文件、分块续传、摘要错误、同名冲突及临时文件清理；只创建 UUID 专属目录和服务，结束删除自有文件及 ADB 转发。可选 `--baseline-binary <旧版匹配ABI二进制>` 对照旧版的原子不覆盖报错。

AAC 被控端播放验证使用独立模拟器：先构建 `:mediaFixture:assembleDebug`，再运行 `uv run --with av --with websockets python runtime/aac_media_target_test.py --serial <模拟器序列号> --binary androidApp/src/main/assets/runtime/airc-runtime-x86_64 --fixture-apk mediaFixture/build/outputs/apk/debug/mediaFixture-debug.apk`。临时生成静音 AAC，验证大小写扩展名、audio/aac、播放副本与实际 MediaPlayer 播放状态；不代表真实设备或其他 AAC 编码配置的兼容性。脚本拒绝替换已有 fixture App，结束移除自有应用、文件、服务和转发。

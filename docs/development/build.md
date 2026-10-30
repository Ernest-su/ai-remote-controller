# 从源码构建

## 环境

- JDK 17 用于应用与模块编译；Gradle daemon 使用仓库 `gradle/gradle-daemon-jvm.properties` 固定的 JetBrains JDK 25，首次由 Gradle 自动获取。Android SDK 36；应用最低 API 26，当前 target SDK 35。
- Android NDK，已验证 `28.2.13676358`。
- SDK build-tools 36.0.0（D8）与 javac 用于构建嵌入的短时媒体桥接；工具链内容仍在宿主机安装，编译只读 SDK。
- Rust 1.88 或更高版本、Cargo/rustup、Python 3.11+；首次构建需要网络。APK 只读解析使用固定 zip 9.0.2，其 Rust 要求见[官方文档](https://docs.rs/zip/9.0.2/zip/#msrv)。
- 构建脚本按 Linux、macOS、Windows 选择 NDK 工具链；Windows 使用原生 Python、Rust MSVC 工具链与 Visual Studio C++ Build Tools。完整验收范围以实际构建结果为准。

从仓库根目录执行：

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./gradlew :androidApp:assembleDebug :shared:jvmTest --no-daemon
```

Windows 的 Android Studio 可直接执行同一 Gradle 任务。命令行可从仓库根目录运行：

```powershell
$env:ANDROID_HOME = "C:\Android\Sdk"
java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :androidApp:assembleDebug --no-daemon
```

SDK 路径换为本机安装目录。Windows 默认调用 `python`，Linux/macOS 默认调用 `python3`；如需指定解释器，给 Gradle 传入 `-PaircPython=C:/Python/python.exe`。先用该解释器执行 `--version`，确认不是 Windows 应用商店的空别名。runtime 子构建会沿用此解释器；Windows NDK 必须包含 `windows-x86_64` 工具链。

`local.properties` 可设置 Android SDK 路径，但不要提交个人配置。Gradle 的 preBuild 会执行 `scripts/build-runtime.py`，构建 ARM64、ARMv7、x86_64 程序及清单，打包进 APK。构建同时生成 `tvInputMethod` 配套电视输入法，将同构建类型的已签名 APK 嵌入 `assets/airc-input-method.apk`；debug 使用开发证书，release 与控制端共用生产签名配置。电视安装由“电视输入 → 输入法提交 → 安装并启用”明确触发，按 APK 摘要校验与更新。

runtime 构建使用 Gradle 增量检查：Rust 源码、Android 桥接源码、Cargo.toml / Cargo.lock、build.rs 和相关 Python 构建脚本未变化，且输出资产未变化时，`:androidApp:bundleRuntime` 显示 `UP-TO-DATE`，跳过二进制构建。输入或产物变化后重新执行，Cargo 继续复用可用的编译缓存；首次构建或切换到独立容器缓存时可能需要完整编译。

| 产物 | 路径 |
| --- | --- |
| release APK（需生产签名配置） | `androidApp/build/outputs/apk/release/androidApp-release.apk` |
| debug APK | `androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| instrumentation APK | `androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk` |
| 生成的 runtime 资产 | `androidApp/src/main/assets/runtime/` |

## Gradle 版本管理

插件、第三方依赖与应用发布版本统一由 `gradle/libs.versions.toml`（Gradle Version Catalog）维护，各模块通过 `libs` 别名引用。控制端、电视输入法与桌面版本显示共用 `app-name` 和 `app-code`；发布脚本从同一目录文件读取版本，并校验标签与 `runtime/Cargo.toml` 一致。设置阶段使用的 Foojay 插件版本保留在 `settings.gradle.kts`，Gradle Wrapper 版本保留在 `gradle/wrapper/gradle-wrapper.properties`。

## 16 KB 页大小与原生依赖

Android APK 的原生库须同时满足 ELF 段对齐与 APK 内未压缩库的 ZIP 对齐，参见 [Android 官方指南](https://developer.android.com/guide/practices/page-sizes)。项目使用 AGP 9.0.1 处理 APK 对齐，runtime 使用 NDK r28 与 `-Wl,-z,max-page-size=16384`；ADB v0.4.0 已提供支持 16 KB 对齐的原生依赖，App 与 `ascrcpy` 直接使用发布库，无须额外覆盖 Conscrypt 版本。

构建 APK 后使用 SDK 的 `zipalign -c -P 16 -v 4 androidApp/build/outputs/apk/debug/androidApp-debug.apk` 验证 ZIP 对齐；解压 APK 后使用 NDK 的 `llvm-objdump -p` 检查各原生库及 `assets/runtime/airc-runtime-*` 的 `LOAD` 段对齐。ZIP 检查通过不能代替 ELF 检查；实际运行兼容性仍需在 `adb shell getconf PAGE_SIZE` 返回 `16384` 的模拟器或设备验证。

## 构建并安装 APK {#build-install-apk}

控制手机先启用并授权 ADB，电脑需要 `adb`。脚本默认先执行 `./gradlew :androidApp:assembleDebug --no-daemon`，成功后安装 debug APK；即使已有 APK 也会先运行构建，构建失败时停止安装。`--apk PATH` 跳过构建并直接安装指定文件。不自动启动应用；使用 `adb install -r` 更新并保留应用数据，安装失败时直接报告错误，不自动卸载。

```sh
./android-install-apk.sh
./android-install-apk.sh --serial DEVICE_SERIAL
./android-install-apk.sh --connect 192.168.1.100:5555
./android-install-apk.sh --apk /path/to/app.apk --serial DEVICE_SERIAL
```

默认 APK 为脚本所在仓库的 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，`--apk` 的相对路径按当前工作目录解析。设备选择与 `android-build-run.sh` 一致：单个在线设备自动选择，多个在线设备提示编号，没有在线设备时提示输入 ADB 连接地址（不是配对端口）。非交互环境在零个或多个在线设备时必须指定目标。支持 `ADB_SERIAL` / `ANDROID_SERIAL` 环境变量；`--serial` 覆盖环境变量，`--connect` 与已选序列号不一致时拒绝执行。

## 单独构建 runtime

```sh
cargo build --locked --manifest-path runtime/Cargo.toml
python3 scripts/build-runtime.py
```

`build-runtime.py` 遵循 `CARGO_TARGET_DIR`，可用独立构建目录隔离缓存。在 Docker/Podman 容器中未显式设置时，默认使用 `runtime/target/android-container`，避免复用与当前 libc 不兼容的宿主机构建脚本；资产仍输出到应用 runtime 目录。

主机程序在 `runtime/target/debug/airc-runtime`，用于协议和生命周期测试。`build-runtime.py --abi x86_64` 可缩小本地调试范围；准备分发前运行默认全 ABI 构建并检查 APK 资产。

资产、build、target 和发布目录均为生成物，不提交到源码仓库。版本来自 `runtime/Cargo.toml`，应用版本在 `androidApp/build.gradle.kts`；发布前同步检查协议与应用兼容范围。

## 一键构建、推送和启动 runtime

目标设备先启用并授权 ADB；主机需要 `adb` 以及上面的 Rust、Python、NDK 环境。在仓库根目录执行：

```sh
./android-build-run.sh
# 指定已有设备，或连接无线 ADB 地址（使用连接端口，不是配对端口）
./android-build-run.sh --serial DEVICE_SERIAL
./android-build-run.sh --connect DEVICE_IP:ADB_PORT --port 9876
# 可选：尝试 root adbd，或明确使用 shell
./android-build-run.sh --serial DEVICE_SERIAL --adb-root
./android-build-run.sh --serial DEVICE_SERIAL --no-root
```

脚本自动选择唯一在线设备，多设备时交互选择，没有设备时提示输入地址；非交互环境需明确指定设备。自动检测 Android API 24+ 和 ABI，仅构建目标 ABI，推送到 `/data/local/tmp/airc`，后台启动并等待服务就绪。构建会更新生成的 runtime 资产与清单；准备 APK/分发前仍需全 ABI 构建。

脚本默认检测当前 root adbd，再尝试 `su -c id -u`；root 不可用时使用 ADB shell。`--no-root` 强制使用 shell，`--root` 显式启用自动检测。`--adb-root` 先尝试 `adb root` 并等待重新连接，仅对允许 root adbd 的系统有效；App 不主动重启 adbd。支持 `--android-dir /data/local/tmp/custom-airc`、`--port`，以及 `ADB_SERIAL`（或 `ANDROID_SERIAL`）、`ANDROID_DIR`、`PORT` 环境变量。自定义目录不由应用的默认部署流程管理。

更新先推送 `.next`，检查可执行性，再停止当前目录所属服务，备份并替换二进制；启动失败时尝试恢复旧程序、原权限及原端口服务。root 部署记录 `.root-mode`，供 App 与脚本后续管理使用；切回 shell 时在停止后恢复状态文件所有权，保留私有权限。需要切换到 shell 或为旧 shell 服务回滚时，会先验证目标 `su 2000 -c` 支持；不支持则保留旧服务并报告失败。保留设备身份、模型配置和日志，不使用全局进程终止。部署期间不要同时从应用或其他终端更新同一目录；ADB 断链或断电仍需检查设备状态。

成功后可在控制手机通过 mDNS 发现，或连接 `ws://DEVICE_IP:9876/ws`。日志位于部署目录的 `state/runtime.log`。下列命令用于 shell 部署或 root adbd。通过 `su` 部署时，需要将服务命令放在 `adb -s DEVICE_SERIAL shell "su -c '...'"` 中执行，或直接使用 App 管理。手动检查和停止：

```sh
adb -s DEVICE_SERIAL shell /data/local/tmp/airc/airc-runtime service status --state-dir /data/local/tmp/airc/state
adb -s DEVICE_SERIAL shell /data/local/tmp/airc/airc-runtime service stop --state-dir /data/local/tmp/airc/state
```

## JVM 桌面工具

`./gradlew --no-daemon :desktopApp:run` 启动 Compose 图形窗口；`./gradlew --no-daemon :desktopApp:jvmJar` 构建 JVM 模块。使用 JDK 17，沿用仓库 Android/Gradle 配置；首次向目标构建/部署 runtime 仍需 Rust 与 NDK。运行和测试参数见[桌面指南](../guide/desktop.md)。Compose desktop application 配置参考 [JetBrains 原生分发文档](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)。

`android-build-run.sh` 与共享 KMP 权限检测支持 command 与 UID-shell 两种 su 风格。CLI 的 `--root`/`--no-root` 分别保持用户意图，管理旧 root 服务时仍验证 root，并在降权前确认 shell UID；不通过 adb root 改变全局 ADB 进程，除非用户显式传入该选项。

生产签名、六项 GitHub Secrets 与自动生成草稿 Release 的步骤见[签名与发布](releasing.md)。本地可用 `scripts/release_signing.py run` 临时注入签名环境，未提供生产密钥时 release 构建会失败；debug 构建不需要生产密钥。

被控端 NDK 编译器和 D8 最低 API 为24，主控 App 最低 API 仍为26。Android 7 支持按目标专项试用验证，不代表所有厂商功能已覆盖；16 KB ELF 对齐保持启用。

ADB 传输实验可用 `-PaircAdbSource=/path/to/adb` 指定本地 ADB 库源码；Gradle composite build 同时替换 App 和 ascrcpy 的 ADB 依赖。普通构建仍使用发布依赖；本地路径只通过命令行提供，不写入仓库或提交二进制依赖。

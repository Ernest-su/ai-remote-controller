# 设备验证方法

界面修改先运行[JVM 与模拟器验证](development/testing.md#emulator-controller)。需要目标工具效果时优先使用独立模拟器；物理设备只补充厂商系统、摄像头、输入、网络和第三方应用兼容等专项。

本页描述稳定的测试方法。执行命令、结果和未覆盖条件放在回复或提交说明，不在文档追加开发记录。

## 环境与隔离

构建应用和测试包，并明确控制端与目标的序列号。`--phone` 可指向自有 Android 模拟器；带目标参数的专项需要另一个目标，二者不能相同。

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --no-daemon
```

设备测试会安装测试包、创建文件或启动服务。使用独立服务目录、临时端口与自建测试资源，避免改变现用服务、应用和用户数据。遇到已安装测试包或签名不匹配，按脚本拒绝条件处理，不卸载用户应用以强行继续。

## 控制端与目标链路

```sh
python3 scripts/test-phone-target.py \
  --phone <控制端序列号> --target <目标序列号> --host <目标可达地址> \
  --suite files
```

按改动选择 `files`、`touchpad`、`screenshots`、`screenshot-share`、`pickers`、`picker-success`、`mirror`、`ascrcpy`、`gamepad-lifecycle` 或 `model-sync`。使用 `--help` 检查可用专项；不要为单个界面改动默认执行所有目标测试。

文件专项核对流式内容与摘要、取消与覆盖确认；媒体专项观察真实会话和播放位置；手柄专项核对配对释放与回中。请求成功不等同于目标应用消费输入，也不证明扬声器可听输出或所有播放器兼容。

## JVM 目标工具

统一入口使用隔离目标服务，并核对原主服务运行状态未被更改：

```sh
python3 scripts/test-target-tools.py --target <目标序列号>
```

该入口可能安装自建接收器、修改测试文件和启动临时 root 服务。仅对授权的专用目标执行。单个工具变更可直接选择相应 JVM 测试类和 `-PaircTargetSerial=<目标序列号>`，避免无关的大集合。

目标专项与 APK 打包按顺序执行：部分脚本只生成目标 ABI 的运行资产，之后构建交付 APK 以恢复三 ABI 并核对摘要，避免并行覆盖资产目录。

## 系统动作

实际锁屏、重启和关机使用自有 root 模拟器专项；入口拒绝物理设备并检查模拟器身份与凭据状态：

```sh
python3 scripts/test-emulator-system.py --emulator <自有模拟器序列号> --action lock
python3 scripts/test-emulator-system.py --emulator <自有模拟器序列号> --action reboot
python3 scripts/test-emulator-system.py --emulator <自有模拟器序列号> --action shutdown --container <自有模拟器容器名>
```

依赖需要 `websockets`。关机观察要求脚本约定的自有容器标签和环境，不直接套用到任意容器。权限与接口存在、提案生成或断开连接均不等于动作成功；以实际锁屏状态、启动身份变化或关机观察判定。

## 真实提供商

提供商测试需要显式启用和授权配置。只读目标检查入口：

```sh
python3 scripts/test-live-model-target.py --target <目标序列号> --host <目标可达地址>
```

该入口在目标上使用现有私有配置的临时副本并启动独立服务，可能产生提供商调用费用；不为普通界面验证运行。不要输出配置或 API Key。需要应用查询、文件提案或任务链路时，按脚本参数选择并独立核对实际工具执行。

## 真机补充检查

按改动影响选择：

- 连接：TCP ADB、六位码与摄像头扫码、无线调试端口变化、服务发现和历史重连。
- 系统交互：厂商键盘、主题与系统栏、后台恢复、文件选择器和跨应用分享。
- 输入：目标实际按键/手势、方向变化、第三方游戏消费与断线释放。
- 数据与服务：更新/回滚、取消、文件覆盖、安装/卸载和缓存清理的确认边界。
- 网络：自然 WLAN 故障、设备切换和旧连接回复隔离。

记录中区分模拟器、主机 fixture、物理控制端与实际目标，注明系统/ABI、版本、连接方式和未执行条件。测试结束只清理自己创建的服务、文件、包与转发。

## 电视输入法与焦点联动

仅在明确授权的 API 31+ 物理电视执行：

```sh
python3 scripts/test-tv-input-method.py --serial <电视ADB序列号> --allow-physical
```

先构建 App 和 mediaFixture。物理专项从 App 提取配套输入法，只临时安装输入法和媒体 fixture，不在电视运行手机 instrumentation，也不安装控制端 App；拒绝覆盖已有配套输入法或媒体 fixture。必须能读取原输入法与前台 Activity 以便恢复。脚本临时安装所需包，在自建输入框中通过同一输入法接口验证就绪/失焦、中文与字面 Shell 字符提交、电视剪贴板不变、无编辑器拒绝及原输入法恢复；结束移除本次创建的包并恢复原前台页面。不要把这一结果扩展为任意第三方自绘输入框兼容，或控制手机上的预览画面与手势验收。

# 工具参考

直接 WebSocket 请求使用下表的协议工具名。模型使用函数名，常规工具映射到同一执行层；模型 Shell 使用单独的受限诊断路径。

| 协议工具 | 模型函数名 | 参数 | Android 行为 |
| --- | --- | --- | --- |
| `remote.key` | `remote_key` | `key`，按住时加 `action`, `pressId` | 一次短按，或连接独占的按下/保活/抬起/取消 |
| `remote.text` | `remote_text` | `text` | 固定参数注入焦点字段 ASCII 文本，字面 `%s` 保留 |
| `remote.tap` | `remote_tap` | `x`, `y`, 可选 `count` | 归一化坐标点击 |
| `remote.swipe` | `remote_swipe` | `startX`, `startY`, `endX`, `endY`, 可选 `durationMs` | 受控滑动 |
| `remote.long_press` | `remote_long_press` | `x`, `y`, 可选 `durationMs` | 原地滑动模拟长按 |
| `apps.list` | `apps_list` | 空对象 | 结构化安装应用与系统类型 |
| `apps.open` | `apps_open` | `package` | 按包名启动应用 |
| `apps.install` | `apps_install` | `root`, `path`, `sha256`, 可选 `update` | 准备待确认 APK 安装长任务 |
| `apps.install_pending` | `apps_install_pending` | 空对象 | 列出待确认 APK 安装提案 |
| `apps.apk_info` | `apps_apk_info` | `root`, `path` | 只读 APK 包名、版本、摘要与声明证书指纹 |
| `apps.info` | `apps_info` | `package` | 当前用户的包名、版本与安装身份 |
| `apps.uninstall` | `apps_uninstall` | `package` | 仅准备卸载确认提案 |
| `apps.pending` | `apps_pending` | 空对象 | 查询待确认的卸载提案 |
| `device.info` | `device_info` | 空对象 | 只读设备信息，结构化 JSON |
| `device.ui_hierarchy` | `device_ui_hierarchy` | 空对象 | 默认显示器当前 UI XML，只读、128 KiB 上限 |
| `device.shell` | `device_shell` | `command` | 直接专家请求执行 shell 文本；模型仅固定只读诊断、无 shell 解释器 |

`key` 支持 `up`、`down`、`left`、`right`、`ok`、`back`、`home`、`menu`、`power`、`volume_up`、`volume_down`、`mute`、`play_pause`、`media_previous`、`media_next`、`0`…`9`、`channel_up`、`channel_down`、`delete`、`enter`。

```json
{"id":"open-settings","protocol":1,"type":"tool","tool":"apps.open","arguments":{"package":"com.android.settings"}}
```

工具以服务当前 shell/root 身份执行，Android 权限仍然适用。普通工具使用独立参数，`device.shell` 的直接专家请求接受 shell 文本，模型调用仅用固定诊断参数。每条命令最长 30 秒，每路输出上限 256 KiB（应用列表框架查询为 1 MiB）；模型工具循环最多 16 轮，整体请求上限 170 秒。

响应通过请求 `id` 关联，失败返回 `ok: false` 与 `error`。完整格式见[协议](../protocol.md)。

## 设备信息 {#device-info}

```json
{"id":"info","protocol":1,"type":"tool","tool":"device.info","arguments":{}}
```

成功响应的 `result` 是 JSON 字符串，包含 `manufacturer`、`model`、`androidRelease`、整数 `sdk`、字符串数组 `abis`、`screen`（`width` / `height`，像素）和 `storage`（固定查询 `/data` 的 `path`、实际 `mountPoint`、`totalBytes`、`usedBytes`、`availableBytes`）。屏幕优先使用 `wm size` 的有效 override；未获得的数据返回 `null`，不能据此声称设备不支持该能力。只使用固定 `getprop`、`wm size` 和 `df -k /data` 参数，不接受自定义命令或路径，不包含设备序列号。

探测整体受 30 秒超时约束。该工具已接入直接调用和模型函数；Android 功能菜单的设备信息页支持刷新与展示这些字段。

## 应用列表 {#apps-list}

`apps.list` 的 `result` 为 JSON 字符串，例如 `{"apps":[{"package":"com.android.settings","label":"设置","system":true}],"source":"package_manager","userId":0,"labelErrors":0}`。应用按包名排序、去重；通过目标包管理器读取当前 Android 用户的应用名称与系统/用户类型。名称读取失败时 `label` 为 `null`，并计入 `labelErrors`，不阻塞其他应用。框架查询受 30 秒与 1 MiB 限制，查询期间安装状态变化时应刷新。

目标包管理器桥接不可用时，使用 `pm list packages` 读取包名与系统类型；此时 `source` 为 `pm`、`labelsAvailable` 为 false、`label` 为 `null`，不返回 `userId` 与 `labelErrors`。

Android 页面显示名称、包名、图标与类型，支持名称/包名搜索、分类筛选和点击启动；名称不可用时保留包名。此列表包含无启动入口的系统组件，点击启动失败时显示实际工具错误。

## 坐标输入 {#gestures}

```json
{"id":"tap","protocol":1,"type":"tool","tool":"remote.tap","arguments":{"x":0.5,"y":0.5}}
```

坐标必须为 0 到 1 的有限数字，原点为默认显示屏当前方向的左上角，1 对应最后一个像素。runtime 读取 `dumpsys input` 中启用的默认显示屏 logical frame，按当前旋转和分辨率 override 换算；无可解析 viewport 时返回错误，不猜测尺寸。仅支持原点为零的默认显示屏，不支持外接显示屏选择。屏幕在探测与注入之间旋转仍可能造成坐标偏移，需要重新操作。

`remote.swipe` 默认 300 毫秒，允许整数 100–5000；`remote.long_press` 默认 800 毫秒，允许整数 500–5000。长按使用同点的 `input swipe`，实际识别由目标应用决定。成功 `result` 为 JSON 字符串，含使用的 `width`、`height` 和平台命令 `output`；这只表示命令退出成功，不证明目标应用接受了手势。

未知字段、字符串坐标、越界坐标、非整数时长会在执行命令前拒绝。整次探测和注入限制 30 秒。直接工具与模型函数使用同一执行层；Android 遥控器类型选择页提供触控板和数字键盘页面。

`remote.tap` 可选 `count` 为整数 1 或 2，默认 1；2 表示同点双击，两次独立 `input tap` 之间等待 80ms，实际间隔还包括进程启动耗时。某次点击失败会返回错误，先前已经执行的点击无法撤销。目标应用的双击识别仍需真机验收。

## 媒体状态快照

`media.status` 接收空参数对象，返回 `{"sessions":[...]}`。每个会话含 `packageName`、`active`、`state`、`positionMs`、`durationMs`、`updatedAtElapsedMs`、`seekSupported` 和 `description`。状态数字使用 Android PlaybackState 值；位置为系统报告的毫秒快照，不自动推算。未知字段为 null。时长仅在系统 dump 明确提供标准 duration 键时返回，常见系统输出不提供时长。

`seekSupported` 表示播放器声明了 ACTION_SEEK_TO（256），不表示当前 runtime 已能执行 seek。读取权限失败返回工具错误。该工具也通过 `media_status` 函数提供给大模型。现有播放按键保持可用，精确 seek 尚未实现。

## 截图二进制资源

`device.screenshot` 接收空参数对象，执行固定命令 `screencap -p`，返回 JSON 描述而非图片文本：

```json
{"id":"随机 UUID","path":"/artifacts/UUID","mimeType":"image/png","sizeBytes":12345,"width":1920,"height":1080,"expiresInSeconds":60}
```

控制端从同一目标的 `GET /artifacts/{id}` 下载 PNG。响应采用 `Cache-Control: no-store`、附件文件名和 `nosniff`，资源不存在、过期或被淘汰返回 404。服务限制截图输出 16 MiB、单次捕获并发为 1，内存最多保留四张；新的截图可能提前淘汰旧链接。60 秒后不再允许下载，过期内存项在下次资源操作时清理，进程退出后全部丢弃。不创建目标临时图片文件，不接受任意文件路径。

大模型函数名为 `device_screenshot`，获得同一资源描述；当前并未将图像注入多模态模型。`client/` 下载器仅允许匹配资源 ID 的路径，禁用重定向，校验 MIME、PNG 标识与长度，超过描述长度即拒绝。手机和桌面已提供截图预览和保存页面，手机支持系统分享。预览有解码失败反馈，下载描述和 PNG 头的尺寸必须一致，像素数量上限为 32 Mi。这是单张截图，不是实时投屏。

## 文件管理与流式传输

`files.list` / `files_list` 与 `files.stat` / `files_stat` 接收 `root`（`downloads` 默认、`inbox` 服务私有收件箱、`full` 文件系统 `/`）及相对 `path`。调用参数仍拒绝绝对路径和 `..`；只读解析允许普通符号链接，但目标必须留在所选根内，权限沿用服务身份。列表额外接收 `limit`（1–100，默认 50）与 `cursor`；返回 root/path/entries/nextCursor/readOnly，按文件名排序，最多扫描 4096 项。路径经过链接时 readOnly=true。条目的 kind 为解析后的 file/directory，另以 symlink=true、linkTarget 标注链接，targetName 提供解析后的文件名以识别无扩展名链接的预览类型；解析失败时 kind=symlink，linkError 区分 link_missing、link_outside_root、link_loop、link_permission、link_special、link_not_directory、link_invalid、link_changed、link_unavailable。stat 返回相同的类型、链接字段与普通文件 sizeBytes。解析使用固定目录/链接描述符，限制40次展开，拒绝特殊文件及 procfs 链接；不能用预先规范化路径后再按字符串打开替代。

`task.start` / `task_start` 的 `direction=download` 配合 root/path 创建流式下载，轮询 `task.progress` / `task_progress` 等待 ready，读取其 sha256 和 `dataPath=/transfers/{id}/data`，随后 GET 该路径。上传使用 direction=upload、sizeBytes、sha256，再 PUT 原始二进制到 dataPath。GET/PUT 的 MIME 为 application/octet-stream，禁止缓存；固定 64 KiB 缓冲，单文件最大 1 TiB。上传长度/摘要必须匹配，fsync 后原子提交；默认 RENAME_NOREPLACE，不支持时明确失败。最多两个活动任务，最多保留 16 条记录；终态记录可提前淘汰，空闲记录在新任务时按 15 分钟清理。任务不跨进程重启恢复。

`task.progress` 返回 id/direction/root/name/sizeBytes/bytesTransferred/phase/sha256/error/unit/dataPath。phase 包含 hashing、ready、awaiting_upload、uploading、downloading、committing、deleting、completed、failed、cancelled。unit 通常是 bytes，删除任务是 items。`task.cancel` / `task_cancel` 与 `DELETE /transfers/{id}` 可取消未提交任务，已提交文件保留；递归删除的已删除项无法恢复。


`files.mkdir` / `files_mkdir` 接收 `root`（downloads/inbox/full）及必填相对 `path`，创建单个新目录并返回 `{root,path,created:true}`。父目录必须存在；不创建父目录、不覆盖文件/目录/链接、不跟随符号链接，不接受递归或权限参数。使用 mkdirat 按打开的父目录执行，权限沿用服务身份，新目录请求模式为 0700，实际模式由文件系统策略决定。

`files.url_info` / `files_url_info` 接收一个 HTTP(S) `url`，从被控端读取建议名称与大小，返回 `{name, sizeBytes}`，大小未知时为 null。优先 HEAD，HEAD 不支持或没有长度时尝试 `Range: bytes=0-0`，不读取或保存完整响应体；206 使用 Content-Range 总长度。文件名优先 Content-Disposition 的 UTF-8 filename*，再 filename，最后最终重定向网址。名称不得包含路径或控制字符。HTTP(S) 重定向最多十次、禁止内嵌凭据，整个探测最多十秒。探测不创建下载任务，不保证下载时资源或长度不变。

`files.download_url` / `files_download_url` 接收必填 HTTP(S) `url`，可选 `root`（默认 downloads）、相对目录 `directory`（默认空）与 `name`（默认网址末段，无末段时 download.bin）。不允许嵌入用户名/密码，仅跟随 HTTP(S) 重定向，最多十次。复用文件管理的 no-follow 路径访问与上传临时文件；优先原子不覆盖重命名，存储卡不支持时在下载完成后独占创建目标并流式复制，仍不覆盖同名文件。复制保存阶段可能暂时显示未完成目标文件，异常退出可能留下该文件；常规失败会清理。返回 `direction=url_download` 的后台任务，使用 `task.progress`/`task.cancel` 查询或取消；阶段为 connecting、downloading、committing、completed、failed、cancelled。`bytesTransferred` 为已写字节，`sizeBytes=0`，实际总长度从 `result.totalBytes` 读取（未知时 null），`result.path` 为目标相对路径。完成时 totalBytes 为最终字节数并提供 SHA-256。最多 1 TiB，与其他传输共享活动任务限额；错误或取消清理临时文件，不覆盖现有文件，无断点续传或重启恢复。

`files.delete` / `files_delete` 接收 root/path/recursive，仅创建待确认提案，不立即删除。`files.prepare_overwrite` / `files_prepare_overwrite` 接收 root/path，返回覆盖提案。提案含 id/kind/root/path/exists/directory/sizeBytes/recursive/approvalRequired，15 分钟过期，最多 32 项。`files.pending` / `files_pending` 可查询尚未确认提案。控制端必须核对目标并让用户确认，再发送独立请求 `type=approve, arguments={id}`；`{id,accepted:false}` 丢弃提案。模型工具没有 approve。删除确认返回 task 描述；覆盖确认返回单次 token，随后 task.start 上传携带 overwriteToken。覆盖前重新核对目标信息，变化时失败；允许覆盖时使用原子替换。递归删除不跟随链接、不跨挂载点、最多 128 层。

`files.download` / `files_download` 保留小文件快照接口，最大 16 MiB，返回短期 FileArtifact（id/path/name/mimeType/sizeBytes/sha256/expiresInSeconds）。GET `/artifacts/{id}` 下载，60 秒过期，与截图共享四项资源容量；大文件应使用 task.start。界面所有文件下载均采用流式任务。详见[目标文件](../guide/files.md)。

## 媒体打开工具

`media.players` / `media_players` 接收 root/path 与可选 mimeType，读取文件属性并查询目标 ACTION_VIEW 处理器。返回 root/path/mimeType/transport/packages；Downloads 使用 file URI，inbox/full 使用目标 loopback HTTP 地址。排除 frameworkpackagestubs 占位处理器。`media.open` / `media_open` 使用同样参数与可选 package（必须来自查询结果）启动播放器；返回 launched、playbackVerified=false、可选 resourceId 和 Android 启动结果。它不证明解码/播放成功，不接受任意外部 URI 或 shell 参数。

`media.release` / `media_release` 接收 resource id，撤销 HTTP 资源及其在途读取，不删除源文件，也不向播放器注入停止命令。GET/HEAD `/media/{id}` 提供真实媒体 MIME、Accept-Ranges、Content-Length、no-store；单段 bytes 范围返回 206 与 Content-Range，不可满足的范围返回 416，多段或畸形范围回退完整响应。If-Range 因没有提供验证器而回退完整响应。分段语义参考 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-range)。资源固定文件描述符，逐块核对文件元数据，文件改变返回 409 或中止流；读缓冲 64 KiB，最大文件 1 TiB。最多四份资源、每份四个读取、24 小时闲置过期，重启不恢复。

## 本地媒体发布

`media.publish` / `media_publish` 接收 root/path，为本地播放器创建服务拥有的 Downloads 副本，返回长任务描述。`task.progress` 终态 completed 的 result 为 `{id,root:"downloads",path,sizeBytes,sha256}`。通过 `media.open` 打开该路径，使用 `media.release` 删除该 id 对应副本。原文件保留。`media.players` 可额外选择 transport=local-copy，仅查询未来 file URI 的处理器，不创建副本；查询返回 sizeBytes。

发布采用独立 UUID 目录、O_EXCL 创建文件、64 KiB 缓冲、源变化检测、fsync 和副本 SHA-256 回读校验；未完成文件不会发送给播放器，失败或取消按创建时的 inode 清理本次文件，不覆盖或递归删除用户路径。`.nomedia` 限制媒体扫描，但不会隐藏文件系统访问。最多四份准备/保留副本，长任务共用两个活动名额，保留 16 MiB 存储余量。task.progress 增加可选 result，方向 media_publish、阶段 publishing；单位仍为 bytes。副本记录持久化在服务私有 media-publications 目录，记录 UUID、目录/文件 dev+inode、创建时间及完成结果，每次写入采用原子替换、0600 权限及 fsync。进程启动时恢复未过期的完成副本，清理有记录的中断/过期副本；身份不匹配或损坏记录保留并报告。`media.publications` / `media_publications` 查询恢复及当前保留的副本，`media.release` 逐项释放。任务本身和 HTTP 媒体资源仍不跨重启恢复。

## 应用卸载 {#app-uninstall}

`apps.info` 返回 package、userId、appId、versionCode、versionName、codePath、lastUpdateTime、system。`apps.uninstall` 仅返回 `{id,kind:"app_uninstall",app,approvalRequired:true,dataLoss:true}`；系统包与 appId 小于 10000 的特权包拒绝卸载。提案最多 32 项，15 分钟过期，不跨服务重启保存。

只有控制端独立 `type=approve_app` 请求提交确认，参数为 id；accepted=false 拒绝提案。确认前重新核对安装身份与当前用户，变化要求重新确认。任务通过 `task.progress` 读取，direction=app_uninstall，unit=items。提交阶段 committing 不可取消；完成后检查该用户的应用已经移除。仅执行当前用户卸载并删除其数据，不卸载其他用户。模型没有确认函数，不应通过 shell 绕过确认。

## APK 信息 {#apk-info}

`apps.apk_info` / `apps_apk_info` 接收 root（默认 downloads）与相对 path，复用文件工具的 no-follow 目录访问。返回长任务描述；task.progress 在 completed 阶段的 result 包含 root、path、package、versionCode、versionName、split、sizeBytes、sha256、signatures 和 signatureVerified=false，不安装文件。签名记录包含 scheme、certificateSha256、certificateExtractionSupported；v2/v3/v3.1 从声明的 signer 首证书计算指纹，v1 目前仅识别记录存在，证书指纹解析未支持。未识别的签名记录不代表已证实 APK 未签名。

此工具只解析声明，不校验证书链、密钥匹配、签名、轮换证明或 APK 内容签名。安装验证仍需包管理器，不能把 SHA-256 或证书指纹当作可信来源认证。签名块布局参考 [AOSP v2](https://source.android.com/docs/security/features/apksigning/v2) 与 [AOSP v3](https://source.android.com/docs/security/features/apksigning/v3)。

输入上限 4 GiB；最多 4096 ZIP 条目、8 MiB 中央目录、8 MiB 解压 Manifest、8 MiB 签名块、16 MiB 解码字符串池、16 signer。只允许一个并发解析，复用两个活动任务与十六项任务记录上限。摘要采用固定 64 KiB 缓冲，长任务不受普通请求 30 秒超时限制。direction=apk_inspect、phase=inspecting、unit=bytes，支持 task.cancel，取消不删除源文件。读取前后核对 dev/inode/大小/时间，变化要求重试。二进制 XML 支持 UTF-8/UTF-16、Android 版本属性资源 ID 核对及有符号 64 位长版本号；未声明版本号默认 0，版本名称最多 4 KiB。versionName 若为资源引用则返回 null，不解析资源表。拆分包仅展示其名称，当前单 APK 安装工具不提交 split。

## APK 安装与更新 {#apk-install}

先通过 apps.apk_info 核对 APK，然后 apps.install 传入该 SHA-256、root/path 和可选 update=true。默认新安装不覆盖已有包；替换需显式选择。返回 apk_prepare 长任务，结果为 `{id,kind:"app_install",apk,currentApp,userId,update,approvalRequired:true,codeMayAffectAllUsers}`。当前仅处理单个非 split APK；系统和特权包替换拒绝。

准备使用私有收件箱所在文件系统的 O_TMPFILE 匿名文件，固定 64 KiB 拷贝并核对源指纹和期望 SHA-256，再对副本解析并回读摘要。没有可修改的暂存路径，取消、拒绝、过期记录被清理或进程退出时关闭 FD 即释放副本。源 APK 保留。要求文件系统支持匿名文件，保留 16 MiB 余量；最多四份准备/待确认副本，提案 15 分钟过期，下一次查询或准备会清理过期记录。准备进度为拷贝与核对两轮的处理字节，sizeBytes 为源大小的两倍。

控制端发送独立 type=approve_install 与 arguments.id，accepted=false 拒绝。确认请求不作为模型函数暴露。提交前重新核对当前 Android 用户、已有包的安装身份与暂存副本元数据；变化要求重新准备。提交使用后台 app_install 任务、不可取消的 committing 阶段，通过 pm 的 stdin、明确 user、expected package 和不覆盖/替换标志执行，最长 300 秒。包管理器负责签名有效性与替换兼容性验证；不自动卸载旧包、降级、授予运行权限。完成后核对当前用户包名和版本，返回 installed=true、verifiedBy=android_package_manager 和 SHA-256。

替换 APK 代码可能影响该包的所有用户，应用自身的数据迁移也可能改变原有数据；不能将 --user 参数解释为程序代码完全隔离。超时、连接丢失或进程重启后的安装状态需要重新查询 apps.info，不能凭请求失败断言安装未发生。提案与任务不跨服务重启恢复。

## 存储分析 {#storage-analysis}

`storage.analyze` / `storage_analyze` 接收 root（默认 downloads）、相对目录 path（默认空）及 maxEntries（1–100000，默认 100000）。复用 no-follow 路径访问，返回 direction=storage_scan、phase=scanning 的长任务。task.progress 的 unit=items，bytesTransferred 表示已访问目录项，sizeBytes 是项数上限，不是目录总字节或预计实际项数；支持 task.cancel，不修改文件。

完成 result 包含 root/path、totals、categories、largestFiles（最多 20）、entriesVisited、directories、duplicateFiles、repeatedDirectories、skippedLinks、skippedSpecial、skippedFilesystems、errorCount/errors（前 8 项）、changedDirectories、truncated、complete、snapshot=false。totals 与类别记录包括 files、logicalBytes 和 allocatedBytes。普通文件按 dev/inode 去重，目录记录已进入的目录数，硬链接类别按首次遇到的路径后缀统计。类别为 apk/images/video/audio/archives/documents/logs/temporary/other。仅按后缀归类，不读取文件内容、不识别应用缓存、不计算可回收空间。

分配量取文件系统报告的文件块统计；稀疏文件、块共享、压缩、目录元数据与保留空间会影响其与实际可释放量的关系。遍历不跟随符号链接，不进入不同 dev 的文件系统，同一文件系统的 bind 别名可能进入并按已访问目录 inode 防止重复。扫描最多 100000 项、64 层和 120 秒，相对路径最多 2048 字节；到限、错误或目录修改产生不完整报告。complete=true 只表示限定范围内未发现限额/访问/目录变化问题，报告始终不是事务快照。只允许一项存储扫描，同时受两项活动任务与十六项记录的公共限额约束；任务不跨服务重启恢复。

## 播放定位 {#media-seek}

`media.status` 优先使用一次性标准 MediaController 桥接，返回 seekAvailable、source=media_controller、userId 和活动 sessions；会话增加 sessionTag，读取真实报告位置、更新时钟、时长、ACTION_SEEK_TO 支持与标题。位置是播放器报告值，不在控制端外推。桥接不可用时回退原 dumpsys 解析，seekAvailable=false 并附有限长度 bridgeError。

`media.seek` / `media_seek` 接收 package、非负整数 positionMs 和可选 sessionTag。选择当前 Android 用户活动会话，检查 ACTION_SEEK_TO 和已知正时长边界；多个匹配会话时拒绝猜测。通过标准 TransportControls.seekTo 提交，不用方向/快进键模拟。返回 packageName、requestedMs、submitted=true、playbackVerified=false 和提交时 session 快照。提交不证明执行：控制端随后读取位置核对，播放器拒绝或未及时更新时如实显示未观察到目标位置。接口依据 [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)) 与 [TransportControls.seekTo](https://developer.android.com/reference/android/media/session/MediaController.TransportControls#seekTo(long))。

常驻服务仍为 Rust 原生二进制。Android 构建时编译并嵌入短时 Java/Dex 桥接，只经系统 app_process 为当前请求调用 framework，不安装服务 APK或后台 Java 服务。初始化到服务私有 platform 目录中的 SHA-256 命名只读资产，核对 owner、模式、长度和内容；子进程默认限时 10 秒、输出 256 KiB；应用列表框架查询限时 30 秒、输出 1 MiB。反射只用于获取系统/当前用户上下文与 Android 媒体模块初始化；厂商或权限阻止时返回不可用，不切换或提权。最多 64 会话，标题限 256 字符、tag 超过 128 字符返回 null。Root 身份及其他 Android 版本尚未实机验收。

## 真实应用名称 {#app-labels}

apps.list / apps_list 优先通过短时框架桥接读取当前 Android 用户的 PackageManager 安装列表与 getApplicationLabel，返回 package、label 和 system；包名排序。标签按目标资源/配置解析，最多 256 个 UTF-16 单元。返回 source=package_manager、labelsAvailable=true、userId 和 labelErrors，单个标签读取失败时保留 null；这不表示标签完整读取成功。最多 4096 条、整体 1 MiB。桥接不可用或结果无效时回退原 pm 枚举，source=pm、labelsAvailable=false，未知 label 保持 null。它不会启动或修改应用。手机/桌面沿用名称与包名搜索、按准确包名启动。

应用元数据缓存：`apps.list` 的框架结果为每条应用提供 `cacheKey`（64 位小写十六进制 SHA-256），由用户、包名、首次安装/更新时间（毫秒）、版本、UID、系统标志、base/split 路径和资源配置生成。被控端每次重新查询 PackageManager 元数据，匹配时复用私有目录中持久化的名称和 96×96 PNG。`apps.icon` 描述符同时携带 `cacheKey`，控制端必须与当前列表标识一致后再下载；标识变化应刷新列表，不缓存该图标。缓存通过进程间锁与原子替换保护，文件限额 64 MiB / 8192 项，PNG 至多 128 KiB 并验证摘要；不持久缓存短时 artifact 地址。`apps.info` 复用静态信息并增加 `firstInstallTime` 字段；`apps.control_info`、待确认操作及实际提交前的身份/权限检查继续实时读取。pm 枚举回退没有 cacheKey，控制端刷新后不能保留这些未知版本的图标。

框架上下文与媒体桥接共用初始化源码，但应用与媒体操作拆为独立 Java 类。常驻服务仍为 Rust 可执行文件，SDK javac/D8 仅在 Android 构建时生成并嵌入短时适配 Dex，目标按请求运行 app_process。生成资产不提交，标签读取不要求额外服务 APK。

## 标准播放器自定义动作 {#media-custom-action}

`media.status` 增加 `customActionAvailable`，会话的 `customActions` 是 `{id,name}` 列表；每会话最多 32 项，动作 ID 最多 512 UTF-8 字节且不含控制字符，名称最多 128 UTF-16 单元。无效或重复声明会让标准状态路径失败并回退，回退不提供动作能力。

`media.custom_action`（模型 `media_custom_action`）接受 `package`、可选 `sessionTag`、`action`。执行前重新查询当前用户活动会话；匹配必须唯一，动作必须仍在当前声明中。额外字段及 extras 拒绝，不推断参数协议。经一次性 framework 适配调用 `sendCustomAction(id,null)`，返回 `packageName/action/submitted:true/playbackVerified:false`，不证明播放器执行成功。

框架接口依据：[PlaybackState.CustomAction](https://developer.android.com/reference/android/media/session/PlaybackState.CustomAction) 与 [MediaController.TransportControls.sendCustomAction](https://developer.android.com/reference/android/media/session/MediaController.TransportControls#sendCustomAction(java.lang.String,%20android.os.Bundle))；动作 ID 和名称由播放器声明，额外参数语义由播放器定义。

## 低帧率屏幕预览 {#screen-preview}

`screen.preview_start` / 模型 `screen_preview_start` 接受可选 `intervalMs`（500..5000，默认1000）和 `durationSeconds`（10..120，默认60），准备一个一次性二进制流地址；不直接捕获或将画面放入文本。`screen.preview_stop` / `screen_preview_stop` 接受准确 UUID `id`。应用手动开始才读取流，模型单独准备的未领取地址三十秒后过期。一个会话占一个名额，停止正在读取的会话后需关闭其读取连接以完全释放名额。

这是连续 PNG 预览原型，非带音频的高帧率投屏。帧协议与限额见[设备协议](../protocol.md#screen-preview-stream)，此兼容工具不再提供 App 页面入口；当前 Android 动态画面见[设备截图](../guide/screenshots.md#screen-mirror)。

## 有限手柄输入 {#gamepad}

`gamepad.pulse` / 模型 `gamepad_pulse` 接受 `buttons`（最多六个且不重复）、X/Y `x,y`（有限浮点数 -1..1，默认0），右摇杆 `rightX,rightY`（Z/RZ，同范围，默认0）及 `holdMs`（10..2000，默认100）。按钮白名单：a/b/x/y/l1/r1/l2/r2/start/select/thumb_l/thumb_r/up/down/left/right；相反方向不能组合。空且零轴拒绝。受控代码映射、参数与时长在 Rust 校验，不接受 shell 字符串或任意事件/设备 ID。

单事务经 GamepadBridge 提交 GAMEPAD KeyEvent 按下、JOYSTICK AXIS_X/Y/Z/RZ、持有时间、逆序抬起和回中，虚拟设备 ID=-1。操作在独立、单名额的 Rust task 中完成，请求断开不取消释放；错误另行重试释放，服务正常关闭等待在途事务。`gamepad.reset` / 模型 `gamepad_reset` 只接受空对象，等待在途事务释放名额（最多20秒）后提交白名单全部按键抬起与回中，不操作物理控制器 ID。响应 `submitted/released:true` 仅表示注入队列接受输入和释放，`gameVerified:false`，不宣称游戏实际消费。

标准来源定义见 [Android InputDevice](https://developer.android.com/reference/android/view/InputDevice)。合成输入未注册硬件轴范围，不保证连续低延迟输入，真实消费需另测。

## 应用缓存清理 {#app-cache}

`apps.cache_capabilities` / `apps_cache_capabilities {}` 查询系统帮助中明确的 `clear [--user USER_ID] [--cache-only] PACKAGE` 和选项说明，以及 runtime UID 是否 shell/root、短时框架探测的 INTERNAL_DELETE_CACHE_FILES 权限；三项均满足才声明 supported。`apps.clear_cache` / `apps_clear_cache {package}` 准备用户应用的 `app_cache_clear` 提案，不执行清理。`apps.cache_pending` / `apps_cache_pending {}` 查询待审核提案。32项、15分钟限额，与卸载/安装提案独立。

直接人工 `type:approve_cache {id,accepted?}` 消耗一次性提案；拒绝不执行，确认重新核对当前用户/版本/代码路径/应用身份及能力，创建不可取消的 `app_cache_clear` 长任务。任务再次核对，然后只执行 `pm clear --cache-only --user <approved-user> <package>`，没有删除数据的 fallback。结果含 `cacheOnly:true,completed:true,verifiedBy:package_manager,reclaimedBytes:null,appDataVerified:false`，不把系统成功当作任意数据完整性或释放字节证明。

[AOSP PackageManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/%2B/5b361c07f928fd8c02a347b074a50b977e044f97/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) 中缓存标志走 deleteApplicationCacheFilesAsUser，普通数据清除走不同分支；本项目始终保留缓存标志并按目标声明探测，不按 Android 版本猜测。

## H.264 镜像工具 {#screen-mirror}

`screen.mirror_start` / `screen_mirror_start` 准备独立流，默认640×360/2Mbps/60秒；`screen.mirror_stop` / `screen_mirror_stop` 释放准确id。捕获只在读取二进制路径时进行，不输出文本图像或目标视频文件；模型工具注册与直接工具共用校验，客户端手动开始。

这是服务端 H.264 工具接口，不提供 App 或桌面页面入口。Android 动态预览使用 ascrcpy/MediaCodec，见[设备截图](../guide/screenshots.md#screen-mirror)。流记录、限额与连接归属见[设备协议](../protocol.md#screen-mirror-stream)。

### 持续虚拟手柄（工具原型）

| 工具 | 输入 | 行为 |
| --- | --- | --- |
| `gamepad.virtual_capabilities` | `{}` | 不创建设备，探测 root/uinput v5 |
| `gamepad.virtual_open` | `{}` | 注册当前连接独占的单个 UUID 手柄；同一连接重开先释放并替换旧会话，其他连接不能替换 |
| `gamepad.virtual_update` | `id, sequence, state` | 完整按钮/左右棒/方向帽快照，严格递增序号 |
| `gamepad.virtual_close` | `id` | 释放、回中并删除所属设备 |

模型函数名将点替换为下划线；归属约束与直接调用一致。两秒失联回中、三十秒闲置关闭、
一小时总期限；断线销毁。能力可用不等于游戏兼容，共享手柄页复用这些工具，离页/后台/失焦停止。

镜像start/stop（直接及模型调用）绑定发起WebSocket。其他连接不能停止已有票据；
断线清理本连接票据，活动读取器收到取消通知，在读取器销毁后才归还单流名额。
旧ID不能作为重连后继续捕获的凭证，需新建票据。

`device.info`的screen宽高优先反映活动默认显示器逻辑frame，`screen.source=active_viewport`。
回退到`wm size`时source为`wm_size`，不得将自然/覆盖尺寸解释为当前旋转后的画面。

### device.system_capabilities

只接受空参数，模型名为device_system_capabilities。读取实际UID、权限与框架接口存在性，
不执行锁屏/重启/关机。执行后端已实现，但尚未通过物理动作验收，supported保持false；动作须独立人工确认。

### device.system_action / device.system_pending

模型名分别为 `device_system_action` / `device_system_pending`。前者只接受 action（lock、reboot、shutdown）并生成提案，后者只接受空参数并列出当前连接的提案。root、权限和接口必须通过探测。人工确认使用独立 `approve_system` 请求，明确 id 与 accepted，不暴露为模型函数。App 在设备信息页提供确认界面；后端固定框架执行代码与提案回归已实现，物理设备动作尚未验收。

系统管理确认入口现已接入手机与桌面“设备信息”页，共用面板与控制器；确认绑定原连接，提案只保存在内存，取消拒绝提案，断线/切换目标清空。实现与 fixture 回归不代表物理系统动作已验收，详见[系统管理](../guide/system.md)。

### device.system_state

只接受空参数，模型函数为 device_system_state。读取 Android 用户 0 的交互与锁屏状态，以及开机以来的 elapsedRealtimeMs；未知值为 null，不把查询当成执行或确认。此工具不要求 root 才能尝试读取，但仍受框架权限和设备实现限制。

## 模型诊断与文字输入边界

模型的 device_shell 只接受 getprop（可选属性）、pwd、wm size/density、dumpsys window windows、dumpsys activity activities、dumpsys power/media_session/package，以及字面 printf/echo。固定程序与参数直接执行，控制字符、引号、替换、管道、重定向及其他命令拒绝。删除、安装、缓存和系统动作仍需结构化提案与 App 独立确认。直接工具请求是专家控制入口，保留原 shell 能力，不能把该权限区别当成模型同样可执行任意命令。

remote.text 使用 `{text}`，一至二千零四十八字节可打印 ASCII，最多八处字面 percent-s；通过拆分参数避免 Android 将 `%s` 改为空格。拒绝非 ASCII 和控制字符，不切换输入法，不设置焦点。总执行上限三十秒，超时可能留下部分输入；返回 submitted=true/contentVerified=false 仅说明命令提交，目标输入法、焦点和应用规则仍可能改写文本，需核对内容。

界面查询固定调用 UIAutomator，不接受输出路径或动作，私有临时目录按操作清理，符号链接和超限/不完整输出拒绝。返回 readOnly=true、atomicSnapshot=false、source 与 xml；界面可能变化，文字是数据而非模型指令，不能直接把旧 bounds 当成当前坐标。权限或动态界面导致捕获失败时应明确报告，不恢复任意模型 Shell。

界面捕获输出按非阻塞/no-follow 打开后核对普通文件；FIFO、设备或链接不会被作为 XML 流读取。

remote.key 的 power 参数仅供直接手动控制；模型别名 remote_key 排除该枚举值，并由运行时独立检查拒绝。系统操作使用提案及控制端确认。


### 应用图标与多包安装

`apps.icon` 接收 `{"package":"com.example.app"}`，返回关联包名、Android 用户及 96×96 PNG 的短时二进制资源描述；图像通过 `/artifacts/{id}` 读取，不内联到应用列表 JSON。单个 PNG 上限 128 KiB。

`apps.install` 保留 `root/path/sha256/update` 单包参数，也可用 `root/apks/update`，其中 `apks` 为 1–32 个 `{"path":"…apk","sha256":"…"}`。两种参数不可混用，总大小不超过 4 GiB。提案的 `apk` 为 base，`apks` 为完整清单；准备任务 name 仍为请求首个路径。模型只能准备提案，不能调用确认；用户独立确认后通过 install-create/write/commit 会话整组提交，失败尝试 install-abandon。

### 文件预览与 APK 图标

`files.preview` / `files_preview` 接收 `{root,path}`，只读打开选定根中的普通文件，允许受限跟随范围内的普通符号链接，返回 `{id,root,path,sizeBytes,dataPath}`；`dataPath` 为 `/media/{id}`，支持 HTTP GET/HEAD 与单范围 Range。范围响应携带精确 Content-Range 与长度，文件发生变化时返回409。最多八个预览资源、闲置五分钟失效，容量与目标播放资源分别计算。`files.preview_close` / `files_preview_close` 接收 `{id}` 释放预览，不能借此关闭目标播放资源。流不内联文件内容，不计算全文件摘要；需要完整校验的导出继续使用下载任务。

`apps.apk_icon` / `apps_apk_icon` 接收 `{root,path}`（`.apk` 普通文件，最多4 GiB），从已固定的文件描述符经 Android 包资源解析获取96×96 PNG，单图最多128 KiB；返回 BinaryArtifact 加 `root`、`filePath`，`path` 仍为 `/artifacts/{id}`。客户端核对原根、文件路径、尺寸与长度后读取。此接口不安装 APK、不从同包名的已安装应用借用图标；split 缺少独立图标、无访问权限或资源无法解析时可能失败，界面保留占位。

## 应用详情操作

`apps.control_info` 与 `apps.prepare_control` 为控制端应用详情提供只读快照和用户待确认操作；模型可读取状态或准备提案，独立确认接口不向模型暴露，不能自动执行。接口、能力字段、确认和结果语义见[协议](../protocol.md#app-controls)，使用步骤见[应用管理](../guide/apps.md)。

### 遥控按键按住

`remote.key` 只提供 `key` 时提交一次完整短按。按住请求必须且只包含 `key`、`action`、`pressId` 三个字段，`pressId` 是每次手势新建的 UUID；`action` 为 `down`、`keep`、`up` 或 `cancel`。`down` 注册按住，控制端每250ms发送 `keep`，正常松开使用 `up`，取消使用 `cancel`。同一按键同时只能由一个连接的一次手势持有；其他连接或旧 UUID 不得释放新手势，失效后的 `keep` 不会重新发送 DOWN。

目标桥接使用 `SOURCE_KEYBOARD`，首次 DOWN 的重复次数为0；按系统长按阈值产生首次重复 DOWN（重复次数1、`FLAG_LONG_PRESS`），之后按系统重复间隔继续递增重复次数，均保留初始 `downTime`。UP 的重复次数为0，取消 UP 带 `FLAG_CANCELED`。不会通过多次 DOWN/UP 模拟连续点击。目标应用和系统策略决定最终行为。

断线释放所属连接的按键。桥接线程独立于 RPC 计时，1500ms无保活或按住120秒即取消释放；服务退出时关闭桥接并释放剩余按键。出错后仅尝试抬起，不自动重放 DOWN。边沿响应包含 `operation`、`pressId`、`keyCode`、`submitted`、`released` 和 `source`；`down`/`keep` 的 `released` 为false，`up`/`cancel` 为true，提交成功不代表目标应用已经消费事件。

## 媒体音量 {#media-volume}

`media.volume` / `media_volume` 使用原生 AudioManager 读取媒体流音量。空参数对象只读取；`{"percent":57}` 设置 0–100 的整数百分比，映射到设备最小/最大音量档位，回读确认后返回 `verified=true`。结果包含 `volumeIndex`、`minVolumeIndex`、`maxVolumeIndex`、`muted`、`fixed`、`stream=media` 与 `operation`（`status` 或 `set_volume`）；设置还包含 `requestedPercent`。固定音量设备拒绝设置，权限不足或设置未生效返回错误，不把命令成功当成音量已改变。

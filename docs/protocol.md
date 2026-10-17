# 设备端协议 v1

App 与 runtime 按同一版本配套分发。目标文件与待部署版本不一致时升级目标程序，不以历史二进制兼容作为继续保留旧文件的依据。

## 发现

目标端发布 `_airc._tcp.local.`，Android NSD 发现类型 `_airc._tcp.`。TXT 包含：

| 字段 | 内容 |
| --- | --- |
| id | 保存在目标状态目录的 UUID，重启和更新保持不变 |
| name | `ro.product.model` 或默认设备名称 |
| protocol | `1` |
| version | 设备程序版本 |
| path | `/ws` |

当前 HTTP/WebSocket 监听 IPv4，因此广播仅发布可通过 IPv4 访问的地址。无线 ADB 的 `_adb-tls-pairing._tcp.`、`_adb-tls-connect._tcp.` 属于独立的安装通道，与遥控器设备发现无关。

配对码连接默认按 GUID 发现 TLS 连接服务，也可指定无线调试首页的连接端口；指定时直接连接该端口。历史重连仍优先发现当前端口，找不到时才尝试保存的旧端点。

## HTTP

`GET /status` 返回 version、protocol、deviceId、name、tools、modelConfigured、uid（进程有效用户 ID，`0` 为 root，`2000` 为 Android shell）；不返回模型密钥。

## WebSocket

连接 `/ws`，文本帧的请求和响应通过 id 关联。

控制端在 HTTP 状态查询和 WebSocket 握手中通过 `Accept-Language` 传递 App 语言：Android 使用当前 App 资源语言，桌面使用当前系统语言。中文语言标签（含 `zh-CN`、`zh-TW`、`zh-Hans`）使用中文，其余控制端语言使用英文。服务支持按标准优先权选择 `zh` 或 `en`，缺少或不支持的语言使用英文。语言绑定到该 WebSocket 连接，多控制端之间互不影响；App 语言变化后重新连接生效。

设备端按语言返回请求错误，手柄 uinput 版本、root 权限等常见错误有对应中英文文案。`gamepad.virtual_capabilities`、`device.system_capabilities`、`device.system_state` 的结构化 `reason` / `error` 也按连接语言显示。未知平台错误在中文说明后保留原始诊断；协议字段、文件内容、路径、工具标识和模型回答不做替换。

```json
{"id":"request-1","protocol":1,"type":"tool","tool":"remote.key","arguments":{"key":"up"}}
```

```json
{"id":"request-1","protocol":1,"ok":true,"result":"","error":null}
```

| type | 字段与行为 |
| --- | --- |
| tool | tool + arguments；直接调用 `remote.key`、`apps.list`、`apps.open`、`device.info`、`device.shell` |
| text | text；由模型决定工具调用，执行结果回填模型，最多 16 轮 |
| configure | arguments 包含 baseUrl、apiKey、model；保存目标端配置 |

### 可选进度事件

请求可添加 `"progress":true`。新版服务在 `text` 执行期间发送同 id 的事件：

```json
{"id":"request-1","protocol":1,"type":"assistant.state","phase":"thinking","text":""}
```

tool 阶段可附带可选 `tool` 字段，例如 `"tool":"files.mkdir"`，表示准备尝试的已注册工具，不表示执行成功。该事件不包含工具参数、结果或 API Key；tool 阶段的 text 保持为空。该字段未提供时按空值处理。

phase 为 thinking 或 tool，text 可为空。最终仍返回原有 ok/result/error 响应。事件不是最终响应，不能完成 pending request；未知事件应忽略。进度事件为尽力发送，不代替最终响应。未请求 progress 的客户端只收到最终响应。中间事件缺失时继续等待最终响应，不伪造思考或工具执行阶段。

当前版本仅支持文字与工具请求，不再接受 audio.start / audio.stop / audio.cancel 或二进制音频。旧模型配置中的 transcriptionModel 字段读取时忽略，保存时移除；文字配置与现有设备记录保持兼容。

工具命令以独立参数执行；只有明确的 `device.shell` 工具接受 shell 文本。遥控按键支持 up/down/left/right/ok/back/home/menu/power/volume_up/volume_down/mute/play_pause/media_previous/media_next。应用启动参数为 `package`，shell 参数为 `command`。

每条工具命令最长 30 秒，每路输出最多 256 KiB；整体请求最长 170 秒。模型执行期间 WebSocket 仍处理心跳。请求缓冲有限制，防止长任务阻塞连接或无限积累内存。

`remote.key`、其他直接 `tool`/确认操作、文字/配置请求分别使用独立 FIFO 队列。模型 HTTP 请求或普通工具执行期间仍可执行遥控按键；文字决策共用会话历史并串行执行。响应可能跨队列交错，客户端必须按 id 匹配。断开连接时取消尚未开始的输入；已开始的按下/抬起事务独立完成释放，其他工作队列及在途执行按原生命周期取消。

每连接的三个请求队列各最多等待 16 条；所有连接共用的输入执行器另有最多 16 条的有界等待队列，每个连接最多一条在途按键进入该执行器。队列已满时，对当前新请求返回相同 id 的 `ok:false` 与“操作队列已满，请等待已有操作完成后重试”（英文连接返回对应英文错误），不关闭 WebSocket、不取消已接受的操作。拒绝响应可能先于较早请求的完成返回；客户端不得自动重试设备动作。收发循环继续处理 ping/pong，队列恢复后同一连接可继续提交。

`remote.key` 的结果是 JSON 字符串，包含 `operation:key,keyCode,submitted,released,source,bridgePid,injectionMicros`。`source=257` 保持普通键盘输入来源；`submitted/released` 表示 Android 接受了按下和抬起的提交，不表示前台应用已消费；`injectionMicros` 为输入执行器与桥接一次事务的耗时，排除网络往返和排队。同一常驻桥接进程串行处理所有普通按键。回执丢失或子进程异常时，结果明确为不确定；恢复只补发待确认键的抬起，不重放按下。释放未确认前不接受下一次按下注入。

## 后台管理

```sh
airc-runtime service start --state-dir /data/local/tmp/airc/state --port 9876
airc-runtime service status --state-dir /data/local/tmp/airc/state
airc-runtime service stop --state-dir /data/local/tmp/airc/state
```

后台启动使用新 session 并脱离 ADB 的标准输入/输出，日志写入私有状态目录。管理操作通过文件锁串行化。进程记录包含 PID、`/proc` 启动时间和实际可执行文件路径；停止前核对身份，避免误杀复用 PID 的其他程序。

安装器按照目标 ABI 列表选择资产，验证文件的大小与 SHA-256，并比较目标文件摘要。摘要和运行权限均相同时不推送或重启；摘要或 root/shell 权限变化时推送到 `.next`，核验后停止原服务并原子替换。

候选先执行 `--version`，与清单不一致时保留原服务。提交脚本备份旧程序、记住原服务端口及实际运行用户，替换后启动候选并等待 ready；失败时恢复旧文件，原服务此前运行则以原权限在原端口重新启动。成功后移除备份。提交阶段暂缓协程取消，避免停掉旧程序后手机直接退出；脚本也对 EXIT/HUP/INT/TERM 注册恢复处理。ADB 断链、SIGKILL 或断电可能中断恢复处理，需要重新核对目标文件与服务状态。

App 的 root 开关默认关闭；开启后优先检测当前 root adbd，其次验证 `su`，不可用时回退 shell。部署和管理命令按已验证权限执行；root 服务通过 `.root-mode` 标记供后续提权管理，关闭时停止旧服务后恢复 shell 所有权，不放宽状态文件权限。详细设置与切换限制见[连接设备](guide/connections.md)。

发布源默认为 APK 内资产，服务管理从项目 GitHub 仓库最新 Release 获取在线 HTTPS 清单。在线清单沿用 version/protocol/assets，并支持 minAppVersionCode、maxAppVersionCode；每个资产增加 url。清单 URL 的路径增加 `.sig` 后缀为原始二进制签名文件（保留查询参数），使用 APK 内固定公钥和 SHA256withRSA 验证清单原始字节，验签后才解析与使用资产 URL/摘要。缺少签名、错误身份、畸形签名或内容篡改均拒绝，不回退到未签名发布。

在线下载按 SHA-256 缓存，缓存命中时再次校验大小与摘要，不保存未验证或部分文件。签名覆盖版本、协议、应用兼容范围及所有 ABI 的资产 URL/大小/摘要；私钥不打包进应用。

在线查询和下载支持协程取消：直接关闭在途 HTTP 请求，清除未完成的下载文件并释放下载锁；等待响应头或数据读取不会继续等待正常网络超时。

`device.info` 使用空参数对象，成功时 `result` 包含 JSON 编码的只读设备信息。字段及缺失值规则见[工具参考](reference/tools.md#device-info)。原有响应 envelope 和协议版本保持兼容。

`apps.list` 成功响应的 `result` 现在包含 JSON 编码的 `apps` 数组，字段见[应用列表](reference/tools.md#apps-list)。调用方按结构化 JSON 字段解码；响应 envelope 保持不变。

坐标工具 `remote.tap`、`remote.swipe`、`remote.long_press` 采用归一化坐标，直接调用和模型函数共用校验与注入层，详见[坐标输入](reference/tools.md#gestures)。`remote.key` 增加数字、频道和删除键枚举，旧按键语义不变。

`remote.tap` 新增可选 `count`（1/2，默认 1）支持双击，旧调用方未传时语义不变。控制端触控板根据 `/status.tools` 决定启用状态；数字键盘使用现有 `remote.key` 枚举。

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

`files.list` / `files_list` 与 `files.stat` / `files_stat` 接收 `root`（`downloads` 默认、`inbox` 服务私有收件箱、`full` 文件系统 `/`）及相对 `path`。拒绝绝对路径、`..` 和符号链接穿越；权限沿用服务身份。列表额外接收 `limit`（1–100，默认 50）与 `cursor`；返回 root/path/entries/nextCursor，按文件名排序，最多扫描 4096 项。stat 返回 kind 与普通文件 sizeBytes。

`task.start` / `task_start` 的 `direction=download` 配合 root/path 创建流式下载，轮询 `task.progress` / `task_progress` 等待 ready，读取其 sha256 和 `dataPath=/transfers/{id}/data`，随后 GET 该路径。上传使用 direction=upload、sizeBytes、sha256，再 PUT 原始二进制到 dataPath。GET/PUT 的 MIME 为 application/octet-stream，禁止缓存；固定 64 KiB 缓冲，单文件最大 1 TiB。上传长度/摘要必须匹配，fsync 后原子提交；默认 RENAME_NOREPLACE，不支持时明确失败。最多两个活动任务，最多保留 16 条记录；终态记录可提前淘汰，空闲记录在新任务时按 15 分钟清理。任务不跨进程重启恢复。

`task.progress` 返回 id/direction/root/name/sizeBytes/bytesTransferred/phase/sha256/error/unit/dataPath。phase 包含 hashing、ready、awaiting_upload、uploading、downloading、committing、deleting、completed、failed、cancelled。unit 通常是 bytes，删除任务是 items。`task.cancel` / `task_cancel` 与 `DELETE /transfers/{id}` 可取消未提交任务，已提交文件保留；递归删除的已删除项无法恢复。


`files.mkdir` / `files_mkdir` 接收 `root`（downloads/inbox/full）及必填相对 `path`，创建单个新目录并返回 `{root,path,created:true}`。父目录必须存在；不创建父目录、不覆盖文件/目录/链接、不跟随符号链接，不接受递归或权限参数。使用 mkdirat 按打开的父目录执行，权限沿用服务身份，新目录请求模式为 0700，实际模式由文件系统策略决定。

`files.download_url` / `files_download_url` 接收必填 HTTP(S) `url`，可选 `root`（默认 downloads）、相对目录 `directory`（默认空）与 `name`（默认网址末段，无末段时 download.bin）。不允许嵌入用户名/密码，仅跟随 HTTP(S) 重定向，最多十次。复用文件管理的 no-follow 路径访问与上传临时文件；优先原子不覆盖重命名，存储卡不支持时在下载完成后独占创建目标并流式复制，仍不覆盖同名文件。复制保存阶段可能暂时显示未完成目标文件，异常退出可能留下该文件；常规失败会清理。返回 `direction=url_download` 的后台任务，使用 `task.progress`/`task.cancel` 查询或取消；阶段为 connecting、downloading、committing、completed、failed、cancelled。`bytesTransferred` 为已写字节，`sizeBytes=0`，实际总长度从 `result.totalBytes` 读取（未知时 null），`result.path` 为目标相对路径。完成时 totalBytes 为最终字节数并提供 SHA-256。最多 1 TiB，与其他传输共享活动任务限额；错误或取消清理临时文件，不覆盖现有文件，无断点续传或重启恢复。

`files.delete` / `files_delete` 接收 root/path/recursive，仅创建待确认提案，不立即删除。`files.prepare_overwrite` / `files_prepare_overwrite` 接收 root/path，返回覆盖提案。提案含 id/kind/root/path/exists/directory/sizeBytes/recursive/approvalRequired，15 分钟过期，最多 32 项。`files.pending` / `files_pending` 可查询尚未确认提案。控制端必须核对目标并让用户确认，再发送独立请求 `type=approve, arguments={id}`；`{id,accepted:false}` 丢弃提案。模型工具没有 approve。删除确认返回 task 描述；覆盖确认返回单次 token，随后 task.start 上传携带 overwriteToken。覆盖前重新核对目标信息，变化时失败；允许覆盖时使用原子替换。递归删除不跟随链接、不跨挂载点、最多 128 层；枚举子项和进入子项时均检查文件系统设备号，用户直接选中的顶层目录可以自身位于挂载点。

`files.download` / `files_download` 保留小文件快照接口，最大 16 MiB，返回短期 FileArtifact（id/path/name/mimeType/sizeBytes/sha256/expiresInSeconds）。GET `/artifacts/{id}` 下载，60 秒过期，与截图共享四项资源容量；大文件应使用 task.start。界面所有文件下载均采用流式任务。详见[目标文件](guide/files.md)。

## 媒体打开工具

`media.players` / `media_players` 接收 root/path 与可选 mimeType，读取文件属性并查询目标 ACTION_VIEW 处理器。返回 root/path/mimeType/transport/packages；Downloads 使用 file URI，inbox/full 使用目标 loopback HTTP 地址。排除 frameworkpackagestubs 占位处理器。`media.open` / `media_open` 使用同样参数与可选 package（必须来自查询结果）启动播放器；返回 launched、playbackVerified=false、可选 resourceId 和 Android 启动结果。它不证明解码/播放成功，不接受任意外部 URI 或 shell 参数。

`media.release` / `media_release` 接收 resource id，撤销 HTTP 资源及其在途读取，不删除源文件，也不向播放器注入停止命令。GET/HEAD `/media/{id}` 提供真实媒体 MIME、Accept-Ranges、Content-Length、no-store；单段 bytes 范围返回 206 与 Content-Range，不可满足的范围返回 416，多段或畸形范围回退完整响应。If-Range 因没有提供验证器而回退完整响应。分段语义参考 [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#name-range)。资源固定文件描述符，逐块核对文件元数据，文件改变返回 409 或中止流；读缓冲 64 KiB，最大文件 1 TiB。最多四份资源、每份四个读取、24 小时闲置过期，重启不恢复。

## 本地媒体发布

`media.publish` / `media_publish` 接收 root/path，为本地播放器创建服务拥有的 Downloads 副本，返回长任务描述。`task.progress` 终态 completed 的 result 为 `{id,root:"downloads",path,sizeBytes,sha256}`。通过 `media.open` 打开该路径，使用 `media.release` 删除该 id 对应副本。原文件保留。`media.players` 可额外选择 transport=local-copy，仅查询未来 file URI 的处理器，不创建副本；查询返回 sizeBytes。

发布采用独立 UUID 目录、O_EXCL 创建文件、64 KiB 缓冲、源变化检测、fsync 和副本 SHA-256 回读校验；未完成文件不会发送给播放器，失败或取消按创建时的 inode 清理本次文件，不覆盖或递归删除用户路径。`.nomedia` 限制媒体扫描，但不会隐藏文件系统访问。最多四份准备/保留副本，长任务共用两个活动名额，保留 16 MiB 存储余量。task.progress 增加可选 result，方向 media_publish、阶段 publishing；单位仍为 bytes。副本记录持久化在服务私有 media-publications 目录，记录 UUID、目录/文件 dev+inode、创建时间及完成结果，每次写入采用原子替换、0600 权限及 fsync。进程启动时恢复未过期的完成副本，清理有记录的中断/过期副本；身份不匹配或损坏记录保留并报告。`media.publications` / `media_publications` 查询恢复及当前保留的副本，`media.release` 逐项释放。任务本身和 HTTP 媒体资源仍不跨重启恢复。

## 应用管理确认

`apps.info` / `apps_info` 查询当前用户应用信息；`apps.uninstall` / `apps_uninstall` 只生成提案，`apps.pending` / `apps_pending` 列出尚未确认的提案。详细字段与限制见[工具参考](reference/tools.md#app-uninstall)。

控制端核对包名、版本、用户和数据删除影响后，发送 `{"id":"request-id","protocol":1,"type":"approve_app","arguments":{"id":"proposal-id"}}`。accepted=false 拒绝。确认请求不作为模型函数暴露，提案只能使用一次。成功提交返回后台任务，task.progress 返回 committing/completed/failed；提交后不支持 task.cancel。失败可能包含包管理器超时，不能据此推断应用数据未改变，应重新查询状态。

## APK 元信息

`apps.apk_info` / `apps_apk_info` 对目标 root/path 指定的 APK 启动只读解析长任务，通过 task.progress 读取进度与最终 result。结果见[工具参考](reference/tools.md#apk-info)。它不生成安装提案或提交安装；signatureVerified 始终 false，指纹只表示读取到的声明证书，不能代表签名有效。

## APK 安装确认

apps.install / apps_install 准备匿名 APK 副本与人工确认提案，apps.install_pending / apps_install_pending 列出提案。准备返回 direction=apk_prepare、phase=preparing 的任务，支持 task.cancel；完成 result 的结构见[安装工具](reference/tools.md#apk-install)。

用户核对包名、版本、用户、摘要、签名声明和覆盖影响后发送 `{"id":"request","protocol":1,"type":"approve_install","arguments":{"id":"proposal-id"}}`。accepted=false 拒绝。接受后返回 direction=app_install 的 committing 任务，task.progress 读取结果，提交后 task.cancel 不取消安装。签名有效性由包管理器在提交时验证；声明信息解析与安装验证保持区别。

## 存储分析长任务

storage.analyze / storage_analyze 对所选目录启动只读 storage_scan 任务，task.progress 读取项数进度与最终 result，task.cancel 取消。sizeBytes 是扫描项数上限，unit=items；报告的逻辑字节和文件块字段见[工具参考](reference/tools.md#storage-analysis)。未识别应用缓存，也不返回可回收空间或隐式执行删除。

## 标准媒体定位

media.status 的标准桥接结果增加 seekAvailable/source/userId 与 sessionTag，保留原 sessions 字段兼容旧客户端。桥接失败回退 dumpsys，明确 seekAvailable=false。media.seek / media_seek 使用 package、positionMs 与可选 sessionTag 提交绝对定位，请求响应的 submitted 与 playbackVerified=false 不等于播放器已完成跳转。详细限制见[工具参考](reference/tools.md#media-seek)。

## 应用标签来源

apps.list 保持 apps 数组兼容旧控制端，增加 source、labelsAvailable、userId 与 labelErrors。标准包管理器读取失败回退 pm，未知 label=null；完整字段与限额见[工具参考](reference/tools.md#app-labels)。

标准媒体会话可包含 `customActions:[{id,name}]`；顶层 `customActionAvailable` 表示标准动作通道可用。直接工具 `media.custom_action` 及模型 `media_custom_action` 仅接受当前活动会话声明的动作 ID，执行时重新核对会话唯一性和声明；不传私有 Bundle。响应 `submitted:true` 只表示送出请求，`playbackVerified:false` 保留；界面刷新会话并要求核对实际结果。

## 低帧率预览流 v1 {#screen-preview-stream}

`screen.preview_start` 返回一次性 `{id,path,mimeType:"application/x-airc-preview",format:"airc-png-v1",intervalMs,durationSeconds,claimWithinSeconds:30,maxFrameBytes:16777216}`。通过 `GET /preview/{id}` 在三十秒内领取一次；后续领取返回 409 或已释放后的 404。只有 HTTP 读取时捕获。预览与截图共用捕获锁；全局一个待领取或读取中的会话。`screen.preview_stop {id}` 停止指定会话，未知 UUID 返回 `stopped:false`，不停止其他媒体。

流首部为 ASCII `AIRCPRV1`，后续每条记录是大端 `sequence:u32,width:u32,height:u32,length:u32,elapsedMs:u64`（24 字节），紧跟 length 字节的 PNG。序号从 1 开始递增；elapsedMs 是服务端本次流的单调经过时间，每帧尺寸以当前截图为准。宽高为零时，length=0 表示正常结束，length>0 是最多 1024 字节的 UTF-8 错误。结束记录序号为已经生成的帧数，截断不算正常结束。HTTP 无整流 Content-Length，带 no-store/nosniff；不通过 WebSocket 传帧或 Base64。

单帧最多 16 MiB、32M 像素，边长最多 16384；间隔 500..5000 毫秒、时长 10..120 秒。无历史帧队列，不追赶补帧；实际间隔包含捕获和消费耗时。捕获失败终止流；停止/断开取消命令进程组并释放读取资源。恶意或停滞的读取者可能延迟资源释放，需关闭旧读取连接再开始；服务重启清空所有会话，无自动续流。

`gamepad.pulse {buttons?,x?,y?,rightX?,rightY?,holdMs?}` 是 10..2000 毫秒的有限输入事务，默认100，按钮最多六个、轴值 -1..1。`gamepad.reset {}` 提交全部受控合成按键抬起与零轴。工具响应包含 `operation:pulse|reset,submitted,released,sourceGamepad:1025,sourceJoystick:16777232,elapsedMs,gameVerified:false`；released 只确认系统队列接受释放。请求中止不取消已接受的在途事务，按钮/轴状态不落盘。输入拒绝或释放失败返回错误；服务强制终止不保证释放。详见[手柄工具](reference/tools.md#gamepad)。

应用缓存清理为独立提案/确认流程：`apps.clear_cache {package}` 返回 `{id,kind:app_cache_clear,app,approvalRequired:true,cacheOnly:true,mode:package_manager_cache_only}`；模型只能准备或查询 `apps.cache_pending`。人工 `approve_cache` 拒绝或提交不可取消的缓存长任务。`apps.cache_capabilities` 只读探测；未明确声明 cache-only、缺少 INTERNAL_DELETE_CACHE_FILES 或 UID 不适用时拒绝，不执行数据清除 fallback。任务结果不提供回收字节，不宣称全面的数据保留验证。

## AVC 镜像流 v1 {#screen-mirror-stream}

`screen.mirror_start` 接受width/height/bitrate/durationSeconds，返回一次性id与`/mirror/{id}`，mimeType `application/x-airc-avc`、format `airc-avc-v1`、请求尺寸/码率/期限、claimWithinSeconds=30、maxChunkBytes=65536、maxStreamBytes=134217728、audio=false。单会话单读取者，与截图共用捕获锁。准备只保留名额，HTTP领取才启动固定screenrecord命令。宽/高160..1280/90..1280且为偶数，像素量≤921600；码率250000..8000000，期限10..120秒。

流首部ASCII `AIRCMIR1`，记录是大端kind:u32,length:u32，再紧跟length字节。kind=1为1..65536字节的Annex-B H.264数据，kind=0/length=0为正常结束，kind=2为≤1024字节UTF-8错误。网络EOF不替代结束记录；错误/截断不报告正常完成。总编码数据≤128MiB，固定缓冲/backpressure，无帧历史队列或Base64/WebSocket图像。`screen.mirror_stop {id}`取消指定会话；HTTP关闭/超时释放编码进程组与捕获锁，不恢复旧流。

## 持续虚拟手柄会话原型

`gamepad.virtual_capabilities {}` 返回 root/uinput 支持与原因；
`gamepad.virtual_open {}` 返回 `id`、`deviceName`、轴映射和超时。
`gamepad.virtual_update {"id":"会话 UUID","sequence":1,"state":{"buttons":["a"],"leftX":0.5,"rightY":-1,"hatX":1}}`
提交完整快照，棒轴 -1..1、帽轴整数 -1/0/1，缺省归零。
`gamepad.virtual_close {"id":"会话 UUID"}` 释放并销毁。

会话绑定发起 WebSocket，序号为 1..4294967295 且严格递增。
两秒无更新尝试回中，三十秒闲置关闭，一小时总期限；断线自动销毁。
`submitted`/`registered` 表示设备写入/注册，`gameVerified:false` 不表示游戏消费成功。
操作仅使用固定 uinput，共享手柄页提供手动开始/停止与按住/松开交互。

H.264镜像票据绑定创建它的WebSocket连接。`screen.mirror_stop`须从同一连接提交；
另一连接即使知道ID也会拒绝。连接断开移除未领取票据并通知活动读取器取消，
活动读取器销毁前仍占用单流槽位，避免同时启动两个编码器。
HTTP仍使用票据的随机路径读取，未更改二进制帧格式；重连需要新建票据。

H.264生产者独立于HTTP body轮询运行，内部队列最多两个固定大小记录（另有一个工作块）。
断线/停止取消可打断等待发送；等待发送也受编码期限加五秒余量限制。
body销毁会中止生产者并释放编码器/捕获许可。背压取消可能无法发送结束记录，
接收端必须继续按原规则将缺少结束记录报告为中断，不能当作完整录制。

`device.info.screen`增加可选`source`：优先使用`active_viewport`，尺寸来自当前活动默认显示器的逻辑frame，
与触控坐标换算一致；无法读取时回退`wm_size`，该值是自然/覆盖尺寸，不能据此保证当前旋转方向。
source 未提供时客户端保留 null，宽高字段按响应读取。

## 系统管理能力探测

`device.system_capabilities {}` 为只读工具，返回runtimeUid/rootRuntime、DEVICE_POWER/REBOOT权限，
lockNow/reboot/shutdown框架接口是否存在与permissionProbeAvailable。
当前`executionImplemented=true`、`supported=false`、`approvalRequired=true`。执行端已实现，但尚未完成物理设备动作验收，接口存在不代表实测可用。

`device.system_action {"action":"lock|reboot|shutdown"}` 仅准备提案，`device.system_pending {}` 查看当前连接提案。提案绑定连接、120 秒过期；断线删除。独立请求 `type=approve_system` 必须明确传入 `id` 和布尔值 `accepted`，模型工具不提供确认入口。执行前重新核对 root、权限及接口，通过固定框架调用执行，不接受命令或重启原因参数。拒绝、过期及已使用提案不会执行。响应仅表示 `submitted=true`、`completed=false`；断线或超时不证明动作成功，需观察目标最终状态。
接口存在与权限检查不等于设备实际操作验收；探测不授予权限、不锁屏、不重启或关机。

系统提案的 `expiresInSeconds` 是查询时剩余有效秒数（向上取整），不是每次查询重新获得 120 秒。服务端仍以创建时间核验到期；过期确认消费提案并拒绝执行。断线只移除所属连接提案，不影响其他连接。

镜像客户端可选择连续续流，正常且已收到画面的片段结束后先调用 stop 清理，再申请全新票据，最多三十段。每段沿用原连接所有者，重新查询所选目标比例；不复用旧 HTTP 路径。异常、旋转、取消或连接变化不续接，不等同于流内旋转或自动重连。

连续续流与方向恢复都须确认旧票据清理响应包含匹配的 id 和布尔 stopped。stopped=false 可表示票据已释放；响应缺失、格式错误或请求失败时停止并提示，不创建下一段。

## 只读系统状态

`device.system_state {}` 返回 userId=0 的 Android 框架观察值：interactive、keyguardLocked、deviceLocked、deviceSecure，以及 elapsedRealtimeMs、runtimeUid、readOnly、available 和 source。字段读取失败为 null，errors 标出失败类型；框架桥接不可用时 available=false，不伪造 false。只接受空参数，不改变电源、锁屏或凭据。观察不能单独证明此前动作完成，断线也不是关机成功证据。

## 文本任务的工具范围

新版 `/status` 返回 `textToolScope: true`。该字段缺失时客户端按 false 处理；需要强制工具范围的调用方不得把未显式声明此能力的目标当作已支持。

`type: "text"` 请求可在 `arguments.allowedTools` 中传入非空的规范工具名数组，例如 `["device.info"]`。省略时保持原有注册工具集合；显式 null、空数组、非字符串、未知名称、模型下划线别名或重复项均拒绝。范围只能缩小现有白名单，不增加工具或人工确认 API。服务先过滤发给提供商的 schema，再检查每次返回的规范工具名，越界调用返回工具错误且不执行、不产生执行进度事件。

受限请求清空旧会话历史后独立开始，避免先前较宽范围的工具结果进入本次任务。范围是单次请求约束，不改变直接工具权限、模型配置或高影响动作的控制端确认机制。

## 模型 Shell 与结构化文字输入

`remote.text` / `remote_text` 接收仅含 text 的对象，提交焦点字段可打印 ASCII，返回 `{submitted:true,characters,contentVerified:false}`。长度一至二千零四十八，字面 `%s` 最多八处，非 ASCII 明确拒绝；内容校验和输入法兼容不由提交成功保证。

`device.shell` 直接请求保持专家 shell 文本语义；模型 alias device_shell 仅固定诊断白名单，直接参数执行，不调用 sh -c。模型不能用该入口删除文件、pm clear、修改显示设置或执行重启等动作，必须使用已有结构化工具和独立确认。包含后续 device.ui_hierarchy 在内，当前工具总数五十三项。

`device.ui_hierarchy` / `device_ui_hierarchy` 仅接受空对象，通过固定 UIAutomator 参数读取默认显示器当前界面，输出 `{readOnly:true,atomicSnapshot:false,source:"android_uiautomator",xml}`。最多 128 KiB UTF-8 XML，未知参数、符号链接、超限或不完整输出拒绝；不提供任意路径、输入或点击。工具数现五十三项。


## 模型工具调用组校验

提供商一次返回多条工具调用时，runtime 在记录该组调用或执行其中任何工具前，校验所有调用具有非空白字符串 ID，且组内 ID 唯一。缺失、类型错误、空白或重复 ID 均拒绝整组，以免留下无法与工具结果对应的历史。调用还必须声明 type=function、非空白 function.name 和字符串 function.arguments；缺失参数不得默认为 {} 执行。参数字符串内的 JSON 或工具参数错误仍作为对应工具结果返回。该校验不替代各工具参数、请求范围及人工确认校验。

模型提供商 message.role 必须为 assistant；tool_calls 只接受缺省、null 或数组。错误类型不能降级为正文答案，避免畸形调用消息进入会话历史。

模型 remote_key 不允许 power，即使请求 allowedTools 包含 remote.key 也拒绝该参数；模型应通过系统提案和 App 确认处理锁屏/重启/关机。直接工具 remote.key 的用户手动电源键仍保持原能力。此检查仅约束该明确的按键入口，不是目标 GUI 行为的通用权限隔离。

`GET /status` 的 `binarySha256` 为服务启动时读取自身运行中可执行文件得到的 SHA-256，启动后缓存；它不读取可能被替换的部署路径。控制端在建立 WebSocket 前对比配套清单的版本与摘要，不匹配或缺少摘要时先部署配套程序，再次校验通过后连接。

### 文件预览与 APK 图标

`files.preview` / `files_preview` 接收 `{root,path}`，只打开选定根中的普通文件，不跟随符号链接，返回 `{id,root,path,sizeBytes,dataPath}`；`dataPath` 为 `/media/{id}`，支持 HTTP GET/HEAD 与单范围 Range。范围响应携带精确 Content-Range 与长度，文件发生变化时返回409。最多八个预览资源、闲置五分钟失效，容量与目标播放资源分别计算。`files.preview_close` / `files_preview_close` 接收 `{id}` 释放预览，不能借此关闭目标播放资源。流不内联文件内容，不计算全文件摘要；需要完整校验的导出继续使用下载任务。

`apps.apk_icon` / `apps_apk_icon` 接收 `{root,path}`（`.apk` 普通文件，最多4 GiB），从已固定的文件描述符经 Android 包资源解析获取96×96 PNG，单图最多128 KiB；返回 BinaryArtifact 加 `root`、`filePath`，`path` 仍为 `/artifacts/{id}`。客户端核对原根、文件路径、尺寸与长度后读取。此接口不安装 APK、不从同包名的已安装应用借用图标；split 缺少独立图标、无访问权限或资源无法解析时可能失败，界面保留占位。

## 应用详情与管理 {#app-controls}

`apps.control_info` 接收 `{package}`，返回 `{app,state}`；app 沿用 `apps.info` 的身份字段，state 含 package/userId、enabled/enabledSetting、stopped、capabilities(forceStop/setEnabled/clearData) 和 permissions(name/label/granted/runtime/mutable/reason)。读取来自当前 Android 用户的 PackageManager，stopped 为停止标记而非进程状态。特殊访问的 grant 结果不代表其 AppOps/设置已允许。

`apps.prepare_control` 接收 package、action(force_stop/enable/disable/clear_data/grant/revoke)，grant/revoke 必须另有 permission。仅允许用户应用。返回 `{id,kind:"app_control",app,action,permission,approvalRequired:true,dataLoss,before}`，最多32份、15分钟有效。目标权限须由应用声明、属于可修改的运行时权限；系统/策略固定权限以及无法读取策略的权限拒绝修改。

控制端独立 `type=approve_app_control`，arguments 为 id 和可选 accepted(false拒绝)，不向模型暴露确认函数。提案单次消费，确认时及后台任务执行前重新核对当前用户、应用安装身份和相关状态。固定 pm/am 参数明确指定用户；不接受任意 shell 或组件。返回 `task.progress` 格式、direction=app_control、unit=items、name=package，提交后不可取消。完成包含 package/userId/action/permission/completed/verifiedBy/state；权限、启停用和强停读取实际包状态核对，数据清除以包管理器 Success 和安装身份核对为准，不报告释放空间。控制端完成后再次读取详情。

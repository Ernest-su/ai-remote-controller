# 设备端 CLI

以下命令在目标设备执行。默认安装路径为 `/data/local/tmp/airc/airc-runtime`，状态目录由手机安装器指定为 `/data/local/tmp/airc/state`。

```sh
/data/local/tmp/airc/airc-runtime --version
/data/local/tmp/airc/airc-runtime service check-port --state-dir /data/local/tmp/airc/state --port 9876
/data/local/tmp/airc/airc-runtime service start --state-dir /data/local/tmp/airc/state --port 9876
/data/local/tmp/airc/airc-runtime service status --state-dir /data/local/tmp/airc/state
/data/local/tmp/airc/airc-runtime service stop --state-dir /data/local/tmp/airc/state
```

启动和停止会改变目标进程状态；查询不会启动服务。后台启动复用已健康运行的进程，状态包含运行/就绪信息，启动记录保存实际端口与进程身份。停止前检查进程身份以防 PID 复用。

`check-port` 检查指定端口是否可绑定；同一状态目录中进程身份有效、占用该端口的已有服务允许通过，供部署事务随后停止。其他占用返回错误，不停止进程或替换程序。检查不保留端口，之后出现的端口竞争仍可能导致启动失败并触发回滚。

前台调试可使用 `serve --state-dir <directory> --port <port>`，调试时可增加 `--no-mdns` 关闭广播；后台管理使用 `service` 子命令脱离 ADB 终端。日志写入状态目录的 `runtime.log`，模型配置为 `model.json`；目录权限 `0700`，私有配置与日志以 `0600` 创建。调试目录不要提交模型凭据。

生命周期、发现和 HTTP/WS 字段见[协议](../protocol.md)。

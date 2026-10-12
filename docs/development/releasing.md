# 准备发布

当前没有公开发布源，也没有自动发布 workflow。本页描述维护者本地准备步骤，执行脚本不会上传文件。

## 设备端资产

先全 ABI 构建：

```sh
python3 scripts/build-runtime.py
python3 scripts/prepare-runtime-release.py \
  --base-url https://github.com/Ernest-su/ai-remote-controller/releases/download/v0.1.0 \
  --output dist/runtime \
  --signing-key /secure/path/release-signing.pem
```

示例 tag 需与实际 GitHub Release 一致。输出包含三 ABI 程序、`manifest.json` 与原始二进制 `manifest.json.sig`。脚本检查大小/摘要，使用 SHA256withRSA 签名，并核对签名私钥、公钥文件与 APK 固定信任根一致。

`--min-app-version-code` 默认 `1`，可用 `--max-app-version-code` 限定上界。清单签名覆盖版本、协议、兼容范围与资产 URL/大小/摘要，发布后不能只修改其中一项而不重新签名。

## 信任根

公开公钥见 [release-public-key.pem](../release-public-key.pem)，APK 固定公钥位于 `ReleaseTrust.kt`。私钥由维护者在仓库之外管理，不进入源码、测试 APK 或公开发布目录。贡献者无需持有该私钥。

更换信任根需要同步公钥文件与 APK 常量，并重新分发应用；仅在更新源替换签名无法完成轮换。首次 fork 自己发布前，应建立并验证自己的信任根，不能假设持有上游私钥。

## 分发检查

检查三 ABI、版本、协议、应用兼容范围及 HTTPS 文件可达性；运行[本地验收](testing.md)和适用的设备测试。将目录中的 manifest.json、manifest.json.sig 与三 ABI 程序作为同一个 GitHub Release 的附件上传；清单内下载 URL 使用该 Release 的固定 tag 路径，应用从项目仓库的最新 Release 入口获取清单。准备脚本不会自动发布或上传。

应用 release 签名与设备端清单签名是两套不同机制。当前仓库没有配置生产 APK 签名或商店上传。正式公开分发前还需要维护者确定项目许可证、核对第三方授权并完成真实环境验收。

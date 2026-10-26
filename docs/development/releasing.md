# 签名与发布

项目原创代码使用 MIT License。`ascrcpy` 及匹配的 scrcpy server 保留 Apache-2.0；APK 的 `assets/licenses/` 与 Release 附件包含相应授权文本和第三方声明。

## 两套独立签名

| 用途 | 文件 | 算法 |
| --- | --- | --- |
| Android APK 安装、后续升级 | `android-release.p12` | RSA 3072 / SHA256withRSA |
| runtime 在线更新清单 | `runtime-signing-private.pem` | RSA 3072 / SHA256withRSA；私钥为密码加密的 PKCS#8 PEM |

公钥见 [release-public-key.pem](../release-public-key.pem)，APK 固定信任根在 `ReleaseTrust.kt`。APK 公开证书见 [android-release-certificate.pem](../android-release-certificate.pem)，两套 SHA-256 指纹与 Secrets 文件名见 [release-signing.json](../release-signing.json)。这些公开文件不包含私钥或密码。

APK 签名与 runtime 清单签名不能互换。后续版本继续使用同一套生产密钥；不要在每次发布时重新生成。公开发布后更换 APK 证书会影响覆盖安装；runtime 公钥更换需要同步固定信任根并重新分发 App。debug APK 与生产 APK 使用不同证书，不能直接覆盖安装。

## 首次生成或 fork 初始化

需要 JDK 的 `keytool`、OpenSSL 和 Python 3.11+。构建时的 Gradle daemon 与模块 JDK 版本见[构建环境](build.md)。输出目录必须位于仓库外且尚不存在；生成器拒绝覆盖已有密钥。

```sh
python3 scripts/release_signing.py generate --output /secure/airc-release
python3 scripts/release_signing.py install-public-key --secrets-dir /secure/airc-release
```

生成器创建独立随机密码、APK keystore、加密的 runtime 私钥、公开证书/公钥与 GitHub Secret 输入文件。私有目录权限为 `0700`、文件为 `0600`。安装公钥命令只复制公开文件，并同步 Android 固定信任根；这些源码改动需要随发布版本提交。私钥和密码要在仓库外做独立备份，不上传到 Release、Actions artifacts、测试 APK 或源码。

## GitHub Actions Secrets

在项目 **Settings → Secrets and variables → Actions → Repository secrets** 配置以下六项。值使用生成目录中对应文件的完整内容；多行 PEM 保留换行。Base64 只用于 keystore 的二进制传输，不是加密。

| Secret 名称 | 取值文件 |
| --- | --- |
| `ANDROID_SIGNING_KEYSTORE_BASE64` | `android-keystore.base64` |
| `ANDROID_SIGNING_STORE_PASSWORD` | `android-store-password.txt` |
| `ANDROID_SIGNING_KEY_ALIAS` | `android-key-alias.txt` |
| `ANDROID_SIGNING_KEY_PASSWORD` | `android-key-password.txt` |
| `RUNTIME_SIGNING_PRIVATE_KEY` | `runtime-signing-private.pem` |
| `RUNTIME_SIGNING_KEY_PASSWORD` | `runtime-key-password.txt` |

手动配置完成后，无需再次上传或重新生成密钥。GitHub 不提供读取已保存 Secret 明文的接口；名称检查不能证明值正确，需由版本标签触发的签名构建校验实际值、APK 证书及 runtime 公钥是否匹配。

已安装 GitHub CLI 并登录有仓库写权限的账户时，也可以一次导入；命令通过标准输入传输值，不把私钥或密码放进命令参数或输出：

```sh
gh auth login
python3 scripts/release_signing.py upload \
  --repository Ernest-su/ai-remote-controller \
  --secrets-dir /secure/airc-release
gh secret list --repo Ernest-su/ai-remote-controller
```

上传操作会更新同名 Secrets；确认使用的密钥与当前仓库公钥一致。无需手动创建 PAT 或 `GITHUB_TOKEN` Secret，workflow 使用 GitHub 自动提供的 token，仅发布 job 申请 `contents: write`。普通 PR 和 main 构建不读取生产签名密钥。[GitHub Secrets 文档](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。

## 版本与触发

先同步 `androidApp/build.gradle.kts` 与 `tvInputMethod/build.gradle.kts` 的 `versionName`、递增两者 `versionCode`，以及 `runtime/Cargo.toml` 的版本。发布脚本同时校验电视输入法与控制端的版本名和版本码一致。标签必须为 `v` 加相同版本，例如 `v0.1.0`；清单兼容范围限定为该 APK versionCode。

提交源码、公钥和 workflow 后创建并推送版本标签：

```sh
git tag -a v0.1.0 -m 'AIRC v0.1.0'
git push origin main
git push origin v0.1.0
```

`.github/workflows/release.yml` 先复用构建 workflow，对同一标签执行 Rust/协议与 JVM/Android 构建检查，再构建生产签名 APK、签名三 ABI runtime 清单，并验证 APK 证书与 APK/独立 runtime 的大小和摘要一致。签名作业只读复用前置检查的 Rust 编译缓存，仍构建并逐项核对实际发布文件。任一 Secret 缺失、版本不匹配或签名不匹配都会失败，不降级为 debug 或 unsigned APK。

成功后上传 `signed-release-<tag>` artifact，并创建 **草稿 GitHub Release**。附件包括签名 APK、三 ABI runtime、`manifest.json`、原始二进制 `manifest.json.sig`、`SHA256SUMS`、项目与第三方许可证、公开证书和公钥。私钥只在 runner 临时目录中恢复，结束时清除，不写入 `gradle.properties`，发布 job 也不上传签名材料到缓存。

手动运行 “Prepare signed release” 时填写已存在的版本 tag。已有同名 Release 时创建会失败，保留已有附件，不自动覆盖。检查适用的真实环境验证后，在 GitHub Release 页面公开草稿；草稿不进入 latest Release，App 在线更新需等待公开。不要修改清单、资产或 URL 后只替换其中一个文件，必须重新签名并整体核对。

## 本地验证同样的产物

```sh
python3 scripts/release_signing.py run --secrets-dir /secure/airc-release -- \
  python3 scripts/build-release.py --tag v0.1.0 --output dist/release
```

输出目录必须为空，防止混入旧版本附件。该命令构建并验证 APK、清单、三 ABI 资产与签名 fixture，不上传 GitHub，也不执行设备部署。发布 workflow 另有完整的构建检查；本地发布前仍按[测试矩阵](testing.md)执行相应完整门禁与设备专项。

只准备 runtime 时可以继续使用：

```sh
python3 scripts/release_signing.py run --secrets-dir /secure/airc-release -- \
  python3 scripts/prepare-runtime-release.py \
    --base-url https://github.com/Ernest-su/ai-remote-controller/releases/download/v0.1.0 \
    --output dist/runtime --signing-key /secure/airc-release/runtime-signing-private.pem
```

清单签名覆盖版本、协议、兼容范围、资产 URL、大小与 SHA-256。下载路径固定到该版本标签，App 从仓库最新公开 Release 入口获取清单。这里的签名是自管 APK 分发签名，不配置 Google Play App Signing 或商店上传。

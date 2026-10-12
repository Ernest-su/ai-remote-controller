# Docs as Code

文档与代码放在同一仓库，同一个改动一起审查。使用MkDocs Material 方式：显式导航、Markdown 源文件、链接检查。当前提供简体中文文档；不创建没有内容的英文版本。

## 本地预览与构建

```sh
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install -r requirements-docs.txt
.venv-docs/bin/python -m mkdocs serve
```

MkDocs 严格构建仅在提交前执行一次：

```sh
.venv-docs/bin/python scripts/verify-local.py --scope commit
```

日常修改、JVM/UI 测试和完整业务门禁不自动运行文档严格构建。提交后的文档 CI 复核构建并生成站点。

预览地址默认 `http://127.0.0.1:8000`。生成站点在 `site/`，不提交生成物。只构建文档不需要 Android SDK、NDK 或模型密钥。

## 组织约定

| 内容 | 位置 |
| --- | --- |
| 仓库简介、构建最短入口 | 根 README |
| 首次使用 | `docs/getting-started.md` |
| 用户操作 | `docs/guide/` |
| 协议与精确参数 | `docs/protocol.md`、`docs/reference/` |
| 架构、构建、测试、发布 | `docs/development/` |
| 贡献规则 | 根 CONTRIBUTING |

新增页面同时修改 `mkdocs.yml` 的 nav；使用相对 Markdown 链接与真实锚点。用户指南描述最终行为，参考页描述字段与约束，开发页解释实现和验证。协议、CLI、UI 或发布行为变化时同步修改对应文档。

不维护开发 TODO、项目状态、需求验收流水账或逐轮测试记录，也不将这些内容换名保留。文档只描述当前使用方法、接口、架构和可复用约束；测试结果放在回复或提交说明。

不要把临时机器路径、测试进程号、聊天记录、私钥位置或未经验证的发布地址当作用户文档。示例使用显式占位参数；说明命令前提及是否写入目标设备。

## 持续集成与独立发布分支

`.github/workflows/docs.yml` 在 pull request、master 推送或手动触发时安装固定版本依赖，执行严格构建并保存站点 artifact。只有 master 推送或从 master 手动触发时，发布 job 才将生成内容写入独立 `gh-pages` 分支；PR 不发布。该分支只存放生成站点，文档 Markdown 继续在 master 的 `docs/` 维护。

首次运行生成 gh-pages 后，在仓库 Settings → Pages 选择 Deploy from a branch，分支 `gh-pages`，目录 `/`。工作流使用仓库自带 GITHUB_TOKEN，发布 job 只授予 contents/pages write；不需要个人令牌或发布私钥。因为该令牌推送不会自动触发 Pages 构建，最后显式请求 Pages 构建。首次尚未启用 Pages 时，请完成设置后重新运行 Documentation。

Pages 限制与 API 参见 [GitHub 发布来源说明](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)和 [Pages 构建请求](https://docs.github.com/en/rest/pages/pages#request-a-github-pages-build)。文档工作流根据当前仓库名注入 repo_url/site_url，支持站点路径前缀；本地预览无需设置公开地址。

`.github/workflows/build.yml` 同样在 PR/master/手动运行，检查 Rust 与主机协议，再构建 Android debug APK、test APK、三 ABI runtime 并运行共享/Android JVM 测试。下载 Actions artifacts 进行开发测试；debug APK 不作为生产签名版本，runtime 资产也没有发布清单签名。生产发布流程见[准备发布](releasing.md)。

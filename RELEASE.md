# 发布流程

所有分支的普通推送都只运行 CI：构建、发布脚本测试、单元测试和双进程集成测试，不创建或修改 Release。只有推送发布 tag 才运行 CD；CD 会重新构建并通过测试后发布。

## 共用一个 Release

在 `main` 的提交上打统一版本 tag，创建共用 Release；在 Minecraft 版本分支上打带 Minecraft 版本的 tag，只向对应 Release 追加自己版本的附件，不创建新 Release，也不修改主 tag 或说明。

| 类型 / 分支 | `mod_version` | 推送的 tag | 目标 Release |
| --- | --- | --- | --- |
| 测试版 / main | `1.0.0-mc26.3-alpha.9` | `v1.0.0-alpha.9` | `v1.0.0-alpha.9`，Pre-release，非 Latest |
| 测试版 / 26.2 | `1.0.0-mc26.2-alpha.9` | `v1.0.0-mc26.2-alpha.9` | 追加到 `v1.0.0-alpha.9` |
| 测试版 / 26.1 | `1.0.0-mc26.1-alpha.9` | `v1.0.0-mc26.1-alpha.9` | 追加到 `v1.0.0-alpha.9` |
| 正式版 / main | `1.0.0+mc26.3` | `v1.0.0` | `v1.0.0`，正式版，Latest |
| 正式版 / 26.2 | `1.0.0+mc26.2` | `v1.0.0+mc26.2` | 追加到 `v1.0.0` |
| 正式版 / 26.1 | `1.0.0+mc26.1` | `v1.0.0+mc26.1` | 追加到 `v1.0.0` |

JAR 内版本号与文件名仍带 Minecraft 版本。tag 必须匹配该提交的 `mod_version`，其中 Minecraft 版本必须与 `minecraft_version` 一致。CD 检查统一 tag 的提交属于 `main`，版本 tag 的提交属于对应版本分支，并始终构建 tag 指向的提交，不构建浮动的分支最新提交。主分支提供 `.github/release-notes/<共用版本>.md`，例如 `1.0.0-alpha.9.md` 或 `1.0.0.md`。

每个 Minecraft 版本只上传可安装 JAR 和 `SHA256SUMS-mc<版本>.txt`，附件直接显示文件名。来源提交记录在校验文件的 `# source-commit: <SHA>` 注释行中，不另行上传 JSON，也不设置附件显示标签。不同分支不会覆盖彼此的附件。源码 JAR 不发布。

## 发布步骤

1. 更新 `main` 的 `mod_version` 和 release notes，提交并推送，等待 CI 通过。
2. 为要发布的 main 提交创建并推送统一 tag，等待 `Release` 工作流成功创建 Release。
3. 在各版本分支设置相同的正式版本或 alpha 序号，保留各自 Minecraft 版本，提交并推送，等待 CI 通过。
4. 为要发布的版本分支提交创建并推送带 Minecraft 版本的 tag，等待 CD 测试通过并追加附件。

版本 tag 的构建如果先完成，会最多等待统一 tag 的 Release 20 分钟；超时后，在主 Release 发布成功后重跑该 tag 的工作流。

只推送提交不会发布，不需要再用 `[skip ci]` 避免发布。tag 是明确的发布操作，CD 不自动创建 tag。旧的历史 tag 和 Release 保留，不改写。alpha 与正式版使用不同的 tag 和 Release，测试版永远不取代正式版 Latest。

## 重跑与版本不可复用

同一提交可以重跑，工作流仅替换该 Minecraft 版本的附件。已发布版本不得用于另一份提交：主 tag 与每个版本校验文件中的来源注释都会检查提交 SHA；代码更新需递增版本。旧校验文件没有来源注释时，首次更新会补上。上传失败的产物保存在 Actions artifacts，便于检查。

可重跑失败的 tag 工作流，或通过 `workflow_dispatch` 指定已有发布 tag；选择分支时发布任务会跳过。发布需要仓库允许 `GITHUB_TOKEN` 写入 contents，并允许向已发布 Release 追加附件；不能启用禁止追加附件的 Release immutable 模式。

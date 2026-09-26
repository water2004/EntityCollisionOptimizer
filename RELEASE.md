# 发布流程

推送 `main` 或 Minecraft 版本分支（例如 `26.1`、`26.2`）会运行 CD：构建三平台原生库与 JAR，通过单元和双进程集成测试后才发布。其他开发分支仍只运行 CI。

## 共用一个 Release

`main` 根据 `gradle.properties` 的 `mod_version` 创建 Release；版本分支只向对应 Release 追加自己版本的附件，不创建新 Release，也不修改主分支的 tag 或说明。

| 分支中的模组版本 | 共用 Release tag |
| --- | --- |
| `1.0.0-mc26.3-alpha.8` | `v1.0.0-alpha.8` |
| `1.0.0-mc26.2-alpha.8` | `v1.0.0-alpha.8` |
| `1.0.0-mc26.1-alpha.8` | `v1.0.0-alpha.8` |
| `1.0.0+mc26.3` | `v1.0.0` |

JAR 内版本号与文件名仍带 Minecraft 版本。数字版本分支名必须与 `minecraft_version` 一致。主分支提供 `.github/release-notes/<共用版本>.md`，例如 `1.0.0-alpha.8.md`。

每个 Minecraft 版本只上传可安装 JAR 和 `SHA256SUMS-mc<版本>.txt`。来源提交记录在 JAR 附件的 `commit:<SHA>` 标签中，不另行上传 JSON。不同分支不会覆盖彼此的附件。源码 JAR 不发布。

## 发布步骤

1. 更新 `main` 的 `mod_version` 和 release notes，提交并推送。
2. 等待 `Release` 工作流成功创建 Release。
3. 在各版本分支设置相同的模组版本与 alpha 序号，保留各自 Minecraft 版本，提交并推送。
4. 等待版本分支构建通过、附件追加完毕。

版本分支如果先完成，会最多等待主分支 Release 20 分钟；超时后，在主分支发布成功后重新运行该工作流。

无需手工创建或推送 tag。旧的带 Minecraft 版本的历史 tag 和 Release 保留，不改写。alpha 自动标记为预发布，正式版本标记为 Latest。

## 重跑与版本不可复用

同一提交可以重跑，工作流仅替换该 Minecraft 版本的附件。已发布版本不得用于另一份提交：主 tag 与每个版本的 JAR 附件标签都会检查提交 SHA；代码更新需递增版本。旧附件没有来源标签时，首次更新会补上。上传失败的产物保存在 Actions artifacts，便于检查。

也可通过 `workflow_dispatch` 为所选分支重跑。发布需要仓库允许 `GITHUB_TOKEN` 写入 contents，并允许向已发布 Release 追加附件；不能启用禁止追加附件的 Release immutable 模式。

# 发布流程

GitHub Release 只由指向 `main` 分支历史的版本 tag 触发。推送普通提交不会发布 Release。

版本号使用以下两种格式：

- 预发布版本：`1.0.0-mc26.2-alpha.1`，对应 tag `v1.0.0-mc26.2-alpha.1`
- 正式版本：`1.0.0+mc26.2`，对应 tag `v1.0.0+mc26.2`

其中前三段是模组语义版本，`mc26.2` 必须与 `gradle.properties` 中的 `minecraft_version` 一致；预发布序号必须是不带前导零的非负整数。Git tag 在版本号前添加 `v`，JAR 文件名和 `fabric.mod.json` 中的版本不含 `v`。

发布前先确保目标提交已经位于 `main`，然后创建并推送 tag：

```bash
git switch main
git pull --ff-only
git tag v1.0.0-mc26.2-alpha.1
git push origin v1.0.0-mc26.2-alpha.1
```

工作流会在 Windows 上使用 Microsoft Build of OpenJDK 25，交叉编译 Windows、Linux 和 macOS x64 原生库，构建 Fabric JAR，并校验 JAR 内版本号、关键 Mixin 类及原生库是否完整。成功后创建 GitHub Release，只附加可安装的主 JAR 和 SHA-256 校验文件。源码 JAR 不是可安装模组，不作为 Release 资产发布。`alpha` 版本标记为 Pre-release，正式版标记为 Latest release。

每个版本必须提供 `.github/release-notes/<version>.md`，内容描述相对于上一版本的用户可见变化；工作流会将其直接用作 GitHub Release notes。

若 tag 格式不合法、Minecraft 版本不匹配或 tag 不属于 `main` 历史，工作流会在发布前失败。删除错误 tag 后，修正版本并推送新的 tag；不要复用已经发布的版本号。

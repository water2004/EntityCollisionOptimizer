# 实体碰撞优化 (Entity Collision Optimizer)

面向 Minecraft Fabric 的服务端实体碰撞优化模组，使用 Java FFM 调用原生空间索引、实体推动和移动碰撞计算。安装后默认启用，支持独立服务器及单人游戏的内置服务器。

## 环境要求

- Minecraft Java Edition **26.2**
- Fabric Loader **0.19.5 或更高的 0.19.x 版本**
- Fabric API **0.159.0+26.2 或兼容版本**
- Java **25**
- Windows、Linux 或 macOS 的 **x64 / AVX2** 环境

仅支持 Fabric。原生后端暂不支持 ARM64。

## 安装

将模组 JAR 与 Fabric API 放入实例的 `mods` 目录。建议添加 JVM 参数：

```text
--enable-native-access=ALL-UNNAMED
```

FFM 初始化或执行失败时会明确报错，不会自动切换后端。

## 配置与命令

首次启动生成 `config/entity_collision_optimizer.json`：

```json
{
  "enableEntityCollision": true,
  "vanillaOrder": true,
  "gridSize": 1
}
```

- `enableEntityCollision`：默认 `true`，启用 FFM；设为 `false` 则使用原版及其他已安装模组的路径。
- `vanillaOrder`：默认保持原版实体推动候选顺序。关闭后仍保留完整候选、去重和计数，但不保证推动顺序及最终行为与原版一致。
- `gridSize`：XYZ 空间索引的单元边长，必须大于 0。建议保留默认值。

命令需要游戏管理员权限：

- `/eco` 或 `/eco check`：查看状态。
- `/eco vanillaOrder true|false`：设置原版顺序开关，自动保存，**下次重启生效**。

手动修改配置文件后重启实例。

## 行为与兼容性

- 默认顺序模式以低、中密度下的原版行为一致性为目标；高密度不作为一致性保证范围。所有密度使用同一套算法，不截断候选，也不按密度切换路径。
- 优化覆盖服务端实体推动、移动碰撞求解和方块碰撞扫描；原版重力、摩擦、方块效果及世界回调仍参与执行。
- 可与 Lithium、Carpet 同时安装。本模组启用时接管对应碰撞路径，**忽略 Carpet 的 `maxEntityCollisions` 设置**；原版 `maxEntityCramming` 挤压伤害规则仍然生效。
- 兼容目标是原版实体及常用优化模组，不承诺其他模组自定义实体或未覆盖的字段修改方式。

## 构建与测试

使用 Java 25 和仓库内的 Gradle Wrapper。Windows 示例：

```powershell
./gradlew.bat build
./gradlew.bat runGameTest -Pparity
```

构建产物位于 `build/libs`。当前原生交叉编译任务需要 Windows，并由构建任务准备工具链；Linux/macOS 可使用 `./gradlew compileJava` 检查 Java 编译。

可通过 `-PcompatModsDir=<目录>` 加载额外模组进行兼容性测试；`-Pbenchmark` 显式启用压测，普通构建不会运行压测。测试工具源码保留在仓库中，采样文件、测试报告和实验记录不纳入版本控制。

发布版本、tag 格式及自动发布流程见 [RELEASE.md](RELEASE.md)。

原生模块职责见 [native/README.md](native/README.md)。

## 许可证

本项目使用 [MIT License](LICENSE)。

项目源自 [Accelerated Recoiling](https://github.com/water2004/AcceleratedRecoiling)。原项目代码由 wiyuka 以 MIT License 发布；Entity Collision Optimizer 的后续重构与维护由 water2004 完成。

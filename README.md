# Entity Collision Optimizer

面向 Minecraft 26.2 Fabric 服务端的实体碰撞优化模组。它通过 Java FFM 调用原生实现，维护持久的实体空间索引，并接管实体查询、实体推动和移动碰撞的热点路径。

本项目适合实体密集、实体推动或移动碰撞已经成为 MSPT 瓶颈的场景。它不优化生物 AI、寻路、区块生成、网络或客户端渲染，因此普通场景中的收益取决于碰撞在服务器负载中的占比。

> 当前版本处于 alpha 阶段。请先备份世界，并在正式服务器使用前验证自己的模组组合。

## 设计目标

- 安装后默认启用，不需要修改游戏规则或限制参与碰撞的实体数量。
- 默认保留原版实体推动的候选顺序，以低、中密度下的原版行为一致性为目标。
- 所有实体密度使用同一条计算路径；不会按密度切换算法，也不会截断候选来掩盖性能问题。
- 高密度下允许浮点误差和迭代顺序的影响自然累积，不承诺逐 tick、逐 bit 与原版一致。
- 当前只以原版实体为兼容目标；其他模组自定义实体或直接改写实体内部状态的行为不在保证范围内。

## 优化范围

模组在服务端为每个维度维护独立的原生碰撞状态，主要覆盖：

- 实体空间索引的增量维护与碰撞候选查询；
- 生物实体之间的推动计算及速度状态同步；
- `Entity.move` 使用的实体与方块碰撞求解；
- 方块碰撞箱扫描与体素几何裁剪。

重力、摩擦、摔落、流体、方块效果、伤害、爆炸以及其他世界回调仍由 Minecraft 的正常逻辑驱动。原生模块的内部边界见 [native/README.md](native/README.md)。

## 环境要求

| 组件 | 要求 |
| --- | --- |
| Minecraft | 26.2 |
| 模组加载器 | Fabric Loader 0.17.0 或更高版本 |
| 依赖 | Fabric API 0.145.4 或更高的 26.2 兼容版本 |
| Java | 25 |
| 操作系统 | Windows、Linux 或 macOS |
| 处理器 | x86-64，支持 AVX2 |

发布 JAR 内置 Windows、Linux 和 macOS 的 x86-64 原生库；目前不支持 ARM64。模组只参与服务端模拟：独立服务器只需服务端安装，单人游戏则安装在运行内置服务器的客户端实例中，加入服务器的客户端不需要同步安装。

## 安装

1. 安装 Fabric Loader 和 Fabric API。
2. 从 [GitHub Releases](https://github.com/water2004/EntityCollisionOptimizer/releases) 下载与 Minecraft 26.2 对应的 JAR，放入实例的 `mods` 目录。
3. 建议为 Java 添加以下 JVM 参数，显式允许 FFM 原生访问：

   ```text
   --enable-native-access=ALL-UNNAMED
   ```

原生库不受支持或 FFM 初始化失败时，模组会明确报错；不会静默回退到另一套实现。

## 配置与命令

首次启动会生成 `config/entity_collision_optimizer.json`。面向用户的配置项只有：

```json
{
  "vanillaOrder": true
}
```

- `true`（默认）：保留原版实体推动候选顺序，适合正常使用和行为一致性验证。
- `false`：启动无序原生路径，同时移除维护原版顺序所需的成本。候选仍会完整查询和去重，但推动顺序以及由此产生的最终状态可能与原版不同。

该路径在启动时确定，修改后必须重启实例。管理员可使用：

- `/eco`：查看当前后端、FFM 初始化状态和顺序模式；
- `/eco vanillaOrder true|false`：保存下次启动使用的顺序模式。

配置文件中的其他字段属于内部实现细节，不是稳定的用户接口。

## 行为与兼容性

- 可与 Lithium 和 Carpet 一同安装。本模组启用时会完整接管重叠的服务端碰撞路径，而不是叠加执行两套实现。
- 实体推动不会读取 Carpet 的 `maxEntityCollisions` 上限；是否限制碰撞候选不属于本模组职责。原版 `maxEntityCramming` 挤压伤害规则仍然生效。
- 不修改存档格式，也不注册必须同步到客户端的内容。
- 与其他性能模组能否共存，取决于对方是否改写同一实体碰撞路径；请用实际模组组合运行一致性测试。

## 测试

仓库包含原版与优化路径的差分 GameTest，覆盖实体推动、玩家交互、载具与投射物、TNT 与爆炸、活塞、粘液块与蜂蜜块、流体与气泡柱、冰面、异形方块碰撞箱、区块加载边界以及跨维度动量等场景。测试目标是比较两条路径的结果，而不只是确认交互曾经发生。

使用 Java 25 和仓库内的 Gradle Wrapper：

```powershell
./gradlew.bat build
./gradlew.bat runGameTest -Pparity
```

压测必须显式使用 `-Pbenchmark`，普通构建不会启动压测服务器。可通过 `-PcompatModsDir=<目录>` 为测试运行加入额外模组。

完整发布 JAR 的原生交叉编译目前需要 Windows；Linux 和 macOS 可使用 `./gradlew compileJava` 检查 Java 代码。构建产物位于 `build/libs`，版本与 tag 规则见 [RELEASE.md](RELEASE.md)。

## 许可证

本项目使用 [MIT License](LICENSE)。

项目源自 [Accelerated Recoiling](https://github.com/water2004/AcceleratedRecoiling)。原项目代码由 wiyuka 以 MIT License 发布；Entity Collision Optimizer 的后续重构与维护由 water2004 完成。

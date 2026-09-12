<p align="center">
  <img src="src/main/resources/assets/entity_collision_optimizer/icon.png" width="180" alt="实体碰撞优化图标">
</p>

<h1 align="center">实体碰撞优化</h1>

<p align="center">面向 Minecraft 26.2 Fabric 服务器的原版等价实体碰撞加速。</p>

<p align="center"><a href="README.md">English</a> | <strong>简体中文</strong></p>

---

实体碰撞优化是面向 Minecraft 26.2 的服务端 Fabric 模组，通过 C++ native 后端加速实体查询、相互推动和移动碰撞，同时在默认模式下**保持原版实体碰撞行为**。可选无序模式在下方高密度实体对照中达到**原版 6.58 倍**、**锂 4.87 倍**的 tick 处理速率。安装即生效，连接服务器的客户端无需安装。

## 为什么使用实体碰撞优化？

拥挤的刷怪塔、运输系统和其他实体密集机器，可能会把大量 tick 时间花在查找附近实体、比较碰撞箱、执行推动以及处理实体与方块的移动碰撞上。实体碰撞优化只专注于这些工作。它不优化 AI、寻路、区块生成、网络或客户端渲染，因此实际收益取决于实体碰撞在服务器负载中的占比。

这不是碰撞数量限制器，也不是近似模拟。默认有序后端会为原版实体产生相同的候选集合和候选顺序，执行相同的碰撞规则，并在与 Mojang 实现相同的时机发布每一次速度或移动更新。算法不会随实体密度改变，也不会丢弃候选。

## 游戏内对照

下面三张截图使用同一个高密度僵尸猪灵围栏和相同的测试条件。实体碰撞优化使用 `vanillaOrder=false`，从而移除所有仅用于复现原版实体候选顺序的工作。游戏内实时 tick 信息显示：

| 方案 | MSPT | 相对原版速度 | 相对原版 MSPT 降幅 |
| --- | ---: | ---: | ---: |
| 原版 | 268.4 | 1.00× | — |
| 锂 | 198.5 | 1.35× | 26.0% |
| 实体碰撞优化 | 40.8 | **6.58×** | **84.8%** |

在这个场景中，相较原版，MSPT **降低 84.8%**；相较锂，MSPT **降低 79.4%**，从而回到 Minecraft 每 tick 50 ms 的预算内。

![原版、锂与实体碰撞优化的 MSPT 和 tick 处理速率柱状图](docs/images/comparison/performance.png)

<table>
  <tr>
    <td width="33%" align="center"><strong>原版</strong><br>268.4 MSPT</td>
    <td width="33%" align="center"><strong>锂</strong><br>198.5 MSPT</td>
    <td width="33%" align="center"><strong>实体碰撞优化</strong><br>40.8 MSPT</td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="docs/images/comparison/vanilla.jpg" width="300" height="188" alt="原版在高密度实体对照场景中为 268.4 MSPT"></td>
    <td width="33%" align="center"><img src="docs/images/comparison/lithium.jpg" width="300" height="188" alt="锂在高密度实体对照场景中为 198.5 MSPT"></td>
    <td width="33%" align="center"><img src="docs/images/comparison/eco.jpg" width="300" height="188" alt="实体碰撞优化在高密度实体对照场景中为 40.8 MSPT"></td>
  </tr>
</table>

关闭 `vanillaOrder` 不会改变红石更新顺序或方块逻辑，因此大部分红石机器不会受到影响。不过，依赖实体推动先后顺序、碰撞时序或精确运动轨迹的机器可能产生不同结果，正式使用前应在关闭该选项的情况下单独验证。

这些数值是该场景的实时截图，并非多轮统计基准。绝对性能会受硬件、JVM、模组组合和实际负载影响；保留截图是为了让这次具体对照可以直接核验。

## 工作原理

Minecraft 按区段存储实体。一次碰撞查询需要遍历相关区段、访问其中的 Java 对象、比较碰撞箱，再为推动或移动代码准备数据。这种实现简单而灵活，但当大量实体集中在很小的空间时，对象访问、临时分配和重复的数据准备会变得昂贵。

实体碰撞优化为每个维度维护独立的 C++ native 碰撞上下文，并在实体开始追踪、移动、跨维度或移除时增量更新。持久的细粒度 XYZ 网格先把查询范围缩小到附近实体；默认后端再通过另一份区段索引恢复 Minecraft 的区段遍历和区段内插入顺序，因此不需要为每次查询临时排序也能保持原版顺序。

碰撞需要的位置、速度、包围盒和同步状态保存在紧凑的共享堆外表中，Java 与 C++ native 代码直接使用同一份状态。`Vec3` 等 Java 对象只在 Java 代码实际读取时按需创建。候选包围盒使用 SoA 布局，让热点 AABB 比较更好地利用 CPU 缓存和 AVX2。

处理实体推动时，一次原生查询会完成空间和规则筛选。连续使用 Minecraft 标准推动公式的碰撞对随后按原版顺序批量计算，并且每一对产生的速度变化都会立即参与下一对计算；具有特殊行为的原版实体回调仍在原来的位置执行。处理移动时，持续维护的方块掩码会跳过不可能碰撞的位置；Java 仍负责求取依赖世界上下文的 `VoxelShape`，native 则批量完成几何裁剪、台阶尝试和移动求解。

因此，一次 FFM 边界调用承载的是完整查询、一段推动序列或一次移动，而不是为每个候选实体反复在 Java 与 native 之间切换。重力、摩擦、摔落、流体、伤害、爆炸、方块效果和世界回调仍由 Minecraft 的正常 Java 逻辑处理。原生模块的职责边界见 [native/README.md](native/README.md)。

## 环境要求

| 组件 | 要求 |
| --- | --- |
| Minecraft | 26.2 |
| 模组加载器 | Fabric Loader 0.17.0 或更高版本 |
| 依赖 | Fabric API 0.145.4 或更高的 26.2 兼容版本 |
| Java | 25 |
| 操作系统 | Windows、Linux 或 macOS |
| 处理器 | 支持 AVX2 的 x86-64 处理器 |

发布 JAR 内置 Windows、Linux 和 macOS 的 x86-64 原生库，目前不支持 ARM64。

## 安装

1. 安装 Fabric Loader 和 Fabric API。
2. 从 [GitHub Releases](https://github.com/water2004/EntityCollisionOptimizer/releases) 下载 Minecraft 26.2 对应的 JAR，放入实例的 `mods` 目录。

在本项目支持的 Java 25 上不需要添加任何 JVM 参数。Minecraft 26.2 官方启动器已经启用了 native access；手动启动的独立服务器如果没有该选项，Java 可能只在日志中输出一次 native access 警告，但 Java 25 仍会允许调用，模组可以正常工作。

希望消除这条警告的服务器管理员可以选择添加：

```text
--enable-native-access=ALL-UNNAMED
```

如果当前平台不受支持或 FFM 初始化失败，模组会明确报错，不会静默回退到其他实现。

## 配置

使用 `/eco` 查看当前后端、FFM 状态和实体顺序模式。使用 `/eco vanillaOrder true|false` 选择下次启动使用的模式：

- `true`（默认）：保留 Minecraft 的实体候选顺序和更新语义。
- `false`：使用无序原生后端，并移除所有仅为复现原版顺序而产生的工作。它仍会完整查询并去重候选，但推动顺序和最终状态可能与原版不同。红石更新顺序和方块逻辑保持不变，因此大部分红石机器不受影响；依赖精确实体推动顺序或运动轨迹的机器需要单独测试。

后端在启动时选择，因此切换模式不会改变当前正在运行的服务器，重启后生效。

## 兼容性

- 可以与锂和 Carpet 一同安装。本模组启用时会接管重叠的服务端碰撞路径，而不是同时运行两套实现。
- 本模组有意忽略 Carpet 的 `maxEntityCollisions` 上限；限制碰撞候选数量不属于本项目的职责。原版 `maxEntityCramming` 挤压伤害规则仍然生效。
- 不修改存档格式，也不注册需要同步到客户端的内容。
- 当前以原版实体为兼容目标，不保证其他模组自定义实体或直接替换同一碰撞路径的实现能够正常工作。

如遇到可以稳定复现的问题，请通过 [Issue Tracker](https://github.com/water2004/EntityCollisionOptimizer/issues) 报告。

## 构建与测试

使用 Java 25 和仓库内的 Gradle Wrapper：

```powershell
./gradlew.bat build
./gradlew.bat runGameTest -Pparity
```

差分 GameTest 会比较原版与优化路径在实体推动、玩家、载具、投射物、爆炸、活塞、粘液块与蜂蜜块、流体、气泡柱、冰面、异形方块碰撞箱、区块加载边界和跨维度传送等场景下的结果。

压测必须通过 `-Pbenchmark` 显式启用，普通构建不会启动压测服务器。可以使用 `-PcompatModsDir=<目录>` 为测试运行加入额外模组。

构建包含全部原生平台的完整发布 JAR 目前需要 Windows；Linux 和 macOS 可以使用 `./gradlew compileJava` 检查 Java 源码。构建产物位于 `build/libs`，版本号和 tag 约定见 [RELEASE.md](RELEASE.md)。

## 许可证

实体碰撞优化使用 [MIT License](LICENSE)。

致谢：本项目的灵感来自 [Accelerated Recoiling](https://github.com/wiyuka-owo/AcceleratedRecoiling)，但在目标和实现上均与其有很大区别。

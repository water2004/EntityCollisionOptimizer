# 实体碰撞优化 (Entity Collision Optimizer)

Entity Collision Optimizer 是一个面向 Fabric 服务器的实体碰撞优化模组。它使用 Java FFM 原生空间索引处理实体推动候选，并使用稀疏方块索引处理实体移动与跨台阶的碰撞扫描。

模组接管服务端实体推动与移动碰撞，不接管或修改实体碰撞数量上限。

## 支持范围

- Minecraft Java Edition **26.2**
- Fabric Loader **0.19.5 或更高的 0.19.x 版本**
- Fabric API **0.159.0+26.2 或更高的 26.2 兼容版本**
- Java **25**
- 仅支持 **Fabric**

模组可同时安装在客户端和服务端，优化逻辑只在服务端世界中执行。

## 运行模式

只保留两种显式模式，由配置项 `enableEntityCollision` 决定：

| 配置值 | 模式 | 行为 |
| --- | --- | --- |
| `false` | Vanilla | 不进入模组碰撞路径，直接执行 Minecraft 原版逻辑 |
| `true` | FFM | 使用 FFM 原生碰撞后端 |

没有自动探测、后端选择参数或回退链。FFM 初始化或执行失败时会明确抛出错误，不会静默切换后端或跳过问题。运行 FFM 模式时建议添加 JVM 参数 `--enable-native-access=ALL-UNNAMED`。

## 原生平台

发布 JAR 的原生构建目标为：

- Windows x64
- Linux x64
- macOS x64

ARM64 暂不作为正式原生后端支持范围；在不受支持的平台上可将 `enableEntityCollision` 设为 `false` 使用 Vanilla 模式。

## 配置

首次启动后会生成：

```text
config/entity_collision_optimizer.json
```

默认配置：

```json
{
  "enableEntityCollision": true,
  "gridSize": 1
}
```

`enableEntityCollision` 必须显式设为 `true`（FFM）或 `false`（Vanilla）。

`gridSize` 是原生 X/Z 空间索引的格子边长，必须大于 `0`。实现始终返回所有相交候选，不包含候选上限、密度阈值、多线程开关或按密度分流的路径。

可使用 `/entitycollisionoptimizer` 查看、修改和保存配置。命令需要游戏管理员权限。

## 行为与兼容性

- 低密度场景以 Minecraft 26.2 原版行为为准。Fabric GameTest 会对照严格 AABB 相交边界、实体所在 section 的查询范围、帧内移动与新增实体、队伍碰撞规则、旁观者与不可推动实体过滤、僵尸/玩家来源的推动速度、精确重叠的无效推动以及挤压伤害。
- 所有密度使用同一套原生空间索引和推动循环，不会在高密度时切换另一条算法，也不会截断候选。旁观、可推动性和队伍谓词只在当前碰撞帧内缓存，并由生命值/移除、位置与包围盒、滑翔标志、游戏模式、世界方块、队伍成员和队伍碰撞规则各自的版本号显式失效；状态改变后的下一次查询会重新执行原版谓词。FFM 查询批量完成 section、可推动性和队伍规则过滤，并返回全部选中目标及原版挤压计数。先执行挤压伤害，再按候选顺序推动，不在查询时预先排除重叠目标。极端高密度下若碰撞副作用依赖候选迭代顺序，仍不承诺与原版逐次观察顺序完全一致。
- 普通实体推动按连续的原版行为分段批量计算 native 冲量，包括普通僵尸继承的 `LivingEntity.push`。有特殊推动覆写的原版实体仍执行各自原版逻辑；这不是密度分流或失败回退。每段在执行前读取实时位置，避免挤压或前面的特殊推动使几何过期。睡眠检查按睡眠数据变化失效，双方的推动条件实时检查，可推动性复用同帧失效缓存。速度每次相加仍按原版顺序舍入，只延迟 `Vec3` 分配；读取速度、有效/无效覆盖、非有限值拒绝、即时同步标志均有差分测试。
- 安装 Carpet 且其 `maxEntityCollisions` 大于 `0` 时，本模组完整让出实体推动，由 Carpet 管理上限；值为 `0` 时不截断候选，本模组接管查询。
- Lithium 可以同时安装。本模组启用时直接接管 `LivingEntity.pushEntities`，不进入 Lithium 对原版实体查询的注入；关闭本模组或由 Carpet 接管时，原版/Lithium 路径正常执行。
- 移动碰撞从 `Entity.collide` 及两个 `collideBoundingBox` 入口接管，普通移动和跨台阶共用同一个有序方块收集器，不执行 Lithium 对应的移动求解路径；不冲突的底层形状优化仍可工作。保持原版的轴处理顺序、台阶高度选择、世界边界及实体形状处理。此功能同样由 `enableEntityCollision` 控制，独立于 Carpet 对实体推动数量的管理。
- 方块扫描按原版 z/y/x 顺序遍历，用非空气位图跳过空气，所有非空气方块仍执行实时的原版形状查询。索引按需建立每行 16 个方块的位图，附着在方块调色板上；单格写入直接更新位图，反序列化使其失效，复制容器独立建立索引。没有跨实体缓存碰撞形状，因此皮革靴、潜行、流体和运动中活塞等上下文不会被快照冻结。没有按实体密度分流、速度限制或候选截断。
- Worldthreader 可以同时安装。每个 `ServerLevel` 独立持有实体 ID、原生碰撞上下文、语义缓存、批次池和查询输出缓冲区；不同维度可以并行执行碰撞查询，不共享或串行化碰撞帧。三维度并发 GameTest 会交错执行 13,500 次原生查询和 4,500 次批量推动，检查跨维度实体、重复实体、计数及冲量缓冲区污染。
- 已联合加载验证 ServerCore、FerriteCore、C2ME、VMP、Krypton、Alternate Current、ScalableLux、Noisium、Lithium、Carpet 与 Worldthreader；该组合的全部 GameTest 通过。这里验证的是这些模组面向 Minecraft 26.2 的当前版本，不替未来版本提供无条件兼容承诺。
- 不为其他模组自定义的 `pushEntities` 或带副作用的 `isPushable` 实现提供额外兼容承诺。

## 构建

常规 Java/Fabric 编译可在 Windows、Linux 或 macOS 上执行，Gradle 会自动获取 Java 25 工具链：

```powershell
./gradlew.bat compileJava
```

包含全部原生库的发布 JAR 需要 Windows 10/11、MSVC 和 WSL：

```powershell
./gradlew.bat build
```

产物位于 `build/libs/`。

## 行为一致性测试

运行低密度和中密度的 Vanilla/FFM 差分 GameTest，以及跨维度并发隔离测试：

```powershell
./gradlew.bat runGameTest -Pparity
```

低密度测试包含 34 个分派情形，覆盖 26.2 中全部 `LivingEntity.doPush` 覆写类型及其主要分支：铁傀儡敌对目标/苦力怕排除/随机失败、Warden 的 no-AI/触碰冷却/出现姿态及愤怒和扰动记忆、鹦鹉与玩家、装备岩浆块后的硫磺立方体接触伤害、船的垂直接受/拒绝，以及蝙蝠和盔甲架的空覆写。目标侧还覆盖未载客马、可移动 Creaking、站立 Warden 和出现中的 Warden。其他分支包括 survival/creative/spectator 玩家、不可推动/死亡/攀爬/睡眠实体、`noPhysics`、乘客和同载具关系、队伍规则、普通挤压、全部精确重叠且推动无效果时的挤压、严格 AABB/section 边界、帧内移动与新增实体，以及同帧存活、攀爬、队伍、玩家旁观、Warden 姿态和马匹载客状态的 12 个双向切换。另有 4 个连续碰撞帧；中密度测试分别让 20 个全允许实体和 24 个混合队伍与状态的实体按原版顺序完成整轮推动，并逐实体对比速度、生命值和存活状态。并发测试为主世界、下界和末地建立不同大小的碰撞集合，并由三个线程同时验证空间查询、可推动计数和非乘客计数。

测试可以同时加载 Carpet 和 Lithium：

```powershell
./gradlew.bat runGameTest -Pparity -PcompatModsDir=<包含 Carpet 和 Lithium JAR 的目录>
```

普通僵尸用例额外断言 native 推动资格，防止测试仅覆盖 Java 路径。225 对冲量计算与原版 `Entity.push` 逐位比较（包含推力阈值与归一化分支边界）；另有 5 种速度观察、4 组舍入/溢出/抵消/非有限输入序列、同帧睡眠—唤醒、嵌套批次隔离及查询后位置变化检查。优化和测试记录见 [实体批量推动](docs/native-push-2026-09-07.md)。

方块碰撞另有 32,366 个已注册方块状态的有序形状对照、1,743 次移动/显式上下文差分、360 次运动中活塞形状及 halo 对照，以及 32 步连续移动的位置、速度、接触标志和落距检查。包含台阶、天花板、落地、轴顺序、微小位移、世界边界、流体、矿车上下文、皮革靴和潜行，以及 20 只僵尸与 4 艘船的中密度混合组；每次启用模式的移动差分还验证确实进入了本模组实现。调色板测试覆盖三种单格写入、复制隔离与反序列化。状态枚举不等于穷举所有世界布局或方块实体状态。

## 碰撞基准测试

项目包含一个显式启用的 Fabric GameTest 基准：1000 只正常 AI、正常重力的僵尸被放进 1×2×1 的封闭石质拥挤室，自然执行实体推动、挤压伤害和实体—方块碰撞。一名真正加入测试世界的生存模式玩家与它们处于同一拥挤室，持击退 II 钻石剑按完整攻击冷却（每 13 tick）轮流攻击僵尸，攻击走原版玩家攻击、附魔伤害和击退链路。测试只把僵尸与测试玩家的最大生命、当前生命提高到一亿以保持样本数量；每轮都会确认持续发起攻击、至少一次攻击被原版伤害路径接受，并检查每个被接受的命中都确实产生水平击退。挤压伤害造成的原版无敌帧不会被测试清除，因此被它正常拒绝的攻击会单独计数。关闭碰撞优化与启用 FFM 的每轮测量都会重新生成独立僵尸种群；普通 `build` 不会运行它。

```powershell
./gradlew.bat runGameTest -Pbenchmark
```

结果会以 `ECO_BENCHMARK_RESULT` 为前缀输出到日志。建议至少独立运行三次，并比较 `baseline_mean_mspt`、`optimized_mean_mspt` 与 `improvement_percent`。

每轮另输出 `ECO_BENCHMARK_TRIAL`。测试布局给拥挤室留出 96 格间距，避免其他 GameTest 建筑进入大位移扫描范围。设置环境变量 `ECO_BENCHMARK_PROFILE=optimized` 或 `baseline` 可以只采集一个模式；使用 `-PjfrOutput=<文件路径>` 保存 JFR。

设置 `ECO_SCAN_DIAGNOSTICS=true` 可按每 67 次僵尸移动采样一次请求/实际位移、扫描范围、方块读取量和步进分支，输出 `ECO_SCAN_RESULT` / `ECO_SCAN_PHASE`。这些统计和注入仅存在于 GameTest 模组；未设置该变量时不加载扫描注入，发布 JAR 不包含诊断代码。统计中的 `state_reads` 是候选扫描读取，不包含首次建立位图的读取，也不包含 Lithium 自己的扫描器。带统计的 MSPT 不应用作正式性能对照。

本次接管的测量、限制和复现说明见 [移动碰撞优化记录](docs/movement-collision-2026-09-07.md)。

## 许可证

本项目使用 MIT License。

项目源自 [Accelerated Recoiling](https://github.com/water2004/AcceleratedRecoiling)。原项目代码由 wiyuka 以 MIT License 发布；Entity Collision Optimizer 的后续重构与维护由 water2004 完成。

# Minecraft 1.21.11 适配记录

本文件记录把本项目从 Minecraft 26.3 适配到 Minecraft 1.21.11 (Fabric) 所做的全部改动。
**模组的优化内容本身没有改变**：native 后端、碰撞查询/推动/移动求解逻辑、缓存失效规则、
字段改写边界都与原实现一致；改动只涉及版本相关的 API、构建配置和一处必须重新审计的混入目标清单。

## 1. 为什么构建配置必须换成 remap 插件

26.x 的 Fabric 生态已经在运行时使用 **Mojang 官方名称**（在 26.3 lithium 的 class 常量池里可以直接看到
`net/minecraft/world/entity/player/Player`），所以上游用 `net.fabricmc.fabric-loom`（no-remap）插件 +
普通 `implementation` 依赖即可。

1.21.11 仍然使用 **intermediary**（1.21.11 lithium 引用的是 `net/minecraft/class_1263` 这类名字），
因此必须走传统的重映射流程，否则产物在正式客户端/服务端里无法加载：

| 项目 | 26.3（原） | 1.21.11（现在） |
| --- | --- | --- |
| Loom 插件 | `net.fabricmc.fabric-loom` | `net.fabricmc.fabric-loom-remap` |
| 映射 | 未声明（no-remap 隐含官方名称） | `mappings loom.officialMojangMappings()` |
| 依赖配置 | `implementation` | `modImplementation`（loader / Fabric API 需要重映射） |
| Minecraft | 26.3 | 1.21.11 |
| Fabric Loader | 0.19.5 | 0.19.3 |
| Fabric API | 0.161.0+26.3 | 0.140.0+1.21.11 |
| mod 版本 | `1.0.0-mc26.3-alpha.10` | `1.0.0-mc1.21.11-alpha.10`（**冻结，勿提升**） |
| 混入配置 | `JAVA_25` | `JAVA_21` |
| `fabric.mod.json` | `minecraft ~26.3`, `java >=25`, `fabric-api >=0.161.0` | `minecraft ~1.21.11`, `java >=21`, `fabric-api >=0.140.0` |

同时移除了 `settings.gradle` 里的 `foojay-resolver-convention`：工具链现在固定为 Java 21，
CI 直接安装 JDK 21（`.github/workflows/build.yml` 已同步改为 `java-version: '21'`），
不再需要自动下载工具链，离线构建也因此可行。

## 2. Java 21 与 `--enable-preview`

1.21.11 运行在 Java 21 上，而模组用的 FFM (`java.lang.foreign`) 在 Java 21 里仍是**预览 API**，
并且 Java 22 及以上的编译器**无法**生成 Java 21 的预览类文件。因此：

* `options.release = 21` + `options.compilerArgs += ['--enable-preview']`
* `java.toolchain.languageVersion = 21`（构建必须有 JDK 21）
* Loom 的 run 配置也加了 `--enable-preview`
* 运行时（游戏 JVM）同样必须带 `--enable-preview`，README 已写明

由此产生两处 FFM API 差异，语义完全等价：

| 文件 | 26.3 写法 | 1.21.11 写法 | 说明 |
| --- | --- | --- | --- |
| `natives/FFMBackend.java` | `downcallHandle(..., Linker.Option.critical(false))` | 不传 Option | Java 21 的 `Linker.Option` 没有 `critical(boolean)`，默认下调用就是 `critical(false)` |
| `natives/FFMBackend.java` | `segment.getString(0)` | `segment.getUtf8String(0)` | `getString` 是 Java 22 起的名字，21 预览版叫 `getUtf8String`，两者都读 NUL 结尾 UTF-8 字符串 |

## 3. 源码改动清单

| 文件 | 改动 | 原因 |
| --- | --- | --- |
| `mixin/EntityMovementMixin.java` | 删除针对 `collideBoundingBox(CollisionContext, …)` 的注入器 | 1.21.11 的 `Entity` 只有 `collideBoundingBox(Entity, …)` 一个重载（实体版本仍在注入）。`defaultRequire: 1` 下这个注入器会造成加载即崩溃 |
| `mixin/EntityTrackingMixin.java` | `@Shadow … this$0` → `field_26936` | 1.21.11 官方映射把 `ServerLevel$EntityCallbacks` 的外部实例字段命名为 `field_26936`，`this$0` 不存在 |
| `mixin/LivingEntityStateMixin.java` | `method = "onSyncedDataUpdated"` → 补上描述符 `(Lnet/minecraft/network/syncher/EntityDataAccessor;)V` | 1.21.11 的 `Entity` 另有 `onSyncedDataUpdated(List)`，只写方法名会产生二义性 |
| `mixin/BodyFieldConsumersMixin.java` | 目标清单按 1.21.11 重新审计：删除 `monster.cubemob.MagmaCube/AbstractCubeMob/SulfurCube`，改为 `monster.MagmaCube`，新增 `monster.Slime`、`item.enchantment.effects.ApplyEntityImpulse` | 该清单必须覆盖所有直接读写 `Entity.position/deltaMovement/bb/needsSync/noPhysics` 字段的类；漏掉会让这些类读到未同步的 Java 字段。26.3 的 `cubemob` 包在 1.21.11 不存在，而 1.21.11 的 `Slime`、`ApplyEntityImpulse` 需要各自覆盖 |

`VanillaMethodDetector` 用到的反射方法名（`getTeam`、`canBeCollidedWith(Entity)`、`canCollideWith(Entity)`、
`push(Entity)`、`push(double,double,double)`、`getDeltaMovement`、`setDeltaMovement(Vec3)`、`doPush(Entity)`）
在 1.21.11 中全部存在，无需修改。

`@Accessor` / `@Shadow` 的其余成员（`position`、`bb`、`deltaMovement`、`needsSync`、`noPhysics`、
`SLEEPING_POS_ID`、`shape`、`get(III)`、`sectionStorage`、`entityManager`）在 1.21.11 中同样存在。

**native 源码（`native/`）没有任何改动。**

## 4. 构建产物与原生库

1.21.11 产物必须打包成 intermediary 命名空间。`remapJar` 在 Loom 1.17 里会直接重映射混入注解的取值
（例如 `@Inject(method="setBoundingBox(...)")` 变成 `method_5857(...)`，`@Mixin(Entity.class)` 变成
`class_1297`），因此不需要 refmap。

发布流水线仍然由固定工具链编译 Windows/Linux/macOS 三个平台。在没有该工具链的机器上：

```powershell
# 用本地 MSYS2/MinGW g++ 编译 Windows 原生库并打包仅含 Windows 原生库的 JAR
powershell -ExecutionPolicy Bypass -File build-local-windows.ps1
```

`native/build-windows-mingw.ps1` 复刻了 `native/CMakeLists.txt` 的 Windows 目标（`-O3`、`-mavx2`、
`-march=x86-64-v2`、`-fno-rtti`、`AR_WINDOWS`/`AR_X64` 等宏，以及对 `push_run.cpp`、
`movement_solver.cpp`、`voxel_geometry.cpp` 使用 `-ffp-contract=off`），并用 `-static`
把 libstdc++/libgcc/libwinpthread 全部静态链接，产物只依赖 Windows 自带的 UCRT/KERNEL32。

## 5. 验证记录

1. **编译**：`compileJava` 对 Minecraft 1.21.11 全部通过。
2. **混入目标静态校验**：把编译后的混入类与 1.21.11 官方映射 jar 做解析，逐条解析
   `@Inject`/`@WrapMethod`/`@WrapOperation`/`@Accessor`/`@Shadow` 的目标，**51 项全部命中、0 失败**
   （`required: true` + `defaultRequire: 1` 意味着任何一条解析失败都会在启动时崩溃）。
   复现方式（`tools/MixinTargetCheck.java`，需要 ASM，和 `BodyFieldAudit.java` 相同）：

   ```powershell
   javac -cp asm-9.10.1.jar;asm-tree-9.10.1.jar -d <out> tools/MixinTargetCheck.java
   java -cp asm-9.10.1.jar;asm-tree-9.10.1.jar;<out> MixinTargetCheck `
       <minecraft-merged-1.21.11-loom.mappings.*.jar> build/classes/java/main
   ```
3. **字段消费清单审计**：用 `tools/BodyFieldAudit.java` 的同类规则对 1.21.11 全量字节码重新审计，
   改写清单覆盖 `Entity` 及上表列出的 34 个类，`Entity` 内部无遗漏访问。
4. **真实运行**：`runServer`（Java 21 + `--enable-preview`）启动 Minecraft 1.21.11 + Fabric Loader 0.19.3，
   日志确认：
   * 混入全部应用成功、`Compatibility level set to JAVA_21`，无混入错误；
   * `Extracted FFM native library /natives/windows-x64/EntityCollisionOptimizer.dll`；
   * `FFM collision backend initialized`（18 个 FFM 符号全部解析成功）；
   * 服务器持续 tick 60 秒无异常。
   * `@Pseudo` 目标里 Carpet/Fuji 的类在本机不存在，只有预期的 `ClassNotFoundException` 警告。
5. **产物检查**：JAR 内为 intermediary 命名空间、混入注解已重映射、`natives/windows-x64/EntityCollisionOptimizer.dll`
   已打包，`fabric.mod.json` 声明 `minecraft ~1.21.11` / `java >=21`。

## 6. 命名空间陷阱（生产环境才暴露，已修复）

这是本次适配最深的一个坑，开发环境（GameTest、runServer）**永远测不出来**：

* 26.x 运行时使用 **Mojang 官方名**，所以 `BodyFieldAccess` 里写死的字符串
  （`net/minecraft/world/entity/Entity`、字段名 `position`/`deltaMovement`/`bb`/`needsSync`/`noPhysics`）
  在生产环境能对上。
* 1.21.11 运行时是 **intermediary**（`class_1297`、`field_22467`…），而 Loom 只重映射
  类引用、混入注解和 `@Shadow` 成员名，**不会重映射字符串常量**。
* 于是字段改写在生产环境**静默失效**：堆外 body 表不再与实体字段同步，native 移动解算读到过期数据，
  表现为**生物浮空、钻地、掉落物弹跳**，以及 `Entity position changed between movement solve and publication` 崩溃。

修复方式（不依赖任何映射表解析器）：从**模组自己的字节码**里取运行时名字——
`@Shadow` 字段名与 `@Invoker` 注解值都已被 Loom 重映射，读取它们即可得到正确名字。
另外加了两道硬校验：实体类 0 处改写、或访问器缺失时**直接报错**，不再静默降级。

> Fabric 生产环境的 `MappingResolver` **没有 `named` 命名空间**，
> `mapClassName/mapMethodName("named", …)` 会原样返回入参，不能用于这个场景。

### 6.1 同一路径上的第二个静默失效：公有标志字段的 owner

命名空间修好之后，`BodyFieldAccess.rewrite` 仍然**一个 `noPhysics` / `needsSync` 写入都没改到**。
原因不在命名空间，而在 owner 匹配：

* `position` / `deltaMovement` / `bb` 是 `Entity` 的 **private** 字段，只能从 `Entity` 自己的字节码里访问，
  所以 `PUTFIELD` 的 owner 必然是 `Entity` —— 精确匹配是对的。
* `noPhysics` / `needsSync` 是 **public** 字段（`Entity.java:243` / `:273`），任何子类都能直接写；
  javac 使用的 owner 是**接收者表达式的静态类型**，于是 `ItemEntity.tick()` 里的 `this.noPhysics = …`
  编译成 `PUTFIELD ItemEntity.noPhysics`（intermediary 下是 `class_1542`），`LivingEntity` 里的
  `this.needsSync = true` 编译成 `PUTFIELD LivingEntity.needsSync`。

旧代码对这两个字段也要求 owner 恰为 `Entity`（`matches(field, physics())`），
`isEntity(field.owner, node)` 又是与精确匹配**与**在一起的，等于死代码 —— 结果是：
**全游戏 38 处 `this.needsSync = …`、12 处 `this.noPhysics = …` 全部漏改**，
原生行里的 `NO_PHYSICS` / `SLEEPING` 守卫位与 `needsSync` 不再随实体字段刷新。

修复：这两个字段改为只比对成员（名字 + 描述符，`matchesMember`），owner 交给 `isEntity(owner)` 证明是
`Entity` 子类；`isEntity` 对无法解析的类返回 false（保持原样不改写）而不是抛异常。

验证：

* 契约测试 `shared_body_state_parity` 修复前稳定失败
  （`target noPhysics on body=0: expected (0.0, -0.0, -0.0), got (-0.010000000153668226, 0.0, 0.0)`），
  修复后 `ECO_PUSH_STATE_PARITY … result=passed`；
* 复现方式（编译后的测试类字节码）：

  ```powershell
  javap -p -c -classpath build/classes/java/gametest `
      org.edtp.entitycollisionoptimizer.gametest.PushStateParity | Select-String noPhysics
  # putfield net/minecraft/world/entity/LivingEntity.noPhysics:Z   ← owner 是 LivingEntity，不是 Entity
  ```

## 7. 测试套件适配状态

四套测试源码已随本次适配一起降到 1.21.11，并且**全部编译通过、全部可运行**：

| 套件 | 命令 | 1.21.11 实测结果 |
| --- | --- | --- |
| `src/gametest`（契约/单元） | `runGameTest -PunitTest` | 35 个测试，**35 通过**（全绿） |
| `src/integrationTest`（跨进程一致性） | `runGameTest -PintegrationTest` | **4/4 通过**，含原版基线逐字节轨迹比对 |
| `src/benchmarkTest`（压测） | `runGameTest -Pbenchmark` | **6/6 通过**，5 个场景产出性能数据 |
| `src/unitTest`（资源） | 随契约套件 | — |

主要测试侧改动：

* `EntityTypes` → `EntityType`（1.21.11 的常量仍在 `EntityType` 上）、`@GameTest` 移除 26.3 才有的 `padding`；
* `ChunkPos.pack/unpack` → `ChunkPos.asLong` / `new ChunkPos(long)`；`ChunkPos.x()/z()` → 字段 `x`/`z`；
* 维度时钟 `level.getDefaultClockTime()/clockManager()` → `getLevelData().getDayTime()` / `level.setDayTime()`；
* `Entity.setInvulnerableTime/getInvulnerableTime` → 公开字段 `invulnerableTime`；
* `Entity.syncVelocity`/`syncPosition` → 1.21.11 只有单一 `needsSync`；该字段属于被改写的堆外字段，
  测试里直接裸读与裸写不可比，故轨迹中不再输出这两个位（详见 `ZombieTrace` 注释）；
* `CollisionContext.positionContext(double)` → `withPosition(Entity, double)`；
* 1.21.11 没有 `Entity.collideBoundingBox(CollisionContext, …)`，`BlockContextParity` 改为用
  `Entity.collectAllColliders` + `collideWithShapes` 重建原版预言（这比 26.3 版里两次调用同一入口更有意义）；
* 26.x 独有的实体（硫磺方块、`cubemob` 包）替换为 1.21.11 的对应物（`MagmaCube`/`Slime`），
  快乐恶魂的"加热方块接触伤害"场景在 1.21.11 没有对应机制，已移除；
* **`vanilla-gametest` 子构建**：改用 `net.fabricmc.fabric-loom-remap` + `officialMojangMappings()` +
  `modImplementation`（同主构建），Java 21 + `--enable-preview`，`fabric.mod.json` 依赖改为 `~1.21.11`；
  `recordVanillaIntegrationTrace` 现在会把外层构建的 `--offline` 传递给子构建；
* **新增 `src/gametest/resources/data/minecraft/worldgen/world_preset/flat.json`**：1.21.11 的
  `GameTestServer` 写死使用 `WorldPresets.FLAT`（源码里 `.getOrThrow(WorldPresets.FLAT)`），
  26.3 时代的 `flat_all_dimensions.json` 不会被选中，导致压测用的 `benchmark_void` 维度不存在。
  该预设可被数据包覆盖，因此用等价的 `flat.json`（overworld 保留 bedrock/dirt/grass 分层、不加结构，
  另附 nether/end 与空的 `benchmark_void`）覆盖它，压测的虚空管道场景才得以运行。
* 新增跨进程场景（见第 8、9 节）：`falling-item-vehicles`、`step-lane`；
  新增压测场景 `sliding_minecarts`（配 `-PecoVanillaPaths` 做原版 A/B）。

### 已知失败项（均为测试期望问题，非模组缺陷）

无。三套件在 1.21.11 上全绿；此前记录的三项失败已逐个定性并处理：

1. `shared_body_state_parity` —— **真实缺陷**，见第 6.1 节（公有标志字段的 owner 匹配）。
2. `zombie_drowned_conversion_lifecycle` —— 测试期望问题：`Zombie.tick()` 先读 `isEyeInFluid`、
   再由 `super.tick()` 刷新 `fluidOnEyes`，所以第一 tick 会把预置的 `inWaterTime` 清成 -1。
   测试现在先 `baseTick()` 刷新一次眼部流体状态再播种 `setInWaterTime(600)`。
3. `entity_ticking_pushability` —— 测试期望问题：1.21.11 的 `LivingEntity.isPushable()`
   （`isAlive() && !isSpectator() && !onClimbable()`）**不再依赖区块 ticking**，26.3 的
   "非 ticking 区块里不可推动"前提不成立；同时新生成的实体要到下一个区块管理器 tick 才进入
   区块存储。测试改为验证真正的不变量：缓存推动性与原版谓词一致、非 ticking（TRACKED）区块里
   的实体仍然可被查询为候选（用 `thenWaitUntil` 等追踪回调，不再假定固定延迟）。

## 8. 移动解算的实体碰撞查询框（1.21.11 语义修正）

`EntityMovementCollision.solve` 曾按 26.3 的规则把**实体**碰撞查询框向上扩 `maxUpStep`：

```java
int[] hardIds = CollisionFrame.hardCollisionIds(entity, scan.expandTowards(0.0, entity.maxUpStep(), 0.0));
```

1.21.11 的 `Entity.collide`（`Entity.java:1059-1088`）只用 `boundingBox.expandTowards(movement)`
查询一次实体碰撞，并且**整份列表直接进入台阶尝试**；被 `maxUpStep` 重查的只有**方块**碰撞
（`collectColliders(..., aABB3)`）。因此旧写法会让"完全位于查询框上方、但在 `maxUpStep` 之内"的
**硬碰撞体**（船、潜影贝、处于 still timeout 的快乐恶魂）凭空参与台阶解算：

* 生物被半砖挡住时，本该踩上去，却因为头顶 0.6 格内的船/恶魂被判定为阻挡而**卡住并抖动**；
* 越靠近船/矿车/快乐恶魂越明显 —— 与用户报告的"异常碰撞区域"一致。

修复后 `Entity.collide` 的实体碰撞列表与原版逐个一致；方块（含台阶阶段）仍按原版规则查询。
跨进程场景 `StepLaneIntegrationGameTests`（半砖台阶 + 悬浮在头顶 2.1 格的船）在旧代码下
报 `step-lane trace differs at byte 6501`，修复后逐字节一致。

## 9. 仍未解决的行为差异（游戏内实测）

第 8 节的修复直接针对"生物被卡住并抖动"，但**尚未在用户环境实测确认**。另外两项：

* 掉落物落地后还会自己弹跳几下；
* 快乐恶魂碰撞箱异常。

已排除的服务器端原因（均为逐字节对齐原版）：

* `FallingItemVehicleIntegrationGameTests`：12 个物品从 6 格高落到石地板（含贴着船/矿车/
  快乐恶魂生成的物品，覆盖 `ItemEntity` 每 tick 的 `noPhysics`/`moveTowardsClosestSpace` 反弹路径）、
  船 + 矿车 + 快乐恶魂 + 8 个僵尸共 140 tick 的完整状态轨迹。
* `StepLaneIntegrationGameTests`、`ZombieCrammingIntegrationGameTests`（320 僵尸）、
  `TntCrowdIntegrationGameTests`（96 僵尸 + TNT）。

另外两项已结案、**与模组无关**（详见 `HANDOFF-1.21.11.md` 第 9、10 节）：

* 用户的 JVM 崩溃：Windows 事件日志显示 220 次 CPU 核心 WHEA 硬件错误（2026-06 起），
  同一崩点在**未安装本模组**的实例上自 8 月起复现 8 次 —— 硬件不稳定，见 `JVM-CRASH-REPORT.md`。
* 用户报告的"矿车让帧率从 240 掉到 100 多"：量化后确认原版同样如此，
  而模组的矿车碰撞路径比原版快约 1.5×（`sliding_minecarts` 场景 MSPT 4.037 → 2.688 ms）。

因此剩余差异更可能是**客户端可见的状态发布**或**未覆盖的场景**。为此新增了运行期开关
`/eco movement|push|index <on|off>`（单人存档**无需作弊**即可使用，见 `HANDOFF-1.21.11.md` 第 6、12 节），
可在一次游戏会话内把症状归因到具体子系统，或直接确认"关掉全部优化后症状是否仍在"。

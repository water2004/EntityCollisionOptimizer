# 交接文档：EntityCollisionOptimizer 的 1.21.11 适配

> 本文档汇总「把该模组从 Minecraft 26.3 降到 1.21.11 Fabric」这条任务线的全部关键信息：
> 环境事实、已完成的改动与验证、剩余问题的线索与下一步。**换新对话时先读这份文件。**
> 最后更新：owner 匹配修复 + 台阶查询框修复 + 测试全绿 + 运行期开关 + `/eco` 生存可用 + 版本冻结 alpha.10。

---

## 0. 一句话现状

模组已完成 1.21.11 适配，**三套测试全部全绿**（契约 35/35、跨进程 4/4、压测 6/6）。
版本号按用户与上游开发者的约定**冻结为 `1.0.0-mc1.21.11-alpha.10`**（见第 11 节）。
本次会话修掉两个**真实缺陷**（都属"静默失效"类）：

1. **公有标志字段的 owner 匹配**：`noPhysics`/`needsSync` 的写入（全游戏 38 + 12 处）此前**一处都没被改写**，
   原生行里的守卫位不刷新 → 契约测试 `shared_body_state_parity` 稳定失败。已修复并验证。
2. **移动解算的实体碰撞查询框多扩了 `maxUpStep`**（26.3 语义）：1.21.11 的 `Entity.collide`
   不会这样做 → 头顶 0.6 格内的「硬碰撞体」（船 / 潜影贝 / 快乐恶魂）会凭空挡住**台阶**，
   生物卡住抖动。已修复，并用新的跨进程场景证明旧代码会导致逐字节不一致。

另外：`/eco` 现在**在未开作弊的单人存档里也能用**（第 12 节）；用户报告的「矿车掉帧」经量化确认
是原版固有表现、与模组无关（第 10 节）；用户的**崩溃问题已查明是 CPU 硬件不稳定**（第 9 节）。

---

## 1. 环境事实（非常重要，很多"卡住"都源于这些）

### 1.1 无网络
这台机器**完全没有外网**（curl 对 meta.fabricmc.net / services.gradle.org / GitHub / bmclapi 全部返回 000），
本地代理 127.0.0.1:10810 未运行。所有依赖必须用本地缓存。

### 1.2 本地工具链
| 用途 | 路径 |
| --- | --- |
| Gradle 发行版 | `E:\Minecraft\MODS\.tools\gradle-9.5.1\bin\gradle.bat`（**wrapper 指向的 9.6.0 没缓存，不能用 `gradlew`**） |
| Gradle 用户目录（含 loom/MC 缓存） | `E:\Minecraft\MODS\.gradle-home`（必须设 `GRADLE_USER_HOME`） |
| JDK 21（编译必需） | `D:\JavaStudy\JDK\zulu-jdk-21`（已在 `.gradle-home\gradle.properties` 里通过 `org.gradle.java.installations.paths` 注册） |
| JDK 25（默认 java） | `D:\JavaStudy\JDK\zulu-jdk-25` |
| MinGW g++ 15.2（编原生库） | `D:\msys64\ucrt64\bin\g++.exe` |
| 反编译的 1.21.11 官方名源码（**API 权威依据**） | `E:\Minecraft\MODS\_research\mcsrc\src` |
| 1.21.11 intermediary 映射（namespaces: official/intermediary/named） | `.gradle-home\caches\fabric-loom\1.21.11\loom.mappings.1_21_11.layered+hash.2198-v2\mappings.tiny` |
| 用户的测试实例（游戏目录，PCL 版本隔离） | `E:\Minecraft\.minecraft\versions\1.21.11test`（日志：`...\logs\latest.log`，mods：`...\mods`） |
| 原版 jar 备份（修复前） | `E:\Minecraft\MODS\_eco-work\eco-original.jar` |
| TravelersBackpack 1.21.11 源码（用户是该作者） | `E:\Minecraft\MODS\_research\wt-1.21.11f` |

### 1.3 沙箱与脚本注意事项
* **文件写只允许 `E:\Minecraft\MODS` 之内**。往 `.minecraft` 装 jar 会被拒；需要一次
  `sandbox_permissions: danger-full-access` 升级重试（已验证可行，用户会收到审批）。**读** `.minecraft` 不受限。
  * 用户的游戏目录是 `E:\Minecraft\.minecraft\versions\1.21.11test`（PCL 版本隔离：`mods`、`logs`、`saves`、
    `options.txt` 都在这一层），所以**要装的 mods 目录是 `...\versions\1.21.11test\mods`**。
* `.ps1` 被执行策略禁止 → 用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File <脚本>` 运行。
* 当前 shell 是 **Windows PowerShell 5.1**（不是 pwsh）。
* **⚠️ PowerShell 5.1 读写 UTF-8 文档的坑（本次踩过，两个 .md 被写坏过）**：`Get-Content -Raw` 在没有 BOM 时
  按 ANSI/GBK 解码，`Set-Content -Encoding UTF8` 再写回 → 中文全部变成乱码且丢失换行，**不可逆**。
  改中文文档一律用 `edit`/`write` 工具，不要用 PowerShell 的 `-replace` + `Set-Content` 管道。
* **g++ 必须把 `D:\msys64\ucrt64\bin` 加到 PATH**，否则 cc1plus/as 静默失败（exit 1、无任何输出）。
* 编译日志经 PowerShell 会乱码/丢 stderr → **一律 `Out-File` / `*>` 到 `_eco-work\*.log` 再读**。

### 1.4 标准构建命令
```powershell
$env:GRADLE_USER_HOME="E:\Minecraft\MODS\.gradle-home"
Set-Location "E:\Minecraft\MODS\EntityCollisionOptimizer-main"
& "E:\Minecraft\MODS\.tools\gradle-9.5.1\bin\gradle.bat" --offline --no-daemon --no-watch-fs `
  remapJar -PnativeWindowsOnly -x compileNativeWin -x compileNativeLinux -x compileNativeMac
```
* `-PnativeWindowsOnly` 会放宽原生库检查（只要求 `EntityCollisionOptimizer.dll`），并打包单平台 jar。
* 测三套件：
  `runGameTest -PunitTest` / `-PintegrationTest` / `-Pbenchmark`（都要带上面的 native 参数）。
  * `-PunitTest` 会跑 `src/gametest/java`（35 个契约/单元测试），约 40 秒；**全绿**。
  * `-PintegrationTest` 会先跑 `vanilla-gametest` 子构建录基线（`build/gametest-baselines/*.bin`），
    再跑优化进程逐字节比对，约 60 秒；**4/4 全绿**。
  * `-Pbenchmark` 约 2.5~4 分钟；**6/6 全绿**（含矿车场景，见第 10 节）。
    加 `-PecoVanillaPaths` 可让压测场景全部走原版路径（A/B 用）。
  * 两个构建**不能并发**（同一个 `build/` 目录）；按顺序跑。日志一律 `*> _eco-work\xxx.log`。
* 本地原生库：`powershell -ExecutionPolicy Bypass -File native\build-windows-mingw.ps1`（等价 CMakeLists 的 Windows 目标，
  用 `-static` 静态链接 libstdc++/libgcc/**libwinpthread**，否则产物依赖 MSYS2 的 DLL 无法在普通机器加载）。
* 一键本地出包：`build-local-windows.ps1`。

---

## 2. 已完成的适配（配置与源码）

### 2.1 构建配置
* 插件必须换：上游用 `net.fabricmc.fabric-loom`（no-remap）——**26.x 运行时是 Mojang 官方名**；
  **1.21.11 运行时是 intermediary**，所以本仓库用 `net.fabricmc.fabric-loom-remap` + `loom.officialMojangMappings()`
  + `modImplementation`。
* `gradle.properties`：`minecraft_version=1.21.11`、`loader_version=0.19.3`、`loom_version=1.17-SNAPSHOT`
  （**只缓存了这个插件 marker**）、`fabric_api_version=0.140.0+1.21.11`、
  `mod_version=1.0.0-mc1.21.11-alpha.10`（**冻结，见第 11 节**）。
* **Java 21 + `--enable-preview`**（用户选择）：FFM 在 Java 21 仍是预览 API；JDK 22+ 编译器**无法**生成 Java 21
  预览类文件。构建 toolchain 固定 21，`options.release=21` + `--enable-preview`；运行时（游戏 JVM）也必须加
  `--enable-preview`（用户已在其启动脚本里加了）。
* `settings.gradle` 去掉了 foojay 插件（离线解析失败；CI 改成装 JDK 21）。
* CI `.github/workflows/build.yml` 改为 `java-version: '21'`。

### 2.2 FFM API 差异（2 处，语义等价）
* `Linker.Option.critical(false)` 在 Java 21 不存在 → 直接不传 Option（默认即非 critical）。
* `MemorySegment.getString(0)` → `getUtf8String(0)`（21 预览版名字）。

### 2.3 混入目标（1.21.11 vs 26.3）
* 删除针对 `Entity.collideBoundingBox(CollisionContext, …)` 的注入器（1.21.11 只有实体版重载；留着会加载即崩）。
* `EntityTrackingMixin`：`@Shadow this$0` → **`field_26936`**（1.21.11 官方映射给外部实例字段的名字）。
* `LivingEntityStateMixin`：`onSyncedDataUpdated` 补描述符（1.21.11 另有 `(List)` 重载，只写名字有二义性）。
* `BodyFieldConsumersMixin` 目标清单按 1.21.11 字节码**重新审计**：删 `cubemob.*`，`MagmaCube` 归位，
  新增 `Slime`、`item.enchantment.effects.ApplyEntityImpulse`。
* 验证工具：`tools/MixinTargetCheck.java`（静态解析全部混入目标，本次 51/51 命中）。

### 2.4 原生库
C++ 源码**零改动**。用 MinGW g++ 编出 Windows DLL（18 个 FFM 符号全部导出，`-static` 后只依赖系统 UCRT/KERNEL32）。
发布流水线仍用固定交叉工具链编三平台。

---

## 3. 核心根因：命名空间陷阱

**现象**：用户真实客户端进世界 1 秒后崩 `IllegalStateException: Entity position changed between movement solve and
publication`；随后（加安全回退后）表现为**生物浮空、钻地、掉落物弹跳**。

**根因**：`BodyFieldAccess`（`collision/bytecode/`）用**字符串常量**匹配字段：
`"net/minecraft/world/entity/Entity"` + `"position"/"deltaMovement"/"bb"/"needsSync"/"noPhysics"`。
* 26.x 运行时就是官方名 → 匹配成功。
* 1.21.11 运行时是 intermediary（`class_1297`、`field_22467`…），而 **Loom 只重映射类引用、混入注解和 `@Shadow`
  成员名，不会重映射字符串常量** → 改写**静默全部失效** → 堆外 body 表不再与实体字段同步 → native 解算读到过期数据。
* 反过来，`VanillaMethodDetector` 用**反射**（`getDeclaredMethod("doPush"…)`）→ 生产环境全部 `NoSuchMethodException`
  → 所有 `usesVanilla*` 返回 false（保守退化，正确但慢，也让原生推动路径从未真正启用）。

**两条走不通的路（别重试）**：
1. 直接写死的官方名 → 就是原 bug。
2. `FabricLoader.getMappingResolver().mapClassName/mapMethodName("named", …)` → **生产环境 resolver 没有 `named`
   命名空间**，原样返回入参（用户日志里报 `Could not map … to the runtime namespace intermediary`）。

**最终修复（正确做法）**：从**模组自己的字节码**里取运行时名字——这些已被 Loom 重映射好：
* 字段身份：扫 `EntityBodyMixin` 的 `eco$readPosition/eco$readVelocity/eco$readBounds/eco$readNeedsSync/
  eco$writeNoPhysics` 方法体里的 `GETFIELD/PUTFIELD`（跳过 `eco$*` 自有字段），得到
  `(owner=class_1297, name=field_22467, desc=class_243)`；
  **owner 必须从 `@Mixin(Entity.class)` 注解值取**（mixin 内部的 shadow 字段引用 owner 是 mixin 自己！），注解值已被重映射。
* 方法名：新增 `EntityMemberNames` / `LivingEntityMemberNames` 两个**只做名字来源**的 `@Invoker` 接口混入
  （`@Invoker("getTeam")` 等，Loom 会把 value 重映射成 `method_5781` 等），`VanillaMethodDetector` 用 ASM 读回这些值。
* 另加两道**硬校验**：实体类 0 处改写 / 访问器缺失 / `@Invoker` 缺失 → **直接抛异常**，不再静默降级。
* 位置不一致不再崩服：`NativeMovement.destination()` 返回 null，混入回退到原版 `Vec3.add`，并打限频
  `ECO_MOVEMENT_POSITION_MISMATCH` 警告（带 solved/observed 原始位、displacement、是否被重写过）。

**验证证据（用户生产环境日志）**：修复后 `ECO_MOVEMENT_POSITION_MISMATCH` 计数 **0**、无任何报错、
`positionRewrittenBeforePublication` 不再触发，用户反馈「生物的碰撞好多了」。

**同时修好的隐患**：`EntityBodyMixin.eco$writePosition` 里加了 `NativeMovement.POSITION_WRITES` 计数器
（AtomicLong，每次位置写入 +1）——它是判断"改写是否真的生效"的唯一廉价指标，**保留它**（`writes=0` 就是改写失效）。

---

## 4. 测试套件适配（已完成，可作为回归工具）

| 套件 | 命令 | 1.21.11 实测 |
| --- | --- | --- |
| 契约/单元 | `runGameTest -PunitTest` | 35 跑完，**35 通过** |
| 跨进程一致性 | `runGameTest -PintegrationTest` | **4/4 通过**（原版基线逐字节轨迹比对） |
| 压测 | `runGameTest -Pbenchmark` | **6/6 通过**，5 场景出性能数据 |

关键点：
* `EntityTypes`→`EntityType`、移除 26.3 的 `@GameTest(padding=)`、`ChunkPos.pack/unpack`→`asLong`/`new ChunkPos(long)`、
  `ChunkPos.x()/z()`→字段、`positionContext(double)`→`withPosition(Entity,double)`、
  `setInvulnerableTime/getInvulnerableTime`→公开字段 `invulnerableTime`、
  `syncVelocity/syncPosition`→1.21.11 只有单一 `needsSync`（`ZombieTrace` 里不再输出这两位，见文件注释）。
* `BlockContextParity` 重写为 `Entity.collectAllColliders` + `EntityCollisionInvoker.eco$collideWithShapes` 构造原版预言。
* `SingleCellParity`：`CubeVoxelShape` 在 1.21.11 是 `final` + protected 构造 → 改用私有 `SubdividedBox extends VoxelShape`。
* `EntityTickingPushabilityParity`：没有 `runBeforeTestEnd` → 每步失败即清理（`cleanupOnFailure`）。
* `vanilla-gametest` 子构建：改 remap 插件 + mappings + `modImplementation` + Java 21 preview；其
  `src/main/resources/fabric.mod.json` 依赖改 `~1.21.11`；`recordVanillaIntegrationTrace` 现在把外层 `--offline` 传给子构建。
* **新增 `src/gametest/resources/data/minecraft/worldgen/world_preset/flat.json`**：1.21.11 的 `GameTestServer`
  源码写死 `.getOrThrow(WorldPresets.FLAT)`，26.3 的 `flat_all_dimensions.json` 不会被选中，压测的
  `benchmark_void` 维度因此不存在。该预设可被数据包覆盖，用等价 `flat.json`（overworld 保留 bedrock/dirt/grass、
  不加结构，另附 nether/end 与空 `benchmark_void`）覆盖后压测全过。

**测试现状（本次会话结束时）**：契约 35/35、跨进程 4/4、压测 6/6，**全绿**。
原先记录的三项失败全部定性：`shared_body_state_parity` 是**真实缺陷**（已在 5.2 修掉），
另两项是测试期望问题（`zombie_drowned_conversion_lifecycle` 先 `baseTick()` 刷新眼部流体再播种；
`entity_ticking_pushability` 改为验证 1.21.11 真正的不变量）。

---

## 5. 剩余问题（未解决）

### 5.1 现象（用户实测，命名空间修复后）
1. **掉落物落地后还会自己弹几下**（`ItemEntity`：会按 `level().noCollision(...)` 反复改 `noPhysics`）。
2. **船和矿车在特定角度碰撞后出现异常碰撞区域，生物/动物被卡住并一直抖动**（幻影碰撞区）。
3. **快乐恶魂（Happy Ghast）碰撞异常**。

### 5.2 ★ 本次会话已修的两个真实缺陷（接手前先读这里）

#### 缺陷 A：公有标志字段的 owner 匹配（`BodyFieldAccess.rewrite`）

**症状**：契约测试 `shared_body_state_parity` 稳定失败：
`target noPhysics on body=0: expected (0.0, -0.0, -0.0), got (-0.010000000153668226, 0.0, 0.0)`
——给目标设了 `noPhysics` 后原生推动**仍然生效**。

**根因**（`collision/bytecode/BodyFieldAccess.java`）：

* `position` / `deltaMovement` / `bb` 是 `Entity` 的 **private** 字段 → `PUTFIELD` 的 owner 必然是 `Entity`，
  精确 owner 匹配没问题。
* `noPhysics` / `needsSync` 是 **public** 字段（`Entity.java:243` / `:273`），子类可以直接写，
  而 javac 用的 owner 是**接收者表达式的静态类型**：
  `ItemEntity.tick()` 的 `this.noPhysics = …` → `PUTFIELD ItemEntity.noPhysics`；
  `LivingEntity` 的 `this.needsSync = true` → `PUTFIELD LivingEntity.needsSync`。
* 旧代码对这两个字段也要求 `owner == Entity`（`matches(field, physics())`），而 `isEntity(field.owner, node)`
  又与它**与**在一起（恒真、等于死代码）→ **全游戏 38 处 `this.needsSync = …`、12 处 `this.noPhysics = …`
  一处都没被改写** → 原生行 `STATE_OFFSET` 里的 `NO_PHYSICS` / `SLEEPING` 位和 `SYNC_OFFSET` 不随实体字段刷新。

**修复**：新增 `matchesMember`（只比对名字 + 描述符），owner 交由 `isEntity(owner)` 证明是 `Entity` 子类；
`isEntity` 遇到无法解析的类返回 false（保持原样不改写）而不是抛异常。
**复现命令**（权威证据）：

```powershell
javap -p -c -classpath build/classes/java/gametest `
    org.edtp.entitycollisionoptimizer.gametest.PushStateParity | Select-String noPhysics
# 修前/修后都是 putfield net/minecraft/world/entity/LivingEntity.noPhysics:Z  ← owner 是 LivingEntity！
```

**验证**：修复后 `ECO_PUSH_STATE_PARITY entity_counts=2,8,20 cached_transitions=22 … result=passed`，
契约套件 35/35 全绿。

#### 缺陷 B：移动解算的实体碰撞查询框被 `maxUpStep` 撑大（26.3 语义）

**症状**：生物被台阶挡住时本该踩上去，却像撞到"幽灵墙"一样卡住并抖动 —— 用户报告的
「船/矿车/快乐恶魂附近出现异常碰撞区域」正是这类现象。

**根因**：`collision/blocks/EntityMovementCollision.solve` 曾经这样查询实体碰撞：

```java
int[] hardIds = CollisionFrame.hardCollisionIds(entity, scan.expandTowards(0.0, entity.maxUpStep(), 0.0));
```

1.21.11 的 `Entity.collide`（`Entity.java:1059-1088`）只用 `boundingBox.expandTowards(movement)` 查**一次**
实体碰撞，并把这**整份列表**交给台阶尝试；只有**方块**碰撞会用台阶框（`aABB3`）重查。
于是"完全在查询框上方、但离头不到 `maxUpStep`（0.6 格）"的**硬碰撞体**
（`canBeCollidedWith` 非默认的实体：船、潜影贝、still-timeout 的快乐恶魂）会凭空参与台阶判定。

**修复**：实体碰撞查询框改回 `scan`（= `bb.expandTowards(movement)`，与原版一致）；
方块（含台阶阶段）仍按原版规则查询。
**验证**：新增跨进程场景 `StepLaneIntegrationGameTests`（半砖台阶 + 悬停在头顶 2.1 格的船 + 2 个持续前压的僵尸）
在**旧代码**下报 `step-lane trace differs at byte 6501`，修复后**逐字节一致**。
旧的单元测试 `StepEntityCollisionParity` 编码的正是 26.3 规则，已改写为 1.21.11 规则
（查询框上方的碰撞体不参与台阶；查询框内的碰撞体仍然参与）。

### 5.3 仍未定位：掉落物弹跳 / 快乐恶魂

**已经用逐字节跨进程场景排除的服务器端原因**（这些场景都通过，说明对应物理与原版完全一致）：

* `FallingItemVehicleIntegrationGameTests`（新增）：12 个物品从 6 格高落在石地板上，其中 4 个**贴着
  船 / 矿车 / 快乐恶魂 / 僵尸**生成（覆盖 `ItemEntity` 每 tick 的 `noPhysics` + `moveTowardsClosestSpace`
  反弹路径 —— 注意 `moveTowardsClosestSpace` 会给物品 **0.1~0.3 的随机速度**并 `scale(0.75)`，
  这就是"弹跳"的机制），加上船 + 矿车 + 快乐恶魂 + 8 个僵尸，共 140 tick 的完整状态轨迹，**逐字节等于原版**。
* `ZombieCrammingIntegrationGameTests`（320 僵尸）、`TntCrowdIntegrationGameTests`（96 僵尸 + TNT）。

因此这两项更可能是**客户端可见的状态发布**（`needsSync` → `ServerEntity` 的
`ClientboundSetEntityMotionPacket`；`ItemEntity` 的 `updateInterval=20`、`trackDeltas()=true`）
或**未覆盖的场景**（水/岩浆里的物品、玩家交互、其它模组）。

**下一步就用运行期开关做游戏内二分**（见第 6 节第 8 条），把现象归因到具体子系统。

### 5.4 其它已确认正确的部分（别重复排查）

* C++ `push_run.cpp` 的推动公式与 1.21.11 `Entity.push(Entity)` 逐项一致（sqrt/min/0.05F/0.01F 全部对齐），
  且 `noPhysics`/`SLEEPING` 守卫齐全（`push_run.cpp:67/70`）。
* `natives/` 下的行布局、`CollisionPushStates.refresh()` 的发布时机（在 `executePushRun` 之前）、
  `CollisionStateTable.sourceSlot()` 的刷新调用链都正确 —— 缺陷 A 是"变更没被登记"，不是"发布路径不通"。
* `LevelCollisionFrame` 的索引成员管理（`trackingStarted/Ended`、`updateSection`、`updateBoundingBox`）
  与 1.21.11 的 `EntitySectionStorage`/`forEachAccessibleNonEmptySection` 语义对齐
  （`LookupSections` 的 ±2 X/Z、-4 Y 扩展与原版逐项一致）。
* `hardCollidable` 元数据只是查询预筛（`hardOnly=1` 时用），Java 侧始终用
  `source.canCollideWith(target)` 复核，所以快乐恶魂的**条件性** `canBeCollidedWith` 不会产生误判。
* `BodyFieldConsumersMixin` 的清单与 `BodyFieldAccess` 的改写规则本身没问题（问题在 owner 匹配）。
* FFM 调用点的缓冲区尺寸与边界检查已逐一复核：`outputBuffer` 为 `3 + 2*capacity` ints
  （native 侧 `bodySlots = outputBuffer + 3 + capacity` 正好落在末尾），`nativePushBuffer`/`runIdBuffer`
  各自独立分配，`executePushRun` 会校验 `targetStart/targetCount/targetSlots` 且把 slot 拷进
  `runIdBuffer` 后才下发；结论是**模组不存在越界写**（与第 9 节的崩溃归因一致）。

---

## 6. 可复用的诊断手法（都验证过有效）

1. **看用户日志**：直接读 `E:\Minecraft\.minecraft\versions\1.21.11test\logs\latest.log`（读不受沙箱限制）。
2. **装诊断版**：`remapJar` 后用一次 `danger-full-access` 升级把 jar 复制进实例的 `mods\`（原版备份在 `_eco-work\eco-original.jar`），
   然后请用户进世界 → 读日志。
3. **开发环境复现**：`runServer` + 数据包召唤实体。**注意坑**：无玩家时区块不 tick 实体，`Entity.move` 根本不会被调用
   （我踩过一次，白白"复现成功"）。数据包放在 `run\world\datapacks\eco_repro\`（forcesay+summon+forceload 可参考）。
4. **`-PcompatModsDir=<目录>`**：把生产模组重映射进开发环境。**限制**：带 access widener 的模组（如 TravelersBackpack）
   会因 namespace 不匹配失败；含非 ASCII 文件名的 jar 会因编码问题加载失败（先重命名成 ASCII）。
5. **静态工具**（`_eco-work\` 里还有 `DumpMove.java` / `audit-classes\`）：
   * `tools/BodyFieldAudit.java`：审计某字段在字节码里的所有读写（`-Deco.audit.field=…`、`-Deco.audit.inventory=…`）。
   * `tools/MixinTargetCheck.java`：把编译产物与映射 jar 对比，逐条解析混入目标（需要 ASM 9.10.1 + asm-tree，jar 在
     `.gradle-home\caches\modules-2\files-2.1\org.ow2.asm\`）。
   * `DumpMove`（临时工具）：dump 某方法里 `Vec3.add`/`Entity.collide` 等指令的**序数**（用来确认
     `@WrapOperation(ordinal=1)` 落在哪条指令上）。
6. **读映射表**：`mappings.tiny` 是 tab 分隔，类行 `c<TAB>official<TAB>intermediary<TAB>named`，
   成员行**以 tab 开头**：`<TAB>f<TAB>desc<TAB>official<TAB>intermediary<TAB>named`（成员行 `$p[0]` 是空串！）。
7. **反编译源码**是最好的 API 依据：`E:\Minecraft\MODS\_research\mcsrc\src`（1.21.11 官方名）；
   查方法可用 `select-string`/grep。
8. **运行期二分开关（游戏内排查首选）**：`/eco` 可以单独关掉三个子系统，每次都回退到**原版路径**（不是中间态），
   因此一次会话就能定位症状来源。**单人存档不需要开作弊**（见第 12 节）。

   | 命令 | 关掉的东西 |
   | --- | --- |
   | `/eco` 或 `/eco check` | 打印当前开关状态 + FFM 初始化状态 |
   | `/eco movement <on\|off>` | 原生位移裁剪/台阶解算（`Entity.move`/`Entity.collide`） |
   | `/eco push <on\|off>` | 原生推动批次 + 原生候选筛选（`LivingEntity.pushEntities`） |
   | `/eco index <on\|off>` | 原生空间索引（方块查询与实体碰撞查询改走原版；movement 的实体碰撞改由原版查询提供） |
   | `/eco all <on\|off>`、`/eco on`、`/eco off` | 一次全开/全关 |

   排查顺序建议：先 `/eco all off`，若症状**仍在**，说明与模组优化无关（或属于客户端/其它模组）；
   若消失，再逐个 `/eco movement off` → `/eco push off` → `/eco index off` 找出唯一能消除症状的那一个。
9. **解码跨进程轨迹**：`build/gametest-baselines/*.bin` 是 Java `DataOutputStream`（**大端**）写的
   `ZombieTrace`/场景帧；`_eco-work\read-steplane.ps1` 是一个可用的解码样例（注意逐字段字节数对齐）。
10. **性能 A/B**：`-Pbenchmark`（优化路径）对比 `-Pbenchmark -PecoVanillaPaths`（原版路径），
    场景确定性一致，可直接比 MSPT。
11. **JFR 抓热点**（客户端问题也能采样）：启动参数加
    `-XX:StartFlightRecording=filename=eco.jfr,settings=profile,duration=180s`，
    再用 `D:\JavaStudy\JDK\zulu-jdk-21\bin\jfr.exe print --events jdk.ExecutionSample` 聚合。

---

## 7. 本次会话改动的文件清单

**模组本体（本次会话）**
* `collision/bytecode/BodyFieldAccess.java` —— **缺陷 A 修复**：`matchesMember`（public 标志字段只比对名字+描述符，
  owner 用 `isEntity` 证明）；`isEntity` 对无法解析的类返回 false。
* `collision/blocks/EntityMovementCollision.java` —— **缺陷 B 修复**：实体碰撞查询框不再扩 `maxUpStep`；
  新增 `EntityColliders`（原生硬碰撞 id / 原版查询列表二选一），`index` 开关关闭时用原版查询。
* `OptimizerSwitches.java`（**新增**）：movement / push / index 三个 volatile 开关 + `describe()`。
* `mixin/LivingEntityMixin.java`、`mixin/EntityMovementMixin.java`、`mixin/EntitySectionStorageMixin.java`、
  `mixin/CommonLevelAccessorMixin.java` —— 接入上述开关（关闭即走原版调用）。
* `commands/CollisionOptimizerCommand.java` —— `/eco` 开关命令树；`on`/`off` 快捷形式；
  **权限放宽到"单人无需作弊"**（见第 12 节）。
* `gradle.properties` —— 版本保持 `1.0.0-mc1.21.11-alpha.10`（加注释说明冻结）。

**模组本体（前几次会话，仍然有效）**
* `mixin/EntityBodyMixin.java`、`mixin/EntityMemberNames.java`、`mixin/LivingEntityMemberNames.java`、
  `collision/VanillaMethodDetector.java`、`natives/NativeMovement.java`、`mixin/EntityTrackingMixin.java`、
  `mixin/LivingEntityStateMixin.java`、`mixin/BodyFieldConsumersMixin.java`、构建文件与原生库脚本。

**测试源码（本次会话）**
* `src/integrationTest/java/.../FallingItemVehicleIntegrationGameTests.java`（**新增**，场景 `falling-item-vehicles`）：
  物品落地 + 贴着船/矿车/快乐恶魂的物品 + 船/矿车/快乐恶魂 + 8 僵尸，140 tick 逐字节比对原版。
* `src/integrationTest/java/.../StepLaneIntegrationGameTests.java`（**新增**，场景 `step-lane`）：
  半砖台阶 + 头顶悬浮的船 + 2 个持续前压的僵尸（缺陷 B 的回归测试）。
* `src/integrationTest/java/.../IntegrationArena.java`（新增 `setBlock`）。
* `src/integrationTest/resources/fabric.mod.json`、`vanilla-gametest/src/main/resources/fabric.mod.json`
  （注册两个新场景；**注意 `fabric-gametest` 入口点必须有 public 无参构造**）。
* `src/gametest/java/.../MinecartCollisionBenchmark.java` + `MinecartBenchmarkChamber.java`（**新增**，
  压测场景 `sliding_minecarts`）与 `src/benchmarkTest/resources/fabric.mod.json`（注册）。
* `build.gradle`（新增 `-PecoVanillaPaths` → `-Deco.benchmark.vanillaPaths=true` 的 vmArg 钩子）。
* `src/gametest/java/.../StepEntityCollisionParity.java`（改写为 1.21.11 规则）、
  `.../EntityTickingPushabilityParity.java`（改写为 1.21.11 真实不变量；用 `thenWaitUntil` 等追踪回调，
  不再假定固定延迟）、`.../CollisionContractGameTests.java`（溺水转化测试先 `baseTick()` 刷新眼部流体）。
* `_eco-work\read-steplane.ps1`（轨迹解码脚本，可复用）。

**文档**
* `JVM-CRASH-REPORT.md`（**新增**：崩溃归因到 CPU 硬件的完整证据链）
* `PORTING-1.21.11.md`、`HANDOFF-1.21.11.md`（本文档）、`README.md` / `README_zh.md`（命令与权限说明）

---

## 8. 下一步建议（按性价比排序）

1. **请用户用当前构建实测**（已装进 `E:\Minecraft\.minecraft\versions\1.21.11test\mods\`）：
   * 若「生物被台阶/船/矿车/恶魂卡住抖动」**消失** → 缺陷 B 命中，收尾。
   * 若「掉落物弹跳」/「快乐恶魂」仍在 → 用 `/eco all off` 判断是否与优化有关（第 6 节第 8 条）；
     若仍是问题，再逐子系统开关定位，并把结论写回本文档。
2. **未覆盖的场景**：水/岩浆中的物品、玩家推动物品、与其它模组（TravelersBackpack / AdvancedNetherite）
   的交互。若第 1 步显示与模组无关，优先怀疑这些；若与 `push` 相关，重点查
   `allowsDeferredVelocityWrites`（1.21.11 只有单一 `needsSync`）。
3. **客户端可见的发布路径**：`entity.needsSync` 现在只有在**已绑定**的实体上才走堆外行
   （未绑定实体仍写真实字段）；`ServerEntity`/`ChunkMap` 的读取都在改写清单内。
   若怀疑客户端不同步，可用 `/eco index off`/`all off` 对比客户端表现。
4. **回归**：任何改动后跑 `runGameTest -PunitTest`（35/35）、`-PintegrationTest`（4/4，含逐字节基线）、
   `-Pbenchmark`（6/6）。三套件本次会话全绿，可直接作为基线。

---

## 9. ⚠️ 用户机器存在**长期 JVM 崩溃**（已证明与模组无关，别浪费时间重查）

2026-10-04 16:25 用户报「又崩溃了一次」，排查结论与完整证据见仓库根目录 **`JVM-CRASH-REPORT.md`**。要点：

* 本次是 **HotSpot C2 编译器崩溃**（`jvm.dll+0x311cc2`，线程 `C2 CompilerThread0`），崩溃时正在编译
  **原版** `LocationPredicate.matches`（`class_2090::method_9018`）；`replay_pid3748.log` 的 `compile`
  内联树里**没有任何模组方法**。
* **同一个崩点（`jvm.dll+0x311cc2` + DEP 违例，地址低位都是 `…1cc2`）历史上已出现 8 次**，
  其中 7 次在另一个实例 `E:\Minecraft\.minecraft\versions\1.21.11Fabric`
  —— 该实例 `mods` 里**从未安装过本模组**。
* 那个实例共有 **49 个 hs_err 日志（2026-08-22 ~ 10-02）**：崩溃方法包含纯 JDK 方法
  （`java.util.jar.Attributes::read`、`ComparableTimSort::binarySort`、`DecimalFormat::applyPattern`）、
  JNA、以及 JIT 编译产物（`# J … c2 …`：lithium/sodium/voxy/xaero/starlight/fastutil…）、
  JVM 桩（`StubRoutines::jint_disjoint_arraycopy`）、`VM Thread`、`GC Thread#0`、`nvoglv64.dll`。
  **42 次 Zulu 21.0.11、7 次 Zulu 25.0.4** → 与 JDK 版本、与模组都无关。
* 判断：**CPU 硬件不稳定，已由 Windows 官方日志确认**：
  `Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Microsoft-Windows-WHEA-Logger'}`
  显示 **2026-06-17 ~ 2026-10-04 共 220 次 WHEA 硬件错误**，全部由 **Processor Core** 上报：
  206 次 `Internal parity error` + 10 次 `Translation Lookaside Buffer Error`（APIC ID 24/25），
  几乎每天都有。这正是"随机方法、随机线程、跨 JDK 版本、不装模组也崩"的原因，典型 Intel 13/14 代
  Raptor Lake（`Model 183`）降级问题。
* 建议用户：更新 BIOS/EC 并恢复 Intel 默认/标准性能档、关掉一切降压与超频；若仍持续 WHEA 报错 → CPU 已降级，
  走保修（13/14 代 Intel 已延长保修，笔记本找 OEM）。临时缓解：JVM 参数加 `-XX:TieredStopAtLevel=3`。

**下次用户再报崩溃时**：先看 `hs_err` 的 `Problematic frame` 与 `Current CompileTask`；
如果又是 `jvm.dll+0x…` / `# J … c2 …` / VM/GC 线程，直接归类为环境问题（引用 `JVM-CRASH-REPORT.md`），
不要再去改模组代码。只有出现**模组自己的异常栈**（Java 异常、`IllegalStateException`、
`VerifyError`、FFM `callFailure` 等）才属于模组问题。

---

## 10. 性能：用户报告「十几辆矿车让帧率从 240 掉到 100 多」（已结案：非模组问题）

用户 2026-10-04 反馈：矿车一多（十几辆）帧率从 240 掉到 100 多；没装模组时最多掉十几帧。
**用户随后自行复核：大量矿车运动时 MSPT 不高、优化也正常，但帧率和原版一样低 —— 即那个掉帧是原版在该场景下的固有表现，不是模组引起的。**

**新增的量化工具**（保留，用于以后任何"帧率/MSPT 回归"）：`MinecartCollisionBenchmark` /
`MinecartBenchmarkChamber`，场景 `sliding_minecarts`：24 辆矿车（对向两排、每 tick 重新给速度）+ 64 个僵尸，200 tick。
`-PecoVanillaPaths` 会让同一场景把三个子系统全部切回原版路径（`OptimizerSwitches.all(false)`）；
场景是确定性的，所以两侧 workload 完全一致、可直接比较。

| 场景（同一 workload） | 原版路径 `-PecoVanillaPaths` | 优化路径（默认） |
| --- | --- | --- |
| `sliding_minecarts` mean / median / p95 MSPT | 4.037 / 2.711 / 6.212 ms | **2.688 / 2.049 / 3.802 ms** |
| `falling_zombies` mean MSPT | 441.3 ms | 394.5 ms |

**结论：服务器端矿车/船所在的那条"实体碰撞立方体"路径（`hardOnly=0`，即缺陷 B 的邻居路径）
比原版快约 1.5×，p95 也更低。帧率问题不是模组引起的。**

**如果以后再报帧率问题**：先按「MSPT vs 帧率」分流 ——
F3 的 `ms tick` 正常而帧率低 → 客户端渲染/环境问题（可用 JFR，见第 6 节第 11 条）；
`ms tick` 也高才回到模组侧，用 `/eco movement|push|index off` 逐项二分。注意这台机器的 CPU 已确认不稳定（第 9 节），
帧率会自带抖动，比较时要固定视角/视距/方块。

---

## 11. 版本号已冻结为 `1.0.0-mc1.21.11-alpha.10`（不要再自行提升）

用户已与上游模组开发者沟通，**版本号保持 `alpha.10`**。因此：

* `gradle.properties` 的 `mod_version=1.0.0-mc1.21.11-alpha.10`，改动处已加注释；
  `fabric.mod.json` 用 `${version}` 占位，只有 `gradle.properties` 需要维护。
* **同一个文件名对应两个不同内容的 jar**（2026-10-04 15:13 的旧构建带 namespace/owner/台阶三个缺陷），
  所以用 **SHA-256** 区分本次交付：
  `F589938EFE9C1B0C7BED272610F802B26B6E4D2205B0F395049CA99A52689BA9`（401503 字节，构建于 2026-10-04 19:19）。
* **游戏内自检**（不需要看哈希）：
  1. `/eco` 在**单人存档、未开作弊**时可用（见第 12 节）；
  2. `/eco` 会打印 `movement=… push=… index=…`；
  3. `/eco off` / `/eco on` 快捷形式存在。
  旧构建这三条都不成立（命令要求权限等级 2，且没有开关）。

---

## 12. `/eco` 现在在生存模式（未开作弊）也能用

**问题**：原实现用 `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` 作为 `requires`，
在单人未开作弊时权限等级为 0 → 命令不可用。

**1.21.11 的坑（关键）**：命令节点是否标记为"受限"由
`Commands.COMMAND_NODE_INSPECTOR.isRestricted(node)` 决定 =
`!node.getRequirement().test(Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS))`，
而这个编译上下文是 **无权限、无 server（`getServer() == null`）** 的 source。
受限节点会被客户端翻译成 `requires(hasPermission(ClientPacketListener.RESTRICTED_COMMAND_CHECK))`，
**未开作弊的客户端不会通过** → 就算服务端放宽，客户端也不会把命令发出去。

**解决**（`commands/CollisionOptimizerCommand.java`）：

```java
private static boolean mayUse(CommandSourceStack source) {
    if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) return true;   // 1.21.11 用 PermissionCheck
    MinecraftServer server = source.getServer();
    return server == null || server.isSingleplayer();   // 编译上下文放行 → 节点不被标记受限
}
```

* 单人（集成服务器）：世界所有者**无需作弊**即可使用 ✓，客户端也能看见/补全 ✓；
* 专用服务器：真实 source 的 `getServer()` 非空且非单人 → 仍要求管理员才能执行 ✓
  （非管理员客户端可能看到该命令，但服务端会正常回绝）；
* 新增快捷形式 `/eco on`、`/eco off`（等于 `all on|off`）；`/eco` 与 `/eco check` 打印状态。

验证：`runGameTest -PunitTest` 35/35 通过（命令注册在 gametest 服务器上也会走节点检查）。

# Minecraft 1.21.1 适配与验证

分支 `codex/mc-1.21.1`；保留上游 FFM 原生后端，目标限定 Minecraft 1.21.1。

## 实现

- Mojang mappings、Loom 1.10.5、Gradle 8.12.1；游戏规则、伤害、命令、碰撞形状及实体 API 回迁。
- `needsSync` 映射为 `hasImpulse`，反射与 ASM 使用 Fabric MappingResolver，适用于正式 intermediary 环境。
- Entity.move 的目标位置注入迁移到 1.21.1 的 `setPos(DDD)` 发布点，保留原生事务生命周期。
- 原生推挤遵循 1.21.1 未过滤非有限速度值的行为；轴向裁剪保留有符号零并按原版顺序移动包围盒。
- 移除该版本不存在的 CollisionContext 重载注入、ApplyEntityImpulse 字段消费者；排除 Creaking 注入及该版本不存在实体的测试分支。
- 测试迁移涵盖旧版区块票据、GameTest 时钟、NoAI 物理条件、玩家出生保护和服务器 PvP 开关；保留原始行为断言。

## 构建与运行

运行需要 **Java 22+** 和 `--enable-native-access=ALL-UNNAMED`。本次运行使用 Windows x64、Java 25。Java 21 无法运行正式 FFM API。

Gradle 用 Java 21 启动，编译及 GameTest 使用 JDK 25 toolchain，正式字节码目标 Java 22。示例：

```powershell
$env:JAVA_HOME = '你的 JDK 21 路径'
.\gradlew.bat build '-Porg.gradle.java.installations.paths=你的 JDK 25 路径'
.\gradlew.bat runGameTest -PunitTest '-Porg.gradle.java.installations.paths=你的 JDK 25 路径'
.\gradlew.bat runGameTest -PintegrationTest '-Porg.gradle.java.installations.paths=你的 JDK 25 路径'
```

## 2026-09-26 验证

- 完整 build/remapJar 成功；Windows/Linux/macOS x64 原生库交叉编译成功。
- **31/31 GameTests 通过**：原生几何、推挤、查询、字段同步、生命周期、移动及交互场景。包括纯几何原版 oracle、90 组实际 Entity.push 原生结果比较，以及边界值/并发原生内存池检查。
- 扫描 8,269 个原版类，五类实体字段消费者清单覆盖检查通过。
- 独立未安装 ECO 的原版进程与安装 ECO 的进程各通过 2 个集成测试，轨迹逐字节相同：320 僵尸/200 tick 为 15,440,180 字节；TNT+96 僵尸/100 tick 为 2,338,942 字节。测试强制检查两进程中 ECO 的加载状态，详情见 [独立基准说明](vanilla-gametest/README_1.21.1.md)。
- 正式 intermediary JAR 与其余四模组共装进入真实客户端世界，FFM DLL 提取与初始化成功，打印/补货/连锁挖掘等 10 项自动场景通过。

产物：`build/libs/entity_collision_optimizer-1.0.0-mc1.21.1-alpha.5.jar`。
运行测试后，Gradle 报告位于 `build/reports/`。生成的验证日志和 JAR 不纳入版本控制。

## 验证边界

部分上游交互用例的 enabled 参数未切换原版实现，两遍结果属于重复性检查；不能将其单独解释为原版等价证明。独立原版对照证据来自上述两进程逐字节轨迹与明确调用原版几何/推挤方法的 oracle。

跨进程场景关闭 AI，以固定物理与生命周期输入。未验证全部 AI、所有模组组合、长期多人负载或性能提升。Linux/macOS 库仅完成交叉编译，未在对应操作系统实机运行；未验证 ARM。上游历史性能图表不代表本次 1.21.1 测量结果。

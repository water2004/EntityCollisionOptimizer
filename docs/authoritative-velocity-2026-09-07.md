# 共享权威速度状态与热点验证（2026-09-07）

本文保留 C1/C2 阶段的实现与测量记录。后续已将共享行扩为 64 字节、统一接管 `needsSync`、删除 Java 发布循环；当前所有权边界见 [后续状态与统一 CPU 报告](current-unified-hotspots-2026-09-07.md)。文中的旧类名和 40 字节布局不是当前接口。

本次将原有逐段快照/写回改成每维度持久状态表，再将速度权威从 Java 字段迁入共享行。
未追加旧实现压测对照；以下改前数据来自已经采集的 B2，改后录制用于验证新热点及行为测试。

## 数据所有权与接口

- `CollisionStateTable`：每个 `LevelCollisionFrame` 独占。稳定 body slot 与每帧空间索引 ID 分离；共享行是 `[x,z,vx,vy,vz]`，40 字节。仅扩容搬迁整表，不为每段重复收集或拷回速度数组。
- 位置是原版世界的权威数据，immutable `Vec3` 引用变化使只读位置输入失效。速度接入表后，其数值仅以共享行为准；Java 写速度也直接修改这一行。
- `PushBatch`：持有稳定槽位数组，不再填充或清空候选 `Entity[]`。段前仍检查实时推动条件、刷新位置；FFM 提交源槽位、目标槽位和动作标志，native 直接原位累加速度。
- `EntityVelocityMixin`：普通推动返回后仅更新 `needsSync`、失效快照，不读取三个共享 double，不分配 `Vec3`。第一次实际读取才从共享行生成快照，后续无写入的读取复用同一对象。
- 原版 `setDeltaMovement` 的有限值检查保留在原位置；接管的是实际字段读写。原生冲量、源速度逐次累加、带符号零、非有限输入与和溢出的规则未改变。
- 特殊推动前，数值和同步标志已经生效；特殊逻辑访问速度时读到当前值。不会等到 tick 结束再兑现冲量，也不合并改变逐次浮点运算顺序。
- 关闭表、实体退休和显式停用/Carpet 接管会解绑并物化最新速度。换表先读取旧权威；旧表回收不能覆盖新表状态。未完成批次持有租约，期间不回收槽位。扩容保留槽位和内容。

`Arena.ofShared` 提供带生命周期检查的共享内存；没有 Unsafe 堆指针、全局生命周期别名、JNI 或逐实体 native 回调。状态按世界线程访问，跨维度独立；没有增加多线程推动或密度分流。

## 注入面与审计

新增一个 `EntityVelocityMixin`（生产 Mixin 总数由 13 到 14），以及一个只处理 `Entity.deltaMovement` 的字段访问转换器。没有按模组名称枚举消费者。

`VelocityMixinPlugin.postApply` 在访问器和普通注入完成后，把这个私有字段的 GETFIELD/PUTFIELD 统一改为 `eco$readVelocity` / `eco$writeVelocity`。仅三个内部存储方法允许直接访问未绑定字段。要求原版字段仍为预期私有字段，并检查 getter、setter、构造器均被覆盖；结构不符直接报错，不回退。

这一执行时机依据 [Mixin 的应用阶段实现](https://github.com/SpongePowered/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/transformer/MixinApplicatorStandard.java)，并对实际使用版本及最终导出字节码核验。

`tools/VelocityFieldAudit.java` 扫描原版 jar、11 个优化模组及其嵌套 jar。实际实体速度字段在原版仅由 Entity 构造器、getter、setter 访问；组合中另有 Carpet 的 `Entity_scarpetEventsMixin.firstPos` 读取。其他同名字段属于传送记录，不是 Entity 的字段。

最终组合导出的 `Entity.class` 审计结果：

- 原版构造器、getter、setter 全部转入统一入口。
- `handler$…$carpet$firstPos` 转入 `eco$readVelocity`。
- 测试中另外一个 Mixin 生成的原字段 getter/setter 均转入同一入口。
- 除三个内部存储方法外，不剩该字段的直接访问；工具对此有失败断言。

兼容范围仍是已核对的 Minecraft 26.2 和优化模组版本。反射/Unsafe 读取实体私有字段不会被此转换器拦截；绑定时该物理字段可以是旧值。任意其他模组、未来版本或另一个转换器在此之后追加字段访问，不在无条件兼容承诺内。

## 行为验证

纯 Fabric 与完整 11 模组组合均通过全部 **21 项 GameTest**；测试进程正常退出，最终 `build runGameTest -Pparity` 成功，三平台 native 库编译及 JAR 打包成功。运行验证平台为 Windows x64。

保留全部已有原版实体/方块/玩家/机械交互差分测试，并增加：

- 2、8、20 个实体，连续 7 段 native 推动期间不读 Java 速度，最终对实际原版逐对推动结果逐位比较。
- 先由独立的 Mixin 字段访问器读结果，再用 getter；验证同版本快照复用、原字段写入、Java 速度相加、无效 setter 不覆盖尚未读取的 native 值。
- 用反射只检查“没有提前写回物理字段”这一实现条件；行为 oracle 仍是真正的原版推动，不通过反射比较失效字段。没有修改旧测试来跳过访问器一致性检查。
- 同步标志立即生效；读取快照不能复活已消费标志。
- 2、8、20 个实体轮换源/目标，12 个阶段，跨帧复用；收集之后改变位置、速度、推动和原始非有限状态。
- 槽位稳定、带租约退休、扩容、回收复用、旧/新表所有权迁移，以及未观察结果时的解绑/关闭/显式停用与再次接管。
- 既有普通/船/普通特殊回调边界、90 组整段/拆段逐位比较、225 对 epsilon 邻界输入和 4 组算术序列继续通过。
- 三维度 13,500 次查询及 4,500 段并发 native 推动继续通过。

优化组合：Lithium 0.25.3、Carpet 26.2+v260906、FerriteCore 9.0.0、ServerCore 1.5.19、C2ME 0.4.2-alpha.0.43、VMP 0.2.0-beta.7.236、Krypton 0.3.1、Alternate Current 1.9.0、ScalableLux 0.3.0-alpha.0.3、Noisium 2.8.5、Worldthreader 3.1.0。

## JFR：目标热点变化

相同现有场景：1000 只普通 AI/重力僵尸、1×2×1 真实石头围栏、1 亿生命、真实生存玩家击退 II 钻石剑；120 tick 预热、600 tick 测量。仅选测量窗口内主世界线程事件。

| 指标 | 已有 B2：逐段发布 | C1：共享权威 | C2：扩展测试后的共享权威 |
| --- | ---: | ---: | ---: |
| Java ExecutionSample 数 | 1289 | 1062 | 1110 |
| 内存生命周期检查叶子 | 132（10.24%） | 1（0.09%） | 14（1.26%） |
| `eco$publishBody` 叶子 | 142（11.02%） | 0 | 0 |
| Vec3 分配采样权重 | 16809.10 MiB | 395.08 MiB | 407.40 MiB |
| 全类型分配采样权重 | 26067.79 MiB | 10621.77 MiB | 9669.34 MiB |
| mean / median / p95 MSPT | 57.776 / 56.474 / 69.520 | 53.581 / 51.997 / 66.667 | 54.609 / 53.592 / 62.233 |
| 接受攻击 / 观察到击退 | 14 / 14 | 16 / 16 | 13 / 13 |

Java 叶子百分比只以各次 Java ExecutionSample 为分母，不是整个 tick 的 CPU 时间，更不能与 NativeMethodSample 相加。分配权重是 JFR 估算，不是精确计数。MSPT 有机器波动，且接受攻击次数不同，不据此宣称固定提升百分比。

证据支持“大量逐对 Vec3 创建和逐字段共享内存访问已经消除”，不支持“Java 推动组织开销全部消失”。方法内联会改变叶子归属，`publishBody` 为零不能单独证明整段开销为零：C2 的 `invalidateVelocity` 仍有 **76 个 Java 叶子样本（6.85%）**。

C2 的主要剩余 Java 叶子：方块扫描 `Scan.run` 17.48%、推动状态 `pushState` 13.69%、调色板读取 11.26%、速度快照失效 6.85%、`PushBatch.applyNativeRun` 2.97%、槽位到实体读取 2.88%。候选实时条件、位置刷新、标志发布仍在 Java。

C2 另有 534 个 NativeMethodSample：候选查询边界 394、推动执行边界 133；这些只能定位 native 调用范围，不能把它们当成 native 内部各函数的 CPU 占比。

## 本地复查

原始文件均在 `D:\ar-profiles`，不纳入发布包：

- `eco-state-table-b2-20260907.jfr`：1788755260567..1788755295358，线程 ID 32136。
- `eco-authoritative-c1-20260907.jfr`：1788756216837..1788756249074，PID 36744，线程 ID 66552。
- `eco-authoritative-c2-20260907.jfr`：1788756488562..1788756521384，PID 4364，线程 ID 33252。
- 同名 `.log` 为压测/测试/正常退出记录；最终纯 Fabric 构建测试见 `eco-authoritative-final-build-parity-20260907.log`。

```powershell
java tools/profiling/JfrWindowSummary.java D:/ar-profiles/eco-authoritative-c2-20260907.jfr 1788756488562 1788756521384 worldthreader_minecraft:overworld
# 使用本地 ASM 9.10.1 jar 作为 classpath；先用 mixin.debug.export 导出组合的最终 Entity 类。
java --class-path <asm-jar> tools/VelocityFieldAudit.java build/run/gameTest/.mixin.out/class/net/minecraft/world/entity/Entity.class
```

摘要工具现也识别混入 Minecraft 类中的 `eco$` / `entityCollisionOptimizer$` 方法，避免把这些本模组方法误列为外部调用。

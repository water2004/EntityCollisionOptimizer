# 当前热点：Java 与 native 同一 CPU 口径（2026-09-07）

结论：已经值得优先优化 native。当前 native DLL 占主世界线程 CPU 样本的 **41.66%**；其中候选排序与查询合计 **30.74%**，推动段执行 **10.82%**。下一步首选消除重复排序，而不是继续微调速度 getter 或只优化冲量公式。

本次只采样、分析并生成报告，没有依据本报告继续修改碰撞算法。采样对应当前未提交工作树：持久 64 字节实体状态表、权威速度/版本/needsSync、按变化刷新的推动状态、原版 inBlockState 失效钩子，已经删除 Java 段后发布循环与客户端专用注入。不是旧实现的地址重解析。

## 统一口径与质量检查

- 场景：1,000 只正常 AI/重力僵尸、1 亿生命、1×2×1 真实石室、生存玩家击退 II 钻石剑；11 个优化模组全部启用，包括 Lithium、Carpet、ServerCore、FerriteCore、C2ME、VMP、Krypton、Alternate Current、ScalableLux、Noisium、Worldthreader。
- 120 tick 预热、600 tick 测量；仅选游戏 PID **66444**、主世界 OS TID **65140**，Unix 毫秒 **1788759478055..1788759510495**。
- ETW/WPR `SampledProfile`，周期 **1 ms**，统一分母 **30,743**。不是把 JFR `ExecutionSample` 和 `NativeMethodSample` 相加，也不是按 JFR 分布分摊未知地址。
- 当前 JVM 的 JVMTI 代码地址/生命周期映射共 33,305 条；16,997 个原未识别样本恢复 16,996 个，只剩 **1 / 0.0033%** 的内核范围地址。1 ms 与 10 ms 生命周期保护得到相同结果。
- 丢失事件、丢失缓冲区均为 **0**。另一个按 native 模块地址范围统计的分析器独立复核了 30,743 总样本、12,808 DLL 样本。
- native 使用 Release/O3/LTO，只增加 PDB 符号。运行时 DLL 与冻结符号目录的 DLL SHA-256 完全相同：`E5D27A82AED0D73B0D0B1F41B653144658696FF0FC336157108FB528E7EB8563`。
- 全部 **22 项 GameTest** 通过，55 次攻击、16 次接受且均观察到击退；游戏正常退出 0。WPR 辅助进程正常结束，已确认没有正在运行的 WPR 录制。系统范围原始采样仅留本地。

## 当前热点列表

下表按执行指令所在的编译方法/原生函数归属，包括编入该方法的内联代码；不包含另外执行的被调用函数。各行互斥，可以相加。

| 路径 | 所在端 | 样本 | 占主世界线程全部 CPU 样本 |
| --- | --- | ---: | ---: |
| 方块扫描 `OrderedBlockColliders.Scan.run`，含内联区块访问 | Java | 5,556 | 18.07% |
| 每次查询后的候选排序 `std::_Sort_unchecked` + `_Med3_unchecked` | native | 5,501 | 17.89% |
| 候选查询主体 `queryPushableEntities`，不重复包含上述排序 | native | 3,950 | 12.85% |
| 推动段 `executePushRun` | native | 3,326 | 10.82% |
| 绑定检查、位置信息准备 `Entity.eco$synchronizeBody` | Java | 2,912 | 9.47% |
| 单独编译的区块获取/缓存方法，见下方定义 | Java | 1,514 | 4.92% |
| 推动入口与分段组织，见下方定义 | Java | 1,489 | 4.84% |
| 其他 Java、native、JVM、系统及唯一未识别样本 | 混合 | 6,495 | 21.13% |
| 总计 | | 30,743 | 100.00% |

区块获取/缓存这一行由 `ServerChunkCache.getChunkBlocking` 680、`NewChunkHolderVanillaInterface.scheduleChunkGenerationTask` 359、`ServerChunkCache.addToCache` 349、`ServerChunkCache.getChunkNow` 126 组成。它们不全都能仅凭编译方法名归因于碰撞调用。

推动入口与分段组织由 `PushBatch.usesNativePush` 632、`PushBatch.applyNativeRun` 451、`LivingEntity.pushEntities` 406 组成，不是仍有逐候选 Java 速度写回。

全线程大类为：Java JIT **16,464 / 53.55%**；本模组 native **12,808 / 41.66%**；JVM 生成代码 **532 / 1.73%**；`jvm.dll` **252 / 0.82%**；其余系统/运行库及未识别 **687 / 2.23%**。热点表的“其他”进一步分为其余 Java 4,993、其余 native 31、全部 JVM/系统/未识别 1,471；没有新的大块未解析 Java 地址。

### 方块扫描这一行不能直接理解为碰撞箱运算

`Scan.run` 的 5,556 个样本包括内联代码。稀疏内联 PC 视图中，4,169 个样本落在 `C2ME instrumentGetChunk ← getChunkForCollisions ← Scan.chunk ← Scan.run` 附近；同窗口 JFR 也指向这条链。

这支持继续研究区块取得成本，但 **不是精确证明 C2ME 自身多花了 13.56%**，更不能把这部分再加到 18.07% 上。把形状数学搬进 native，不能自动省掉区块取得、加载状态检查等工作。

## native 已经拿到了哪些信息

查询侧已有 AABB、网格成员、section 坐标与原版插入顺序、可选中状态、队伍和碰撞规则、稳定 body slot。推动侧共享行已有执行前刷新的 X/Z、权威速度、版本、needsSync，以及可推动/载客/乘客/睡眠/noPhysics/根载具身份。

所以排序和候选处理的主要数据已经在 native 内，不需要为了每次比较回调 Java。当前缺口主要是：Java 仍逐目标检查绑定并刷新位置，候选槽位仍从 native 拷进 Java 数组再提交回 native；方块形状仍由 Java 按原版上下文取得。

## 优先顺序与可尝试方案

1. **先处理 17.89% 的重复排序。** 现在 `query_api.cpp` 每次查询都对结果调用 `std::sort`；排序键只是 section 与插入顺序。可以让 native 空间单元维护可复用的有序成员，成员/排序键变化时更新，查询时有序合并并去重。需保留 section 边界、插入/移出/再进入顺序，以及元数据失效后刷新排序键的时机。排序工作从“每个来源重新排序”转为“结构变化时维护”；所有密度仍使用同一算法。
2. **将查询结果留在 native，并减少 9.47% 的 Java 位置准备。** 用有租约的 native 候选快照承载段边界，避免 ID 的往返拷贝；位置变化时刷新共享镜像，代替每个候选重复检查。必须保留 `setPosRaw`、`setPos`、AABB 和世界通知的独立时序，以及挤压伤害、特殊推动回调之间的观察边界，不能简单整 tick 合并提交。
3. **再优化 12.85% 的查询主体及 10.82% 的推动循环。** 可以围绕有序、连续的数据布局进一步做批量筛选/冲量计算；这份采样只定位到函数，没有证明瓶颈是平方根、缓存未命中或某条具体指令。不要先据此替换数值公式。低/中密度对原版的状态序列与逐位边界测试继续作为约束，不改碰撞数量上限、不按密度切换路径。

优先排序的理由是占比大、数据已经驻留 native、工作可复用，而且不需要先引入行为近似。按当前样本份额做理想化估算，若排序成本减半且没有新增成本，约能少 **8.95%** 的该线程 CPU 工作；这不是承诺同等 MSPT 提升。

## 耗时与复查

这次同时开 WPR/JFR/JVMTI 的均值/中位数/p95 为 **53.961 / 52.805 / 62.864 MSPT**。同一实现之前两次仅 JFR 的均值为 61.803、63.710；运行波动明显。本次用于统一归因，不能拿这些不同录制直接计算实现前后的收益，也不把更高的 native 占比解释为 native 绝对耗时增长。

本地文件前缀 `D:\ar-profiles\eco-body-unified-20260907`：`.etl`、`.jfr`、`.tsv`、`.log`、`-groups.csv`、`-owners.csv`、`-near-leaves.csv`、`-near-stacks.csv`、`-unresolved.csv`。全部逐方法热点见 `-owners.csv`，近邻视图只作补充，不与主表相加。

其他审计文件为 `eco-body-unified-{summary,native-check,tracestats,frequency,jfr-summary}-20260907.txt`，以及 `eco-body-unified-wpr-20260907.log`。

ETL UTC 起点 `2026-09-07T05:37:43.5581788Z`；相对测量窗口 `14496822..46936822` 微秒。统一分析复现命令：

```powershell
java -Xmx3g -cp D:/ar-profiles/jit-map-classes UnifiedCpuSummary D:/ar-profiles/eco-body-unified-20260907.tsv D:/ar-profiles/eco-body-unified-samples-20260907.csv 66444 65140 2026-09-07T05:37:43.5581788Z 1788759478055 1788759510495 D:/ar-profiles/eco-body-unified-review
```

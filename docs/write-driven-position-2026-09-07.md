# 写入驱动的位置镜像与提前绑定（2026-09-07）

历史阶段记录：本文的 Java 位置权威、64 字节行及 25 项测试已被后续 [native 移动与位置权威](native-movement-position-2026-09-07.md) 更新；本文保留原始实现和测量作为基线。

本轮消除 `PushBatch.applyNativeRun` 的逐目标绑定检查/位置准备循环。基线直接复用 [上一轮查询优化](native-query-2026-09-07.md) 的联合 CPU 采样，没有重跑旧实现。

## 实现

- `CollisionStateTable.slot` 在返回候选槽位前保证绑定已建立；首次绑定或换表时才初始化位置、速度和同步标志。已有绑定不重新采集位置/速度。查询元数据不能发布一个尚未绑定的 body slot。
- `Entity.position` 仍由原版维护，Java getter、字段引用身份及整个移动流程不变。现有 `BodyFieldAccess` 统一转换该字段的写操作：`eco$writePosition` 先写原字段，再同步共享行 X/Z，在后续原版 section/world 回调前完成。没有扩大到位置读取接管，也没有让 native 决定实体位置。
- 构造、普通移动、`setPosRaw` 及合并进 Entity 的访问器走同一个写入口；不依赖 `setBoundingBox` 或方法返回时补同步。单独改碰撞箱不改位置，原版位置和 AABB 的独立性保留。
- 删除 `eco$synchronizeBody` 和整个逐目标准备循环，删除 `positions[]` 引用缓存数组。推动段只保留一次来源槽位获取、原有的脏语义状态刷新以及 native 调用。
- 绑定/解绑、扩容、批次借用保护及换表继续使用既有表生命周期。实体持有 table/slot，而不是可能在扩容后失效的裸内存片段；解绑后的位置写入不访问共享内存，重新绑定时初始化最新位置。
- 维度之间不增加共享表或锁。native 源码、DLL、64 字节共享行布局、碰撞数学、候选顺序、挤压规则、特殊回调边界均未改变。没有新增 Mixin 目标或客户端专用注入，也没有密度分流、候选截断或静默回退。

这不是把原来的逐候选循环挪到另一个位置：推动段不再访问每个目标的 Java Entity。镜像更新随位置字段写入发生，绑定检查随槽位获取发生；不存在每个来源再扫描所有目标的位置刷新阶段。候选数组复制与 ID 往返仍保留，不宣称它们已经消除。

## 一致性与字节码审计

纯 Fabric、原有 11 优化模组组合都通过 **25 项 GameTest**；既有实体/方块/流体/机械/玩家/网络同步及三维度并发测试均保留。

新增：

- `PositionWriteParity`：2/8/20 实体，每组 12 阶段。先收集完整有序候选，再修改来源和目标位置，使用实际原版 `target.push(source)` 序列作为 oracle，逐位比较 native 拆分段的全部速度和同步标志。覆盖原始位置写入、跨单元移动、独立 AABB 修改、合并访问器写入、活塞移动、原始跨 section 位移，以及外层批次借用期间嵌套帧重建。最后还加强了嵌套查询的原版候选顺序对照。
- `PositionMirrorChecks`：不重新查询、不重新绑定、不执行 native，直接在字段写入后读取共享行，验证它已经更新。覆盖 Java 位置引用身份、负零、相邻 double、远坐标、AABB 独立性、借用期间扩容、换表、旧表清理、解绑、槽位复用和关闭后继续移动。
- 既有 `CollisionStateTableChecks` 继续验证未观察的 native 速度/同步标志在绑定、扩容、换表、解绑期间保持，不被提前绑定覆盖。

表迁移测试与三维度并发测试不等于穷举所有 Worldthreader 传送调度。本轮没有另行改变实际跨维度调度机制。

输入字节码审计扫描 Minecraft 和完整优化模组目录（包括嵌套 JAR），原版 Entity 的位置直接写入在构造和 `setPosRaw`。组合环境仅导出最终 Entity 类作审计，不改变生成字节码；严格检查结果：3 个路由写入（含独立测试访问器），唯一原始写入留在 `eco$writePosition` 内。Carpet 合并的位置读取继续直接读原字段。记录：

- `D:\ar-profiles\eco-position-field-input-audit-20260907.txt`
- `D:\ar-profiles\eco-position-final-field-audit-20260907.txt`
- `D:\ar-profiles\eco-position-parity-p1-20260907.log`
- `D:\ar-profiles\eco-position-final-build-parity-20260907.log`
- `D:\ar-profiles\eco-position-final-pure-parity-20260907.log`（加强嵌套查询断言后的纯 Fabric 复验）

## 同一坐标系的 CPU 结果

同样的 1,000 普通 AI/重力僵尸、1 亿血量、真实石室、生存玩家击退 II 钻石剑，以及相同 11 个优化模组；120 tick 预热、600 tick 测量。两次使用 1 ms ETW + JIT 映射 + 匹配 native PDB，分母分别为该次主世界线程测量窗口内的全部 CPU 样本。Java 编译方法包含自身内联代码，不重复包含另行执行的被调用函数；JFR 只用于辨认线程/辅助核对，不与 ETW 相加。

| 项目 | 上一轮查询优化 | 写入驱动镜像 |
| --- | ---: | ---: |
| 全线程 CPU 样本 | 21,292 | 19,697 |
| Java JIT 总计 | 12,998 / 61.05% | 10,936 / 55.52% |
| 原 `eco$synchronizeBody` | 2,741 / 12.87% | 方法及逐目标循环已删除 |
| `CollisionStateTable.slot` 编译方法 | 87 / 0.41% | 67 / 0.34% |
| Java 方块扫描 `Scan.run`，含内联区块访问 | 3,478 / 16.33% | 4,572 / 23.21% |
| native 查询主体 | 3,141 / 14.75% | 3,221 / 16.35% |
| native 合并 | 1,035 / 4.86% | 1,034 / 5.25% |
| native 推动 | 2,911 / 13.67% | 3,263 / 16.57% |
| native DLL 总计 | 7,133 / 33.50% | 7,575 / 38.46% |
| mean / median / p95 MSPT | 36.534 / 35.582 / 43.526 | 33.933 / 32.721 / 42.532 |
| 接受攻击 / 观察到击退 | 13 / 13 | 17 / 17 |

Java 总样本减少 **15.9%**，全线程样本减少 **7.5%**，平均 MSPT 观察值下降 **7.1%**。原准备循环确实删除，但新绑定和写入维护不是零成本：它们可以内联在调用者中，不能把一个方法名不再出现解释成所有相关成本都为零。当前 `slot` 整个编译方法仅 0.34%，近邻内联信息中 `eco$bindBody` 为 5 个样本，未形成另一个同量级热点；近邻视图不是精确指令归因，也不能与主表相加。

这仍不是稳定性能承诺。有效攻击数与实际运动不同，Java 方块扫描、native 推动等样本增加；native DLL 本身完全未变。不能把原先 12.87% 全算成最终收益，也不能仅凭 native 占比上升断言 native 变慢。当前主要热点为方块扫描/区块访问，以及 native 查询与推动；本轮不继续扩大到这些路径。

## 采样复核

- PID **73564**，OS TID **24044**，线程 `worldthreader_minecraft:overworld`。
- Unix 毫秒窗口 **1788766217068..1788766237519**；ETL UTC 起点 **2026-09-07T07:30:04.1381539Z**，相对微秒 **12929847..33380847**。
- 丢失事件/缓冲区 **0**；33,636 条 JIT 代码记录；11,393 个原未识别样本全部恢复，最终未识别 **0**；1 ms/10 ms 生命周期保护结果相同。
- 独立 native 地址分析器复核 **19,697** 全线程样本与 **7,575** DLL 样本。
- native 未修改，使用此前冻结目录 `D:\ar-profiles\eco-query-unified-symbols-20260907`；DLL SHA-256 为 `2709726680BB05313EB3714E86BE550D833393F6095A9357599436968736802D`。
- 数据前缀 `D:\ar-profiles\eco-position-unified-20260907`，包括 ETL/JFR/JIT TSV/日志与 groups/owners/near-leaves/near-stacks/unresolved CSV；另有 tracestats、frequency、native-rvas、native-check、JFR 窗口报告。系统级原始采样只留本地，不上传。
- 实际抽取 DLL 与冻结符号对应 DLL 的哈希已核对一致。WPR 已停止，辅助进程和游戏进程均正常退出；最终 JAR 包含三平台 x64 库，不含 GameTest、PDB、JIT agent 或客户端专用注入。Windows 上运行验证，其他平台为构建验证。

```powershell
java -Xmx3g -cp D:/ar-profiles/jit-map-classes UnifiedCpuSummary D:/ar-profiles/eco-position-unified-20260907.tsv D:/ar-profiles/eco-position-unified-samples-20260907.csv 73564 24044 2026-09-07T07:30:04.1381539Z 1788766217068 1788766237519 D:/ar-profiles/eco-position-review
```

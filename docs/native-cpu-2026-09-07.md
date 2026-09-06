# 原生 CPU 采样（2026-09-07）

## 结论

Windows WPR 的 CPU 采样确认：在测量窗口内，主世界 tick 线程有 32,214 个 CPU 样本，其中本模组原生 DLL 为 4,215 个（13.08%）。原生部分不能忽略，但不是该线程 CPU 样本的多数。

| 原生函数 | CPU 样本 | 占该线程全部样本 |
| --- | ---: | ---: |
| `queryPushableEntities` | 2,981 | 9.25% |
| `calculatePushImpulses` | 1,205 | 3.74% |
| 其他本模组原生函数 | 29 | 0.09% |

这里是 DLL 内指令的采样归属，不包括 Java 端候选准备、FFM 桥接或 DLL 外的运行时成本。26,962 个样本（83.70%）落在未映射到 PE 模块的地址，不能靠 Windows 的模块符号将这些 JIT/动态代码地址进一步命名；Java 热点需结合同次 JFR 判断。

之前 `jfr view hot-methods` 只查询 `ExecutionSample`，没有包含 `NativeMethodSample`。它不能当作整个模组的 CPU 热点排序。旧录制中的 217 个本模组 FFM 原生状态样本（查询 152、冲量 58、帧构建 7）也不能直接与 Java 样本数相除来估计 CPU 占比。

## 测量口径

- 同一套 1,000 僵尸真实石室、正常 AI/重力、生命一亿、生存玩家击退 II 剑场景，加载完整优化模组组合。
- `Release` + O3 + LTO + `/OPT:REF /OPT:ICF`，只增加 `/Z7 /DEBUG:FULL` 生成 PDB，不切换 Debug 构建。添加 `-PnativeSymbols` 开启符号，常规构建默认关闭；PDB 不打入发行 JAR。
- WPR 的 `CPU` 配置，1,000 微秒采样周期；ETL 报告丢失事件及缓冲区均为 0。
- 游戏 PID 44708，主世界 tick 线程 OS TID 32592（由同次 JFR 的线程事件确认）。没有把整个 Java 进程的 GC、JIT、其他维度或其他进程算入分母。
- 测量起止 Unix 毫秒：1788717599434 / 1788717632501；ETL 起点 UTC 为 `2026-09-06 17:59:54.9668263`，因此截取相对 4.467174–37.534174 秒。排除预热、启动、其他 GameTest 和停止时的元数据收集。
- 分母使用全部 CPU 采样事件，不只使用成功采到调用栈的事件；后者本线程只有 32,071 个，会引入栈展开成功率偏差。
- 同次压测均值 54.979 MSPT，中位数 54.370，p95 61.641；55 次攻击，11 次接受且全部产生击退。全部 8 项 GameTest 通过。带 WPR 的耗时不与之前无 WPR 的均值直接相减来计算优化收益。

本次仅建立 native 的成本口径，没有依据指令采样宣称缓存未命中、分支预测失败等硬件原因，也没有改动碰撞算法。继续优化应优先处理已确认的碰撞主循环与方块状态访问，并由前后测试决定收益。

## 本机复现记录

原始文件位于 `D:\ar-profiles\eco-native-cpu-20260907a.{etl,jfr,log}`；ETL 含系统范围的原始采样，不上传、不加入 Git。提升权限的辅助进程只运行 WPR 启停，结束后已退出；构建与游戏仍以普通权限运行。

```powershell
./gradlew.bat runGameTest -Pbenchmark -PnativeSymbols -PcompatModsDir=<优化模组目录> -PjfrOutput=<录制.jfr>
# 单模式另设置 ECO_BENCHMARK_PROFILE=optimized。
# 管理员终端在预热时启动，在结果打印后停止：
wpr -start CPU -filemode
wpr -stop <录制.etl>
# 本次时间范围，单位微秒：
xperf -i <录制.etl> -symbols -a profile -detail -range 4467174 37534174
```

解析时将 `_NT_SYMBOL_PATH` 指向 `native/out/entity-collision-optimizer/win-x64`，并使用 `xperf -a dumper` 导出的 `SampledProfile` 事件按 PID/TID 过滤。符号解析已识别本模组导出函数及 `eco::coveredCells` 等内部函数。权重 CPU 时间与样本计数不是完全相同的统计量；上述表格报告样本占比，不冒充精确函数耗时。

# 加速碰撞 (Accelerated Recoiling)

Accelerated Recoiling 是一个面向 Fabric 服务器的实体碰撞优化模组。它使用 Java FFM 调用随模组发布的原生库，处理高密度实体的 AABB 碰撞候选计算。

> 本模组仍属于实验性优化，实体挤压结果可能与原版不完全一致。在服务器存档上使用前请先备份。

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

ARM64 暂不作为正式原生后端支持范围；在不受支持的平台上只能将 `enableEntityCollision` 设为 `false` 使用 Vanilla 模式。

## 配置

首次启动后会生成：

```text
config/acceleratedrecoiling.json
```

默认配置：

```json
{
  "enableEntityCollision": true,
  "maxCollision": 32,
  "gridSize": 1,
  "densityWindow": 4,
  "densityThreshold": 16,
  "maxThreads": 1
}
```

`enableEntityCollision` 必须显式设为 `true`（FFM）或 `false`（Vanilla）。

`maxCollision` 设为 `0` 时不截断碰撞候选；正数表示每个实体最多保留的候选数。

可使用 `/acceleratedrecoiling` 或 `/togglefold` 查看、修改和保存配置。命令需要游戏管理员权限。

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

## 碰撞基准测试

项目包含一个显式启用的 Fabric GameTest 基准：每个服务端 tick 都会将 1000 只无 AI、无重力的僵尸锁定在同一坐标，并分别采集关闭碰撞优化与启用 FFM 优化时的 MSPT。普通 `build` 不会运行它。

```powershell
./gradlew.bat runGameTest -Pbenchmark
```

结果会以 `AR_BENCHMARK_RESULT` 为前缀输出到日志。建议至少独立运行三次，并比较 `baseline_mean_mspt`、`optimized_mean_mspt` 与 `improvement_percent`。

## 许可证

本项目使用 MIT License。

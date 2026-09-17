# Test suites

The project keeps correctness contracts, cross-process integration scenarios, and performance
benchmarks separate. Each Gradle invocation enables exactly one suite.

## Contract and unit GameTests

```powershell
.\gradlew.bat runGameTest -PunitTest
```

These tests load Entity Collision Optimizer and exercise focused algorithms, native-memory
contracts, edge cases, and deterministic interaction fixtures. They may call test invokers,
inspect optimizer state, or compare an optimized operation with a small vanilla oracle in the
same process. Failures should identify the violated contract precisely.

Sources live under `src/gametest`; the entrypoint manifest lives under `src/unitTest`.

## Cross-process integration GameTests

```powershell
.\gradlew.bat runGameTest -PintegrationTest
```

The `vanilla-gametest` project first runs the integration scenarios without Entity Collision
Optimizer and records their traces. The root project then runs the same source with the mod and
requires byte-for-byte equality. Integration scenarios use normal server ticks and public
Minecraft/Fabric APIs; they must not import optimizer code or unit-test mixins.

Each scenario owns a fixed, non-overlapping arena, restores it after completion, and writes a
separate trace through `CrossProcessTrace`. Add its public GameTest class to both integration
manifests.

Sources and the optimized manifest live under `src/integrationTest`; the vanilla manifest lives
under `vanilla-gametest/src/main/resources`.

## Performance benchmarks

```powershell
.\gradlew.bat runGameTest -Pbenchmark
```

Benchmarks measure workloads and validate only the benchmark fixture itself. They are not counted
as correctness tests and use the entrypoint manifest under `src/benchmarkTest`.

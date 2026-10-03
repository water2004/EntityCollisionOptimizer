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

The workloads are falling zombies, an elder-guardian void pipe, unpushable climbing zombies,
and tamed cats/wolves in separate pens. The pet workload uses 512 cats and 512 wolves in 32
stone pens for 1000 ticks. Pets retain normal AI/gravity and are ordered to sit; invulnerability
keeps the population fixed. Half inherit a connected survival owner's `ALWAYS` team and half
have a UUID-only offline owner. This exercises spatially local derived-team resolution through
normal entity ticks, not manually repeated collision queries. It is a repeatable workload,
not a copy of an issue reporter's world or a chunk-unloading/CME reproduction.

Run just the pet workload when profiling it:

```powershell
.\gradlew.bat runGameTest -Pbenchmark -PgameTestFilter=entity_collision_optimizer-benchmark-gametest:tamed_animal_benchmark_separated_pens
```

Use `-PcompatModsDir=<directory>` for an optional local mod stack. Timing, measurement-window
markers, fixture validation and cleanup use the same runner as the other benchmarks. Owner
connections, scoreboard teams and newly forced chunks are released after the workload; chunks
already forced by the test framework are preserved.

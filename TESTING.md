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

With `-PcompatModsDir=<directory>` containing Lithium, the optional pushable-query contract also
checks its adapter against vanilla section traversal, including explicit query bounds, captured
team rules, candidate order and custom-predicate callbacks. Without Lithium, this check is skipped.

Block-intersection contracts use vanilla's block iterator as the oracle. They cover live collision
contexts, palette updates and first-hit termination; with Lithium present they also exercise its
bridge and verify that hard-entity, world-border and liquid checks remain the caller's responsibility.

Shared index publications cover ordered bounds/section/metadata updates, all query boundaries,
removal and ID reuse, bounded-buffer draining without queries, and per-level isolation. Metadata
callback reentry is covered separately by `metadataReentryContract`.

Block-mask cursor contracts use an independent per-block halo oracle, including negative
coordinates, missing sections, nested scans and live palette changes between candidates.
Packed-row updates cover every column and flag combination while checking untouched columns
and planes; early-return reuse checks that pooled cursor progress starts afresh.
Block-intersection tests also compare shape callback order after callback-driven palette writes
and a nested query against Minecraft's iterator.

Movement contracts check Java-owned position/bounds, captured scan geometry and body ownership
across table growth, transfer and retirement. Batched hard-collision geometry is compared with
vanilla clipping across page growth, mixed shape references, nested calls and pooled-storage reuse.
Body-slot checks cover owner-qualified reuse, nested-lease retirement cancellation and recycled slots.
Spatial-to-body associations also cover ownership transfer, index growth and native-ID reuse during a lease.

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

Minecraft 26.3's GameTest server selects `minecraft:flat_all_dimensions`, so the world-preset
override uses `flat_all_dimensions.json` to include the empty-layer `benchmark_void` dimension.
Minecraft 26.2 selects `minecraft:flat`; its branch must retain `flat.json` instead.

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

`mixed_collision_benchmark_dense_hard_and_soft_entities` exercises normal movement and pushing
with 600 zombified piglins and 20 boats in one section. It warms up for 1200 ticks, then measures
400 ticks. AI and gravity remain enabled; invulnerability keeps the population fixed. This is a
controlled hard/soft-candidate workload, not a reproduction of a particular piglin farm.

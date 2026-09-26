# Minecraft 1.21.1 independent vanilla comparison

Run from the parent repository with JDK 25 available to Gradle's toolchain detection:

```sh
./gradlew -PintegrationTest runGameTest -Porg.gradle.java.installations.paths=/path/to/jdk-25
```

This runs two separate Minecraft server processes. `vanilla-gametest` records the baseline with Fabric API but without Entity Collision Optimizer. The main project then loads ECO and compares the same scenarios byte for byte. Each phase asserts the expected ECO presence to prevent accidental self-comparison. Neither phase disables production mixins. Trace files are written to the parent `build/gametest-baselines` directory; a mismatch also writes `*-actual.bin`.

The shared scenarios preserve the upstream counts and coverage:

- 320 zombies in a cramming chamber for 200 ticks (201 frames, 15,440,180 bytes).
- TNT in a crowd of 96 zombies for 100 ticks (101 frames, 2,338,942 bytes).

The trace includes physical and lifecycle state, raw floating-point bits, TNT state and final per-entity random-generator state. Entity AI is disabled by the original fixture; this suite proves equality for these controlled scenarios, not every AI behavior or a performance improvement.

For 1.21.1 the fixture explicitly processes forced-chunk tickets and light batches before spawning, then waits for entity-ticking readiness. It keeps world game time monotonic because vanilla GameTest uses that clock for callbacks and deadlines. Day time, random seeds, positions, IDs, UUIDs and initial entity state are normalized in both processes.

Verified on 2026-09-26: vanilla 2/2 passed and ECO 2/2 passed with exact trace equality. Both phases print scenario names, trace lengths and ECO loading status to the Gradle output. Re-running the command generates fresh baseline files before comparing the optimized process.

# Contributing

English | [简体中文](CONTRIBUTING_zh.md)

This guide covers development and project standards for collaborators and pull request contributors.

## Development

### Build and test

The project uses Java 25 and C++20. The current multi-platform native build runs on Windows through the repository's toolchain wrappers.

Java sources live under `src/main/java`, native implementation under `native/src`, and public native ABI declarations under `native/include/eco`.

```powershell
.\gradlew.bat build
.\gradlew.bat runGameTest -PunitTest
.\gradlew.bat runGameTest -PintegrationTest
```

Enable one test suite per Gradle invocation. Benchmarks are available through `-Pbenchmark`; see [TESTING.md](TESTING.md) for workloads and suite boundaries.

`main`, `26.2` and `26.1` target Minecraft 26.3, 26.2 and 26.1.x respectively. Shared fixes should reach all affected branches without replacing their version-specific APIs or metadata.

### Performance profiling

Profiling is useful when investigating performance problems or evaluating changes to hot paths. It is not required for every contribution.

Use an optimized build with debug symbols (`-PnativeSymbols`), rather than a Debug build. Preserve the matching binaries and symbols for later analysis. Keep normal JIT compilation, inlining and floating-point settings unchanged.

JFR is useful for Java call paths, allocation and runtime events. On Windows, WPR or xperf can capture CPU samples, with WPA providing interactive inspection. `llvm-addr2line` can help resolve native addresses against matching symbols.

For combined Java/native analysis, the repository's optional JVMTI agent records JIT code mappings. These mappings allow Java and native instruction addresses to be attributed within the same ETW CPU-sample distribution. Do not combine percentages from separate JFR and ETW distributions. See the [profiling tools guide](tools/profiling/jit-map/README.md) for implementation details.

Prefer representative benchmarks and repeated comparisons under consistent conditions. Restrict analysis to the workload's measurement window and relevant threads; check capture completeness, symbol resolution and unresolved samples before interpreting results.

Focus on hotspot shares and changes in the work performed. Absolute MSPT is supporting context, not sufficient evidence by itself. Percentages are relative: reducing one hotspot can raise another's share without increasing its cost. Distinguish whole-thread shares from native-only shares, and instruction ownership from inclusive call-stack attribution. Use separate unprofiled runs when comparing timing.

## Project standards

The project optimizes entity collisions while preserving vanilla behavior. Contributions should make the implementation easier to understand and maintain without weakening that contract. The standards below cover the behavior we preserve, how we implement changes, and how we demonstrate their correctness.

### Preserve behavior and compatibility

Vanilla behavior is the reference, including details that may appear incidental to an optimization. Compatibility must be evaluated through the paths affected by a change, not inferred from a successful build or benchmark.

- Preserve candidate membership and order, callback timing, movement results and floating-point semantics.
- Keep native exports, FFM bindings and shared-memory layouts consistent. C++ exceptions must not cross the C ABI.
- Check relevant entity lifecycle and callback interactions when changing behavior shared with other mods.

### Keep the implementation explicit

Code should express its responsibilities directly. Additional abstractions, checks and bookkeeping should solve a concrete problem; they should not obscure ownership, conceal invalid state or introduce unjustified hot-path costs.

- Use role-specific names and clear responsibility boundaries.
- Remove code unused by the main mod. Test-only hooks belong in test source sets.
- Recover only where meaningful recovery is possible. Propagate unrecoverable failures without speculative repair or fallback, while retaining normal resource lifecycle handling.
- Verify internal invariants in tests and validate external inputs at meaningful boundaries.
- Account for scans, allocations, synchronization and bookkeeping introduced on hot paths.

### Demonstrate and communicate the change

Validation should provide independent evidence for the claimed behavior or benefit. Keep the contribution reviewable by separating concerns and making its scope and evidence clear.

- Use independent contracts or vanilla behavior as test expectations, rather than another path through the optimized implementation.
- Keep unit tests, integration tests and benchmarks distinct. Integration tests exercise public Minecraft/Fabric behavior; benchmarks do not replace correctness coverage.
- Keep commits focused and follow the existing subject style, such as `fix:`, `test:`, `refactor:` and `docs:`.
- Explain the change, affected versions and validation in the PR. Include profiling evidence when relevant.
- Exclude temporary diagnostics, generated artifacts and machine-specific paths.

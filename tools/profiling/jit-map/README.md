# Windows JIT + native CPU attribution

This is an opt-in **profiling agent**, not a collision backend. Its sources and DLL are outside the mod's source sets, native build and release JAR. Loading it does not disable JIT compilation/inlining or modify Java bytecode. Only the game JVM receives `-agentpath`; never put the agent in `JAVA_TOOL_OPTIONS`.

Build with the repository's Windows CMake wrapper and `-DECO_JDK=<JDK directory>`, using an output directory outside the repository. The project only needs JDK headers, not a JVM import library. Compile the three analyzer Java sources with Java 25 into an external directory; run `JitMapTest` there. `Smoke.java` exercises actual compiled-method callbacks. Output paths must be new: the agent uses exclusive creation.

Example capture arguments in addition to the normal benchmark options:

```text
-PnativeSymbols
-PjitMapAgent=D:\ar-profiles\jit-map-build\EcoJitMap.dll
-PjitMapOutput=D:\ar-profiles\capture.tsv
-PjfrOutput=D:\ar-profiles\capture.jfr
```

With user authorization, launch `tools/profiling/WprCapture.ps1` elevated with `-BenchmarkLog`, `-Trace`, and `-StatusLog`. It records CPU only during the benchmark, stops only a recording it started, and times out if the benchmark does not finish. Freeze the matching collision DLL/PDB before rebuilding. Raw ETL is system-wide and stays local.

Export all PerfInfo/Image events, including image rundown before the measurement window:

```powershell
$env:_NT_SYMBOL_PATH='<frozen DLL/PDB directory>'
xperf -i capture.etl -symbols -o samples.csv -a dumper -provider '{ce1dbfb4-137e-4da6-87b0-3f59aa102cbc}' '{2cb15d1d-5fc1-11d2-abe1-00a0c911f518}'
xperf -i capture.etl -a tracestats -timespan
xperf -i capture.etl -a profile -freq
java -Xmx3g -cp <analyzer classes> UnifiedCpuSummary capture.tsv samples.csv <PID> <OS-TID> <ETL-start-UTC-ISO8601> <start-epoch-ms> <end-epoch-ms> <output-prefix>
```

Get the exact measurement window from `ECO_MEASUREMENT_WINDOW` and OS TID from `JfrWindowSummary.java`. JFR is used only to identify the thread and cross-check qualitative call paths, **never to scale percentages into the ETW denominator**. The analyzer reads every selected `SampledProfile`, including unresolved and kernel samples. C++ symbols can contain commas; their trailing count is parsed separately.

## Map and interpretation

- `H`: version, PID, startup Unix microseconds. `L`: sequence, receipt time, address, size, owning method. `I`: PC and inline methods/BCIs. `R`: completed load transaction. `U`: unload receipt and address. `D`: generated-code range and name. `E`: VMDeath. Times use `GetSystemTimePreciseAsFileTime`; ETW relative QPC times are aligned through its UTC trace origin.
- The agent copies callback metadata while valid. Method-name caching is limited to one callback, so class unloading cannot leave a stale `jmethodID` cache. Writes are serialized. The analyzer rejects incomplete transactions, missing VMDeath, incompatible versions, PID/window mismatches, and unmatched unloads.
- Instruction addresses are matched to compiled ranges with lifetimes. Ambiguous overlaps and samples near lifecycle boundaries remain unresolved. Unload notifications can be delayed; a 1 ms guard is conservative screening, **not a formal bound on notification/clock error**. The report also checks sensitivity to a 10 ms guard. No dynamic unload event exists; ambiguous reused stub ranges remain unresolved.
- `owners.csv` is the primary attribution: executing instructions within the owning compiled method, including code inlined into it. It is **not** inclusive call-stack time; separately executing callees are counted elsewhere, so rows can be added without double-counting.
- `near-leaves.csv` and `near-stacks.csv` are secondary sparse-debug views. OpenJDK emits leaf-first inline scopes; `pc_desc_near` selects the first descriptor at or after the PC. These metadata are not byte-exact source ownership for every instruction. Never add these reports to `owners.csv`, nor pretend generated dispatch/arraycopy stubs have a known Java caller without a recovered stack.
- Profiling changes runtime load: this capture generated about 580 MB of metadata. CPU shares describe the instrumented run, not an exact decomposition of uninstrumented MSPT. Keep performance A/B runs separate.

Primary interface reference: [JVMTI compiled-method events](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#CompiledMethodLoad). Implementation references: [OpenJDK 25 inline records](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/prims/jvmtiExport.cpp) and [PC descriptor lookup](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/hotspot/share/code/nmethod.hpp).

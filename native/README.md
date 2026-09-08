# Native modules

The native library is consumed through Java FFM. Exported names and shared-memory layouts are compatibility boundaries, independent of the source directory structure.

| Directory | Responsibility |
| --- | --- |
| `include/eco` | Public C ABI declarations and symbol visibility |
| `src/state` | Per-level context, entity membership lifecycle and metadata publication |
| `src/spatial` | XYZ grid maintenance, ordered/unordered traversal and candidate masks |
| `src/query` | Query entry points, section bounds and team rules |
| `src/motion` | Shared body layout, entity impulses and movement solving |
| `src/geometry` | Voxel geometry and shape clipping |
| `src/blocks` | Block-row scanning and block candidate filtering |

Use module-qualified includes, such as `spatial/spatial_index.h`, rather than adding each module directory to the include search path. Build sources are listed explicitly in `CMakeLists.txt`.

The ordered and unordered libraries share sources; `ECO_VANILLA_ORDER` selects ordering at compile time. Preserve export signatures, struct layout and floating-point flags when reorganizing code. Collision arithmetic must not silently enable contraction or fast-math.

Build and test from the repository root using the Gradle Wrapper. Keep generated native outputs, profiles and experiment reports outside the tracked source directories.

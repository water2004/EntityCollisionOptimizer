# Builds the Windows x64 native library with an MSYS2/MinGW g++ toolchain.
#
# The release pipeline uses the pinned cross-compilation toolchain (cpp-build.gradle),
# which needs network access. This script is the offline/local alternative: it mirrors
# native/CMakeLists.txt for the Windows target, including -ffp-contract=off for the
# files whose floating-point arithmetic must stay bit-identical to vanilla.
#
# Usage: powershell -ExecutionPolicy Bypass -File native/build-windows-mingw.ps1 [-Gxx <path>]

param(
    [string]$Gxx = "D:\msys64\ucrt64\bin\g++.exe"
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot

if (-not (Test-Path $Gxx)) {
    throw "g++ not found at '$Gxx'. Pass -Gxx <path to g++.exe>."
}
# MSYS2 g++ needs its own bin directory on PATH so cc1plus/as can load their DLLs.
$env:PATH = (Split-Path $Gxx) + ";" + $env:PATH

$outDir = Join-Path $Root "out\entity-collision-optimizer\win-x64"
$objDir = Join-Path $Root "out\obj\win-x64"
New-Item -ItemType Directory -Force -Path $outDir, $objDir | Out-Null

# Keep in sync with ECO_COMMON_SOURCES in CMakeLists.txt.
$sources = @(
    'src/native_error.cpp',
    'src/state/context_api.cpp',
    'src/state/metadata_api.cpp',
    'src/state/persistent_index.cpp',
    'src/spatial/spatial_index.cpp',
    'src/spatial/section_index.cpp',
    'src/query/collision_rules.cpp',
    'src/query/query_api.cpp',
    'src/motion/push_run.cpp',
    'src/motion/movement_solver.cpp',
    'src/geometry/voxel_geometry.cpp',
    'src/blocks/block_scan.cpp'
)
# Batch velocity accumulation must not fuse/reassociate vanilla's individual operations.
$noContract = @('src/motion/push_run.cpp', 'src/motion/movement_solver.cpp', 'src/geometry/voxel_geometry.cpp')

$defs = @(
    '-DAR_WINDOWS', '-DAR_X64',
    '-DUNICODE', '-D_UNICODE',
    '-DWINVER=0x0601', '-D_WIN32_WINNT=0x0601',
    '-D_AMD64_', '-D_HAS_STATIC_RTTI=0'
)
$common = @('-std=c++20', '-O3', '-fno-rtti', '-mavx2', '-march=x86-64-v2', '-mtune=generic', '-c') `
    + $defs + @('-I', (Join-Path $Root 'src'), '-I', (Join-Path $Root 'include'))

$objects = @()
foreach ($source in $sources) {
    $object = Join-Path $objDir ((Split-Path $source -Leaf) -replace '\.cpp$', '.o')
    $argList = $common
    if ($noContract -contains $source) { $argList += '-ffp-contract=off' }
    $argList += (Join-Path $Root $source)
    $argList += @('-o', $object)
    Write-Host "> compile $source"
    & $Gxx @argList
    if ($LASTEXITCODE -ne 0) { throw "compile failed: $source" }
    $objects += $object
}

$dll = Join-Path $outDir 'EntityCollisionOptimizer.dll'
Write-Host "> link $dll"
# Everything except the Windows UCRT is linked statically (-static also pulls in
# libwinpthread, which -static-libstdc++/-static-libgcc alone leave as a DLL
# dependency). The released library must load on machines with no MinGW runtime.
& $Gxx @(@('-shared', '-o', $dll) + $objects + @(
    '-static', '-static-libgcc', '-static-libstdc++'
))
if ($LASTEXITCODE -ne 0) { throw "link failed" }

Get-Item $dll | Select-Object FullName, Length | Format-Table -AutoSize

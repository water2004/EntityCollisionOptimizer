# Local Windows release build without the cross-compilation toolchain.
#
# 1. Builds native/out/entity-collision-optimizer/win-x64/EntityCollisionOptimizer.dll
#    with the local MSYS2/MinGW g++ toolchain.
# 2. Runs Loom's remapJar with -PnativeWindowsOnly so the JAR packages the Windows
#    library only, skipping the pinned Linux/macOS cross toolchain.
#
# The result is build/libs/entity_collision_optimizer-<version>.jar, containing
# natives/windows-x64/EntityCollisionOptimizer.dll.
#
# The release pipeline still builds all three platforms; see .github/workflows/build.yml.

param(
    [string]$Gxx = "D:\msys64\ucrt64\bin\g++.exe",
    [string]$Gradle = ".\gradlew.bat",
    [string]$GradleUserHome = "",
    [switch]$SkipNative
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

if (-not $SkipNative) {
    Write-Host "== building Windows native library =="
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'native\build-windows-mingw.ps1') -Gxx $Gxx
    if ($LASTEXITCODE -ne 0) { throw "native build failed" }
} else {
    Write-Host "== skipping native build (-SkipNative) =="
}

Write-Host "== packaging JAR =="
$previousHome = $env:GRADLE_USER_HOME
if ($GradleUserHome) { $env:GRADLE_USER_HOME = $GradleUserHome }
try {
    Push-Location $root
    & $Gradle remapJar -PnativeWindowsOnly `
        -x compileNativeWin -x compileNativeLinux -x compileNativeMac
    if ($LASTEXITCODE -ne 0) { throw "gradle remapJar failed" }
} finally {
    Pop-Location
    $env:GRADLE_USER_HOME = $previousHome
}

Get-ChildItem (Join-Path $root 'build\libs') -Filter '*.jar' |
    Select-Object FullName, Length | Format-Table -AutoSize

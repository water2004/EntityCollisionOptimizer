param(
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$Minecraft,
    [string]$Commit = $env:GITHUB_SHA
)

$ErrorActionPreference = 'Stop'
if ($Commit -notmatch '^[0-9a-f]{40}$') { throw 'A full source commit SHA is required.' }
$mainJar = "build/libs/entity_collision_optimizer-$Version.jar"
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $mainJar))
try {
    foreach ($entry in @(
        'fabric.mod.json',
        'org/edtp/entitycollisionoptimizer/mixin/BodyFieldConsumersMixin.class',
        'natives/windows-x64/EntityCollisionOptimizer.dll',
        'natives/linux-x64/libEntityCollisionOptimizer.so',
        'natives/macos-x64/libEntityCollisionOptimizer.dylib'
    )) {
        if ($null -eq $archive.GetEntry($entry)) { throw "Release JAR is missing $entry." }
    }
    $reader = [System.IO.StreamReader]::new($archive.GetEntry('fabric.mod.json').Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json }
    finally { $reader.Dispose() }
    if ($metadata.version -ne $Version -or $metadata.depends.minecraft -ne "~$Minecraft") {
        throw 'Release JAR version or Minecraft dependency does not match the branch.'
    }
} finally { $archive.Dispose() }

New-Item -ItemType Directory -Path dist -Force | Out-Null
Copy-Item -LiteralPath $mainJar -Destination dist
$jarName = Split-Path -Leaf $mainJar
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $mainJar).Hash.ToLowerInvariant()
# Checksum comments preserve provenance without adding a download or changing its display name.
"# source-commit: $Commit`n$hash  $jarName" | Set-Content "dist/SHA256SUMS-mc$Minecraft.txt" -Encoding utf8NoBOM

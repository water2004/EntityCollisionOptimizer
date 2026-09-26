$ErrorActionPreference = 'Stop'
$scripts = $PSScriptRoot
$fixture = Join-Path ([System.IO.Path]::GetTempPath()) "eco-release-test-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $fixture | Out-Null
$oldRepository = $env:GITHUB_REPOSITORY
$env:GITHUB_REPOSITORY = 'test/example'
$commit = 'a' * 40
$state = @{
    calls = [System.Collections.Generic.List[object]]::new()
    mode = 'missing'
    tagCommit = $commit
    assetCommit = $commit
}

function Assert($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Expect-Failure([scriptblock]$Action) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Assert $failed 'Expected rejection.'
}
function Properties([string]$Minecraft, [string]$Version) {
    "minecraft_version=$Minecraft`nmod_version=$Version" | Set-Content gradle.properties
}
# These mocks are scoped to this test script and never contact GitHub.
Set-Item Function:gh -Value ({
    $state.calls.Add(@($args))
    $global:LASTEXITCODE = 0
    if ($args[0] -ne 'api') { return }
    $route = $args[1]
    if ($route -like '*/releases/tags/*') {
        if ($state.mode -eq 'missing') {
            $global:LASTEXITCODE = 1
            return 'Not Found (HTTP 404)'
        }
        $assets = @()
        if ($state.mode -in @('source', 'legacy')) {
            $assets = @(@{
                id = 42
                name = 'SHA256SUMS-mc26.1.txt'
            })
        }
        return (@{ draft = $false; immutable = $false; assets = $assets } | ConvertTo-Json -Depth 5)
    }
    if ($route -like '*/commits/*') { return $state.tagCommit }
    if ($route -like '*/releases/assets/*') {
        if ($state.mode -eq 'source') { return "# source-commit: $($state.assetCommit)`nchecksum  fixture.jar" }
        return 'checksum  fixture.jar'
    }
    throw "Unexpected GitHub route $route."
}.GetNewClosure())
function Start-Sleep { param([int]$Seconds) }

Push-Location $fixture
try {
    foreach ($mc in @('26.1', '26.2', '26.3')) {
        Properties $mc "1.0.0-mc$mc-alpha.8"
        $metadata = & "$scripts/release-metadata.ps1" -Branch $mc
        Assert ($metadata.Tag -eq 'v1.0.0-alpha.8' -and $metadata.Prerelease) 'Shared alpha tag.'
    }
    Properties '26.3' '1.0.0+mc26.3'
    $metadata = & "$scripts/release-metadata.ps1" -Branch main
    Assert ($metadata.Tag -eq 'v1.0.0' -and -not $metadata.Prerelease) 'Stable tag.'
    Expect-Failure { & "$scripts/release-metadata.ps1" -Branch '26.1' }
    Expect-Failure { & "$scripts/release-metadata.ps1" -Branch 'feature' }
    Properties '26.3' '1.0.0-mc26.2-alpha.8'
    Expect-Failure { & "$scripts/release-metadata.ps1" -Branch main }
    Properties '26.3' '1.0.0-mc26.3-alpha.08'
    Expect-Failure { & "$scripts/release-metadata.ps1" -Branch main }

    Properties '26.1' '1.0.0-mc26.1-alpha.8'
    New-Item -ItemType Directory build/libs, .github/release-notes | Out-Null
    'Release fixture' | Set-Content .github/release-notes/1.0.0-alpha.8.md
    $jar = Join-Path $fixture 'build/libs/entity_collision_optimizer-1.0.0-mc26.1-alpha.8.jar'
    $archive = [System.IO.Compression.ZipFile]::Open($jar, 'Create')
    try {
        foreach ($name in @('fabric.mod.json',
            'org/edtp/entitycollisionoptimizer/mixin/BodyFieldConsumersMixin.class',
            'natives/windows-x64/EntityCollisionOptimizer.dll',
            'natives/linux-x64/libEntityCollisionOptimizer.so',
            'natives/macos-x64/libEntityCollisionOptimizer.dylib')) {
            $writer = [System.IO.StreamWriter]::new($archive.CreateEntry($name).Open())
            try {
                if ($name -eq 'fabric.mod.json') {
                    $writer.Write('{"version":"1.0.0-mc26.1-alpha.8","depends":{"minecraft":"~26.1"}}')
                } else { $writer.Write('fixture') }
            } finally { $writer.Dispose() }
        }
    } finally { $archive.Dispose() }
    & "$scripts/stage-release.ps1" -Version '1.0.0-mc26.1-alpha.8' -Minecraft '26.1' -Commit $commit
    Assert ((Get-ChildItem dist -File).Count -eq 2) 'Only the JAR and checksum are staged.'
    Assert (-not (Test-Path 'dist/source-mc26.1.json')) 'No source JSON download.'
    $checksum = Get-Content 'dist/SHA256SUMS-mc26.1.txt'
    Assert ($checksum[0] -eq "# source-commit: $commit") 'Source commit stays in a checksum comment.'
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash.ToLowerInvariant()
    Assert ($checksum[1] -eq "$hash  $(Split-Path -Leaf $jar)") 'Standard checksum line is retained.'
    Expect-Failure { & "$scripts/stage-release.ps1" -Version '1.0.0-mc26.1-alpha.8' -Minecraft '26.3' -Commit $commit }
    Expect-Failure { & "$scripts/stage-release.ps1" -Version '1.0.0-mc26.1-alpha.8' -Minecraft '26.1' -Commit '' }

    & "$scripts/publish-release.ps1" -Branch main -Commit $commit
    Assert ($state.calls.Where({ $_[0] -eq 'release' -and $_[1] -eq 'create' }).Count -eq 1) 'Main creates release.'
    $createCall = $state.calls.Where({ $_[0] -eq 'release' -and $_[1] -eq 'create' })[0]
    Assert ($createCall -contains 'dist/entity_collision_optimizer-1.0.0-mc26.1-alpha.8.jar') 'Creation uses the original filename.'
    Assert (@($createCall | Where-Object { $_ -like '*#*' }).Count -eq 0) 'Creation must not set display labels.'
    Assert (@($createCall | Where-Object { $_ -like '*.json*' }).Count -eq 0) 'Creation does not upload JSON.'
    $state.calls.Clear()
    $state.mode = 'existing'
    & "$scripts/publish-release.ps1" -Branch '26.1' -Commit $commit
    Assert ($state.calls.Where({ $_[0] -eq 'release' -and $_[1] -eq 'upload' }).Count -eq 1) 'Version branch appends assets.'
    Assert ($state.calls.Where({ $_[0] -eq 'release' -and $_[1] -eq 'create' }).Count -eq 0) 'Version branch never creates release.'
    $uploadCall = $state.calls.Where({ $_[0] -eq 'release' -and $_[1] -eq 'upload' })[0]
    Assert ($uploadCall -contains 'dist/entity_collision_optimizer-1.0.0-mc26.1-alpha.8.jar') 'Upload uses the original filename.'
    Assert (@($uploadCall | Where-Object { $_ -like '*#*' }).Count -eq 0) 'Upload must not set display labels.'
    Assert (@($uploadCall | Where-Object { $_ -like '*.json*' }).Count -eq 0) 'Upload does not include JSON.'
    $state.mode = 'legacy'
    & "$scripts/publish-release.ps1" -Branch '26.1' -Commit $commit
    $state.mode = 'source'
    & "$scripts/publish-release.ps1" -Branch '26.1' -Commit $commit
    $state.assetCommit = 'b' * 40
    Expect-Failure { & "$scripts/publish-release.ps1" -Branch '26.1' -Commit $commit }
    $state.tagCommit = 'b' * 40
    Expect-Failure { & "$scripts/publish-release.ps1" -Branch main -Commit $commit }
    $state.mode = 'missing'
    $state.calls.Clear()
    Expect-Failure { & "$scripts/publish-release.ps1" -Branch '26.1' -Commit $commit }
    Assert ($state.calls.Where({ $_[0] -eq 'release' }).Count -eq 0) 'Missing main release cannot be created by version branch.'
    Write-Host 'Release metadata, staging, publication routing and retry checks passed.'
} finally {
    Pop-Location
    $env:GITHUB_REPOSITORY = $oldRepository
    # Only remove the unique test directory created above, never a caller-supplied path.
    $resolved = (Resolve-Path -LiteralPath $fixture).Path
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
    if ((Split-Path -Parent $resolved) -ne $tempRoot -or (Split-Path -Leaf $resolved) -notlike 'eco-release-test-*') {
        throw 'Unexpected test cleanup path.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
exit 0

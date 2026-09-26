param(
    [string]$Branch = $env:GITHUB_REF_NAME,
    [string]$Commit = $env:GITHUB_SHA
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$metadata = & "$PSScriptRoot/release-metadata.ps1" -Branch $Branch
$repository = $env:GITHUB_REPOSITORY
$tag = $metadata.Tag

function Get-Release {
    $response = & gh api "repos/$repository/releases/tags/$tag" 2>&1
    if ($LASTEXITCODE -eq 0) { return (($response -join "`n") | ConvertFrom-Json) }
    if (($response -join "`n") -match 'HTTP 404') { return $null }
    throw "Cannot read release ${tag}: $response"
}

$assets = @(
    "dist/entity_collision_optimizer-$($metadata.Version).jar",
    "dist/SHA256SUMS-mc$($metadata.Minecraft).txt"
)
foreach ($asset in $assets) {
    if (-not (Test-Path -LiteralPath $asset -PathType Leaf)) { throw "Missing release asset $asset." }
}
# Keep provenance in the JAR's GitHub asset label, not in a separate download.
$uploadAssets = @("$($assets[0])#commit:$Commit", $assets[1])
$release = Get-Release
if ($Branch -eq 'main') {
    if ($null -eq $release) {
        if (-not (Test-Path -LiteralPath $metadata.NotesFile -PathType Leaf)) {
            throw "Missing release notes: $($metadata.NotesFile)"
        }
        $arguments = @('release', 'create', $tag) + $uploadAssets + @(
            '--repo', $repository, '--target', $Commit,
            '--title', "Entity Collision Optimizer $($metadata.ReleaseVersion)",
            '--notes-file', $metadata.NotesFile
        )
        if ($metadata.Prerelease) { $arguments += @('--prerelease', '--latest=false') }
        else { $arguments += '--latest' }
        & gh @arguments
        if ($LASTEXITCODE -ne 0) { throw 'GitHub Release creation failed.' }
        exit 0
    }
    $tagCommit = & gh api "repos/$repository/commits/$tag" --jq .sha
    if ($LASTEXITCODE -ne 0) { throw "Cannot resolve release tag $tag." }
    if ($tagCommit -ne $Commit) {
        throw "Release $tag already belongs to another main commit; increment mod_version."
    }
} else {
    # Branch builds may finish before main; wait for its release, never create one here.
    for ($attempt = 0; $null -eq $release -and $attempt -lt 40; $attempt++) {
        Write-Host "Waiting for main to publish $tag..."
        Start-Sleep -Seconds 30
        $release = Get-Release
    }
    if ($null -eq $release) { throw "Main has not published $tag. Re-run after its release succeeds." }
}
if ($release.draft -or $release.immutable) {
    throw "Release $tag is not open for additional published assets."
}

# A retry may replace its own assets, but a new commit requires a new version.
$jarName = Split-Path -Leaf $assets[0]
$jarAsset = $release.assets | Where-Object name -EQ $jarName | Select-Object -First 1
# Older releases have no label; their next publication establishes provenance.
if ($null -ne $jarAsset -and $jarAsset.label -and $jarAsset.label -ne "commit:$Commit") {
    throw "Minecraft $($metadata.Minecraft) is already published from another commit; increment mod_version."
}
& gh release upload $tag @uploadAssets --repo $repository --clobber
if ($LASTEXITCODE -ne 0) { throw 'GitHub Release asset upload failed.' }

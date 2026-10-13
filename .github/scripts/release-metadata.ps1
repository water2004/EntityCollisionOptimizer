param(
    [string]$PropertiesPath = 'gradle.properties',
    [string]$RefName = $env:GITHUB_REF_NAME,
    [string]$RefType = $env:GITHUB_REF_TYPE
)

$ErrorActionPreference = 'Stop'
if ($RefType -ne 'tag') { throw 'Only a release tag can publish artifacts; branch pushes run CI only.' }
$properties = ConvertFrom-StringData (Get-Content -LiteralPath $PropertiesPath -Raw)
$version = $properties.mod_version
$minecraft = $properties.minecraft_version
$number = '(?:0|[1-9][0-9]*)'
$semver = "$number\.$number\.$number"
$minecraftPattern = '[0-9]+(?:\.[0-9]+)+'

if ($version -match "^(?<base>$semver)-mc(?<minecraft>$minecraftPattern)-(?<suffix>alpha\.$number)$") {
    $releaseVersion = "$($Matches.base)-$($Matches.suffix)"
    $prerelease = $true
} elseif ($version -match "^(?<base>$semver)\+mc(?<minecraft>$minecraftPattern)$") {
    $releaseVersion = $Matches.base
    $prerelease = $false
} else {
    throw "Invalid mod version '$version'."
}
if ($Matches.minecraft -ne $minecraft) {
    throw "Mod version '$version' does not target Minecraft $minecraft."
}
$tag = "v$releaseVersion"
if ($RefName -eq $tag) {
    $branch = 'main'
    $createsRelease = $true
} elseif ($RefName -eq "v$version") {
    $branch = $minecraft
    $createsRelease = $false
} else {
    throw "Tag '$RefName' must match '$tag' (main) or 'v$version' (Minecraft $minecraft)."
}

[pscustomobject]@{
    Version = $version
    Minecraft = $minecraft
    ReleaseVersion = $releaseVersion
    Tag = $tag
    SourceTag = $RefName
    Branch = $branch
    CreatesRelease = $createsRelease
    Prerelease = $prerelease
    NotesFile = ".github/release-notes/$releaseVersion.md"
}

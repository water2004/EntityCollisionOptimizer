param(
    [string]$PropertiesPath = 'gradle.properties',
    [string]$Branch = $env:GITHUB_REF_NAME
)

$ErrorActionPreference = 'Stop'
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
if ($Branch -ne 'main' -and $Branch -ne $minecraft) {
    throw "Only main or the matching Minecraft branch '$minecraft' can publish this build."
}

[pscustomobject]@{
    Version = $version
    Minecraft = $minecraft
    ReleaseVersion = $releaseVersion
    Tag = "v$releaseVersion"
    Prerelease = $prerelease
    NotesFile = ".github/release-notes/$releaseVersion.md"
}

param(
    [Parameter(Mandatory)][string]$Samples,
    [Parameter(Mandatory)][int]$GameProcessId,
    [Parameter(Mandatory)][int]$OsThreadId,
    [Parameter(Mandatory)][long]$StartUs,
    [Parameter(Mandatory)][long]$EndUs,
    [Parameter(Mandatory)][string]$OutputCsv
)
$ErrorActionPreference = 'Stop'
if ($EndUs -le $StartUs) { throw 'EndUs must follow StartUs' }

# Input: xperf -symbols -a dumper, including PerfInfo and Image providers.
# Symbol names come from xperf's resolution of the matching PDB, not address guesses.
$ecoBase = [uint64]0
$ecoEnd = [uint64]0
$ecoImageStart = [long]::MaxValue
$ecoTotal = [long]0
$ecoNative = [long]0
$ecoAddresses = @{}
$ecoModules = @{}
$ecoProcessTag = "($GameProcessId)"
foreach ($ecoLine in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $Samples).Path)) {
    if (!$ecoLine.Contains($ecoProcessTag)) { continue }
    if (($ecoLine.Contains('I-DCStart,') -or $ecoLine.Contains('I-Start,')) -and
            $ecoLine.Contains($ecoProcessTag) -and
            $ecoLine.IndexOf('entityCollisionOptimizer.dll', [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
        $ecoFields = $ecoLine.Split(',')
        $ecoImageTime = [long]$ecoFields[1]
        if ($ecoLine.Contains('I-Start,') -and $ecoImageTime -gt $ecoImageStart) { continue }
        $ecoNextBase = [Convert]::ToUInt64($ecoFields[3].Trim().Substring(2), 16)
        if ($ecoBase -ne 0 -and $ecoBase -ne $ecoNextBase) { throw 'Multiple native images; use an explicit image-lifetime analysis' }
        $ecoBase = $ecoNextBase
        $ecoEnd = [Convert]::ToUInt64($ecoFields[4].Trim().Substring(2), 16)
        $ecoImageStart = $ecoImageTime
        continue
    }
    if (!$ecoLine.Contains('SampledProfile,')) { continue }
    $ecoFields = $ecoLine.Split(',')
    $ecoTime = [long]$ecoFields[1]
    if ($ecoTime -lt $StartUs -or $ecoTime -gt $EndUs -or [int]$ecoFields[3] -ne $OsThreadId) { continue }
    if ($ecoBase -eq 0) { throw 'Module rundown must precede the selected window in this export' }
    # C++ template names may contain commas. Count and sample type are the last two fields.
    $ecoCount = [long]$ecoFields[$ecoFields.Length - 2]
    $ecoTotal += $ecoCount
    $ecoModule = $ecoFields[7].Trim().Split('!')[0]
    $ecoModules[$ecoModule] += $ecoCount
    $ecoAddress = [Convert]::ToUInt64($ecoFields[4].Trim().Substring(2), 16)
    if ($ecoAddress -ge $ecoBase -and $ecoAddress -lt $ecoEnd) {
        $ecoNative += $ecoCount
        $ecoRva = $ecoAddress - $ecoBase
        if (!$ecoAddresses.ContainsKey($ecoRva)) {
            $ecoMatch = [regex]::Match($ecoLine, 'EntityCollisionOptimizer\.dll!(.*),\s*\d+,\s*\w+\s*$')
            $ecoFunction = if ($ecoMatch.Success) { $ecoMatch.Groups[1].Value.Trim() } else { 'unresolved' }
            $ecoAddresses[$ecoRva] = [pscustomobject]@{ Samples=0L; Rva=('0x{0:x}' -f $ecoRva); Function=$ecoFunction }
        }
        $ecoAddresses[$ecoRva].Samples += $ecoCount
    }
}
if ($ecoBase -eq 0 -or $ecoTotal -eq 0 -or $ecoNative -eq 0) { throw 'No matching module/thread/window samples; inspect the ETL export' }
$ecoRows = @($ecoAddresses.Values)
$ecoRows | Sort-Object Samples -Descending | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation
[pscustomobject]@{ ProcessId=$GameProcessId; ThreadId=$OsThreadId; TotalSamples=$ecoTotal; NativeSamples=$ecoNative;
    NativePercent=(100.0 * $ecoNative / $ecoTotal); ImageBase=('0x{0:x}' -f $ecoBase) } | Format-List
$ecoRows | Group-Object Function | ForEach-Object {
    $ecoSum = ($_.Group | Measure-Object Samples -Sum).Sum
    [pscustomobject]@{ Samples=$ecoSum; ThreadPercent=100.0*$ecoSum/$ecoTotal; NativePercent=100.0*$ecoSum/$ecoNative; Function=$_.Name }
} | Sort-Object Samples -Descending | Format-Table -AutoSize -Wrap
Write-Output 'Counts include every CPU sample in the window, not only samples with a successfully unwound stack.'
$ecoModules.GetEnumerator() | ForEach-Object {
    [pscustomobject]@{ Samples=$_.Value; ThreadPercent=100.0*$_.Value/$ecoTotal; Module=$_.Key }
} | Sort-Object Samples -Descending | Format-Table -AutoSize -Wrap

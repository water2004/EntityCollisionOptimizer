param(
    [Parameter(Mandatory)][string]$BenchmarkLog,
    [Parameter(Mandatory)][string]$Trace,
    [Parameter(Mandatory)][string]$StatusLog
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Trace) { throw 'Trace already exists' }
$ecoWpr = 'C:\Windows\System32\wpr.exe'
$ecoStarted = $false
$ecoExit = 0
try {
    "Waiting for benchmark; elevated WPR helper only. $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog
    $ecoDeadline = [DateTime]::UtcNow.AddMinutes(15)
    while ([DateTime]::UtcNow -lt $ecoDeadline) {
        if (Test-Path -LiteralPath $BenchmarkLog) {
            if (Select-String -LiteralPath $BenchmarkLog -Pattern 'ECO_BENCHMARK_START' -Quiet) { break }
            if (Select-String -LiteralPath $BenchmarkLog -Pattern 'BUILD FAILED' -Quiet) { throw 'Benchmark build failed before capture' }
        }
        Start-Sleep -Milliseconds 200
    }
    if ([DateTime]::UtcNow -ge $ecoDeadline) { throw 'Timed out waiting for benchmark' }
    & $ecoWpr -start CPU -filemode *> ($StatusLog + '.start')
    if ($LASTEXITCODE -ne 0) { throw "WPR start failed: $LASTEXITCODE" }
    $ecoStarted = $true
    "Started $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog -Append
    $ecoDeadline = [DateTime]::UtcNow.AddMinutes(3)
    while ([DateTime]::UtcNow -lt $ecoDeadline) {
        if (Get-Content -LiteralPath $BenchmarkLog -Tail 100 | Select-String -Pattern 'ECO_PROFILE_RESULT|BUILD FAILED|Game test server shutting down' -Quiet) { break }
        Start-Sleep -Milliseconds 200
    }
} catch {
    $ecoExit = 1
    $_ | Out-File -LiteralPath $StatusLog -Append
} finally {
    if ($ecoStarted) {
        & $ecoWpr -stop $Trace *> ($StatusLog + '.stop')
        if ($LASTEXITCODE -ne 0) { $ecoExit = 1 }
        "Stopped with exit $LASTEXITCODE $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog -Append
    }
    "Helper finished $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog -Append
}
exit $ecoExit

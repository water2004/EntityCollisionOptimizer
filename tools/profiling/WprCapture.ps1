param(
    [Parameter(Mandatory)][string]$BenchmarkLog,
    [Parameter(Mandatory)][string]$Trace,
    [Parameter(Mandatory)][string]$StatusLog,
    [string]$StartPattern = 'ECO_BENCHMARK_START',
    [string]$WprProfile = 'CPU',
    [switch]$StartImmediately
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $Trace) { throw 'Trace already exists' }
$ecoWpr = 'C:\Windows\System32\wpr.exe'
$ecoStarted = $false
$ecoExit = 0
try {
    "Waiting for benchmark; elevated WPR helper only. $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog
    if (!$StartImmediately) {
        $ecoDeadline = [DateTime]::UtcNow.AddMinutes(15)
        while ([DateTime]::UtcNow -lt $ecoDeadline) {
            if (Test-Path -LiteralPath $BenchmarkLog) {
                if (Select-String -LiteralPath $BenchmarkLog -Pattern $StartPattern -Quiet) { break }
                if (Select-String -LiteralPath $BenchmarkLog -Pattern 'BUILD FAILED' -Quiet) { throw 'Benchmark build failed before capture' }
            }
            Start-Sleep -Milliseconds 200
        }
        if ([DateTime]::UtcNow -ge $ecoDeadline) { throw 'Timed out waiting for benchmark' }
    }
    $ecoPreviousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $ecoWpr -start $WprProfile -filemode *> ($StatusLog + '.start')
    $ecoWprExit = $LASTEXITCODE
    $ErrorActionPreference = $ecoPreviousErrorAction
    if ($ecoWprExit -ne 0) { throw "WPR start failed: $ecoWprExit" }
    $ecoStarted = $true
    "Started $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog -Append
    $ecoDeadline = [DateTime]::UtcNow.AddMinutes(3)
    while ([DateTime]::UtcNow -lt $ecoDeadline) {
        $ecoFinished = $false
        if (Test-Path -LiteralPath $BenchmarkLog) {
            $ecoFinished = [bool](Get-Content -LiteralPath $BenchmarkLog -Tail 100 |
                    Select-String -Pattern 'ECO_MEASUREMENT_WINDOW phase=end|ECO_BENCHMARK_RESULT|ECO_PROFILE_RESULT|BUILD FAILED|Game test server shutting down' -Quiet)
        }
        if ($ecoFinished) { break }
        Start-Sleep -Milliseconds 200
    }
} catch {
    $ecoExit = 1
    $_ | Out-File -LiteralPath $StatusLog -Append
} finally {
    if ($ecoStarted) {
        $ecoPreviousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $ecoWpr -stop $Trace *> ($StatusLog + '.stop')
        $ecoWprExit = $LASTEXITCODE
        $ErrorActionPreference = $ecoPreviousErrorAction
        if ($ecoWprExit -ne 0) { $ecoExit = 1 }
        "Stopped with exit $ecoWprExit $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog -Append
    }
    "Helper finished $(Get-Date -Format o)" | Out-File -LiteralPath $StatusLog -Append
}
exit $ecoExit

$ErrorActionPreference = 'Stop'
$taskCandidates = @($PSScriptRoot,
    (Join-Path (Split-Path -Parent $PSScriptRoot) 'qijian-two-tier-server/go-game-two-tier'),
    (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'qijian-two-tier-server/go-game-two-tier'))
foreach ($taskRoot in $taskCandidates) {
    $taskState = Join-Path $taskRoot 'logs/local-test-server.json'
    if (!(Test-Path -LiteralPath $taskState)) { continue }
    $taskSaved = Get-Content -LiteralPath $taskState -Raw -Encoding UTF8 | ConvertFrom-Json
    $taskProcess = Get-Process -Id $taskSaved.id -ErrorAction SilentlyContinue
    if ($taskProcess -and $taskProcess.Path -eq $taskSaved.java -and
        $taskProcess.StartTime.ToUniversalTime().Ticks -eq [long]$taskSaved.startedTicks) {
        # Shut down this Java process and its owned KataGo child together.
        & taskkill.exe /PID $taskProcess.Id /T /F | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Unable to stop the local test server.' }
        Write-Host 'Stopped local test server. Close the client window separately.'
    } else { Write-Host 'Local test server is already stopped.' }
    return
}
Write-Host 'No local test server record found.'

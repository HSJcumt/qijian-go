param(
    [ValidateRange(1,65535)] [int] $Port = 9528,
    [switch] $NoClient
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/runtime.ps1')

function Find-LocalSide([string] $Side) {
    $taskCandidates = @($PSScriptRoot, (Join-Path (Split-Path -Parent $PSScriptRoot) "qijian-two-tier-$Side/go-game-two-tier"),
        (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) "qijian-two-tier-$Side/go-game-two-tier"))
    foreach ($taskCandidate in $taskCandidates) {
        if (Test-Path -LiteralPath (Join-Path $taskCandidate 'dist')) {
            $taskJar = Get-ChildItem -LiteralPath (Join-Path $taskCandidate 'dist') -File |
                Where-Object { $_.Name -match ('^qijian-' + $Side + '(?:-next|-pending|-\d{17})?\.jar$') } |
                Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
            if ($taskJar) { return @{ root=[IO.Path]::GetFullPath($taskCandidate); jar=$taskJar.FullName } }
        }
    }
    throw "Missing $Side JAR. Build the project or extract both client and server packages side by side."
}

$taskServer = Find-LocalSide 'server'
$taskClient = if (!$NoClient) { Find-LocalSide 'client' } else { $null }
$taskJava = [IO.Path]::GetFullPath((Find-JavaTool 'java'))
$taskLogs = Join-Path $taskServer.root 'logs'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskStatePath = Join-Path $taskLogs 'local-test-server.json'
$taskHash = [Security.Cryptography.SHA256]::Create()
try { $taskLockName = [BitConverter]::ToString($taskHash.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskServer.root.ToLowerInvariant()))).Replace('-','') }
finally { $taskHash.Dispose() }
$taskLock = New-Object Threading.Mutex($false, ('Local\QijianTwoTier_' + $taskLockName))
$taskLocked = $false
$taskStarted = $false
$taskProcess = $null
try {
    try { $taskLocked = $taskLock.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $taskLocked = $true }
    if (!$taskLocked) { throw 'Another local launcher is still running. Please wait.' }
    if (Test-Path -LiteralPath $taskStatePath) {
        $taskSaved = Get-Content -LiteralPath $taskStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $taskExisting = Get-Process -Id $taskSaved.id -ErrorAction SilentlyContinue
        if ($taskExisting -and $taskExisting.Path -eq $taskSaved.java -and
            $taskExisting.StartTime.ToUniversalTime().Ticks -eq [long]$taskSaved.startedTicks) {
            if ($taskSaved.port -ne $Port) { throw "The local test server is already running on port $($taskSaved.port). Stop it before changing ports." }
            $taskProcess = $taskExisting
        }
    }
    if (!$taskProcess) {
        # Reserve-check the requested port; never stop or reuse an unrelated listener.
        $taskProbe = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
        try { $taskProbe.Start() }
        catch { throw "Port $Port is already in use. Close the existing server, or run this launcher with -Port 9538." }
        finally { $taskProbe.Stop() }
        $taskProcess = Start-Process -FilePath $taskJava -ArgumentList @('-Dfile.encoding=UTF-8', '-jar', ('"' + $taskServer.jar + '"'), "$Port", '127.0.0.1') `
            -WorkingDirectory $taskServer.root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskLogs 'local-test-server-console.log') `
            -RedirectStandardError (Join-Path $taskLogs 'local-test-server-errors.log') -PassThru
        $taskStarted = $true
        $taskStateJson = @{ id=$taskProcess.Id; java=$taskJava; startedTicks=$taskProcess.StartTime.ToUniversalTime().Ticks.ToString(); port=$Port } | ConvertTo-Json
        [IO.File]::WriteAllText($taskStatePath, $taskStateJson, (New-Object Text.UTF8Encoding($false)))
        Write-Host "Starting integrated server (PID $($taskProcess.Id)) ..."
    } else { Write-Host "Reusing local test server (PID $($taskProcess.Id))." }
    $taskReady = $false
    $taskDeadline = [datetime]::UtcNow.AddSeconds(20)
    do {
        $taskProcess.Refresh()
        if ($taskProcess.HasExited) { throw "Server exited. See $taskLogs/local-test-server-errors.log" }
        $taskSocket = New-Object Net.Sockets.TcpClient
        try { $taskConnect = $taskSocket.ConnectAsync('127.0.0.1', $Port); $taskReady = $taskConnect.Wait(250) -and $taskSocket.Connected }
        catch { $taskReady = $false }
        finally { $taskSocket.Dispose() }
        if (!$taskReady) { Start-Sleep -Milliseconds 150 }
    } while (!$taskReady -and [datetime]::UtcNow -lt $taskDeadline)
    if (!$taskReady) { throw "Server startup timed out. See $taskLogs" }
    Write-Host "Local server ready: 127.0.0.1:$Port"
    if (!$NoClient) {
        # Use a separate test configuration; preserve the normal server selection.
        $taskConfig = Join-Path $taskClient.root 'config/local-test.properties'
        New-Item -ItemType Directory -Path (Split-Path -Parent $taskConfig) -Force | Out-Null
        [IO.File]::WriteAllText($taskConfig, "server.host=127.0.0.1`nserver.port=$Port`n", (New-Object Text.UTF8Encoding($false)))
        $taskJavaWindow = Join-Path (Split-Path -Parent $taskJava) 'javaw.exe'
        if (!(Test-Path -LiteralPath $taskJavaWindow)) { $taskJavaWindow = $taskJava }
        Start-Process -FilePath $taskJavaWindow -ArgumentList @('-Dfile.encoding=UTF-8', ('"-Dgo.client.config=' + $taskConfig + '"'), '-jar', ('"' + $taskClient.jar + '"')) `
            -WorkingDirectory $taskClient.root -WindowStyle Hidden | Out-Null
        Write-Host 'Client opened. Log in, then click the entrance screen to connect.'
    }
    Write-Host 'Server logs: logs/local-test-server-console.log and logs/local-test-server-errors.log'
    Write-Host 'Use stop-local.ps1 to stop the local test server.'
} catch {
    if ($taskStarted -and $taskProcess -and !$taskProcess.HasExited) { Stop-Process -InputObject $taskProcess -ErrorAction SilentlyContinue }
    throw
} finally {
    if ($taskLocked) { $taskLock.ReleaseMutex() }
    $taskLock.Dispose()
}

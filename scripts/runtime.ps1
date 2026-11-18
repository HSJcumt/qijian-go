$ErrorActionPreference = 'Stop'

function Find-JavaTool([string] $Tool) {
    $taskRoot = Split-Path -Parent $PSScriptRoot
    $taskParent = Split-Path -Parent $taskRoot
    $taskCandidates = @()
    if ($env:JAVA_HOME) { $taskCandidates += (Join-Path $env:JAVA_HOME "bin\$Tool.exe") }
    $taskCandidates += (Join-Path $taskRoot "runtime\bin\$Tool.exe")
    foreach ($taskBase in @((Join-Path $taskRoot '.tools'), (Join-Path $taskParent '.tools'))) {
        if (Test-Path -LiteralPath $taskBase) {
            foreach ($taskJdk in (Get-ChildItem -LiteralPath $taskBase -Directory)) {
                $taskCandidates += (Join-Path $taskJdk.FullName "bin\$Tool.exe")
            }
        }
    }
    foreach ($taskCandidate in $taskCandidates) {
        if (Test-Path -LiteralPath $taskCandidate) { return $taskCandidate }
    }
    $taskCommand = Get-Command "$Tool.exe" -ErrorAction SilentlyContinue
    if ($taskCommand) { return $taskCommand.Source }
    throw "Java tool '$Tool' was not found. Install JDK 17+ and set JAVA_HOME, or extract a JDK into .tools. See README.md."
}

# Use the newest complete build, including updates made while the app is running.
function Find-GameArtifact([string] $Side) {
    $taskGameRoot = Split-Path -Parent $PSScriptRoot
    $taskPattern = '^qijian-' + [regex]::Escape($Side) + '(?:-next|-pending|-\d{17})?\.jar$'
    $taskArtifact = Get-ChildItem -LiteralPath (Join-Path $taskGameRoot 'dist') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match $taskPattern } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($taskArtifact) { return $taskArtifact.FullName }
    return (Join-Path $taskGameRoot "dist/qijian-$Side.jar")
}

# Share locked-file handling across client, game server, and model builds.
function Get-GameBuildTarget([string] $Side, [string] $Preferred) {
    $taskBuildRoot = Split-Path -Parent $PSScriptRoot
    $taskBuildTarget = Join-Path $taskBuildRoot $Preferred
    $taskPending = Join-Path $taskBuildRoot "dist/qijian-$Side-pending.jar"
    if (Test-Path -LiteralPath $taskPending) { $taskBuildTarget = $taskPending }
    if (Test-Path -LiteralPath $taskBuildTarget) {
        try { $taskProbe = [IO.File]::Open($taskBuildTarget, 'Open', 'ReadWrite', 'None'); $taskProbe.Dispose() }
        catch [IO.IOException] { $taskBuildTarget = Join-Path $taskBuildRoot ('dist/qijian-' + $Side + '-' + [datetime]::UtcNow.ToString('yyyyMMddHHmmssfff') + '.jar') }
    }
    return $taskBuildTarget
}

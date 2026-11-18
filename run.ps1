param(
    [ValidateSet('client','server')] [string] $Mode = 'client',
    [ValidateRange(0,65535)] [int] $Port = 0,
    [string] $BindAddress = ''
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
. (Join-Path $PSScriptRoot 'scripts\runtime.ps1')
$taskJava = Find-JavaTool 'java'
$taskTarget = Find-GameArtifact $Mode
if (!(Test-Path -LiteralPath $taskTarget)) {
    & (Join-Path $PSScriptRoot 'build.ps1')
    $taskTarget = Find-GameArtifact $Mode
}
$taskArguments = @('-Dfile.encoding=UTF-8','-jar',$taskTarget)
if ($Mode -eq 'server' -and ($Port -gt 0 -or $BindAddress)) {
    if ($Port -eq 0) { $Port = 9528 }
    if (!$BindAddress) { $BindAddress = '0.0.0.0' }
    $taskArguments += @("$Port",$BindAddress)
}
if ($Mode -eq 'client') {
    $taskWindowJava = Join-Path (Split-Path -Parent $taskJava) 'javaw.exe'
    if (Test-Path -LiteralPath $taskWindowJava) { $taskJava = $taskWindowJava }
    $taskArguments = @('-Dfile.encoding=UTF-8','-jar',('"' + $taskTarget + '"'))
    Start-Process -FilePath $taskJava -ArgumentList $taskArguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden | Out-Null
    return
}
& $taskJava @taskArguments
if ($LASTEXITCODE -ne 0) { throw 'The application exited with an error.' }
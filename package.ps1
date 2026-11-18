param(
    [ValidateSet('all','client','server')] [string] $Side = 'all',
    [string] $OutputDirectory = '',
    [string] $ServerHost = '127.0.0.1',
    [ValidateRange(1,65535)] [int] $ServerPort = 9528,
    [switch] $IncludeModels
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
. (Join-Path $PSScriptRoot 'scripts/runtime.ps1')
$taskRoot = [IO.Path]::GetFullPath($PSScriptRoot)
if (!$OutputDirectory) { $OutputDirectory = Join-Path $taskRoot 'dist/packages' }
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
if ([string]::IsNullOrWhiteSpace($ServerHost) -or $ServerHost -match '[\r\n=:/\\]') {
    throw 'Use a hostname or IPv4 address without a URL scheme.'
}
$taskSides = if ($Side -eq 'all') { @('client','server') } else { @($Side) }
foreach ($taskSide in $taskSides) {
    $taskArtifact = Find-GameArtifact $taskSide
    if (!(Test-Path -LiteralPath $taskArtifact)) { throw 'Run build.ps1 first.' }
    $taskArchivePath = Join-Path $taskOutput "qijian-two-tier-$taskSide.zip"
    $taskStream = [IO.File]::Open($taskArchivePath,[IO.FileMode]::Create,[IO.FileAccess]::Write)
    $taskZip = New-Object IO.Compression.ZipArchive($taskStream,[IO.Compression.ZipArchiveMode]::Create)
    try {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,$taskArtifact,"go-game-two-tier/dist/qijian-$taskSide.jar",[IO.Compression.CompressionLevel]::Fastest) | Out-Null
        $taskPattern = '^(runtime/|README\.md$|run\.ps1$|scripts/runtime\.ps1$|start-local\.ps1$|stop-local\.ps1$|启动本机两端测试\.bat$|停止本机测试服务\.bat$)'
        $taskRoots = @('runtime','scripts')
        if ($taskSide -eq 'client') { $taskPattern += '|^启动游戏\.bat$' }
        else {
            $taskPattern += '|^(启动服务器\.bat$|config/server\.properties$|setup-katago\.ps1$|安装KataGo模型\.bat$|katago/(engine/|gtp\.cfg$|ENGINE-LICENSE\.txt$|NETWORK-LICENSE\.html$))'
            if ($IncludeModels) { $taskPattern += '|^katago/models/[^/]+\.bin\.gz$' }
            $taskRoots += @('config','katago')
        }
        $taskFiles = @(Get-ChildItem -LiteralPath $taskRoot -File)
        foreach ($taskFolder in $taskRoots) {
            if (!(Test-Path -LiteralPath (Join-Path $taskRoot $taskFolder))) { continue }
            $taskFiles += @(Get-ChildItem -LiteralPath (Join-Path $taskRoot $taskFolder) -File -Recurse)
        }
        foreach ($taskFile in $taskFiles) {
            $taskRelative = $taskFile.FullName.Substring($taskRoot.Length+1).Replace('\','/')
            if ($taskRelative -match $taskPattern) {
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip,$taskFile.FullName,"go-game-two-tier/$taskRelative",[IO.Compression.CompressionLevel]::Fastest) | Out-Null
            }
        }
        if ($taskSide -eq 'client') {
            $taskEntry = $taskZip.CreateEntry('go-game-two-tier/config/client.properties')
            $taskWriter = New-Object IO.StreamWriter($taskEntry.Open(),(New-Object Text.UTF8Encoding($false)))
            try { $taskWriter.Write("server.host=$ServerHost`nserver.port=$ServerPort`n") }
            finally { $taskWriter.Dispose() }
        }
    } finally { $taskZip.Dispose(); $taskStream.Dispose() }
    Write-Host "Package ready: $taskArchivePath"
}

param([switch] $Test)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
. (Join-Path $PSScriptRoot 'scripts/runtime.ps1')
$taskCompiler = Find-JavaTool 'javac'
$taskJar = Find-JavaTool 'jar'
foreach ($taskDir in @('build/classes','build/test-classes','build/client-classes','build/server-classes')) {
    $taskPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot $taskDir))
    if (!$taskPath.StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid output directory' }
    if (Test-Path -LiteralPath $taskPath) {
        if ((Get-Item -LiteralPath $taskPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Output cannot be a link' }
        Remove-Item -LiteralPath $taskPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $taskPath -Force | Out-Null
}
New-Item -ItemType Directory -Path 'dist' -Force | Out-Null
$taskSources = @(Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object FullName)
& $taskCompiler '-encoding' 'UTF-8' '--release' '17' '-Xlint:all' '-d' 'build/classes' @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item src/go/client/assets build/classes/go/client/assets -Recurse
foreach ($taskSide in @('client','server')) {
    New-Item -ItemType Directory -Path "build/$taskSide-classes/go" -Force | Out-Null
    foreach ($taskModule in @('core','net','ai',$taskSide)) {
        Copy-Item "build/classes/go/$taskModule" "build/$taskSide-classes/go/$taskModule" -Recurse
    }
    if ($taskSide -eq 'server') { Copy-Item build/classes/go/model build/server-classes/go/model -Recurse }
    else {
        # The player package contains difficulty metadata, never the native engine adapter.
        Get-ChildItem build/client-classes/go/ai -Filter 'KataGoEngine*.class' | Remove-Item -Force
    }
    $taskTarget = Get-GameBuildTarget $taskSide "dist/qijian-$taskSide.jar"
    $taskMain = if ($taskSide -eq 'server') { 'go.server.GameServer' } else { 'go.client.GoApp' }
    & $taskJar '--create' '--file' $taskTarget '--main-class' $taskMain '-C' "build/$taskSide-classes" '.'
    if ($LASTEXITCODE -ne 0) { throw "$taskSide packaging failed" }
}
if ($Test) {
    $taskTests = @(Get-ChildItem test -Recurse -Filter '*.java' | ForEach-Object FullName)
    & $taskCompiler '-encoding' 'UTF-8' '--release' '17' '-Xlint:all' '-cp' 'build/classes' '-d' 'build/test-classes' @taskTests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & (Find-JavaTool 'java') '-Dfile.encoding=UTF-8' '-Djava.awt.headless=true' '-ea' '-cp' 'build/classes;build/test-classes' 'go.test.AllTests'
    if ($LASTEXITCODE -ne 0) { throw 'Regression tests failed' }
}
Write-Host 'Built two-tier client and integrated server.'

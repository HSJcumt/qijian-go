param([ValidateSet('Small','Medium','Large','Human','All')] [string] $Models = 'All')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$taskRoot = Join-Path $PSScriptRoot 'katago'
New-Item -ItemType Directory -Force -Path $taskRoot,(Join-Path $taskRoot 'models') | Out-Null

function Get-OfficialFile([string] $Url, [string] $Target, [string] $Sha256 = '') {
    if (Test-Path -LiteralPath $Target) {
        if (!$Sha256 -or (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash -eq $Sha256) {
            Write-Host "Already installed: $Target"
            return
        }
    }
    $taskPartial = "$Target.part"
    Write-Host "Downloading: $Url"
    if (Get-Command 'curl.exe' -ErrorAction SilentlyContinue) {
        & curl.exe '--fail' '--location' '--retry' '3' '--connect-timeout' '20' '--max-time' '900' '--speed-time' '60' '--speed-limit' '1024' '--output' $taskPartial $Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed; retry installation: $Url" }
    } else {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $taskPartial -TimeoutSec 900
    }
    if ($Sha256 -and (Get-FileHash -LiteralPath $taskPartial -Algorithm SHA256).Hash -ne $Sha256) {
        throw "SHA256 mismatch: $Url"
    }
    Move-Item -LiteralPath $taskPartial -Destination $Target -Force
}

# OpenCL needs no separate CUDA/cuDNN installation. Official archive includes licenses.
if (!(Test-Path -LiteralPath (Join-Path $taskRoot 'engine/katago.exe'))) {
    $taskRelease = Invoke-RestMethod -Uri 'https://api.github.com/repos/lightvector/KataGo/releases/tags/v1.18.1'
    $taskAsset = $taskRelease.assets | Where-Object { $_.name -eq 'katago-v1.18.1-opencl-windows-x64.zip' }
    if (!$taskAsset) { throw 'Official OpenCL Windows release not found.' }
    $taskDigest = if ($taskAsset.digest) { $taskAsset.digest -replace '^sha256:', '' } else { '' }
    $taskArchive = Join-Path $taskRoot $taskAsset.name
    Get-OfficialFile $taskAsset.browser_download_url $taskArchive $taskDigest
    Expand-Archive -LiteralPath $taskArchive -DestinationPath (Join-Path $taskRoot 'engine') -Force
}

$taskCatalog = @{
    Small = 'kata1-b18c384nbt-s9996604416-d4316597426'
    Medium = 'kata1-b28c512nbt-s13255194368-d5935380940'
    Large = 'kata1-zhizi-b40c768nbt-s11472M-d5982M'
}
$taskSelected = if ($Models -eq 'All') { @('Small','Medium','Large','Human') } else { @($Models) }
foreach ($taskSize in $taskSelected) {
    if($taskSize -eq 'Human') {
        Get-OfficialFile 'https://media.katagotraining.org/uploaded/networks/models_extra/b18c384nbt-humanv0.bin.gz' (Join-Path $taskRoot 'models/b18c384nbt-humanv0.bin.gz') '637746E44F0EFE00AD1245A50AA9BBF0716EFE364C43965EAD97BD6835D84AB5'
        continue
    }
    $taskName = $taskCatalog[$taskSize]
    Get-OfficialFile "https://media.katagotraining.org/uploaded/networks/models/kata1/$taskName.bin.gz" (Join-Path $taskRoot "models/$taskName.bin.gz")
}
Write-Host 'KataGo is ready. Start the game and choose a model in human-vs-AI practice.'

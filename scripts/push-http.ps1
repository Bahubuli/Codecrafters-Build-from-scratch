param(
    [string]$CommitMessage = "Sync HTTP server from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_HTTP_REMOTE) { $env:CODECRAFTERS_HTTP_REMOTE } else { "http-codecrafters" }

$uncommitted = git -C $rootDir status --porcelain
if ($uncommitted) {
    Write-Error "Commit or stash changes in the monorepo before submitting to CodeCrafters.`n$(git -C $rootDir status --short)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $tempDir | Out-Null

try {
    Write-Host "Cloning current CodeCrafters HTTP server repo..."
    git clone --branch master --single-branch $remoteUrl "$tempDir/http"

    $sourceDir = Join-Path $rootDir "codecrafters-http-server-java"
    Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
        $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
        $targetPath = Join-Path "$tempDir/http" $relativePath
        if ($_.PSIsContainer) {
            if (-not (Test-Path $targetPath)) {
                New-Item -ItemType Directory -Path $targetPath | Out-Null
            }
        } else {
            Copy-Item -Path $_.FullName -Destination $targetPath -Force
        }
    }

    $diff = git -C "$tempDir/http" status --porcelain
    if (-not $diff) {
        Write-Host "No HTTP server changes to submit to CodeCrafters."
        exit 0
    }

    git -C "$tempDir/http" add -A
    git -C "$tempDir/http" commit -m $CommitMessage
    git -C "$tempDir/http" push origin master
    Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green
}
finally {
    [System.GC]::Collect()
    [System.GC]::WaitForPendingFinalizers()
    Start-Sleep -Milliseconds 500
    Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
}

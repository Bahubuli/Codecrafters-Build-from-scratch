param(
    [string]$CommitMessage = "Sync HTTP server from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_HTTP_REMOTE) { $env:CODECRAFTERS_HTTP_REMOTE } else { "http-codecrafters" }

$uncommitted = git -C $rootDir status --porcelain -- codecrafters-http-server-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in codecrafters-http-server-java before submitting to CodeCrafters.`n$(git -C $rootDir status --short -- codecrafters-http-server-java)"
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
        $relPath = $_.FullName.Substring($sourceDir.Length + 1)
        if ($relPath -like "target*" -or $relPath -like ".idea*") { return }

        $dest = Join-Path "$tempDir/http" $relPath
        if ($_.PSIsContainer) {
            if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest | Out-Null }
        } else {
            $destDir = Split-Path -Parent $dest
            if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir | Out-Null }
            Copy-Item $_.FullName -Destination $dest -Force
        }
    }

    git -C "$tempDir/http" add -A
    $st = git -C "$tempDir/http" status --porcelain
    if ($st) {
        git -C "$tempDir/http" commit -m $CommitMessage
        git -C "$tempDir/http" push origin master
        Write-Host "Successfully submitted to CodeCrafters!"
    } else {
        Write-Host "No changes detected to submit."
    }
} finally {
    Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
}

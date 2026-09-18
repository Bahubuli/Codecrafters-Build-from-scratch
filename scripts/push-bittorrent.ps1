param(
    [string]$CommitMessage = "Sync BitTorrent from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_BITTORRENT_REMOTE) { $env:CODECRAFTERS_BITTORRENT_REMOTE } else { "bittorrent-codecrafters" }

# Check for uncommitted changes in bittorrent folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-bittorrent-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the bittorrent folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-bittorrent-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$bittorrentRepoDir = Join-Path $rootDir ".bittorrent-repo"
if (-not (Test-Path $bittorrentRepoDir)) {
    Write-Host "Cloning current CodeCrafters BitTorrent repo..."
    git clone --branch master --single-branch $remoteUrl $bittorrentRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-bittorrent-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git") -or $relativePath.StartsWith("target")) {
        return
    }
    $targetPath = Join-Path $bittorrentRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $bittorrentRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No BitTorrent changes to submit to CodeCrafters."
    exit 0
}

git -C $bittorrentRepoDir add -A
git -C $bittorrentRepoDir commit -m $CommitMessage
git -C $bittorrentRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

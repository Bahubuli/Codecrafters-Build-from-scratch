param(
    [string]$CommitMessage = "Sync SQLite from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_SQLITE_REMOTE) { $env:CODECRAFTERS_SQLITE_REMOTE } else { "sqlite-codecrafters" }

# Check for uncommitted changes in sqlite folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-sqlite-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the sqlite folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-sqlite-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$sqliteRepoDir = Join-Path $rootDir ".sqlite-repo"
if (-not (Test-Path $sqliteRepoDir)) {
    Write-Host "Cloning current CodeCrafters SQLite repo..."
    git clone --branch master --single-branch $remoteUrl $sqliteRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-sqlite-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git")) {
        return
    }
    $targetPath = Join-Path $sqliteRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $sqliteRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No SQLite changes to submit to CodeCrafters."
    exit 0
}

git -C $sqliteRepoDir add -A
git -C $sqliteRepoDir commit -m $CommitMessage
git -C $sqliteRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

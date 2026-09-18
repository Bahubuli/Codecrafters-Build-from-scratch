param(
    [string]$CommitMessage = "Sync Shell from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_SHELL_REMOTE) { $env:CODECRAFTERS_SHELL_REMOTE } else { "shell-codecrafters" }

# Check for uncommitted changes in shell folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-shell-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the shell folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-shell-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$shellRepoDir = Join-Path $rootDir ".shell-repo"
if (-not (Test-Path $shellRepoDir)) {
    Write-Host "Cloning current CodeCrafters shell repo..."
    git clone --branch master --single-branch $remoteUrl $shellRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-shell-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    $targetPath = Join-Path $shellRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $shellRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No Shell changes to submit to CodeCrafters."
    exit 0
}

git -C $shellRepoDir add -A
git -C $shellRepoDir commit -m $CommitMessage
git -C $shellRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

param(
    [string]$CommitMessage = "Sync Grep from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_GREP_REMOTE) { $env:CODECRAFTERS_GREP_REMOTE } else { "grep-codecrafters" }

# Check for uncommitted changes in grep folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-grep-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the grep folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-grep-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$grepRepoDir = Join-Path $rootDir ".grep-repo"
if (-not (Test-Path $grepRepoDir)) {
    Write-Host "Cloning current CodeCrafters grep repo..."
    git clone --branch master --single-branch $remoteUrl $grepRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-grep-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git")) {
        return
    }
    $targetPath = Join-Path $grepRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $grepRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No file diff detected. Creating empty commit to advance stage..."
    git -C $grepRepoDir commit --allow-empty -m $CommitMessage
} else {
    git -C $grepRepoDir add -A
    git -C $grepRepoDir commit -m $CommitMessage
}

git -C $grepRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

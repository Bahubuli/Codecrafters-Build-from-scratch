param(
    [string]$CommitMessage = "Sync Git from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_GIT_REMOTE) { $env:CODECRAFTERS_GIT_REMOTE } else { "git-codecrafters" }

# Check for uncommitted changes in git folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-git-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the git folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-git-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$gitRepoDir = Join-Path $rootDir ".git-challenge-repo"
if (-not (Test-Path $gitRepoDir)) {
    Write-Host "Cloning current CodeCrafters Git repo..."
    git clone --branch master --single-branch $remoteUrl $gitRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-git-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git")) {
        return
    }
    $targetPath = Join-Path $gitRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $gitRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No Git changes to submit to CodeCrafters."
    exit 0
}

git -C $gitRepoDir add -A
git -C $gitRepoDir commit -m $CommitMessage
git -C $gitRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

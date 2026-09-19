param(
    [string]$CommitMessage = "Sync Claude Code from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_CLAUDE_CODE_REMOTE) { $env:CODECRAFTERS_CLAUDE_CODE_REMOTE } else { "claude-code-codecrafters" }

# Check for uncommitted changes in claude-code folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-claude-code-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the claude-code folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-claude-code-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$claudeCodeRepoDir = Join-Path $rootDir ".claude-code-repo"
if (-not (Test-Path $claudeCodeRepoDir)) {
    Write-Host "Cloning current CodeCrafters Claude Code repo..."
    git clone --branch master --single-branch $remoteUrl $claudeCodeRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-claude-code-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git") -or $relativePath.StartsWith("target")) {
        return
    }
    $targetPath = Join-Path $claudeCodeRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $claudeCodeRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No Claude Code changes to submit to CodeCrafters."
    exit 0
}

git -C $claudeCodeRepoDir add -A
git -C $claudeCodeRepoDir commit -m $CommitMessage
git -C $claudeCodeRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

param(
    [string]$CommitMessage = "Sync Interpreter from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_INTERPRETER_REMOTE) { $env:CODECRAFTERS_INTERPRETER_REMOTE } else { "interpreter-codecrafters" }

# Check for uncommitted changes in interpreter folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-interpreter-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the interpreter folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-interpreter-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$interpreterRepoDir = Join-Path $rootDir ".interpreter-repo"
if (-not (Test-Path $interpreterRepoDir)) {
    Write-Host "Cloning current CodeCrafters interpreter repo..."
    git clone --branch master --single-branch $remoteUrl $interpreterRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-interpreter-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git")) {
        return
    }
    $targetPath = Join-Path $interpreterRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $interpreterRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No Interpreter changes to submit to CodeCrafters."
    exit 0
}

git -C $interpreterRepoDir add -A
git -C $interpreterRepoDir commit -m $CommitMessage
git -C $interpreterRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

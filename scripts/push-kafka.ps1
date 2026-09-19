param(
    [string]$CommitMessage = "Sync Kafka from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_KAFKA_REMOTE) { $env:CODECRAFTERS_KAFKA_REMOTE } else { "kafka-codecrafters" }

# Check for uncommitted changes in kafka folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-kafka-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the kafka folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-kafka-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$kafkaRepoDir = Join-Path $rootDir ".kafka-repo"
if (-not (Test-Path $kafkaRepoDir)) {
    Write-Host "Cloning current CodeCrafters kafka repo..."
    git clone --branch master --single-branch $remoteUrl $kafkaRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-kafka-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git") -or $relativePath.StartsWith("target")) {
        return
    }
    $targetPath = Join-Path $kafkaRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $kafkaRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No file diff detected. Creating empty commit to advance stage..."
    git -C $kafkaRepoDir commit --allow-empty -m $CommitMessage
} else {
    git -C $kafkaRepoDir add -A
    git -C $kafkaRepoDir commit -m $CommitMessage
}

git -C $kafkaRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

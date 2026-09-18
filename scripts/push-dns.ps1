param(
    [string]$CommitMessage = "Sync DNS server from monorepo"
)

$ErrorActionPreference = "Stop"

$rootDir = Split-Path -Parent $PSScriptRoot
$remoteName = if ($env:CODECRAFTERS_DNS_REMOTE) { $env:CODECRAFTERS_DNS_REMOTE } else { "dns-codecrafters" }

# Check for uncommitted changes in dns folder
$uncommitted = git -C $rootDir status --porcelain codecrafters-dns-server-java
if ($uncommitted) {
    Write-Error "Commit or stash changes in the dns folder before submitting to CodeCrafters.`n$(git -C $rootDir status --short codecrafters-dns-server-java)"
    exit 1
}

$remoteUrl = git -C $rootDir remote get-url $remoteName 2>$null
if (-not $remoteUrl) {
    Write-Error "No '$remoteName' remote found. Add it with: git remote add $remoteName <codecrafters-git-url>"
    exit 1
}

$dnsRepoDir = Join-Path $rootDir ".dns-repo"
if (-not (Test-Path $dnsRepoDir)) {
    Write-Host "Cloning current CodeCrafters DNS repo..."
    git clone --branch master --single-branch $remoteUrl $dnsRepoDir
}

$sourceDir = Join-Path $rootDir "codecrafters-dns-server-java"
Get-ChildItem -Path $sourceDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($sourceDir.Length + 1)
    if ($relativePath.StartsWith("Tasks") -or $relativePath.StartsWith(".git")) {
        return
    }
    $targetPath = Join-Path $dnsRepoDir $relativePath
    if ($_.PSIsContainer) {
        if (-not (Test-Path $targetPath)) {
            New-Item -ItemType Directory -Path $targetPath | Out-Null
        }
    } else {
        Copy-Item -Path $_.FullName -Destination $targetPath -Force
    }
}

$diff = git -C $dnsRepoDir status --porcelain
if (-not $diff) {
    Write-Host "No DNS server changes to submit to CodeCrafters."
    exit 0
}

git -C $dnsRepoDir add -A
git -C $dnsRepoDir commit -m $CommitMessage
git -C $dnsRepoDir push origin master
Write-Host "Successfully submitted to CodeCrafters!" -ForegroundColor Green

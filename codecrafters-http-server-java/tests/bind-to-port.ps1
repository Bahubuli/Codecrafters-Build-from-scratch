$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$classesPath = Join-Path $projectRoot 'target\classes'
$sourcePath = Join-Path $projectRoot 'src\main\java\Main.java'

New-Item -ItemType Directory -Force -Path $classesPath | Out-Null
javac -d $classesPath $sourcePath
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

$process = $null
$client = $null
try {
    $process = Start-Process -FilePath 'java' -ArgumentList '-cp', $classesPath, 'Main' -PassThru -NoNewWindow
    $deadline = (Get-Date).AddSeconds(5)
    $connected = $false

    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            break
        }

        try {
            $client = [System.Net.Sockets.TcpClient]::new()
            $client.Connect('127.0.0.1', 4221)
            $connected = $true
            break
        }
        catch [System.Net.Sockets.SocketException] {
            if ($client) {
                $client.Dispose()
                $client = $null
            }
            Start-Sleep -Milliseconds 100
        }
    }

    if (-not $connected) {
        throw 'Main did not accept a TCP connection on 127.0.0.1:4221 within 5 seconds.'
    }
}
finally {
    if ($client) {
        $client.Dispose()
    }
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        $process.WaitForExit()
    }
}

Write-Output 'PASS: Main accepted a TCP connection on 127.0.0.1:4221.'

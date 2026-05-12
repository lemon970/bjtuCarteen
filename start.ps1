param(
    [int]$Port = 8080,
    [switch]$SkipBuild,
    [switch]$Foreground
)

$ErrorActionPreference = "Stop"

function Get-ListenerPid {
    param([int]$LocalPort)
    $conn = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $conn) {
        return $null
    }
    return $conn.OwningProcess
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

$existingPid = Get-ListenerPid -LocalPort $Port
if ($null -ne $existingPid) {
    Write-Host "Port $Port is already in use by PID $existingPid."
    Write-Host "Please stop that process first, then rerun this script."
    exit 1
}

if (-not $SkipBuild) {
    Write-Host "Building project (skip tests)..."
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed. Exit code: $LASTEXITCODE"
    }
}

$jarPath = Join-Path $ProjectRoot "target\simulation-0.0.1-SNAPSHOT-exec.jar"
if (-not (Test-Path $jarPath)) {
    throw "Jar not found: $jarPath"
}

$runDir = Join-Path $ProjectRoot "run"
if (-not (Test-Path $runDir)) {
    New-Item -Path $runDir -ItemType Directory | Out-Null
}

$pidFile = Join-Path $runDir "simulation.pid"
$outLog = Join-Path $runDir "simulation.out.log"
$errLog = Join-Path $runDir "simulation.err.log"

if ($Foreground) {
    Write-Host "Starting in foreground on port $Port..."
    & java -jar $jarPath "--server.port=$Port"
    exit $LASTEXITCODE
}

Write-Host "Starting in background on port $Port..."
$proc = Start-Process `
    -FilePath "java" `
    -ArgumentList @("-jar", $jarPath, "--server.port=$Port") `
    -WorkingDirectory $ProjectRoot `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -PassThru

Set-Content -Path $pidFile -Value $proc.Id -Encoding ascii
Start-Sleep -Seconds 4

$listeningPid = Get-ListenerPid -LocalPort $Port
if ($null -eq $listeningPid) {
    Write-Host "Process started (PID $($proc.Id)), but port $Port is not listening yet."
    Write-Host "Check logs:"
    Write-Host "  $outLog"
    Write-Host "  $errLog"
    exit 1
}

Write-Host "Started successfully."
Write-Host "PID: $($proc.Id)"
Write-Host "URL: http://localhost:$Port/"
Write-Host "PID file: $pidFile"
Write-Host "Logs:"
Write-Host "  $outLog"
Write-Host "  $errLog"

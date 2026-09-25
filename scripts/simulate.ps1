<#
.SYNOPSIS
  Runs the partition simulation against a real Gatekeeper server started just for the run.

.DESCRIPTION
  Builds the jar, starts a server on its own throwaway database (never ./data/gatekeeper),
  drives it with simulated gates over HTTP, prints the server's own metrics, then stops it and
  deletes the database. The exit code is the simulation's: 0 passed, 1 wrong result, 2 could not run.

.EXAMPLE
  .\scripts\simulate.ps1
  .\scripts\simulate.ps1 -Tickets 50000 -Concurrency 8
  .\scripts\simulate.ps1 -SkipBuild -- --shared-rate 0.2 --skew-seconds 5

  Anything after -- is passed straight to the simulation CLI (see its --help).
#>
[CmdletBinding()]
param(
    [int]$Tickets = 20000,
    [int]$Gates = 12,
    [int]$Concurrency = 4,
    [int]$Batch = 1000,
    [long]$Seed = 42,
    [int]$Port = 18095,
    [switch]$SkipBuild,
    [switch]$KeepLog,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArgs
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $java = "$env:JAVA_HOME\bin\java.exe"
} else {
    $found = Get-Command java -ErrorAction SilentlyContinue
    if (-not $found) { throw "java was not found. Install JDK 21 or set JAVA_HOME." }
    $java = $found.Source
}

if (-not $SkipBuild) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw "mvn was not found; install Maven or pass -SkipBuild." }
    Write-Host "Building..."
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}

$jar = Get-ChildItem target -Filter "gatekeeper-*.jar" | Where-Object { $_.Name -notlike "*original*" } | Select-Object -First 1
if (-not $jar) { throw "no jar in target/; run without -SkipBuild" }

$dataDir = Join-Path $root "data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$dbBase = (Join-Path $dataDir "simulation").Replace("\", "/")
$log = Join-Path $dataDir "sim-server.log"   # deliberately not "simulation*": that pattern is what gets cleaned up
function Remove-SimulationDatabase { Get-ChildItem $dataDir -Filter "simulation*" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue }
Remove-SimulationDatabase

$env:SERVER_PORT = "$Port"
$env:SPRING_DATASOURCE_URL = "jdbc:h2:file:$dbBase"
$server = $null
$exit = 2
try {
    Write-Host "Starting a server on port $Port with a throwaway database..."
    $server = Start-Process -FilePath $java -ArgumentList @("-jar", $jar.FullName) -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err"

    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        try { if ((Invoke-WebRequest "http://localhost:$Port/healthz" -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200) { $ready = $true; break } } catch { }
        if ($server.HasExited) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "the server did not start; see $log" }

    Write-Host ""
    & $java -cp (Join-Path $root "target\classes") dev.gatekeeper.simulation.SimulationCli `
        --url "http://localhost:$Port" --tickets $Tickets --gates $Gates --concurrency $Concurrency `
        --batch $Batch --seed $Seed @CliArgs
    $exit = $LASTEXITCODE

    Write-Host ""
    Write-Host "Server metrics after the run (GET /actuator/prometheus):"
    try {
        (Invoke-WebRequest "http://localhost:$Port/actuator/prometheus" -UseBasicParsing).Content -split "`n" |
            Where-Object { $_ -match '^gatekeeper_' -and $_ -notmatch '_bucket' } |
            ForEach-Object { "  $_" }
    } catch { Write-Host "  (could not read metrics: $($_.Exception.Message))" }
}
finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force; $server.WaitForExit(10000) | Out-Null }
    Remove-Item Env:\SERVER_PORT, Env:\SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    Remove-SimulationDatabase
    if ($KeepLog) { Write-Host "Server log kept at $log" } else { Remove-Item $log, "$log.err" -ErrorAction SilentlyContinue }
    # Leave no trace: remove the folder too, but only if it is empty, so a real dev database is never touched.
    if (-not (Get-ChildItem $dataDir -Force -ErrorAction SilentlyContinue)) { Remove-Item $dataDir -ErrorAction SilentlyContinue }
}
exit $exit

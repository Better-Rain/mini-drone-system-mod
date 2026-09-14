# Preflight for a Minecraft <-> main-project live session.
#
# Run this before starting Minecraft. It checks the things that waste a live
# round trip: an instance still holding an older build of the mod, a missing
# Fabric API, a port already taken, or the isolated backend not running.
#
# Read-only: it inspects files, hashes and port ownership, and starts nothing.

[CmdletBinding()]
param(
    [string]$MinecraftRoot = "C:\Users\VLT_BR\Saved Games\Minecraft\.minecraft",
    [string]$InstanceName = "Mini Drone System 1.21.1",
    [string]$MainProjectRoot = "C:\Users\VLT_BR\Projects\mini-drone-system",
    [int]$WebSocketPort = 18082,
    [int]$MavlinkPort = 14561,
    [int]$MocapHealthPort = 18151,
    [int]$MocapControlPort = 18152,
    [int]$ModLocalPort = 14601,
    [switch]$AllowStaleMod
)

$ErrorActionPreference = "Stop"

$results = @()
function Add-Check {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][ValidateSet("OK", "WARN", "FAIL")][string]$Level,
        [Parameter(Mandatory)][string]$Detail,
        [string]$Fix = ""
    )
    $script:results += [pscustomobject]@{ Name = $Name; Level = $Level; Detail = $Detail; Fix = $Fix }
    $line = "{0,-5} {1,-34} {2}" -f $Level, $Name, $Detail
    switch ($Level) {
        "OK" { Write-Host $line -ForegroundColor Green }
        "WARN" { Write-Host $line -ForegroundColor Yellow }
        "FAIL" { Write-Host $line -ForegroundColor Red }
    }
    if ($Fix) {
        Write-Host ("      -> {0}" -f $Fix) -ForegroundColor DarkGray
    }
}

function Get-NewestModJar {
    param([string]$Directory)
    if (-not (Test-Path -LiteralPath $Directory -PathType Container)) {
        return $null
    }
    $candidates = Get-ChildItem -LiteralPath $Directory -Filter "mini-drone-system-mod-*.jar" -File |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending
    if (-not $candidates) {
        return $null
    }
    return $candidates[0]
}

function Test-PortListeners {
    param([Parameter(Mandatory)][int[]]$TcpPorts, [Parameter(Mandatory)][int[]]$UdpPorts)

    $owners = @{}
    foreach ($port in $TcpPorts) {
        $connection = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($connection) {
            $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
            $owners[$port] = if ($process) { "$($process.ProcessName) (pid $($process.Id))" } else { "pid $($connection.OwningProcess)" }
        } else {
            $owners[$port] = $null
        }
    }
    foreach ($port in $UdpPorts) {
        $endpoint = Get-NetUDPEndpoint -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($endpoint) {
            $process = Get-Process -Id $endpoint.OwningProcess -ErrorAction SilentlyContinue
            $owners[$port] = if ($process) { "$($process.ProcessName) (pid $($process.Id))" } else { "pid $($endpoint.OwningProcess)" }
        } else {
            $owners[$port] = $null
        }
    }
    return $owners
}

function Test-BackendReachable {
    param([Parameter(Mandatory)][int]$Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync("127.0.0.1", $Port)
        return $connect.Wait(400) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$libsPath = Join-Path $repositoryRoot "build\libs"
$instancePath = Join-Path ([System.IO.Path]::GetFullPath($MinecraftRoot)) "versions\$InstanceName"
$installedModsPath = Join-Path $instancePath "mods"

Write-Host "Mini Drone System preflight"
Write-Host "  repository : $repositoryRoot"
Write-Host "  instance   : $instancePath"
Write-Host ""

# --- the build the operator is about to test -------------------------------
$builtJar = Get-NewestModJar -Directory $libsPath
if (-not $builtJar) {
    Add-Check -Name "built mod JAR" -Level "FAIL" `
        -Detail "no mini-drone-system-mod-*.jar in $libsPath" `
        -Fix "run .\gradlew.bat build"
    $builtHash = $null
} else {
    $builtHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $builtJar.FullName).Hash
    Add-Check -Name "built mod JAR" -Level "OK" `
        -Detail "$($builtJar.Name)  sha256 $($builtHash.Substring(0, 16))..."
}

# --- what the instance will actually load ---------------------------------
$installedJar = Get-NewestModJar -Directory $installedModsPath
if (-not $installedJar) {
    Add-Check -Name "installed mod JAR" -Level "FAIL" `
        -Detail "none in $installedModsPath" `
        -Fix "run .\scripts\install-pcl-instance.ps1"
} elseif ($builtHash) {
    $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installedJar.FullName).Hash
    if ($installedHash -eq $builtHash) {
        Add-Check -Name "installed mod JAR" -Level "OK" `
            -Detail "$($installedJar.Name) matches the build ($($installedHash.Substring(0, 16))...)"
    } else {
        Add-Check -Name "installed mod JAR" `
            -Level $(if ($AllowStaleMod) { "WARN" } else { "FAIL" }) `
            -Detail "$($installedJar.Name) is a different build (sha256 $($installedHash.Substring(0, 16))..., built $($installedJar.LastWriteTime))" `
            -Fix "run .\scripts\install-pcl-instance.ps1, then launch Minecraft again"
    }
}

$fabricApi = Get-ChildItem -LiteralPath $installedModsPath -Filter "fabric-api-*.jar" -File -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($fabricApi) {
    Add-Check -Name "fabric API in instance" -Level "OK" -Detail $fabricApi.Name
} else {
    Add-Check -Name "fabric API in instance" -Level "FAIL" `
        -Detail "no fabric-api-*.jar in $installedModsPath" `
        -Fix "run .\scripts\install-pcl-instance.ps1"
}

# --- ports -----------------------------------------------------------------
# Who binds what matters: the isolated backend binds 18082/14561/18151, the mod
# binds 18152 and 14601, and the backend only ever probes 18152. Treating them
# alike would warn on a healthy setup.
$owners = Test-PortListeners -TcpPorts @($WebSocketPort, 8080) `
    -UdpPorts @($MavlinkPort, $MocapHealthPort, $MocapControlPort, $ModLocalPort)

function Test-HeldByBackend {
    param([string]$Owner)
    return $Owner -and $Owner -like "drone_backend*"
}

function Test-HeldByGame {
    param([string]$Owner)
    return $Owner -and $Owner -like "java*"
}

$backendPorts = @($WebSocketPort, $MavlinkPort, $MocapHealthPort)
$misbound = @($backendPorts | Where-Object { $owners[$_] -and -not (Test-HeldByBackend $owners[$_]) })
$backendRunning = @($backendPorts | Where-Object { Test-HeldByBackend $owners[$_] }).Count
if ($misbound.Count -gt 0) {
    $detail = ($misbound | ForEach-Object { "$_=$($owners[$_])" }) -join '; '
    Add-Check -Name "backend ports $($backendPorts -join '/')" -Level "FAIL" `
        -Detail "held by something other than the isolated backend: $detail" `
        -Fix "stop that process; these three must be free or owned by drone_backend"
} elseif ($backendRunning -eq $backendPorts.Count) {
    $detail = ($backendPorts | ForEach-Object { "$_=$($owners[$_])" }) -join '; '
    Add-Check -Name "backend ports $($backendPorts -join '/')" -Level "OK" `
        -Detail "the isolated backend holds all three ($detail)"
} elseif ($backendRunning -eq 0) {
    Add-Check -Name "backend ports $($backendPorts -join '/')" -Level "WARN" `
        -Detail "all free; the isolated backend is not running yet" `
        -Fix "start it with .\scripts\start-isolated-backend.ps1 before connecting the frontend"
} else {
    $detail = ($backendPorts | ForEach-Object {
        "$_=$(if ($owners[$_]) { $owners[$_] } else { 'free' })"
    }) -join '; '
    Add-Check -Name "backend ports $($backendPorts -join '/')" -Level "WARN" `
        -Detail "partly held: $detail" `
        -Fix "check for a leftover backend process"
}

# The mod owns these two; a game holding them is the normal running state.
$modPorts = @($MocapControlPort, $ModLocalPort)
$modPortProblems = @($modPorts | Where-Object {
    $owners[$_] -and -not (Test-HeldByBackend $owners[$_]) -and -not (Test-HeldByGame $owners[$_])
})
$backendOnModPort = @($modPorts | Where-Object { Test-HeldByBackend $owners[$_] })
if ($backendOnModPort.Count -gt 0) {
    $detail = ($backendOnModPort | ForEach-Object { "$_=$($owners[$_])" }) -join '; '
    Add-Check -Name "mod ports $($modPorts -join '/')" -Level "FAIL" `
        -Detail "the backend is holding a port the mod must bind: $detail" `
        -Fix "start the isolated backend with the default control port; it probes 18152, it must not bind it"
} elseif ($modPortProblems.Count -gt 0) {
    $detail = ($modPortProblems | ForEach-Object { "$_=$($owners[$_])" }) -join '; '
    Add-Check -Name "mod ports $($modPorts -join '/')" -Level "FAIL" `
        -Detail "held by neither the backend nor a Java process: $detail" `
        -Fix "stop that process; the mod must be able to bind both"
} else {
    $gameRunning = @($modPorts | Where-Object { Test-HeldByGame $owners[$_] }).Count -gt 0
    $detail = if ($gameRunning) {
        "held by the running game, as expected"
    } else {
        "free; Minecraft is not running yet"
    }
    Add-Check -Name "mod ports $($modPorts -join '/')" -Level "OK" -Detail $detail
}

if (Test-BackendReachable -Port $WebSocketPort) {
    Add-Check -Name "isolated backend websocket" -Level "OK" `
        -Detail "127.0.0.1:$WebSocketPort accepts connections"
} else {
    Add-Check -Name "isolated backend websocket" -Level "WARN" `
        -Detail "nothing accepts connections on 127.0.0.1:$WebSocketPort" `
        -Fix "start .\scripts\start-isolated-backend.ps1 (the frontend needs ?backendWs=ws://127.0.0.1:$WebSocketPort)"
}

# --- main project ----------------------------------------------------------
$launcher = Join-Path ([System.IO.Path]::GetFullPath($MainProjectRoot)) "scripts\start-backend-mavlink-sitl.ps1"
$backendExe = Join-Path ([System.IO.Path]::GetFullPath($MainProjectRoot)) "backend\build\drone_backend.exe"
if ((Test-Path -LiteralPath $launcher -PathType Leaf) -and (Test-Path -LiteralPath $backendExe -PathType Leaf)) {
    Add-Check -Name "main project backend build" -Level "OK" `
        -Detail "drone_backend.exe built $((Get-Item -LiteralPath $backendExe).LastWriteTime)"
} else {
    Add-Check -Name "main project backend build" -Level "FAIL" `
        -Detail "missing launcher or backend\build\drone_backend.exe" `
        -Fix "build the main project's backend first"
}

Write-Host ""
# Informational: never affects the exit code.
if ($owners[8080]) {
    Write-Host "note: an on-site backend is listening on 8080 ($($owners[8080])); the live test must use the isolated ports above." -ForegroundColor DarkGray
} else {
    Write-Host "note: nothing is listening on 8080, so no on-site backend is running." -ForegroundColor DarkGray
}

$failures = @($results | Where-Object { $_.Level -eq "FAIL" })
$warnings = @($results | Where-Object { $_.Level -eq "WARN" })
if ($failures.Count -gt 0) {
    Write-Host "$($failures.Count) check(s) failed, $($warnings.Count) warning(s)." -ForegroundColor Red
    exit 1
}
Write-Host "Preflight passed ($($warnings.Count) warning(s)). Next: docs/live-run-guide.md" -ForegroundColor Green
exit 0

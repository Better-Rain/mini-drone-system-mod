[CmdletBinding()]
param(
    [string]$MainProjectRoot = "C:\Users\VLT_BR\Projects\mini-drone-system",
    [string]$BackendExecutable = "backend\build\drone_backend.exe",
    [int]$WebSocketPort = 18082,
    [int]$MavlinkPort = 14561,
    [int]$MocapHealthPort = 18151,
    [int]$MocapControlPort = 18152,
    [string]$MocapExpectedDroneId = "minecraft_drone_01"
)

$ErrorActionPreference = "Stop"

function Test-TcpPortAvailable {
    param([int]$Port)

    $listener = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback,
        $Port
    )
    try {
        $listener.Start()
        return $true
    } catch {
        return $false
    } finally {
        $listener.Stop()
    }
}

function Test-UdpPortAvailable {
    param([int]$Port)

    $socket = [System.Net.Sockets.UdpClient]::new()
    try {
        $socket.Client.ExclusiveAddressUse = $true
        $socket.Client.Bind(
            [System.Net.IPEndPoint]::new([System.Net.IPAddress]::Loopback, $Port)
        )
        return $true
    } catch {
        return $false
    } finally {
        $socket.Dispose()
    }
}

$mainRoot = [System.IO.Path]::GetFullPath($MainProjectRoot)
$launcher = Join-Path $mainRoot "scripts\start-backend-mavlink-sitl.ps1"
$backendPath = if ([System.IO.Path]::IsPathRooted($BackendExecutable)) {
    [System.IO.Path]::GetFullPath($BackendExecutable)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $mainRoot $BackendExecutable))
}

if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
    throw "Main-project MAVLink launcher not found: $launcher"
}
if (-not (Test-Path -LiteralPath $backendPath -PathType Leaf)) {
    throw "Backend executable not found: $backendPath"
}
if (-not (Test-TcpPortAvailable -Port $WebSocketPort)) {
    throw "Isolated WebSocket port 127.0.0.1:$WebSocketPort is already in use."
}
if (-not (Test-UdpPortAvailable -Port $MavlinkPort)) {
    throw "Minecraft MAVLink port 127.0.0.1:$MavlinkPort is already in use."
}
if (-not (Test-UdpPortAvailable -Port $MocapHealthPort)) {
    throw "Motion-capture health port 127.0.0.1:$MocapHealthPort is already in use; an existing backend may be active."
}

Write-Host "Starting isolated Minecraft backend"
Write-Host "WebSocket : ws://127.0.0.1:$WebSocketPort"
Write-Host "MAVLink  : udpin://127.0.0.1:$MavlinkPort"
Write-Host "Vehicle  : minecraft_drone_01 (system 54, component 1)"
Write-Host "Mocap health: 127.0.0.1:$MocapHealthPort"
Write-Host "Mocap expected drone id: $MocapExpectedDroneId"
Write-Host "The mod must advertise the same id; override it with"
Write-Host "  -Dmini_drone.mocap.expected_drone_id=$MocapExpectedDroneId"
Write-Host "when its built-in default differs from this value."

& $launcher `
    -BackendExecutable $backendPath `
    -Endpoint "udpin://127.0.0.1:$MavlinkPort" `
    -TargetSystemId 54 `
    -TargetComponentId 1 `
    -BindIds "minecraft_drone_01" `
    -WsPort $WebSocketPort `
    -BindAddress "127.0.0.1" `
    -MavlinkLocalAddress "127.0.0.1" `
    -MocapMode "virtual" `
    -MocapProfile "minecraft_virtual_mocap" `
    -MocapSourceHost "127.0.0.1" `
    -MocapSourcePort 15150 `
    -MocapHealthHost "127.0.0.1" `
    -MocapHealthPort $MocapHealthPort `
    -MocapControlHost "127.0.0.1" `
    -MocapControlPort $MocapControlPort `
    -MocapExpectedDroneId $MocapExpectedDroneId

exit $LASTEXITCODE

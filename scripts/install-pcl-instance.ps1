[CmdletBinding()]
param(
    [string]$MinecraftRoot = "C:\Users\VLT_BR\Saved Games\Minecraft\.minecraft",
    [string]$InstanceName = "Mini Drone System 1.21.1",
    [string]$ModJar = "build\libs\mini-drone-system-mod-0.6.0.jar",
    [string]$MinecraftVersion = "1.21.1",
    [string]$LoaderVersion = "0.19.3",
    [string]$FabricApiVersion = "0.116.15+1.21.1"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Write-Utf8Json {
    param(
        [Parameter(Mandatory)]$Value,
        [Parameter(Mandatory)][string]$Path
    )

    $json = $Value | ConvertTo-Json -Depth 100
    [System.IO.File]::WriteAllText(
        $Path,
        $json + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Save-RemoteFile {
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$Destination
    )

    $temporaryPath = "$Destination.download-$([Guid]::NewGuid().ToString('N'))"
    try {
        Invoke-WebRequest -Uri $Uri -OutFile $temporaryPath -UseBasicParsing
        Move-Item -LiteralPath $temporaryPath -Destination $Destination -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Force
        }
    }
}

if ([string]::IsNullOrWhiteSpace($InstanceName) -or
    $InstanceName.IndexOfAny([System.IO.Path]::GetInvalidFileNameChars()) -ge 0) {
    throw "InstanceName is not a valid Windows directory name: $InstanceName"
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$minecraftPath = [System.IO.Path]::GetFullPath($MinecraftRoot)
$modJarPath = if ([System.IO.Path]::IsPathRooted($ModJar)) {
    [System.IO.Path]::GetFullPath($ModJar)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $ModJar))
}

if (-not (Test-Path -LiteralPath $minecraftPath -PathType Container)) {
    throw "Minecraft root not found: $minecraftPath"
}
if (-not (Test-Path -LiteralPath $modJarPath -PathType Leaf)) {
    throw "Built mod JAR not found: $modJarPath`nRun .\gradlew.bat build first."
}

$versionsPath = Join-Path $minecraftPath "versions"
$baseVersionPath = Join-Path $versionsPath $MinecraftVersion
$instancePath = Join-Path $versionsPath $InstanceName
$modsPath = Join-Path $instancePath "mods"
$pclPath = Join-Path $instancePath "PCL"

New-Item -ItemType Directory -Path $baseVersionPath -Force | Out-Null
New-Item -ItemType Directory -Path $modsPath -Force | Out-Null
New-Item -ItemType Directory -Path $pclPath -Force | Out-Null

# PCL requires the inherited vanilla JSON to exist before it accepts a Fabric profile.
$baseJsonPath = Join-Path $baseVersionPath "$MinecraftVersion.json"
if (-not (Test-Path -LiteralPath $baseJsonPath -PathType Leaf)) {
    $manifest = Invoke-RestMethod -Uri "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json" -UseBasicParsing
    $baseEntry = $manifest.versions | Where-Object { $_.id -eq $MinecraftVersion } | Select-Object -First 1
    if ($null -eq $baseEntry) {
        throw "Minecraft version is absent from Mojang's manifest: $MinecraftVersion"
    }

    Save-RemoteFile -Uri $baseEntry.url -Destination $baseJsonPath
    $baseHash = (Get-FileHash -Algorithm SHA1 -LiteralPath $baseJsonPath).Hash.ToLowerInvariant()
    if ($baseHash -ne $baseEntry.sha1.ToLowerInvariant()) {
        throw "Mojang version JSON checksum mismatch for $MinecraftVersion"
    }
}

$baseJson = Get-Content -LiteralPath $baseJsonPath -Raw | ConvertFrom-Json
if ($baseJson.id -ne $MinecraftVersion -or [string]::IsNullOrWhiteSpace($baseJson.mainClass)) {
    throw "Invalid base version JSON: $baseJsonPath"
}
if ($baseJson.javaVersion.majorVersion -ne 21) {
    throw "Minecraft $MinecraftVersion does not request Java 21 as expected."
}

$profileUri = "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion/$LoaderVersion/profile/json"
$profile = Invoke-RestMethod -Uri $profileUri -UseBasicParsing
if ($profile.inheritsFrom -ne $MinecraftVersion -or
    $profile.mainClass -ne "net.fabricmc.loader.impl.launch.knot.KnotClient") {
    throw "Unexpected Fabric profile returned by $profileUri"
}
$profile.id = $InstanceName
$instanceJsonPath = Join-Path $instancePath "$InstanceName.json"
Write-Utf8Json -Value $profile -Path $instanceJsonPath

$destinationModJar = Join-Path $modsPath ([System.IO.Path]::GetFileName($modJarPath))
$staleModJars = Get-ChildItem -LiteralPath $modsPath -Filter "mini-drone-system-mod-*.jar" -File |
    Where-Object { $_.FullName -ne $destinationModJar }
foreach ($staleModJar in $staleModJars) {
    Remove-Item -LiteralPath $staleModJar.FullName -Force
}
Copy-Item -LiteralPath $modJarPath -Destination $destinationModJar -Force

$fabricApiFile = "fabric-api-$FabricApiVersion.jar"
$fabricApiPath = Join-Path $modsPath $fabricApiFile
$staleFabricApiJars = Get-ChildItem -LiteralPath $modsPath -Filter "fabric-api-*.jar" -File |
    Where-Object { $_.FullName -ne $fabricApiPath }
if ($staleFabricApiJars) {
    throw "Another Fabric API JAR is already installed: $($staleFabricApiJars.FullName -join ', ')"
}

$fabricApiBaseUri = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FabricApiVersion/$fabricApiFile"
$expectedFabricApiSha1 = (Invoke-RestMethod -Uri "$fabricApiBaseUri.sha1" -UseBasicParsing).Trim().ToLowerInvariant()
if (-not (Test-Path -LiteralPath $fabricApiPath -PathType Leaf) -or
    (Get-FileHash -Algorithm SHA1 -LiteralPath $fabricApiPath).Hash.ToLowerInvariant() -ne $expectedFabricApiSha1) {
    Save-RemoteFile -Uri $fabricApiBaseUri -Destination $fabricApiPath
}
$actualFabricApiSha1 = (Get-FileHash -Algorithm SHA1 -LiteralPath $fabricApiPath).Hash.ToLowerInvariant()
if ($actualFabricApiSha1 -ne $expectedFabricApiSha1) {
    throw "Fabric API checksum mismatch: $fabricApiPath"
}

$modHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationModJar).Hash
Write-Host "PCL instance installed: $instancePath"
Write-Host "Minecraft/Fabric: $MinecraftVersion / $LoaderVersion"
Write-Host "Fabric API: $FabricApiVersion ($actualFabricApiSha1)"
Write-Host "Mod SHA-256: $modHash"
Write-Host "Virtual mocap remains disabled unless mini_drone.mocap.enabled=true is added to this instance's JVM arguments."

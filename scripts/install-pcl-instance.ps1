[CmdletBinding()]
param(
    [string]$MinecraftRoot = "C:\Users\VLT_BR\Saved Games\Minecraft\.minecraft",
    [string]$InstanceName = "Mini Drone System 1.21.1",
    [string]$ModJar = "",
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
$libsPath = Join-Path $repositoryRoot "build\libs"

# Resolve the built JAR instead of naming a version: a hardcoded file name here
# would survive a version bump and fail with "Run .\gradlew.bat build first"
# even though the build succeeded.
function Resolve-ModJarPath {
    param([string]$Requested)

    if (-not [string]::IsNullOrWhiteSpace($Requested)) {
        $expanded = $Requested
        if (-not [System.IO.Path]::IsPathRooted($expanded)) {
            $expanded = Join-Path $repositoryRoot $expanded
        }
        return [System.IO.Path]::GetFullPath($expanded)
    }
    if (-not (Test-Path -LiteralPath $libsPath -PathType Container)) {
        return ""
    }
    $candidates = Get-ChildItem -LiteralPath $libsPath -Filter "mini-drone-system-mod-*.jar" -File |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
        Sort-Object LastWriteTime -Descending
    if (-not $candidates) {
        return ""
    }
    return $candidates[0].FullName
}

$modJarPath = Resolve-ModJarPath -Requested $ModJar

if (-not (Test-Path -LiteralPath $minecraftPath -PathType Container)) {
    throw "Minecraft root not found: $minecraftPath"
}
if ([string]::IsNullOrWhiteSpace($modJarPath) -or
    -not (Test-Path -LiteralPath $modJarPath -PathType Leaf)) {
    throw "Built mod JAR not found in $libsPath`nRun .\gradlew.bat build first."
}

# Installing a sources or javadoc JAR, or a JAR from somewhere else, would look
# like success here and only fail when Minecraft starts. The sources JAR carries
# fabric.mod.json too, so the check has to look for compiled classes as well.
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
$archive = [System.IO.Compression.ZipFile]::OpenRead($modJarPath)
try {
    $hasModJson = $archive.Entries | Where-Object { $_.FullName -eq "fabric.mod.json" } | Select-Object -First 1
    $hasClasses = $archive.Entries | Where-Object { $_.FullName -like "*.class" } | Select-Object -First 1
} finally {
    $archive.Dispose()
}
if (-not $hasModJson -or -not $hasClasses) {
    throw "Not a loadable Fabric mod JAR (needs fabric.mod.json and compiled classes): $modJarPath"
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
Write-Host "Mod JAR: $modJarPath"
Write-Host "Minecraft/Fabric: $MinecraftVersion / $LoaderVersion"
Write-Host "Fabric API: $FabricApiVersion ($actualFabricApiSha1)"
Write-Host "Mod SHA-256: $modHash"
Write-Host "Virtual mocap is disabled by default. In a world, run /minidrone mocap enable; JVM override: -Dmini_drone.mocap.enabled=true"

# Start a local Halo 2.27.1 instance for halo-chat development and (re)install
# the built plugin JAR. The server keeps running after the script exits.
#
# Usage:
#   .\scripts\dev-halo.ps1
#   .\scripts\dev-halo.ps1 -Port 18090 -WorkDir "E:\...\workdir" -Reset
#
param(
    [int]$Port = 18090,
    [string]$WorkDir = "",
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "Admin@12345",
    [switch]$Reset
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

$ProjectRoot = Split-Path $PSScriptRoot -Parent
$WorkspaceRoot = Split-Path $ProjectRoot -Parent
$HaloJar = Join-Path $WorkspaceRoot "halo主程序\halo-2.27.1.jar"
if (-not (Test-Path $HaloJar)) {
    $HaloJar = Get-ChildItem $WorkspaceRoot -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName "halo-2.27.1.jar" } |
        Where-Object { Test-Path $_ } |
        Select-Object -First 1
}
if (-not $HaloJar) {
    throw "Halo 2.27.1 JAR not found below $WorkspaceRoot"
}

$PluginJar = Get-ChildItem (Join-Path $ProjectRoot "build\libs") -Filter "plugin-halo-chat-*.jar" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $PluginJar) {
    throw "Plugin JAR not found. Run scripts/build.ps1 first."
}

if ([string]::IsNullOrWhiteSpace($WorkDir)) {
    $WorkDir = Join-Path $ProjectRoot ".dev\halo-workdir"
}
$connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($connection) {
    Write-Host "Stopping process on port $Port (pid $($connection.OwningProcess))"
    Stop-Process -Id $connection.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
}
# Reset after stopping Halo so the log/database files are not locked anymore.
if ($Reset -and (Test-Path $WorkDir)) {
    Write-Host "Resetting work directory: $WorkDir"
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        try {
            Remove-Item $WorkDir -Recurse -Force -ErrorAction Stop
            break
        } catch {
            Start-Sleep -Seconds 2
        }
    }
}
$PluginDir = Join-Path $WorkDir "plugins"
New-Item -ItemType Directory -Force -Path $PluginDir | Out-Null
$Log = Join-Path $ProjectRoot ".dev\halo-run.log"
New-Item -ItemType Directory -Force -Path (Split-Path $Log -Parent) | Out-Null

$workDirForJava = $WorkDir.Replace('\', '/')
$baseUrl = "http://127.0.0.1:$Port"
$credentials = "$AdminUsername`:$AdminPassword"

Write-Host "Starting Halo (workdir: $WorkDir) ..."
Start-Process -FilePath (Join-Path $env:JAVA_HOME "bin\java.exe") -ArgumentList @(
    "-jar", $HaloJar,
    "--server.port=$Port",
    "--halo.work-dir=$workDirForJava",
    "--halo.security.basic-auth.disabled=false",
    "--halo.external-url=$baseUrl"
) -RedirectStandardOutput $Log -RedirectStandardError (Join-Path $ProjectRoot ".dev\halo-run.err.log") -WindowStyle Hidden

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    try {
        $response = Invoke-WebRequest "$baseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        # keep waiting
    }
}
if (-not $ready) {
    throw "Halo did not become healthy. Check $Log"
}
Write-Host "Halo is healthy."

# First run only: initialize the super admin. On an initialized site this
# redirects to the console and is harmless.
$setupBody = "username=$AdminUsername&password=$AdminPassword&email=$AdminUsername@example.com&siteTitle=HaloChat&language=zh-CN&externalUrl=$baseUrl"
curl.exe -s -o NUL -X POST "$baseUrl/system/setup" -H "Content-Type: application/x-www-form-urlencoded" --data $setupBody
Start-Sleep -Seconds 3

$authHeader = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($credentials)) }
$pluginsResponse = Invoke-RestMethod -Uri "$baseUrl/apis/api.console.halo.run/v1alpha1/plugins?page=1&size=100" -Headers $authHeader -TimeoutSec 30
$installed = $pluginsResponse.items | Where-Object { $_.metadata.name -eq "halo-chat" } | Select-Object -First 1

if ($installed) {
    $relativePath = $installed.metadata.annotations.'plugin.halo.run/plugin-path'
    if (-not $relativePath) {
        $relativePath = "halo-chat.jar"
    }
    $targetJar = Join-Path $PluginDir $relativePath
    # Remove stale copies from earlier runs so Halo only sees one plugin JAR.
    Get-ChildItem $PluginDir -Filter "halo-chat*.jar" | Where-Object { $_.FullName -ne $targetJar } | Remove-Item -Force
    Copy-Item $PluginJar.FullName $targetJar -Force
    Write-Host "Reloading halo-chat from $relativePath ..."
    curl.exe -s -o NUL -u $credentials -X PUT "$baseUrl/apis/api.console.halo.run/v1alpha1/plugins/halo-chat/reload"
} else {
    Write-Host "Installing halo-chat ..."
    curl.exe -s -o NUL -u $credentials -F "file=@$($PluginJar.FullName);type=application/java-archive" "$baseUrl/apis/api.console.halo.run/v1alpha1/plugins/install"
    Start-Sleep -Seconds 3
    Write-Host "Enabling halo-chat ..."
    Invoke-RestMethod -Method Put -Uri "$baseUrl/apis/api.console.halo.run/v1alpha1/plugins/halo-chat/plugin-state" -Headers $authHeader -ContentType "application/json" -Body '{"enable":true,"async":false}' -TimeoutSec 60 | Out-Null
}

Start-Sleep -Seconds 8
$pluginsResponse = Invoke-RestMethod -Uri "$baseUrl/apis/api.console.halo.run/v1alpha1/plugins?page=1&size=100" -Headers $authHeader -TimeoutSec 30
$plugin = $pluginsResponse.items | Where-Object { $_.metadata.name -eq "halo-chat" } | Select-Object -First 1
Write-Host "halo-chat phase: $($plugin.status.phase)"
Write-Host "Console chat:     $baseUrl/console/chat"
Write-Host "Chat management:  $baseUrl/console/chat/admin"
Write-Host "Public chat page: $baseUrl/plugins/halo-chat/"
Write-Host "Admin:            $AdminUsername / $AdminPassword"
Write-Host "Log:              $Log"

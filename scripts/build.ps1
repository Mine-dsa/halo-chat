# Build the halo-chat plugin jar on Windows.
# Usage:  .\scripts\build.ps1
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\env.ps1"
Set-Location (Split-Path $PSScriptRoot -Parent)
& .\gradlew.bat clean build --console=plain
exit $LASTEXITCODE
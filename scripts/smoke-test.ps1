# Smoke test for a running halo-chat installation.
#
# Usage:
#   .\scripts\smoke-test.ps1
#   .\scripts\smoke-test.ps1 -Port 18090 -AdminUsername admin -AdminPassword Admin@12345
#
# Requires PowerShell 5.1+ / 7+, curl and a Halo instance where halo-chat is
# installed and enabled (scripts/dev-halo.ps1 can prepare one).
param(
    [int]$Port = 18090,
    [string]$AdminUsername = "admin",
    [string]$AdminPassword = "Admin@12345"
)

$ErrorActionPreference = "Continue"
$base = "http://127.0.0.1:$Port"
$publicApi = "$base/apis/api.chat.halo.run/v1alpha1"
$adminApi = "$base/apis/console.api.chat.halo.run/v1alpha1"
$credentials = "$AdminUsername`:$AdminPassword"
$adminHeaders = @{ Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($credentials)) }
$guestSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

$work = Join-Path (Split-Path $PSScriptRoot -Parent) ".dev\smoke"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$smallFile = Join-Path $work "smoke.txt"
[System.IO.File]::WriteAllText($smallFile, "halo-chat smoke test file")

$script:failures = 0
function Check([string]$name, [bool]$passed, [string]$detail = "") {
    if ($passed) {
        Write-Host "PASS  $name" -ForegroundColor Green
    } else {
        Write-Host "FAIL  $name  $detail" -ForegroundColor Red
        $script:failures++
    }
}

function AdminGet([string]$url) {
    return Invoke-RestMethod -Uri $url -Headers $adminHeaders -TimeoutSec 30
}

function AdminJson([string]$method, [string]$url, [string]$body) {
    return Invoke-RestMethod -Method $method -Uri $url -Headers $adminHeaders -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 30
}

function GuestJson([string]$method, [string]$url, [string]$body) {
    if ([string]::IsNullOrEmpty($body)) {
        return Invoke-RestMethod -Method $method -Uri $url -WebSession $guestSession -TimeoutSec 30
    }
    return Invoke-RestMethod -Method $method -Uri $url -WebSession $guestSession -ContentType "application/json; charset=utf-8" -Body $body -TimeoutSec 30
}

Write-Host "== health =="
$status = & curl.exe -s -o NUL -w "%{http_code}" "$base/actuator/health"
Check "Halo health endpoint" ($status -eq "200") "status=$status"

Write-Host "== public page =="
$page = [string](& curl.exe -s "$base/plugins/halo-chat/")
Check "public chat page served" ($page -match '<div id="app"></div>')
Check "public page references bundled assets" ($page -match "/plugins/halo-chat/assets/web/assets/")

Write-Host "== identity & channels =="
$me = AdminGet "$publicApi/me"
Check "admin /me resolves as USER" ($me.type -eq "USER" -and $me.canManage -eq $true)
$anonMe = Invoke-RestMethod -Uri "$publicApi/me" -TimeoutSec 30
Check "anonymous /me resolves as ANONYMOUS" ($anonMe.type -eq "ANONYMOUS")
$channels = AdminGet "$publicApi/channels"
$publicChannel = $channels.items | Where-Object { $_.name -eq "public" } | Select-Object -First 1
Check "built-in public channel exists" ($null -ne $publicChannel)
Check "public channel is pinned first" ($channels.items[0].name -eq "public")
Check "public channel is open to everyone" ($publicChannel.postPolicy -eq "EVERYONE" -and $publicChannel.visibility -eq "EVERYONE")

Write-Host "== guest session & message =="
$guest = GuestJson "Post" "$publicApi/guest-sessions" '{"nickname":"SmokeGuest"}'
Check "guest session created" ($null -ne $guest -and $guest.guestId)
$guestMe = GuestJson "Get" "$publicApi/me" $null
Check "guest identity resolved" ($guestMe.type -eq "GUEST" -and $guestMe.displayName -eq "SmokeGuest")
$guestMessage = GuestJson "Post" "$publicApi/channels/public/messages" '{"content":"Smoke test message"}'
Check "guest can send message" ($guestMessage.content -eq "Smoke test message")
$history = GuestJson "Get" "$publicApi/channels/public/messages?limit=20" $null
$found = $history.items | Where-Object { $_.name -eq $guestMessage.name }
Check "message appears in history" ($null -ne $found)

Write-Host "== admin API authorization =="
$overview = AdminGet "$adminApi/overview"
Check "admin overview available" ($overview.channelCount -ge 1)
$anonymousStatus = & curl.exe -s -o NUL -w "%{http_code}" "$adminApi/overview"
Check "anonymous cannot use admin API" ($anonymousStatus -ne 200) "status=$anonymousStatus"

Write-Host "== channel lifecycle =="
$channel = AdminJson "Post" "$adminApi/channels" '{"type":"GROUP","displayName":"Smoke Channel","visibility":"EVERYONE","postPolicy":"EVERYONE","members":[],"composerMode":"INHERIT"}'
Check "channel created" ($null -ne $channel -and $null -ne $channel.name)
if ($channel) {
    $asUser = AdminJson "Post" "$adminApi/channels/$($channel.name)/messages/as-user" '{"username":"admin","content":"Smoke on behalf"}'
    Check "admin can send as user" ($asUser.onBehalf -eq $true)
    $edited = AdminJson "Patch" "$adminApi/messages/$($asUser.name)" '{"content":"Smoke edited"}'
    Check "admin can edit message" ($edited.content -eq "Smoke edited")
    & curl.exe -s -o NUL -u $credentials -X DELETE "$adminApi/messages/$($asUser.name)"
    & curl.exe -s -o NUL -u $credentials -X DELETE "$adminApi/channels/$($channel.name)"
    $after = AdminGet "$adminApi/channels"
    $stillThere = $after.items | Where-Object { $_.name -eq $channel.name }
    Check "channel dissolved" ($null -eq $stillThere)
}

Write-Host "== files =="
$fileMessage = & curl.exe -s -u $credentials -F "file=@$smallFile;type=text/plain" -F "caption=smoke" "$publicApi/channels/public/files" | ConvertFrom-Json
Check "admin can upload file" ($null -ne $fileMessage -and $fileMessage.attachments.Count -eq 1)
if ($fileMessage -and $fileMessage.attachments.Count -eq 1) {
    $fileRef = $fileMessage.attachments[0].fileRef
    $downloadStatus = & curl.exe -s -o NUL -w "%{http_code}" -u $credentials "$publicApi/files/$fileRef"
    Check "file download works" ($downloadStatus -eq "200") "status=$downloadStatus"
    & curl.exe -s -o NUL -u $credentials -X DELETE "$adminApi/files/$fileRef"
    $files = AdminGet "$adminApi/files?page=1&size=100"
    $left = $files.items | Where-Object { $_.name -eq $fileRef }
    Check "file deleted" ($null -eq $left)
}

Write-Host "== settings =="
$settings = AdminGet "$adminApi/settings"
Check "plugin settings available" ($null -ne $settings.settings.files)
Check "official comment widget detected" ($settings.commentWidgetAvailable -eq $true)

Write-Host ""
if ($script:failures -eq 0) {
    Write-Host "ALL CHECKS PASSED" -ForegroundColor Green
    exit 0
}
Write-Host "$($script:failures) CHECK(S) FAILED" -ForegroundColor Red
exit 1

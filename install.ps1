param(
    [ValidateSet("Install", "Latest", "Status", "Uninstall")]
    [string]$Mode = "Install",
    [string]$Apk = "",
    [string]$Repo = $env:PORTAL_JARVIS_REPO
)

$ErrorActionPreference = "Stop"
$Package = "com.german.portaljarviswake"
$Jarvis = "com.portal.assistant"
$Asset = "portal-jarvis-wake-gen2.apk"
$Cache = Join-Path $PSScriptRoot ".platform-tools"

function Resolve-Adb {
    if ($env:ADB -and (Test-Path $env:ADB)) { return $env:ADB }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    New-Item -ItemType Directory -Force $Cache | Out-Null
    $zip = Join-Path $Cache "platform-tools.zip"
    $exe = Join-Path $Cache "platform-tools\adb.exe"
    if (!(Test-Path $exe)) {
        Write-Host "Downloading official Android platform-tools..."
        Invoke-WebRequest "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive $zip $Cache -Force
    }
    return $exe
}

function Wait-Portal([string]$Adb) {
    Write-Host "Waiting for Portal over USB-C (accept its debugging prompt)..."
    & $Adb start-server | Out-Null
    & $Adb wait-for-device
    if ((& $Adb get-state).Trim() -ne "device") { throw "Portal is offline or unauthorized." }
}

function Find-LocalApk {
    $items = Get-ChildItem -Path @(
        (Join-Path $PSScriptRoot "app\build\outputs\apk\release\*.apk"),
        (Join-Path $PSScriptRoot "app\build\outputs\apk\debug\*.apk"),
        (Join-Path $PSScriptRoot "*.apk")
    ) -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
    return $items | Select-Object -First 1 -ExpandProperty FullName
}

$Adb = Resolve-Adb
if ($Mode -eq "Status") {
    Wait-Portal $Adb
    & $Adb shell pm path $Package
    & $Adb shell pm path $Jarvis
    & $Adb shell dumpsys activity services "$Package/.WakeService"
    & $Adb logcat -d -t 80 -s PortalJarvisWake
    exit
}
if ($Mode -eq "Uninstall") {
    Wait-Portal $Adb
    & $Adb uninstall $Package
    exit
}
if ($Mode -eq "Latest") {
    if (!$Repo) { throw "Set PORTAL_JARVIS_REPO=OWNER/portal-jarvis-wake-gen2 or pass -Repo." }
    $Apk = Join-Path $Cache $Asset
    Invoke-WebRequest "https://github.com/$Repo/releases/latest/download/$Asset" -OutFile $Apk
} elseif (!$Apk) {
    $Apk = Find-LocalApk
    if (!$Apk) {
        if (!$Repo) { throw "No local APK. Build one or set PORTAL_JARVIS_REPO for release downloads." }
        $Apk = Join-Path $Cache $Asset
        Invoke-WebRequest "https://github.com/$Repo/releases/latest/download/$Asset" -OutFile $Apk
    }
}
if (!(Test-Path $Apk)) { throw "APK not found: $Apk" }

Wait-Portal $Adb
& $Adb install -r -d -g $Apk
& $Adb shell pm grant $Package android.permission.RECORD_AUDIO 2>$null
& $Adb shell appops set $Package SYSTEM_ALERT_WINDOW allow 2>$null
& $Adb shell dumpsys deviceidle whitelist "+$Package" 2>$null
$jarvisPath = & $Adb shell pm path $Jarvis 2>$null
& $Adb shell am start -n "$Package/.SettingsActivity" | Out-Null
& $Adb shell am start-foreground-service -n "$Package/.WakeService" 2>$null | Out-Null

Write-Host ""
Write-Host "Installation checklist"
Write-Host "  Helper installed: yes"
Write-Host "  Jarvis installed:  $([bool]$jarvisPath)"
Write-Host "  Microphone, overlay, and battery allowlist requested"
Write-Host "Enable the listener on the Portal and wait for the model download."
Write-Host "Diagnostics: adb logcat -s PortalJarvisWake"

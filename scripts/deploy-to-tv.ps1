# Installs the karaoke app on the Mi TV. It touches the TV, so only run it when nobody is watching.
# Usage: .\deploy-to-tv.ps1 -TvIp <your TV IP>
# The TV needs USB debugging AND MiTV ADB debugging on, then a reboot, for this to connect.
param([Parameter(Mandatory = $true)][string]$TvIp)

$adb = if ($env:ADB_PATH) { $env:ADB_PATH } else { "adb" }  # adb from PATH, or set ADB_PATH
$apk = Join-Path $PSScriptRoot "..\app\build\outputs\apk\release\app-release.apk"
$target = "${TvIp}:5555"

if (-not (Test-Path $apk)) {
    Write-Output "APK not found at $apk. Build it first."
    exit 1
}
& $adb connect $target
# -r replaces an earlier build, -g grants the USB storage permission up front.
& $adb -s $target install -r -g $apk
if ($LASTEXITCODE -ne 0) {
    Write-Output "FAILED: install did not complete."
    exit 1
}
Write-Output "Installed. Open Karaoke from the app library on the TV."
Write-Output "To remove it: .\remove-from-tv.ps1 -TvIp $TvIp"

# Removes the karaoke app from the Mi TV. Touches nothing else on the TV.
# Usage: .\remove-from-tv.ps1 -TvIp <your TV IP>
# The TV needs USB debugging AND MiTV ADB debugging on, then a reboot, for this to connect.
param([Parameter(Mandatory = $true)][string]$TvIp)

$adb = if ($env:ADB_PATH) { $env:ADB_PATH } else { "adb" }  # adb from PATH, or set ADB_PATH
$package = "com.raghav.karaoke"
$target = "${TvIp}:5555"

& $adb connect $target
$installed = & $adb -s $target shell pm list packages $package
if ($installed -notmatch [regex]::Escape($package)) {
    Write-Output "Karaoke app is not installed on $target. Nothing to do."
    exit 0
}

& $adb -s $target uninstall $package
$after = & $adb -s $target shell pm list packages $package
if ($after -match [regex]::Escape($package)) {
    Write-Output "FAILED: the app is still installed."
    exit 1
}
Write-Output "Removed $package from $target."
Write-Output "Now switch USB debugging and MiTV ADB debugging back off on the TV."

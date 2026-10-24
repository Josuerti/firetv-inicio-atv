# Restaura la Fire TV (192.168.100.13) al estado original de Amazon.
#   .\restaurar.ps1             -> quita Inicio ATV (vuelve la home de Amazon)
#   .\restaurar.ps1 -Todo       -> además reactiva las 18 apps de Amazon y las animaciones a 1x
param([switch]$Todo, [string]$Ip = "192.168.100.13")
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
& $adb connect "${Ip}:5555"

"Quitando Inicio ATV..."
# Antes de instalar no había ningún servicio de accesibilidad activo (ver backup\*\settings-secure.txt)
& $adb shell "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0"
& $adb shell "cmd package set-home-activity com.amazon.tv.launcher/.ui.HomeActivity_vNext" 2>$null
& $adb uninstall com.josue.atvhome
& $adb shell "input keyevent KEYCODE_HOME"

if ($Todo) {
    "Reactivando apps de Amazon y animaciones..."
    & $adb shell "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings delete global animator_duration_scale"
    $pk = @(
     'com.amazon.tv.acr','com.amazon.device.telemetry.emitter','com.amazon.dp.logger','com.amazon.perfcollection',
     'com.amazon.wirelessmetrics.service','com.amazon.neo.minerva.logcat','com.amazon.minitv.android.app',
     'com.amazon.shoptv.client','com.amazon.shoptv.firetv.client','com.amazon.gamehub','com.amazon.bueller.photos',
     'com.amazon.bueller.music','com.amazon.sneakpeek','com.amazon.ods.kindleconnect','com.amazon.tv.earlyaccess',
     'com.amazon.smarthomemapviewapp','com.amazon.storm.lightning.tutorial','com.amazon.tmm.tutorial'
    )
    foreach ($p in $pk) { & $adb shell "pm enable $p" }
    # Ajustes de privacidad/autoplay cambiados el 2026-10-24 (valores originales: todos a 1)
    & $adb shell "settings put global com.amazon.tv.settings.featured_item_video_enabled 1; settings put global com.amazon.tv.settings.featured_item_video_volume 1.0"
    & $adb shell "settings put secure advertisingIdApp:interestBasedAds:value 1; settings put secure settingsApp:appUsageData:value 1; settings put secure settingsApp:deviceUsageData:value 1; settings put secure usage_metrics_marketing_enabled 1"
}
"Hecho."

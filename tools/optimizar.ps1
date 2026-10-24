# Optimiza una Fire TV Stick 4K (Fire OS 8) por ADB: animaciones, apps de Amazon sobrantes, privacidad y caché.
# Todo es reversible con .\restaurar.ps1 -Todo
#   .\optimizar.ps1 -Ip 192.168.100.13
param([string]$Ip = "192.168.100.13")
$adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
& $adb connect "${Ip}:5555"

# Respaldo antes de tocar nada
$b = Join-Path $PSScriptRoot "..\backup\$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force $b | Out-Null
& $adb shell "pm list packages -f -u" > "$b\packages-all.txt"
& $adb shell "pm list packages -d" > "$b\packages-disabled.txt"
foreach ($ns in 'global', 'secure', 'system') { & $adb shell "settings list $ns" > "$b\settings-$ns.txt" }
"Respaldo en $b"

"Animaciones a 0.5x..."
& $adb shell "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5"

"Desactivando telemetría, publicidad y apps que sobran (no se borran)..."
$pk = @(
    'com.amazon.tv.acr',                          # reconocimiento de lo que ves (ACR)
    'com.amazon.device.telemetry.emitter',        # telemetría
    'com.amazon.dp.logger', 'com.amazon.perfcollection', 'com.amazon.wirelessmetrics.service', 'com.amazon.neo.minerva.logcat',
    'com.amazon.minitv.android.app',              # MiniTV
    'com.amazon.shoptv.client', 'com.amazon.shoptv.firetv.client',   # Amazon Shop
    'com.amazon.sneakpeek',                       # vídeos de prueba automáticos
    'com.amazon.gamehub', 'com.amazon.bueller.photos', 'com.amazon.bueller.music', 'com.amazon.ods.kindleconnect',
    'com.amazon.tv.earlyaccess', 'com.amazon.smarthomemapviewapp',
    'com.amazon.storm.lightning.tutorial', 'com.amazon.tmm.tutorial'
)
foreach ($p in $pk) { & $adb shell "pm disable-user --user 0 $p" }

"Privacidad y autoplay..."
& $adb shell "settings put global com.amazon.tv.settings.featured_item_video_enabled 0; settings put global com.amazon.tv.settings.featured_item_video_volume 0.0"
& $adb shell "settings put secure advertisingIdApp:interestBasedAds:value 0; settings put secure settingsApp:appUsageData:value 0; settings put secure settingsApp:deviceUsageData:value 0; settings put secure usage_metrics_marketing_enabled 0"

"Liberando caché..."
& $adb shell "pm trim-caches 8G"
& $adb shell "df -h /data | tail -1"
"Hecho. Reinicia la TV para notar todo el efecto."

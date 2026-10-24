# Instala JDK 17 y el Android SDK minimo (android-30 + build-tools 34) para compilar el launcher
$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
winget install --id Microsoft.OpenJDK.17 -e --accept-source-agreements --accept-package-agreements --disable-interactivity
$jdk = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" | Select-Object -First 1
$env:JAVA_HOME = $jdk.FullName
"JAVA_HOME=$env:JAVA_HOME"

New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
$zip = "$env:TEMP\cmdline-tools.zip"
Invoke-WebRequest "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $zip -UseBasicParsing
Expand-Archive $zip "$sdk\cmdline-tools\tmp" -Force
if (Test-Path "$sdk\cmdline-tools\latest") { Remove-Item -Recurse -Force "$sdk\cmdline-tools\latest" }
Move-Item "$sdk\cmdline-tools\tmp\cmdline-tools" "$sdk\cmdline-tools\latest"
Remove-Item -Recurse -Force "$sdk\cmdline-tools\tmp"

$sm = "$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$yes = ("y`n" * 20)
$yes | & $sm --sdk_root=$sdk --licenses | Out-Null
& $sm --sdk_root=$sdk "platforms;android-30" "build-tools;34.0.0"
"SDK_DONE"
Get-ChildItem "$sdk\build-tools\34.0.0" -Filter "*.bat" | Select-Object -ExpandProperty Name

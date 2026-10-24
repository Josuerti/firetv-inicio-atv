# Compila Inicio ATV sin Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$app = "$root\launcher"
$build = "$root\build"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-30\android.jar"
$jdk = (Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

function Run($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "Fallo: $exe $args" }
}

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force "$build\gen", "$build\classes", "$build\dex" | Out-Null

"[1/6] recursos"
Run "$bt\aapt2.exe" compile --dir "$app\res" -o "$build\res.zip"
Run "$bt\aapt2.exe" link -o "$build\base.apk" -I $jar --manifest "$app\AndroidManifest.xml" --java "$build\gen" "$build\res.zip"

"[2/6] javac"
$srcs = @(Get-ChildItem "$app\src", "$build\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run "$jdk\bin\javac.exe" -encoding UTF-8 --release 8 "-Xlint:-options" -classpath $jar -d "$build\classes" @srcs

"[3/6] d8"
$classes = @(Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object FullName)
Run "$bt\d8.bat" --release --min-api 25 --lib $jar --output "$build\dex" @classes

"[4/6] empaquetar dex"
Copy-Item "$build\base.apk" "$build\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\dex\classes.dex", "classes.dex") | Out-Null
$zip.Dispose()

"[5/6] zipalign"
Run "$bt\zipalign.exe" -p -f 4 "$build\unsigned.apk" "$build\aligned.apk"

"[6/6] firmar"
$ks = "$root\keystore\atvhome.jks"
$pwFile = "$root\keystore\password.txt"
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force "$root\keystore" | Out-Null
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content -Path $pwFile -Value $pw -NoNewline -Encoding ascii
    Run "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -alias atvhome -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $pw -keypass $pw -dname "CN=Inicio ATV"
}
$pw = Get-Content $pwFile -Raw
Run "$bt\apksigner.bat" sign --ks $ks --ks-key-alias atvhome --ks-pass "pass:$pw" --key-pass "pass:$pw" --out "$root\InicioATV.apk" "$build\aligned.apk"
Run "$bt\apksigner.bat" verify --print-certs -v "$root\InicioATV.apk" | Select-String "Verified using|Signer #1 certificate SHA-256"
"APK listo: $root\InicioATV.apk ($([math]::Round((Get-Item "$root\InicioATV.apk").Length/1KB)) KB)"

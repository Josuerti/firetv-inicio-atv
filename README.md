# Inicio ATV — Fire TV con estilo Apple TV

Pantalla de inicio propia, ligera y sin anuncios, con estilo Apple TV, para la **Fire TV Stick 4K (2.ª gen.)**. Incluye scripts para optimizar Fire OS por ADB y para deshacerlo todo.

![Inicio](docs/inicio.png)
![Apps](docs/apps.png)

> Probado en una Fire TV Stick 4K 2.ª gen. (AFTKM), Fire OS 8.1.8.5, 1080p. El código está escrito en Java solo con el framework de Android (sin Gradle ni AndroidX) y el APK pesa unos 45 KB.

## Qué hace

### 1. Optimización de Fire OS (`tools/optimizar.ps1`)
- Pone las **animaciones a 0.5x**: los menús van el doble de rápido.
- **Desactiva 18 apps de Amazon**: telemetría, ACR (reconoce lo que ves), Amazon Shop, MiniTV, vídeos de prueba automáticos, Game Hub, Photos, Music, Kindle, tutoriales… Se desactivan con `pm disable-user`, **no se borran**.
- **Privacidad**: apaga los anuncios personalizados, el envío de datos de uso y los vídeos con sonido que se reproducen solos en la pantalla de Amazon.
- Libera la caché.
- No toca las actualizaciones del sistema, el launcher de Amazon ni las apps protegidas.

### 2. Launcher "Inicio ATV" (`launcher/`)
- Reloj grande y fino, saludo y fecha en español.
- Botones de cristal: Wi-Fi (muestra la red), *Inicio Amazon* y Ajustes.
- **Dock** de hasta 5 apps. El nombre de la app con foco aparece en grande y el fondo negro se tiñe del color de su logo.
- **Cuadrícula** con el resto de apps: la que tiene el foco crece, brilla y muestra su nombre.
- Oculta las herramientas internas de Fire OS que salen con icono genérico.
- Consumo bajo: reduce las portadas al tamaño del recuadro y libera la memoria cuando abres otra app (unos 45 MB de RAM).

| Mando | Acción |
|---|---|
| OK | Abrir la app |
| **Menú ☰** o mantener OK | Añadir o quitar del dock, mover, ocultar, ver información de la app |
| Atrás | Volver al dock |
| Inicio | Siempre vuelve a Inicio ATV |

### Cómo sustituye a la pantalla de Amazon
Fire OS 8 no deja cambiar la pantalla de inicio por defecto. Inicio ATV trae un **servicio de accesibilidad** (`HomeService`) que detecta cuándo aparece la home de Amazon (al pulsar Inicio, al arrancar o después de elegir perfil) y abre Inicio ATV encima. Solo mira qué app está en primer plano. Puede verse un destello de medio segundo de la pantalla de Amazon. El launcher de Amazon sigue instalado por debajo, porque no se puede quitar sin riesgo.

## Instalación paso a paso

**En la Fire TV**
1. Ve a *Configuración → Mi Fire TV → Acerca de* y pulsa 7 veces sobre el nombre del dispositivo.
2. En *Mi Fire TV → Opciones para desarrolladores*, activa **Depuración ADB**.
3. Apunta la IP que sale en *Acerca de → Red*.

**En el PC (Windows)**
```powershell
winget install Google.PlatformTools                      # ADB
powershell -ExecutionPolicy Bypass -File tools\setup-sdk.ps1    # JDK 17 + Android SDK (~2 GB, solo para compilar)
powershell -ExecutionPolicy Bypass -File tools\optimizar.ps1 -Ip 192.168.x.x
powershell -ExecutionPolicy Bypass -File tools\build.ps1        # genera InicioATV.apk
adb connect 192.168.x.x:5555
adb install -r InicioATV.apk
adb shell settings put secure enabled_accessibility_services com.josue.atvhome/com.josue.atvhome.HomeService
adb shell settings put secure accessibility_enabled 1
adb shell pm grant com.josue.atvhome android.permission.ACCESS_FINE_LOCATION   # opcional: muestra el nombre de la red Wi-Fi
```
La primera vez que conectes, acepta el aviso **"Permitir siempre"** en la TV.

`build.ps1` crea una llave de firma en `keystore/` (no se sube al repo). **Guárdala**: sin ella no podrás actualizar la app sin desinstalarla antes.

**Al terminar:** desactiva *Depuración ADB* en la TV. Mientras esté activada, cualquiera en tu Wi-Fi puede intentar conectarse.

## Deshacer
```powershell
powershell -ExecutionPolicy Bypass -File tools\restaurar.ps1         # quita Inicio ATV
powershell -ExecutionPolicy Bypass -File tools\restaurar.ps1 -Todo   # y además deshace la optimización
```
Si una actualización de Fire OS apaga el servicio: *Ajustes → Accesibilidad → Inicio ATV → Activar*.

## Estructura
```
launcher/            código del launcher (AndroidManifest, res, src)
tools/setup-sdk.ps1  instala JDK 17 y el Android SDK mínimo
tools/build.ps1      compila: aapt2 → javac → d8 → zipalign → apksigner
tools/make-art.ps1   genera el icono y el banner
tools/optimizar.ps1  optimiza Fire OS (con respaldo previo en backup/)
tools/restaurar.ps1  deshace los cambios
docs/                capturas
```
No se suben al repo: `backup/` (datos de la TV), `keystore/` (llave de firma), `build/` ni los APK.

## Personalizar
- **Apps del dock por defecto:** `DEFAULT_DOCK` en `HomeActivity.java`. Desde la TV se cambian con el botón Menú ☰.
- **Apps ocultas siempre:** `EXCLUDE` en `AppEntry.java`.
- **Tamaño de los recuadros y columnas:** `tileW`, `tileH` y `COLS` en `HomeActivity.java`.

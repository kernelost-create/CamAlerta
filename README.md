# CamAlerta — avisos de cámaras en rutas de Argentina

## Cómo compilarla e instalarla
1. Instalá **Android Studio** (gratis): https://developer.android.com/studio
2. Descomprimí el zip y en Android Studio: **File > Open** → elegí la carpeta `CamAlerta`.
   Esperá a que termine el "Gradle sync" (la primera vez baja cosas, tarda unos minutos).
   Si te ofrece actualizar Gradle o el plugin de Android, podés aceptar.
3. En el celular: activá **Opciones de desarrollador** (tocá 7 veces "Número de compilación"
   en Ajustes > Acerca del teléfono) y prendé **Depuración USB**.
4. Conectá el celu por USB y tocá el botón ▶ **Run**.
   Para tener el APK suelto: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.

## Cómo se usa
- La primera vez baja solas todas las cámaras de Argentina cargadas en OpenStreetMap.
- **Iniciar vigilancia** → queda andando en segundo plano (aunque uses Waze/Spotify o apagues la pantalla).
- Avisa dos veces: a la distancia que elijas (200–1000 m) y a 150 m. Si sabe la máxima
  y vas pasado, te lo dice.
- Solo avisa si la cámara está **adelante** tuyo (no la que ya pasaste o una de costado).
- **Marcar cámara aquí** (también desde la notificación) guarda una cámara nueva donde estás.

## Archivos
- `CameraWatchService.kt` → GPS + lógica de avisos (voz, pitido, notificación)
- `CameraRepository.kt` → descarga de OpenStreetMap y cámaras propias
- `MainActivity.kt` → pantalla principal
- `Geo.kt` → cálculos de distancia y rumbo

## Tip
Si encontrás cámaras que faltan, cargarlas en https://www.openstreetmap.org
(etiqueta `highway=speed_camera`) las suma para todos los que usen la app.

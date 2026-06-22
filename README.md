# MIDI Drums (Android · Kotlin · Jetpack Compose)

App Android que carga un archivo `.mid`, lo reproduce en loop, y cada nota
de batería suena con un sample WAV/MP3 que tú asignas — el mismo flujo de
trabajo que cargar un groove MIDI en EZdrummer, pero como app standalone
con tus propias muestras.

## Cómo funciona

1. **Cargar MIDI**: seleccionas un archivo `.mid` desde tu almacenamiento.
   La app parsea los eventos Note On/Off y el tempo con un parser SMF
   propio (no depende de `javax.sound.midi`, que no existe en Android).
2. **Asignar samples**: en la lista de piezas (bombo, caja, hi-hats, toms,
   crash, ride) cargas un archivo de audio para cada una.
3. **Mapeo de notas**: por defecto se usa el mapeo General MIDI / Yamaha
   estándar (bombo=36, caja=38, hi-hat cerrado=42, etc.). Si tu archivo
   MIDI viene de un mapeo distinto, puedes reasignar el número de nota de
   cada pieza desde "Editar notas MIDI".
4. **Reproducir**: el motor de audio (`SoundPool`) dispara cada sample en
   el momento exacto indicado por el MIDI, en loop continuo, respetando
   los cambios de tempo del archivo.

## Sobre el mapeo MIDI

El mapeo específico de **EZdrummer 3** (Toontrack) es propiedad de
Toontrack y varía según el kit/EZX cargado — no es un estándar abierto.
Esta app usa como base el estándar General MIDI / Yamaha extendido, que
cubre las piezas principales de cualquier kit de batería estándar. Si
necesitas que coincida exactamente con un mapeo específico de EZD3, usa
la pantalla de reasignación de notas con los valores de tu propia
documentación de Toontrack.

## Estructura del proyecto

```
MidiDrums/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/mididrums/
│   │   │   ├── MainActivity.kt     -> UI Compose completa
│   │   │   ├── MidiParser.kt       -> Parser SMF puro en Kotlin
│   │   │   ├── DrumEngine.kt       -> Motor SoundPool + reproducción en loop
│   │   │   └── SamplePrefs.kt      -> Persistencia de mapeo nota->sample
│   │   └── res/
├── .github/workflows/build.yml     -> Compilación automática vía GitHub Actions
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Compilar vía GitHub Actions

1. Sube el **contenido** de esta carpeta directo a la raíz de tu repositorio
   (no la carpeta `MidiDrums/` como subcarpeta — debe quedar `app/`,
   `.github/`, `build.gradle.kts`, etc. directamente en la raíz).
2. Ve a la pestaña "Actions" de tu repo en GitHub.
3. El workflow "Build APK" corre automáticamente con cada push a `main`.
4. Al terminar, descarga el `.apk` desde la sección "Artifacts" del run.

## Limitaciones actuales

- Un archivo MIDI a la vez (no hay librería/lista de varios MIDIs guardados).
- Loop simple del archivo completo, sin selección de rango/compases.
- Sin step sequencer todavía (solo reproducción de archivos MIDI externos).
- Sin entrada MIDI en vivo por USB/Bluetooth (solo reproducción de archivos).
- `SoundPool` soporta hasta 16 sonidos simultáneos, suficiente para
  patrones de batería normales, pero no para densidades extremas tipo
  blast-beats con muchísima superposición.

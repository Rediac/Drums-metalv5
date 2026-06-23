package com.example.mididrums

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mididrums.model.MidiLibraryNode
import com.example.mididrums.model.BundledMidiLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

private val AccentBlue = Color(0xFF4FC3F7)
private val TextWhite = Color(0xFFFFFFFF)
private val TextWhiteSoft = Color(0xFFB0BEC5)
private val BgDark = Color(0xFF12141A)

private val AppColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.Black,
    background = BgDark,
    onBackground = TextWhite,
    surface = BgDark,
    onSurface = TextWhite
)

private const val SLOT_COUNT = 3

data class MidiSlot(
    val name: String? = null,
    val parsed: ParsedMidi? = null,
    val speedFactor: Float = 1f,
    val offsetMs: Int = 0
)

class MainActivity : ComponentActivity() {

    private lateinit var drumEngine: DrumEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        drumEngine = DrumEngine(this)
        drumEngine.initialize()

        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    MidiDrumsScreen(engine = drumEngine)
                }
            }
        }
    }

    override fun onDestroy() {
        drumEngine.release()
        super.onDestroy()
    }
}

@Composable
fun MidiDrumsScreen(engine: DrumEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var pieces by remember { mutableStateOf(SamplePrefs.loadPieces(context)) }
    var slots by remember { mutableStateOf(List(SLOT_COUNT) { MidiSlot() }) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showMappingScreen by remember { mutableStateOf(false) }
    var showLibraryScreen by remember { mutableStateOf(false) }
    var showMixerScreen by remember { mutableStateOf(false) }
    var showVisualizerScreen by remember { mutableStateOf(false) }
    var activeNote by remember { mutableStateOf<Int?>(null) }
    var activeSlotIndex by remember { mutableStateOf<Int?>(null) }
    var pendingLibraryFile by remember { mutableStateOf<MidiLibraryNode.File?>(null) }
    var masterVolume by remember { mutableStateOf(1f) }

    // Notas editadas por slot (índice -> lista de DrumHit)
    var editedHits by remember { mutableStateOf<Map<Int, List<DrumHit>>>(emptyMap()) }

    LaunchedEffect(pieces) {
        engine.loadSamples(pieces)
    }

    engine.onProgress = { p -> progress = p }
    engine.onNoteTriggered = { note -> activeNote = note }
    engine.onSlotChanged = { index -> activeSlotIndex = index }

    fun buildChainConfigs(): List<ChainSlotConfig> =
        slots.mapIndexed { index, slot ->
            val customHits = editedHits[index]?.map {
                DrumHit(it.timeMicros, it.note, it.velocity, 9)
            }
            ChainSlotConfig(
                hits = customHits ?: (slot.parsed?.hits ?: emptyList()),
                durationMicros = slot.parsed?.durationMicros ?: 0L,
                speedFactor = slot.speedFactor,
                offsetMs = slot.offsetMs
            )
        }

    fun restartPlaybackIfNeeded() {
        if (isPlaying) {
            engine.playChain(buildChainConfigs())
        }
    }

    fun assignMidiToSlot(slotIndex: Int, displayName: String, result: ParsedMidi) {
        val newSlots = slots.toMutableList()
        newSlots[slotIndex] = MidiSlot(
            name = displayName,
            parsed = result,
            speedFactor = 1f,
            offsetMs = 0
        )
        slots = newSlots
        statusMessage = "${result.hits.size} golpes detectados"
        restartPlaybackIfNeeded()
    }

    fun pickMidiForSlot(slotIndex: Int, uri: Uri?) {
        if (uri == null) return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
        }

        coroutineScope.launch {
            statusMessage = "Cargando MIDI..."
            var errorDetail: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        MidiParser.parse(stream)
                    }
                } catch (e: Exception) {
                    errorDetail = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
            }
            if (result != null) {
                assignMidiToSlot(slotIndex, uri.lastPathSegment ?: "archivo.mid", result)
            } else {
                statusMessage = "No se pudo leer el MIDI" +
                    if (errorDetail != null) " ($errorDetail)" else ""
            }
        }
    }

    fun pickBundledMidiForSlot(slotIndex: Int, node: MidiLibraryNode.File) {
        coroutineScope.launch {
            statusMessage = "Cargando ${node.name}..."
            var errorDetail: String? = null
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = BundledMidiLibrary.openMidiStream(context, node.path)
                    if (stream != null) {
                        stream.use { MidiParser.parse(it) }
                    } else {
                        errorDetail = "No se encontró en el ZIP"
                        null
                    }
                } catch (e: Exception) {
                    errorDetail = "${e.javaClass.simpleName}: ${e.message}"
                    null
                }
            }
            if (result != null) {
                assignMidiToSlot(slotIndex, node.name, result)
            } else {
                statusMessage = "No se pudo leer ${node.name}" +
                    if (errorDetail != null) " ($errorDetail)" else ""
            }
        }
    }

    if (showMappingScreen) {
        MappingScreen(
            pieces = pieces,
            onPiecesChanged = { updated -> pieces = updated },
            onBack = { showMappingScreen = false }
        )
        return
    }

    if (showVisualizerScreen) {
        MidiVisualizerScreen(
            slots = slots,
            activeNote = activeNote,
            activeSlotIndex = activeSlotIndex,
            progress = progress,
            pieces = pieces,
            editedHits = editedHits,
            onNotesChanged = { slotIndex, notes ->
                editedHits = editedHits + (slotIndex to notes.map {
                    DrumHit(it.timeMicros, it.note, it.velocity, 9)
                })
            },
            onBack = { showVisualizerScreen = false }
        )
        return
    }

    if (showMixerScreen) {
        MixerScreen(
            pieces = pieces,
            masterVolume = masterVolume,
            onMasterVolumeChanged = { masterVolume = it },
            onVolumeChanged = { pieceId, newVolume ->
                SamplePrefs.saveVolume(context, pieceId, newVolume)
                pieces = SamplePrefs.loadPieces(context)
                engine.loadSamples(pieces)
            },
            onBack = { showMixerScreen = false }
        )
        return
    }

    if (showLibraryScreen) {
        LibraryScreen(
            onBack = { showLibraryScreen = false },
            onFileSelected = { file ->
                pendingLibraryFile = file
                showLibraryScreen = false
            }
        )
        return
    }

    val fileForDialog = pendingLibraryFile
    if (fileForDialog != null) {
        AlertDialog(
            onDismissRequest = { pendingLibraryFile = null },
            containerColor = BgDark,
            title = { Text("Asignar a slot", color = TextWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        fileForDialog.name,
                        color = TextWhiteSoft,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "¿En qué slot quieres cargar este MIDI?",
                        color = TextWhite,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0 until SLOT_COUNT) {
                        TextButton(onClick = {
                            pickBundledMidiForSlot(i, fileForDialog)
                            pendingLibraryFile = null
                        }) {
                            Text("Slot ${i + 1}", color = AccentBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLibraryFile = null }) {
                    Text("Cancelar", color = TextWhiteSoft)
                }
            }
        )
    }

    val anySlotLoaded = slots.any { it.parsed != null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            "MIDI Drums",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextWhite
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Carga hasta 3 archivos .mid: se reproducen en cadena, uno tras otro, en loop.",
            style = MaterialTheme.typography.bodySmall,
            color = TextWhiteSoft
        )

        Spacer(Modifier.height(12.dp))

        Button(
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White.copy(alpha = 0.12f),
                contentColor = AccentBlue
            ),
            onClick = { showLibraryScreen = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Explorar librería de MIDIs incluidos", fontWeight = FontWeight.Bold)
        }

        if (statusMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(statusMessage!!, color = TextWhiteSoft, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(20.dp))

        DrumKitVisual(
            activeNote = activeNote,
            modifier = Modifier.fillMaxWidth()
        )

        // ─── Piano Roll en pantalla principal ───
        val firstLoadedSlot = slots.firstOrNull { it.parsed != null }
        val firstLoadedIndex = slots.indexOfFirst { it.parsed != null }
        if (firstLoadedSlot?.parsed != null) {
            Spacer(Modifier.height(12.dp))
            PianoRollView(
                hits = firstLoadedSlot.parsed.hits,
                durationMicros = firstLoadedSlot.parsed.durationMicros,
                progress = progress,
                pieces = pieces,
                onNotesChanged = { notes ->
                    editedHits = editedHits + (firstLoadedIndex to notes.map {
                        DrumHit(it.timeMicros, it.note, it.velocity, 9)
                    })
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.Black),
                enabled = anySlotLoaded,
                onClick = {
                    engine.playChain(buildChainConfigs())
                    isPlaying = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Reproducir cadena", fontWeight = FontWeight.Bold)
            }
            Button(
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.15f),
                    contentColor = TextWhite
                ),
                enabled = isPlaying,
                onClick = {
                    engine.stop()
                    isPlaying = false
                    progress = 0f
                    activeSlotIndex = null
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Detener", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = AccentBlue,
            trackColor = Color.White.copy(alpha = 0.15f)
        )

        Spacer(Modifier.height(28.dp))

        Text("Cadena de MIDIs", style = MaterialTheme.typography.titleMedium, color = TextWhite)
        Spacer(Modifier.height(4.dp))
        Text(
            "El orden de reproducción es Slot 1 → Slot 2 → Slot 3 → Slot 1 (loop). " +
                "Usa el offset para adelantar o atrasar el inicio de cada slot y " +
                "conectar suavemente con el anterior.",
            style = MaterialTheme.typography.bodySmall,
            color = TextWhiteSoft
        )

        Spacer(Modifier.height(12.dp))

        slots.forEachIndexed { index, slot ->
            SlotEditor(
                index = index,
                slot = slot,
                isActive = activeSlotIndex == index,
                onLoadRequested = { uri -> pickMidiForSlot(index, uri) },
                onSpeedChanged = { newSpeed ->
                    val newSlots = slots.toMutableList()
                    newSlots[index] = slot.copy(speedFactor = newSpeed)
                    slots = newSlots
                },
                onSpeedChangeFinished = { restartPlaybackIfNeeded() },
                onOffsetChanged = { newOffset ->
                    val newSlots = slots.toMutableList()
                    newSlots[index] = slot.copy(offsetMs = newOffset)
                    slots = newSlots
                },
                onOffsetChangeFinished = { restartPlaybackIfNeeded() }
            )
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Piezas y samples",
                style = MaterialTheme.typography.titleMedium,
                color = TextWhite
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showVisualizerScreen = true }) {
                    Text("🎹 Visor", color = AccentBlue)
                }
                TextButton(onClick = { showMixerScreen = true }) {
                    Text("🎛️ Mixer", color = AccentBlue)
                }
                TextButton(onClick = { showMappingScreen = true }) {
                    Text("Editar notas MIDI", color = AccentBlue)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        pieces.filter { !it.id.startsWith("custom") }.forEach { piece ->
            PieceRow(
                piece = piece,
                onSampleSelected = { uri ->
                    SamplePrefs.saveSampleUri(context, piece.id, uri?.toString())
                    pieces = SamplePrefs.loadPieces(context)
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        // ─── Slots Personalizados ───
        Text("Slots personalizados", style = MaterialTheme.typography.titleMedium, color = TextWhite)
        Spacer(Modifier.height(4.dp))
        Text("Cargá samples y asignales una nota MIDI", style = MaterialTheme.typography.bodySmall, color = TextWhiteSoft)
        Spacer(Modifier.height(8.dp))

        val customPieces = pieces.filter { it.id.startsWith("custom") }
        customPieces.forEach { piece ->
            CustomSlotRow(
                piece = piece,
                onSampleSelected = { uri ->
                    SamplePrefs.saveSampleUri(context, piece.id, uri?.toString())
                    pieces = SamplePrefs.loadPieces(context)
                },
                onNoteChanged = { newNote ->
                    SamplePrefs.saveNote(context, piece.id, newNote)
                    pieces = SamplePrefs.loadPieces(context)
                    engine.loadSamples(pieces)
                }
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SlotEditor(
    index: Int,
    slot: MidiSlot,
    isActive: Boolean,
    onLoadRequested: (Uri?) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onSpeedChangeFinished: () -> Unit,
    onOffsetChanged: (Int) -> Unit,
    onOffsetChangeFinished: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> onLoadRequested(uri) }

    val hasMidi = slot.parsed != null
    val displayedBpm = slot.parsed?.let { it.originalBpm * slot.speedFactor }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isActive) AccentBlue.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Slot ${index + 1}" + if (isActive) " ▶" else "",
                color = if (isActive) AccentBlue else TextWhite,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = { picker.launch(arrayOf("audio/midi", "audio/mid", "*/*")) }) {
                Text(if (hasMidi) "Cambiar archivo" else "Cargar archivo", color = AccentBlue)
            }
        }

        Text(
            slot.name ?: "vacío",
            color = TextWhiteSoft,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )

        if (hasMidi) {
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Tempo", color = TextWhiteSoft, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (displayedBpm != null) "${displayedBpm.toInt()} BPM" else "—",
                    color = AccentBlue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = slot.speedFactor,
                valueRange = 0.5f..2f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentBlue,
                    activeTrackColor = AccentBlue,
                    inactiveTrackColor = TextWhiteSoft.copy(alpha = 0.3f)
                ),
                onValueChange = onSpeedChanged,
                onValueChangeFinished = onSpeedChangeFinished
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Offset de inicio", color = TextWhiteSoft, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${slot.offsetMs} ms",
                    color = AccentBlue,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = slot.offsetMs.toFloat(),
                valueRange = -1000f..1000f,
                colors = SliderDefaults.colors(
                    thumbColor = AccentBlue,
                    activeTrackColor = AccentBlue,
                    inactiveTrackColor = TextWhiteSoft.copy(alpha = 0.3f)
                ),
                onValueChange = { onOffsetChanged(it.toInt()) },
                onValueChangeFinished = onOffsetChangeFinished
            )
            Text(
                "Negativo: el slot entra antes/se solapa con el anterior. " +
                    "Positivo: recorta silencio inicial, empieza más tarde.",
                color = TextWhiteSoft,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PieceRow(piece: DrumPiece, onSampleSelected: (Uri?) -> Unit) {
    val samplePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onSampleSelected(uri)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(piece.label, color = TextWhite, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Nota MIDI ${piece.note}" + if (piece.sampleUri != null) " · sample cargado" else " · sin sample",
                color = TextWhiteSoft,
                style = MaterialTheme.typography.bodySmall
            )
        }
        TextButton(
            onClick = { samplePickerLauncher.launch(arrayOf("audio/*")) }
        ) {
            Text(if (piece.sampleUri != null) "Cambiar" else "Cargar", color = AccentBlue)
        }
    }
}

@Composable
private fun LibraryScreen(
    onBack: () -> Unit,
    onFileSelected: (MidiLibraryNode.File) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rootNodes = remember { BundledMidiLibrary.listRoot(context) }

    var stack by remember { mutableStateOf(listOf("Librería" to rootNodes)) }
    val currentTitle = stack.last().first
    val currentNodes = stack.last().second

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                if (stack.size > 1) {
                    stack = stack.dropLast(1)
                } else {
                    onBack()
                }
            }) {
                Text("← Volver", color = AccentBlue)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            currentTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextWhite
        )

        Spacer(Modifier.height(16.dp))

        if (currentNodes.isEmpty()) {
            var debugMsg = "No se encontraron MIDIs."
            try {
                val zipStream = context.assets.open("midi/EZDrummer1.zip")
                val bytes = zipStream.readBytes()
                zipStream.close()
                debugMsg += "\nZIP abierto: ${bytes.size} bytes"
                
                val zip = ZipInputStream(bytes.inputStream())
                var count = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    count++
                    entry = zip.nextEntry
                }
                zip.close()
                debugMsg += "\nEntradas en ZIP: $count"
            } catch (e: Exception) {
                debugMsg += "\nError: ${e.javaClass.simpleName}: ${e.message}"
            }
            
            Text(
                debugMsg,
                color = TextWhiteSoft,
                style = MaterialTheme.typography.bodySmall
            )
        }

        LazyColumn {
            val nodesSnapshot = currentNodes
            items(nodesSnapshot.size) { index ->
                val node = nodesSnapshot[index]
                when (node) {
                    is MidiLibraryNode.Folder -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            TextButton(
                                onClick = {
                                    stack = stack + (node.name to node.children)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "📁 ${node.name}",
                                    color = TextWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    is MidiLibraryNode.File -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    node.name,
                                    color = TextWhite,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { onFileSelected(node) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentBlue,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text("Usar", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── VISOR MIDI ─────────────────────────────────────────────────────

@Composable
private fun MidiVisualizerScreen(
    slots: List<MidiSlot>,
    activeNote: Int?,
    activeSlotIndex: Int?,
    progress: Float,
    pieces: List<DrumPiece>,
    editedHits: Map<Int, List<DrumHit>>,
    onNotesChanged: (Int, List<EditableNote>) -> Unit,
    onBack: () -> Unit
) {
    val firstLoadedSlot = slots.firstOrNull { it.parsed != null }
    val firstLoadedIndex = slots.indexOfFirst { it.parsed != null }
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Volver", color = AccentBlue) }
        }
        Spacer(Modifier.height(8.dp))
        Text("🎹 Piano Roll", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextWhite)
        Text("Slot: ${if (activeSlotIndex != null) "${activeSlotIndex + 1}" else "—"}  |  ${(progress * 100).toInt()}%", color = TextWhiteSoft, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        if (firstLoadedSlot?.parsed != null) {
            PianoRollView(
                hits = firstLoadedSlot.parsed.hits,
                durationMicros = firstLoadedSlot.parsed.durationMicros,
                progress = progress,
                pieces = pieces,
                onNotesChanged = { notes ->
                    onNotesChanged(firstLoadedIndex, notes)
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Text("Carga un MIDI para ver el piano roll", color = TextWhiteSoft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 40.dp))
        }
    }
}

// ─── FIN VISOR ───────────────────────────────────────────────────────

// ─── MIXER ───────────────────────────────────────────────────────────

@Composable
private fun MixerScreen(
    pieces: List<DrumPiece>,
    masterVolume: Float,
    onMasterVolumeChanged: (Float) -> Unit,
    onVolumeChanged: (String, Float) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Volver", color = AccentBlue) }
        }
        Spacer(Modifier.height(8.dp))
        Text("🎛️ Mixer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = TextWhite)

        Spacer(Modifier.height(20.dp))

        // MASTER
        Text("🔊 MASTER", color = AccentBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(value = masterVolume, onValueChange = onMasterVolumeChanged, modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue, inactiveTrackColor = TextWhiteSoft.copy(alpha = 0.3f)))
            Text("${(masterVolume * 100).toInt()}%", color = TextWhite, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(45.dp))
        }

        Spacer(Modifier.height(24.dp))

        // PIEZAS
        Text("Piezas", color = AccentBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        pieces.forEach { piece ->
            var pieceVolume by remember(piece.id) { mutableStateOf(piece.volume) }
            val isCustom = piece.id.startsWith("custom")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(piece.label, color = TextWhite, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(100.dp))
                Slider(value = pieceVolume, onValueChange = { pieceVolume = it; onVolumeChanged(piece.id, it) }, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = if (isCustom) Color(0xFFFF9800) else Color.White,
                        activeTrackColor = if (isCustom) Color(0xFFFF9800) else Color.White,
                        inactiveTrackColor = TextWhiteSoft.copy(alpha = 0.3f)))
                Text("${(pieceVolume * 100).toInt()}%", color = TextWhiteSoft, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── FIN MIXER ───────────────────────────────────────────────────────

@Composable
private fun MappingScreen(
    pieces: List<DrumPiece>,
    onPiecesChanged: (List<DrumPiece>) -> Unit,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var localPieces by remember { mutableStateOf(pieces) }
    var showPresets by remember { mutableStateOf(false) }

    val presets = mapOf(
        "General MIDI" to mapOf(
            "kick" to 36, "snare" to 38, "tom1" to 48, "tom2" to 45, "tom3" to 41,
            "hihat_closed" to 42, "hihat_open" to 46, "crash1" to 49, "crash2" to 57,
            "ride" to 51, "china" to 52
        ),
        "EZDrummer" to mapOf(
            "kick" to 36, "snare" to 38, "tom1" to 48, "tom2" to 47, "tom3" to 43,
            "hihat_closed" to 42, "hihat_open" to 46, "crash1" to 49, "crash2" to 57,
            "ride" to 51, "china" to 55
        ),
        "EZDrummer 3" to mapOf(
            "kick" to 36, "snare" to 38, "tom1" to 48, "tom2" to 47, "tom3" to 41,
            "hihat_closed" to 42, "hihat_open" to 60, "crash1" to 49, "crash2" to 57,
            "ride" to 52, "china" to 55
        ),
        "Superior Drummer" to mapOf(
            "kick" to 36, "snare" to 37, "tom1" to 48, "tom2" to 47, "tom3" to 43,
            "hihat_closed" to 42, "hihat_open" to 46, "crash1" to 49, "crash2" to 57,
            "ride" to 51, "china" to 52
        ),
        "Addictive Drums" to mapOf(
            "kick" to 36, "snare" to 38, "tom1" to 48, "tom2" to 45, "tom3" to 43,
            "hihat_closed" to 42, "hihat_open" to 46, "crash1" to 49, "crash2" to 57,
            "ride" to 51, "china" to 57
        ),
        "GGD (GetGood)" to mapOf(
            "kick" to 36, "snare" to 38, "tom1" to 48, "tom2" to 50, "tom3" to 47,
            "hihat_closed" to 42, "hihat_open" to 46, "crash1" to 49, "crash2" to 57,
            "ride" to 51, "china" to 52
        ),
        "Metal Machine EZX" to mapOf(
            "kick" to 36, "snare" to 38, "tom1" to 48, "tom2" to 45, "tom3" to 41,
            "hihat_closed" to 42, "hihat_open" to 46, "crash1" to 49, "crash2" to 57,
            "ride" to 51, "china" to 55
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Volver", color = AccentBlue)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Reasignar notas MIDI",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextWhite
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Elige un preset famoso o ajusta manualmente cada nota (0-127).",
            style = MaterialTheme.typography.bodySmall,
            color = TextWhiteSoft
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { showPresets = !showPresets },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentBlue,
                contentColor = Color.Black
            )
        ) {
            Text(
                if (showPresets) "▲ Ocultar presets" else "▼ Cargar preset famoso",
                fontWeight = FontWeight.Bold
            )
        }

        if (showPresets) {
            Spacer(Modifier.height(8.dp))
            presets.forEach { (name, mapping) ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, color = TextWhite, modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                mapping.forEach { (id, note) ->
                                    SamplePrefs.saveNote(context, id, note)
                                }
                                localPieces = SamplePrefs.loadPieces(context)
                                onPiecesChanged(localPieces)
                                showPresets = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = AccentBlue
                            )
                        ) {
                            Text("Aplicar")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        localPieces.filter { !it.id.startsWith("custom") }.forEach { piece ->
            var noteText by remember(piece.id) { mutableStateOf(piece.note.toString()) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    piece.label,
                    color = TextWhite,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            val newNote = (piece.note - 1).coerceIn(0, 127)
                            SamplePrefs.saveNote(context, piece.id, newNote)
                            localPieces = SamplePrefs.loadPieces(context)
                            onPiecesChanged(localPieces)
                        },
                        modifier = Modifier.width(40.dp)
                    ) {
                        Text("−", color = AccentBlue, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                                noteText = newValue
                                val noteInt = newValue.toIntOrNull()
                                if (noteInt != null && noteInt in 0..127) {
                                    SamplePrefs.saveNote(context, piece.id, noteInt)
                                    localPieces = SamplePrefs.loadPieces(context)
                                    onPiecesChanged(localPieces)
                                }
                            }
                        },
                        modifier = Modifier.width(65.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextWhite,
                            unfocusedTextColor = TextWhite,
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = TextWhiteSoft
                        )
                    )

                    TextButton(
                        onClick = {
                            val newNote = (piece.note + 1).coerceIn(0, 127)
                            SamplePrefs.saveNote(context, piece.id, newNote)
                            localPieces = SamplePrefs.loadPieces(context)
                            onPiecesChanged(localPieces)
                        },
                        modifier = Modifier.width(40.dp)
                    ) {
                        Text("+", color = AccentBlue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── SLOTS PERSONALIZADOS ────────────────────────────────────────────

@Composable
private fun CustomSlotRow(
    piece: DrumPiece,
    onSampleSelected: (Uri?) -> Unit,
    onNoteChanged: (Int) -> Unit
) {
    val samplePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onSampleSelected(uri)
        }
    }

    var noteText by remember(piece.id) { mutableStateOf(if (piece.note == 0) "" else piece.note.toString()) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = Color.White.copy(alpha = 0.05f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(piece.label, color = TextWhite, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (piece.sampleUri != null) "Sample cargado" else "Sin sample",
                    color = TextWhiteSoft,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || (newValue.all { it.isDigit() } && newValue.length <= 3)) {
                            noteText = newValue
                            val noteInt = newValue.toIntOrNull()
                            if (noteInt != null && noteInt in 0..127) {
                                onNoteChanged(noteInt)
                            }
                        }
                    },
                    modifier = Modifier.width(60.dp),
                    singleLine = true,
                    placeholder = { Text("Nota", color = TextWhiteSoft, fontSize = 10.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextWhite,
                        unfocusedTextColor = TextWhite,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextWhiteSoft
                    )
                )

                TextButton(onClick = { samplePickerLauncher.launch(arrayOf("audio/*")) }) {
                    Text(if (piece.sampleUri != null) "Cambiar" else "Cargar", color = AccentBlue, fontSize = 12.sp)
                }
            }
        }
    }
}

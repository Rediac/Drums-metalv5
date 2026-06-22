package com.example.mididrums

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// Colores por pieza (los nuestros, no canales MIDI)
private val PIECE_COLORS = listOf(
    Color(0xFF4FC3F7), // Kick
    Color(0xFF81C784), // Snare
    Color(0xFFFFB74D), // Tom 1
    Color(0xFFE57373), // Tom 2
    Color(0xFFBA68C8), // Tom 3
    Color(0xFF4DD0E1), // Hi-Hat cerrado
    Color(0xFFFFF176), // Hi-Hat abierto
    Color(0xFFA1887F), // Crash 1
    Color(0xFF90A4AE), // Crash 2
    Color(0xFFFF8A65), // Ride
    Color(0xFF7986CB), // China
    Color(0xFFFF5555), // Nota desconocida
    Color(0xFFFF9800), // Sin sample
)

private val BLACK_NOTE_POSITIONS = setOf(1, 3, 6, 8, 10)
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

@Composable
fun PianoRollView(
    hits: List<DrumHit>,
    durationMicros: Long,
    progress: Float,
    pieces: List<DrumPiece> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Zoom
    var zoomH by remember { mutableStateOf(1f) }
    var zoomV by remember { mutableStateOf(1f) }
    
    // Nota seleccionada
    var selectedNote by remember { mutableStateOf<DrumHit?>(null) }
    
    // Offset de scroll
    var scrollX by remember { mutableStateOf(0f) }
    var scrollY by remember { mutableStateOf(0f) }

    // Mapa de nota -> color
    val noteToColor = remember(pieces) {
        val map = mutableMapOf<Int, Color>()
        pieces.forEachIndexed { index, piece ->
            map[piece.note] = PIECE_COLORS[index % PIECE_COLORS.size]
        }
        map
    }

    val noteRange = remember(hits) {
        if (hits.isEmpty()) 24..96
        else {
            val min = (hits.minOf { it.note } - 4).coerceAtLeast(0)
            val max = (hits.maxOf { it.note } + 4).coerceAtMost(127)
            min..max
        }
    }
    val noteCount = noteRange.last - noteRange.first + 1

    // BPM para calcular compases
    val bpm = 120.0
    val beatsPerMinute = bpm
    val microsPerBeat = (60_000_000.0 / beatsPerMinute).toLong()
    val totalBeats = durationMicros.toDouble() / microsPerBeat

    Column(modifier = modifier) {
        // Barra de herramientas
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Zoom H:", color = Color.White, fontSize = 10.sp)
            TextButton(onClick = { zoomH = (zoomH * 1.3f).coerceAtMost(5f) }) {
                Text("+", color = Color(0xFF4FC3F7))
            }
            TextButton(onClick = { zoomH = (zoomH / 1.3f).coerceAtLeast(0.3f) }) {
                Text("−", color = Color(0xFF4FC3F7))
            }
            
            Spacer(Modifier.width(8.dp))
            
            Text("Zoom V:", color = Color.White, fontSize = 10.sp)
            TextButton(onClick = { zoomV = (zoomV * 1.3f).coerceAtMost(3f) }) {
                Text("+", color = Color(0xFF4FC3F7))
            }
            TextButton(onClick = { zoomV = (zoomV / 1.3f).coerceAtLeast(0.5f) }) {
                Text("−", color = Color(0xFF4FC3F7))
            }

            Spacer(Modifier.weight(1f))

            // Info de nota seleccionada
            if (selectedNote != null) {
                val noteName = NOTE_NAMES[selectedNote!!.note % 12]
                val octave = selectedNote!!.note / 12 - 2
                Text(
                    "$noteName$octave  vel:${selectedNote!!.velocity}",
                    color = Color(0xFF4FC3F7),
                    fontSize = 10.sp
                )
            }
        }

        // Piano Roll
        Row(modifier = Modifier.weight(1f)) {
            // Teclado lateral
            PianoKeyboard(
                noteRange = noteRange,
                zoomV = zoomV,
                modifier = Modifier.width(30.dp).fillMaxHeight()
            )

            // Área de notas con scroll
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0xFF0D1117))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scrollX = (scrollX - pan.x).coerceAtLeast(0f)
                            scrollY = (scrollY - pan.y).coerceIn(-500f, 500f)
                            zoomH = (zoomH * zoom).coerceIn(0.3f, 5f)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            // Detectar si tocó una nota
                            val noteH = (size.height / noteCount) * zoomV
                            val noteIdx = ((offset.y - scrollY) / noteH).toInt()
                            if (noteIdx in 0 until noteCount) {
                                val note = noteRange.last - noteIdx
                                val hit = hits.find { it.note == note }
                                selectedNote = hit
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val noteH = (h / noteCount) * zoomV
                    val playheadX = w * 0.3f

                    // Fondo
                    drawRect(Color(0xFF0D1117), Offset.Zero, Size(w, h))

                    // Líneas de grid vertical (compases)
                    val pixelsPerMicro = (w * zoomH) / (10_000_000f)
                    for (beat in 0..totalBeats.toInt() step 1) {
                        val x = (beat * microsPerBeat * pixelsPerMicro / microsPerBeat) + playheadX + scrollX
                        if (x in 0f..w) {
                            val isDownbeat = beat % 4 == 0
                            drawLine(
                                color = if (isDownbeat) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                                start = Offset(x % w, 0f),
                                end = Offset(x % w, h),
                                strokeWidth = if (isDownbeat) 1.5f else 0.5f
                            )
                        }
                    }

                    // Notas
                    hits.forEach { hit ->
                        val noteIdx = noteRange.last - hit.note
                        if (noteIdx < 0 || noteIdx >= noteCount) return@forEach

                        val x = ((hit.timeMicros * pixelsPerMicro) + playheadX + scrollX)
                        val y = noteIdx * noteH + scrollY
                        val isSelected = selectedNote == hit
                        val noteColor = noteToColor[hit.note] ?: PIECE_COLORS.last()
                        val noteWidth = (noteH * 4f).coerceAtLeast(6f)
                        val alpha = (hit.velocity / 127f).coerceIn(0.4f, 1f)

                        if (x + noteWidth > 0 && x < w) {
                            drawRoundRect(
                                color = if (isSelected) Color.White else noteColor.copy(alpha = alpha),
                                topLeft = Offset(x, y + noteH * 0.05f),
                                size = Size(noteWidth, noteH * 0.9f),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }
                    }

                    // Línea de playback
                    val playheadPixel = playheadX + scrollX
                    drawLine(
                        color = Color(0xFFFF4444),
                        start = Offset(playheadPixel, 0f),
                        end = Offset(playheadPixel, h),
                        strokeWidth = 2f
                    )
                }
            }
        }
    }
}

@Composable
private fun PianoKeyboard(
    noteRange: IntRange,
    zoomV: Float,
    modifier: Modifier = Modifier
) {
    val noteCount = noteRange.last - noteRange.first + 1

    Canvas(modifier = modifier.background(Color(0xFF0D1117))) {
        val h = size.height
        val w = size.width
        val noteH = (h / noteCount) * zoomV

        for (i in 0 until noteCount) {
            val note = noteRange.last - i
            val y = i * noteH
            val isBlack = (note % 12) in BLACK_NOTE_POSITIONS
            val isC = note % 12 == 0

            if (isBlack) {
                drawRect(Color(0xFF1A1A1A), Offset(0f, y), Size(w * 0.7f, noteH))
            } else {
                drawRect(Color(0xFFE0E0E0), Offset(0f, y + 0.5f), Size(w - 1f, noteH - 1f))
                if (isC) {
                    val noteName = "${NOTE_NAMES[0]}${note / 12 - 2}"
                    // Indicador de octava
                    drawRect(
                        Color(0xFF4FC3F7).copy(alpha = 0.5f),
                        Offset(w * 0.7f, y + noteH * 0.2f),
                        Size(w * 0.25f, noteH * 0.6f)
                    )
                }
            }

            if (!isBlack) {
                drawLine(Color(0xFF999999), Offset(0f, y), Offset(w, y), 0.5f)
            }
        }
    }
}

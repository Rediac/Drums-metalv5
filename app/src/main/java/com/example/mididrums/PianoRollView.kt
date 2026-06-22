package com.example.mididrums

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_NOTE_POSITIONS = setOf(1, 3, 6, 8, 10)

private val PIECE_COLORS = listOf(
    Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF4DD0E1),
    Color(0xFFFFF176), Color(0xFFA1887F), Color(0xFF90A4AE),
    Color(0xFFFF8A65), Color(0xFF7986CB),
    Color(0xFFFF5555), Color(0xFFFF9800)
)

@Composable
fun PianoRollView(
    hits: List<DrumHit>,
    durationMicros: Long,
    progress: Float,
    pieces: List<DrumPiece> = emptyList(),
    modifier: Modifier = Modifier
) {
    var zoomH by remember { mutableStateOf(1f) }
    var scrollX by remember { mutableStateOf(0f) }
    var selectedNote by remember { mutableStateOf<DrumHit?>(null) }

    val noteToColor = remember(pieces) {
        val map = mutableMapOf<Int, Color>()
        pieces.forEachIndexed { index, piece ->
            map[piece.note] = PIECE_COLORS[index % (PIECE_COLORS.size - 2)]
        }
        map
    }

    val noteRange = remember(hits) {
        if (hits.isEmpty()) 36..84
        else {
            val min = (hits.minOf { it.note } - 2).coerceAtLeast(0)
            val max = (hits.maxOf { it.note } + 2).coerceAtMost(127)
            min..max
        }
    }
    val noteCount = noteRange.last - noteRange.first + 1

    val pixelsPerMicro = 0.0005f * zoomH

    Column(modifier = modifier) {
        // Barra de herramientas
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A2E)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Zoom:", color = Color.White, fontSize = 10.sp)
            TextButton(onClick = { zoomH = (zoomH * 1.3f).coerceAtMost(5f) }) { Text("+", color = Color(0xFF4FC3F7)) }
            TextButton(onClick = { zoomH = (zoomH / 1.3f).coerceAtLeast(0.2f) }) { Text("−", color = Color(0xFF4FC3F7)) }

            Spacer(Modifier.weight(1f))

            if (selectedNote != null) {
                val noteName = NOTE_NAMES[selectedNote!!.note % 12]
                val octave = selectedNote!!.note / 12 - 2
                Text("$noteName$octave vel:${selectedNote!!.velocity}", color = Color(0xFF4FC3F7), fontSize = 10.sp)
            }
        }

        // Piano Roll
        Row(modifier = Modifier.weight(1f)) {
            // Teclado lateral
            Box(modifier = Modifier.width(30.dp).fillMaxHeight().background(Color(0xFF0D1117))) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val h = size.height
                    val noteH = h / noteCount
                    for (i in 0 until noteCount) {
                        val note = noteRange.last - i
                        val y = i * noteH
                        val isBlack = (note % 12) in BLACK_NOTE_POSITIONS
                        val isC = note % 12 == 0

                        if (isBlack) {
                            drawRect(Color(0xFF1A1A1A), Offset(0f, y), Size(size.width * 0.7f, noteH))
                        } else {
                            drawRect(Color(0xFFE0E0E0), Offset(0f, y + 0.5f), Size(size.width - 1f, noteH - 1f))
                            if (isC) {
                                drawRect(Color(0xFF4FC3F7).copy(alpha = 0.5f), Offset(size.width * 0.7f, y + noteH * 0.2f), Size(size.width * 0.25f, noteH * 0.6f))
                            }
                        }

                        if (!isBlack) {
                            drawLine(Color(0xFF999999), Offset(0f, y), Offset(size.width, y), 0.5f)
                        }
                    }
                }
            }

            // Área de notas
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0D1117))
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            scrollX = (scrollX + dragAmount)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val noteH = (size.height / noteCount)
                            val noteIdx = ((offset.y) / noteH).toInt()
                            if (noteIdx in 0 until noteCount) {
                                val note = noteRange.last - noteIdx
                                selectedNote = hits.find { it.note == note }
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val noteH = h / noteCount
                    val playheadX = w * 0.3f

                    // Fondo
                    drawRect(Color(0xFF0D1117), Offset.Zero, Size(w, h))

                    // Líneas horizontales
                    for (i in 0..noteCount) {
                        val y = i * noteH
                        drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(w, y), 0.5f)
                    }

                    // Notas
                    val currentTime = (progress * durationMicros).toLong()

                    hits.forEach { hit ->
                        val noteIdx = noteRange.last - hit.note
                        if (noteIdx < 0 || noteIdx >= noteCount) return@forEach

                        val x = (hit.timeMicros * pixelsPerMicro) + playheadX - (currentTime * pixelsPerMicro) + scrollX
                        val y = noteIdx * noteH
                        val noteColor = noteToColor[hit.note] ?: PIECE_COLORS.last()
                        val noteWidth = (noteH * 4f).coerceAtLeast(6f)
                        val hasPassed = hit.timeMicros <= currentTime
                        val alpha = if (hasPassed) 0.6f else (hit.velocity / 127f).coerceIn(0.4f, 1f)

                        if (x + noteWidth > 0 && x < w) {
                            val isSelected = selectedNote == hit
                            drawRoundRect(
                                color = if (isSelected) Color.White else noteColor.copy(alpha = alpha),
                                topLeft = Offset(x, y + noteH * 0.05f),
                                size = Size(noteWidth, noteH * 0.9f),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }
                    }

                    // Línea de playback (roja, fija al 30%)
                    drawLine(
                        color = Color(0xFFFF4444),
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, h),
                        strokeWidth = 2f
                    )

                    // Triángulo arriba
                    val trianglePath = Path().apply {
                        moveTo(playheadX, 0f)
                        lineTo(playheadX - 6f, 10f)
                        lineTo(playheadX + 6f, 10f)
                        close()
                    }
                    drawPath(trianglePath, Color(0xFFFF4444))
                }
            }
        }
    }
}

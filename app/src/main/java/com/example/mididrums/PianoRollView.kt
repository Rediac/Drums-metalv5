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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val BLACK_NOTE_POSITIONS = setOf(1, 3, 6, 8, 10)

private val PIECE_COLORS = listOf(
    Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFFFFB74D),
    Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF4DD0E1),
    Color(0xFFFFF176), Color(0xFFA1887F), Color(0xFF90A4AE),
    Color(0xFFFF8A65), Color(0xFF7986CB),
    Color(0xFFFF5555), Color(0xFFFF9800)
)

data class PianoRollNote(
    val timeMicros: Long,
    val note: Int,
    val velocity: Int = 100
)

@Composable
fun PianoRollView(
    hits: List<DrumHit>,
    durationMicros: Long,
    progress: Float,
    pieces: List<DrumPiece> = emptyList(),
    onNotesChanged: ((List<PianoRollNote>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var zoomH by remember { mutableStateOf(1f) }
    var scrollX by remember { mutableStateOf(0f) }
    var selectedNote by remember { mutableStateOf<PianoRollNote?>(null) }

    var editableNotes by remember(hits) {
        mutableStateOf(hits.map { PianoRollNote(it.timeMicros, it.note, it.velocity) })
    }

    var draggingNote by remember { mutableStateOf<PianoRollNote?>(null) }
    var dragStartTime by remember { mutableStateOf(0L) }
    var dragStartNote by remember { mutableStateOf(0) }
    var longPressTime by remember { mutableStateOf(0L) }
    var isLongPress by remember { mutableStateOf(false) }

    val noteToColor = remember(pieces) {
        val map = mutableMapOf<Int, Color>()
        pieces.forEachIndexed { index, piece ->
            map[piece.note] = PIECE_COLORS[index % (PIECE_COLORS.size - 2)]
        }
        map
    }

    val noteRange = remember(editableNotes) {
        if (editableNotes.isEmpty()) 36..84
        else {
            val min = (editableNotes.minOf { it.note } - 2).coerceAtLeast(0)
            val max = (editableNotes.maxOf { it.note } + 2).coerceAtMost(127)
            min..max
        }
    }
    val noteCount = noteRange.last - noteRange.first + 1

    val pixelsPerMicro = 0.0005f * zoomH
    val snapMicros = 50_000L

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

            Spacer(Modifier.width(16.dp))

            TextButton(onClick = {
                editableNotes = hits.map { PianoRollNote(it.timeMicros, it.note, it.velocity) }
                onNotesChanged?.invoke(editableNotes)
            }) {
                Text("↩ Restaurar", color = Color(0xFFFF9800), fontSize = 10.sp)
            }

            Spacer(Modifier.weight(1f))

            if (selectedNote != null) {
                val noteName = NOTE_NAMES[selectedNote!!.note % 12]
                val octave = selectedNote!!.note / 12 - 2
                Text("$noteName$octave vel:${selectedNote!!.velocity}", color = Color(0xFF4FC3F7), fontSize = 10.sp)
            }

            if (draggingNote != null) {
                Text("Editando...", color = Color(0xFF00E676), fontSize = 10.sp)
            }
        }

        // Piano Roll
        Row(modifier = Modifier.weight(1f)) {
            // Teclado lateral
            Box(modifier = Modifier.width(30.dp).fillMaxHeight().background(Color(0xFF0D1117))) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasH = size.height
                    val noteH = canvasH / noteCount
                    for (i in 0 until noteCount) {
                        val note = noteRange.last - i
                        val noteY = i * noteH
                        val isBlack = (note % 12) in BLACK_NOTE_POSITIONS
                        val isC = note % 12 == 0

                        if (isBlack) {
                            drawRect(Color(0xFF1A1A1A), Offset(0f, noteY), Size(size.width * 0.7f, noteH))
                        } else {
                            drawRect(Color(0xFFE0E0E0), Offset(0f, noteY + 0.5f), Size(size.width - 1f, noteH - 1f))
                            if (isC) {
                                drawRect(Color(0xFF4FC3F7).copy(alpha = 0.5f), Offset(size.width * 0.7f, noteY + noteH * 0.2f), Size(size.width * 0.25f, noteH * 0.6f))
                            }
                        }

                        if (!isBlack) {
                            drawLine(Color(0xFF999999), Offset(0f, noteY), Offset(size.width, noteY), 0.5f)
                        }
                    }
                }
            }

            // Área de notas
            val currentTime = (progress * durationMicros).toLong()
            
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().background(Color(0xFF0D1117))
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val noteH = (size.height / noteCount)
                                val noteIdx = ((offset.y) / noteH).toInt()
                                if (noteIdx in 0 until noteCount) {
                                    val note = noteRange.last - noteIdx
                                    val playheadX = size.width * 0.3f
                                    val timeAtTouch = ((offset.x - playheadX + currentTime * pixelsPerMicro - scrollX) / pixelsPerMicro).toLong()

                                    val hit = editableNotes.find {
                                        abs(it.timeMicros - timeAtTouch) < 200_000L && it.note == note
                                    }
                                    if (hit != null) {
                                        draggingNote = hit
                                        dragStartTime = hit.timeMicros
                                        dragStartNote = hit.note
                                        longPressTime = System.currentTimeMillis()
                                    }
                                }
                            },
                            onDragEnd = {
                                if (draggingNote != null && isLongPress) {
                                    val idx = editableNotes.indexOf(draggingNote)
                                    if (idx >= 0 && abs(draggingNote!!.timeMicros - dragStartTime) > 500_000L) {
                                        editableNotes = editableNotes.toMutableList().apply { removeAt(idx) }
                                    }
                                }
                                draggingNote = null
                                isLongPress = false
                                onNotesChanged?.invoke(editableNotes)
                            },
                            onDragCancel = {
                                draggingNote = null
                                isLongPress = false
                            }
                        ) { _, dragAmount ->
                            if (draggingNote != null) {
                                if (System.currentTimeMillis() - longPressTime > 500) {
                                    isLongPress = true
                                }

                                val idx = editableNotes.indexOf(draggingNote)
                                if (idx >= 0) {
                                    val newTime = (dragStartTime + (dragAmount.x / pixelsPerMicro).toLong()).coerceAtLeast(0)
                                    val snappedTime = (newTime / snapMicros) * snapMicros

                                    val noteH = (size.height / noteCount)
                                    val noteDelta = -(dragAmount.y / noteH).roundToInt()
                                    val newNote = (dragStartNote + noteDelta).coerceIn(noteRange.first, noteRange.last)

                                    if (!isLongPress) {
                                        editableNotes = editableNotes.toMutableList().apply {
                                            set(idx, PianoRollNote(snappedTime, newNote, draggingNote!!.velocity))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val noteH = (size.height / noteCount)
                                val noteIdx = ((offset.y) / noteH).toInt()
                                if (noteIdx in 0 until noteCount) {
                                    val note = noteRange.last - noteIdx
                                    val playheadX = size.width * 0.3f
                                    val timeAtTouch = ((offset.x - playheadX + currentTime * pixelsPerMicro - scrollX) / pixelsPerMicro).toLong()
                                    val snappedTime = (timeAtTouch / snapMicros) * snapMicros

                                    val newNote = PianoRollNote(snappedTime.coerceAtLeast(0), note, 100)
                                    editableNotes = editableNotes.toMutableList().apply {
                                        add(newNote)
                                        sortBy { it.timeMicros }
                                    }
                                    onNotesChanged?.invoke(editableNotes)
                                }
                            },
                            onTap = { offset ->
                                val noteH = (size.height / noteCount)
                                val noteIdx = ((offset.y) / noteH).toInt()
                                if (noteIdx in 0 until noteCount) {
                                    val note = noteRange.last - noteIdx
                                    val playheadX = size.width * 0.3f
                                    val timeAtTouch = ((offset.x - playheadX + currentTime * pixelsPerMicro - scrollX) / pixelsPerMicro).toLong()

                                    val hit = editableNotes.find {
                                        abs(it.timeMicros - timeAtTouch) < 200_000L && it.note == note
                                    }
                                    selectedNote = hit
                                }
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val noteH = h / noteCount
                    val playheadX = w * 0.3f

                    drawRect(Color(0xFF0D1117), Offset.Zero, Size(w, h))

                    for (i in 0..noteCount) {
                        val lineY = i * noteH
                        drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, lineY), Offset(w, lineY), 0.5f)
                    }

                    editableNotes.forEach { note ->
                        val noteIdx = noteRange.last - note.note
                        if (noteIdx < 0 || noteIdx >= noteCount) return@forEach

                        val noteX = (note.timeMicros * pixelsPerMicro) + playheadX - (currentTime * pixelsPerMicro) + scrollX
                        val noteY = noteIdx * noteH
                        val noteColor = noteToColor[note.note] ?: PIECE_COLORS.last()
                        val noteWidth = (noteH * 4f).coerceAtLeast(6f)
                        val hasPassed = note.timeMicros <= currentTime
                        val alpha = if (hasPassed) 0.6f else (note.velocity / 127f).coerceIn(0.4f, 1f)

                        if (noteX + noteWidth > 0 && noteX < w) {
                            val isSelected = selectedNote == note
                            val isDragging = draggingNote == note

                            drawRoundRect(
                                color = when {
                                    isDragging -> Color(0xFF00E676)
                                    isSelected -> Color.White
                                    else -> noteColor.copy(alpha = alpha)
                                },
                                topLeft = Offset(noteX, noteY + noteH * 0.05f),
                                size = Size(noteWidth, noteH * 0.9f),
                                cornerRadius = CornerRadius(3f, 3f)
                            )

                            if (isSelected) {
                                drawRoundRect(
                                    color = Color(0xFF4FC3F7),
                                    topLeft = Offset(noteX, noteY + noteH * 0.05f),
                                    size = Size(noteWidth, noteH * 0.9f),
                                    cornerRadius = CornerRadius(3f, 3f),
                                    style = Stroke(2f)
                                )
                            }
                        }
                    }

                    drawLine(
                        color = Color(0xFFFF4444),
                        start = Offset(playheadX, 0f),
                        end = Offset(playheadX, h),
                        strokeWidth = 2f
                    )

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

        // Barra de estado
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A2E)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Notas: ${editableNotes.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
            Text("✂️ Doble tap = crear", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp)
            Text("✋ Arrastrar = mover", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp)
            Text("🗑️ Arrastrar lejos = borrar", color = Color.White.copy(alpha = 0.4f), fontSize = 9.sp)
        }
    }
}

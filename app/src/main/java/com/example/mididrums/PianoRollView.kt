package com.example.mididrums

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val CHANNEL_COLORS = listOf(
    Color(0xFF4FC3F7), // CH 0
    Color(0xFFAB47BC), // CH 1
    Color(0xFF66BB6A), // CH 2
    Color(0xFFEF5350), // CH 3
    Color(0xFFFFCA28), // CH 4
    Color(0xFF26C6DA), // CH 5
    Color(0xFFFF7043), // CH 6
    Color(0xFF8D6E63), // CH 7
    Color(0xFF78909C), // CH 8
    Color(0xFF4FC3F7), // CH 9 batería GM
    Color(0xFFD4E157), // CH 10
    Color(0xFFEC407A), // CH 11
    Color(0xFF5C6BC0), // CH 12
    Color(0xFF26A69A), // CH 13
    Color(0xFFFFA726), // CH 14
    Color(0xFF7E57C2)  // CH 15
)

private val BLACK_NOTE_POSITIONS = setOf(1, 3, 6, 8, 10)

@Composable
fun PianoRollView(
    hits: List<DrumHit>,
    durationMicros: Long,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val noteRange = remember(hits) {
        if (hits.isEmpty()) 36..84
        else {
            val min = (hits.minOf { it.note } - 2).coerceAtLeast(0)
            val max = (hits.maxOf { it.note } + 2).coerceAtMost(127)
            min..max
        }
    }
    val noteCount = noteRange.last - noteRange.first + 1

    Row(modifier = modifier) {
        PianoKeyboard(
            noteRange = noteRange,
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF0D1117))
        ) {
            val w = size.width
            val h = size.height
            val noteH = h / noteCount
            val playheadX = w * 0.25f

            val windowMicros = 10_000_000L
            val currentMicros = (progress * durationMicros).toLong()
            val windowStartMicros = currentMicros - (windowMicros * 0.25f).toLong()
            val windowEndMicros = currentMicros + (windowMicros * 0.75f).toLong()

            for (i in 0 until noteCount) {
                val note = noteRange.last - i
                val y = i * noteH
                val isBlack = (note % 12) in BLACK_NOTE_POSITIONS
                val rowColor = when {
                    isBlack -> Color(0xFF141920)
                    note % 12 == 0 -> Color(0xFF1A2030)
                    else -> Color(0xFF111519)
                }
                drawRect(color = rowColor, topLeft = Offset(0f, y), size = Size(w, noteH))
            }

            for (i in 0..noteCount) {
                val y = i * noteH
                drawLine(
                    color = Color.White.copy(alpha = 0.04f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 0.5f
                )
            }

            hits.forEach { hit ->
                val hitMicros = hit.timeMicros
                if (hitMicros < windowStartMicros || hitMicros > windowEndMicros) return@forEach

                val noteIdx = noteRange.last - hit.note
                if (noteIdx < 0 || noteIdx >= noteCount) return@forEach

                val xFraction = (hitMicros - windowStartMicros).toFloat() /
                    (windowEndMicros - windowStartMicros).toFloat()
                val x = xFraction * w
                val y = noteIdx * noteH
                val noteWidth = (noteH * 3f).coerceAtLeast(4f)
                val noteColor = CHANNEL_COLORS[hit.channel % 16]
                val alpha = (hit.velocity / 127f).coerceIn(0.5f, 1f)

                drawRoundRect(
                    color = noteColor.copy(alpha = alpha),
                    topLeft = Offset(x, y + noteH * 0.1f),
                    size = Size(noteWidth, noteH * 0.8f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, h),
                strokeWidth = 1.5f
            )
            drawCircle(
                color = Color.White,
                radius = 4f,
                center = Offset(playheadX, 4f)
            )
        }
    }
}

@Composable
private fun PianoKeyboard(
    noteRange: IntRange,
    modifier: Modifier = Modifier
) {
    val noteCount = noteRange.last - noteRange.first + 1

    Canvas(modifier = modifier.background(Color(0xFF0D1117))) {
        val h = size.height
        val w = size.width
        val noteH = h / noteCount

        for (i in 0 until noteCount) {
            val note = noteRange.last - i
            val y = i * noteH
            val isBlack = (note % 12) in BLACK_NOTE_POSITIONS
            val isC = note % 12 == 0

            if (isBlack) {
                drawRect(
                    color = Color(0xFF1A1A1A),
                    topLeft = Offset(0f, y),
                    size = Size(w * 0.6f, noteH)
                )
            } else {
                drawRect(
                    color = Color(0xFFE8E8E8),
                    topLeft = Offset(0f, y + 0.5f),
                    size = Size(w - 1f, noteH - 1f)
                )
                if (isC) {
                    drawRect(
                        color = Color(0xFF4FC3F7).copy(alpha = 0.4f),
                        topLeft = Offset(w * 0.75f, y + noteH * 0.2f),
                        size = Size(w * 0.2f, noteH * 0.6f)
                    )
                }
            }

            if (!isBlack) {
                drawLine(
                    color = Color(0xFFAAAAAA),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 0.5f
                )
            }
        }
    }
}

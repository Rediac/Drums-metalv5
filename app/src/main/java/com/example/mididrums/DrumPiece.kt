package com.example.mididrums

data class DrumPiece(
    val id: String,
    val label: String,
    val note: Int,
    val sampleUri: String? = null,  // null = usar asset por defecto
    val volume: Float = 1f           // 0.0 a 1.0
)

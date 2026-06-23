package com.example.mididrums

import android.content.Context
import android.content.SharedPreferences

object SamplePrefs {

    private const val PREFS_NAME = "midi_drums_prefs"
    private const val KEY_SAMPLE_URI_PREFIX = "sample_uri_"
    private const val KEY_NOTE_PREFIX = "note_"
    private const val KEY_VOLUME_PREFIX = "volume_"

    private val defaultSampleAssets = mapOf(
        "kick" to "sounds/Kick.wav",
        "snare" to "sounds/Snare.mp3",
        "tom1" to "sounds/Tom1.wav",
        "tom2" to "sounds/Tom2.wav",
        "tom3" to "sounds/Tom3.wav",
        "hihat_closed" to "sounds/HiHat_Closed.mp3",
        "hihat_open" to "sounds/HiHat_Open.mp3",
        "crash1" to "sounds/Crash.mp3",
        "crash2" to "sounds/Crash2.wav",
        "ride" to "sounds/Ride.mp3",
        "china" to "sounds/China.mp3",
        "nota26" to "sounds/Ride.mp3"  // Usa Ride como sonido temporal
    )

    fun loadPieces(context: Context): List<DrumPiece> {
        val prefs = getPrefs(context)
        return listOf(
            DrumPiece("kick", "Kick", 36),
            DrumPiece("snare", "Snare", 38),
            DrumPiece("hihat_closed", "Hi-Hat Cerrado", 42),
            DrumPiece("hihat_open", "Hi-Hat Abierto", 60),
            DrumPiece("tom1", "Tom 1", 48),
            DrumPiece("tom2", "Tom 2", 47),
            DrumPiece("tom3", "Tom 3", 41),
            DrumPiece("crash1", "Crash 1", 49),
            DrumPiece("crash2", "Crash 2", 57),
            DrumPiece("ride", "Ride", 52),
            DrumPiece("china", "China", 55),
            DrumPiece("nota26", "Nota 26", 26)
        ).map { piece ->
            val savedUri = prefs.getString(KEY_SAMPLE_URI_PREFIX + piece.id, null)
            val savedNote = prefs.getInt(KEY_NOTE_PREFIX + piece.id, piece.note)
            val savedVolume = prefs.getFloat(KEY_VOLUME_PREFIX + piece.id, 1f)
            piece.copy(
                sampleUri = savedUri,
                note = savedNote,
                volume = savedVolume
            )
        }
    }

    fun getDefaultAssetPath(pieceId: String): String? {
        return defaultSampleAssets[pieceId]
    }

    fun saveSampleUri(context: Context, pieceId: String, uri: String?) {
        getPrefs(context).edit().putString(KEY_SAMPLE_URI_PREFIX + pieceId, uri).apply()
    }

    fun saveNote(context: Context, pieceId: String, note: Int) {
        getPrefs(context).edit().putInt(KEY_NOTE_PREFIX + pieceId, note).apply()
    }

    fun saveVolume(context: Context, pieceId: String, volume: Float) {
        getPrefs(context).edit().putFloat(KEY_VOLUME_PREFIX + pieceId, volume).apply()
    }

    fun getVolume(context: Context, pieceId: String): Float {
        return getPrefs(context).getFloat(KEY_VOLUME_PREFIX + pieceId, 1f)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

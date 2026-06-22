package com.example.mididrums

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import kotlinx.coroutines.*

data class ChainSlotConfig(
    val hits: List<DrumHit>,
    val durationMicros: Long,
    val speedFactor: Float,
    val offsetMs: Int
)

class DrumEngine(private val context: Context) {

    private var soundPool: SoundPool? = null
    private val noteToSoundId = mutableMapOf<Int, Int>()
    private val noteToVolume = mutableMapOf<Int, Float>()

    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var isPlaying: Boolean = false
        private set

    var onProgress: ((Float) -> Unit)? = null
    var onNoteTriggered: ((Int) -> Unit)? = null
    var onSlotChanged: ((Int) -> Unit)? = null

    fun initialize() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(attributes)
            .build()
    }

    fun loadSamples(pieces: List<DrumPiece>) {
        val pool = soundPool ?: return
        noteToSoundId.clear()
        noteToVolume.clear()

        for (piece in pieces) {
            val soundId = if (piece.sampleUri != null) {
                loadFromUri(piece.sampleUri)
            } else {
                val assetPath = SamplePrefs.getDefaultAssetPath(piece.id)
                if (assetPath != null) {
                    loadFromAsset(assetPath)
                } else {
                    0
                }
            }

            if (soundId > 0) {
                noteToSoundId[piece.note] = soundId
                noteToVolume[piece.note] = piece.volume
            }
        }
    }

    private fun loadFromAsset(assetPath: String): Int {
        return try {
            val afd = context.assets.openFd(assetPath)
            val soundId = soundPool?.load(afd, 1) ?: 0
            afd.close()
            soundId
        } catch (e: Exception) {
            0
        }
    }

    private fun loadFromUri(uriString: String): Int {
        return try {
            val uri = Uri.parse(uriString)
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd != null) {
                val soundId = soundPool?.load(afd, 1) ?: 0
                afd.close()
                soundId
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    fun playChain(slots: List<ChainSlotConfig>) {
        stop()

        val playableSlots = slots.filter { it.hits.isNotEmpty() && it.durationMicros > 0 }
        if (playableSlots.isEmpty()) return

        data class TimedHit(val timeMicros: Long, val note: Int, val velocity: Int, val slotIndex: Int)

        val timeline = mutableListOf<TimedHit>()
        var cursorMicros = 0L

        playableSlots.forEachIndexed { index, slot ->
            val speed = slot.speedFactor.coerceAtLeast(0.01f)
            val offsetMicros = slot.offsetMs.toLong() * 1000L
            val scaledDuration = (slot.durationMicros / speed).toLong()
            val effectiveDuration = (scaledDuration - offsetMicros).coerceAtLeast(1L)

            for (hit in slot.hits) {
                val scaledTime = (hit.timeMicros / speed).toLong()
                val adjustedTime = scaledTime - offsetMicros

                if (adjustedTime in 0 until effectiveDuration) {
                    timeline.add(
                        TimedHit(cursorMicros + adjustedTime, hit.note, hit.velocity, index)
                    )
                }
            }
            cursorMicros += effectiveDuration
        }

        val totalDurationMicros = cursorMicros
        if (timeline.isEmpty() || totalDurationMicros <= 0) return

        timeline.sortBy { it.timeMicros }

        isPlaying = true

        playbackJob = scope.launch {
            while (isActive && isPlaying) {
                val loopStartNanos = System.nanoTime()
                var hitIndex = 0
                var lastNotifiedSlot = -1

                while (isActive && isPlaying && hitIndex < timeline.size) {
                    val hit = timeline[hitIndex]
                    val targetNanos = loopStartNanos + hit.timeMicros * 1000L
                    val waitMicros = (targetNanos - System.nanoTime()) / 1000L

                    if (waitMicros > 0) {
                        delay(waitMicros / 1000L)
                    }

                    if (!isActive || !isPlaying) break

                    if (hit.slotIndex != lastNotifiedSlot) {
                        onSlotChanged?.invoke(hit.slotIndex)
                        lastNotifiedSlot = hit.slotIndex
                    }

                    triggerNote(hit.note, hit.velocity)
                    onNoteTriggered?.invoke(hit.note)
                    hitIndex++

                    val progress = (hit.timeMicros.toFloat() / totalDurationMicros.toFloat())
                        .coerceIn(0f, 1f)
                    onProgress?.invoke(progress)
                }

                val elapsedMicros = (System.nanoTime() - loopStartNanos) / 1000L
                val remainingMicros = totalDurationMicros - elapsedMicros
                if (remainingMicros > 0) {
                    delay(remainingMicros / 1000L)
                }
            }
        }
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
    }

    private fun triggerNote(note: Int, velocity: Int) {
        val pool = soundPool ?: return
        val soundId = noteToSoundId[note] ?: return

        val normalizedVelocity = velocity / 127f

        // Curva EZDrummer: ghost notes < normal < acento
        val baseVolume = when {
            velocity <= 40 -> normalizedVelocity * 0.6f + 0.05f
            velocity <= 90 -> normalizedVelocity * 0.7f + 0.15f
            else -> normalizedVelocity * 0.5f + 0.45f
        }

        // Ajuste por tipo de pieza
        val volumeMultiplier = when (note) {
            36 -> 1.10f
            38 -> 1.0f
            42, 46, 60 -> 0.90f
            49, 57 -> 1.05f
            48, 47, 41 -> 1.0f
            51, 52 -> 0.95f
            55 -> 1.05f
            else -> 1.0f
        }

        // Volumen del mixer por pieza
        val pieceVolume = noteToVolume[note] ?: 1f

        val finalVolume = (baseVolume * volumeMultiplier * pieceVolume).coerceIn(0.05f, 1f)
        pool.play(soundId, finalVolume, finalVolume, 1, 0, 1f)
    }

    fun release() {
        stop()
        soundPool?.release()
        soundPool = null
        noteToSoundId.clear()
        noteToVolume.clear()
    }
}

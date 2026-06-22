package com.example.mididrums

import java.io.InputStream

/**
 * Representa un único golpe de batería extraído del archivo MIDI:
 * en qué momento (microsegundos desde el inicio) suena, qué nota MIDI es,
 * y con qué velocidad (intensidad, 0-127).
 */
data class DrumHit(
    val timeMicros: Long,
    val note: Int,
    val velocity: Int
)

/**
 * Resultado del parseo: la lista de golpes ordenada por tiempo, la
 * duración total del archivo en microsegundos (para saber cuándo hacer
 * loop), y el tempo original detectado en BPM (para mostrar como
 * referencia y como punto de partida del control de velocidad).
 */
data class ParsedMidi(
    val hits: List<DrumHit>,
    val durationMicros: Long,
    val originalBpm: Double
)

/** Tipo de evento crudo leído de la pista, antes de convertir ticks a tiempo real. */
private sealed class RawType {
    object NoteOn : RawType()
    object NoteOff : RawType()
    data class Tempo(val microsPerQuarter: Long) : RawType()
}

private data class RawEvent(val tick: Long, val type: RawType, val a: Int, val b: Int)

/**
 * Parser de archivos Standard MIDI File (SMF) escrito en Kotlin puro,
 * sin dependencias externas. Android no incluye javax.sound.midi, así
 * que este parser implementa lo mínimo necesario para nuestro caso de uso:
 * leer eventos Note On / Note Off y eventos de tempo, ignorando todo lo
 * demás (control changes, program changes, eventos de texto, etc.).
 *
 * Soporta formato SMF 0 (una sola pista) y formato 1 (múltiples pistas
 * que se reproducen en paralelo, típico de DAWs como exportan sus MIDIs).
 */
object MidiParser {

    private const val DEFAULT_TEMPO_MICROS_PER_QUARTER = 500_000L // 120 BPM

    fun parse(input: InputStream): ParsedMidi {
        val data = input.readBytes()
        val reader = ByteReader(data)

        // --- Header chunk "MThd" ---
        val headerId = reader.readString(4)
        require(headerId == "MThd") { "No es un archivo MIDI válido (falta cabecera MThd)" }

        val headerLength = reader.readInt32()
        val format = reader.readInt16()
        val numTracks = reader.readInt16()
        val division = reader.readInt16()

        if (headerLength > 6) {
            reader.skip(headerLength - 6)
        }

        val ticksPerQuarter = if (division and 0x8000 != 0) {
            96
        } else {
            division and 0x7FFF
        }

        val allTrackEvents = mutableListOf<RawEvent>()
        var maxTick = 0L

        repeat(numTracks) {
            val trackId = reader.readString(4)
            val trackLength = reader.readInt32()
            val trackEnd = reader.position + trackLength

            if (trackId != "MTrk") {
                reader.skip(trackLength)
                return@repeat
            }

            var tick = 0L
            var runningStatus = 0

            while (reader.position < trackEnd) {
                tick += reader.readVarLength()

                var statusByte = reader.readUInt8()
                if (statusByte < 0x80) {
                    reader.position -= 1
                    statusByte = runningStatus
                } else {
                    runningStatus = statusByte
                }

                when {
                    statusByte == 0xFF -> {
                        val metaType = reader.readUInt8()
                        val length = reader.readVarLength().toInt()
                        val metaStart = reader.position
                        if (metaType == 0x51 && length == 3) {
                            val b0 = reader.readUInt8()
                            val b1 = reader.readUInt8()
                            val b2 = reader.readUInt8()
                            val micros = (b0.toLong() shl 16) or (b1.toLong() shl 8) or b2.toLong()
                            allTrackEvents.add(RawEvent(tick, RawType.Tempo(micros), 0, 0))
                        }
                        reader.position = metaStart + length
                    }
                    statusByte == 0xF0 || statusByte == 0xF7 -> {
                        val length = reader.readVarLength().toInt()
                        reader.skip(length)
                    }
                    else -> {
                        val channelEventType = statusByte and 0xF0
                        when (channelEventType) {
                            0x80 -> {
                                val note = reader.readUInt8()
                                val velocity = reader.readUInt8()
                                allTrackEvents.add(RawEvent(tick, RawType.NoteOff, note, velocity))
                            }
                            0x90 -> {
                                val note = reader.readUInt8()
                                val velocity = reader.readUInt8()
                                if (velocity == 0) {
                                    allTrackEvents.add(RawEvent(tick, RawType.NoteOff, note, 0))
                                } else {
                                    allTrackEvents.add(RawEvent(tick, RawType.NoteOn, note, velocity))
                                }
                            }
                            0xA0, 0xB0, 0xE0 -> {
                                reader.readUInt8()
                                reader.readUInt8()
                            }
                            0xC0, 0xD0 -> {
                                reader.readUInt8()
                            }
                            else -> {
                                reader.position = trackEnd
                            }
                        }
                    }
                }

                if (tick > maxTick) maxTick = tick
            }

            reader.position = trackEnd
        }

        allTrackEvents.sortBy { it.tick }

        val hits = mutableListOf<DrumHit>()
        var currentTempo = DEFAULT_TEMPO_MICROS_PER_QUARTER
        var firstTempoMicros = DEFAULT_TEMPO_MICROS_PER_QUARTER
        var foundFirstTempo = false
        var lastTick = 0L
        var accumulatedMicros = 0L

        for (event in allTrackEvents) {
            val deltaTicks = event.tick - lastTick
            val deltaMicros = (deltaTicks * currentTempo) / ticksPerQuarter
            accumulatedMicros += deltaMicros
            lastTick = event.tick

            when (val type = event.type) {
                is RawType.Tempo -> {
                    currentTempo = type.microsPerQuarter
                    if (!foundFirstTempo) {
                        firstTempoMicros = type.microsPerQuarter
                        foundFirstTempo = true
                    }
                }
                RawType.NoteOn -> {
                    hits.add(DrumHit(accumulatedMicros, event.a, event.b))
                }
                RawType.NoteOff -> {
                    // No necesitamos el evento de apagado para samples
                    // one-shot de batería; se ignora a propósito.
                }
            }
        }

        val totalDeltaTicks = maxTick - lastTick
        val totalDeltaMicros = (totalDeltaTicks * currentTempo) / ticksPerQuarter
        val durationMicros = accumulatedMicros + totalDeltaMicros

        hits.sortBy { it.timeMicros }

        // BPM = 60,000,000 microsegundos por minuto / microsegundos por negra
        val originalBpm = 60_000_000.0 / firstTempoMicros.toDouble()

        return ParsedMidi(hits, durationMicros.coerceAtLeast(1L), originalBpm)
    }
}

/**
 * Lector secuencial de bytes con soporte para los tipos de datos específicos
 * del formato SMF: enteros big-endian de 16/32 bits, y "variable length
 * quantities" (VLQ), que es la forma en que MIDI codifica los delta-times.
 */
private class ByteReader(private val data: ByteArray) {
    var position: Int = 0

    fun readUInt8(): Int {
        val value = data[position].toInt() and 0xFF
        position += 1
        return value
    }

    fun readInt16(): Int {
        val b0 = readUInt8()
        val b1 = readUInt8()
        return (b0 shl 8) or b1
    }

    fun readInt32(): Int {
        val b0 = readUInt8()
        val b1 = readUInt8()
        val b2 = readUInt8()
        val b3 = readUInt8()
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    fun readString(length: Int): String {
        val bytes = ByteArray(length) { data[position + it] }
        position += length
        return String(bytes, Charsets.US_ASCII)
    }

    fun readVarLength(): Long {
        var result = 0L
        while (true) {
            val byte = readUInt8()
            result = (result shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) break
        }
        return result
    }

    fun skip(count: Int) {
        position += count
    }
}

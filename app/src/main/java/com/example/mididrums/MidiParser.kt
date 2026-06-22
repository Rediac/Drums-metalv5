package com.example.mididrums

import java.io.InputStream

data class DrumHit(
    val timeMicros: Long,
    val note: Int,
    val velocity: Int,
    val channel: Int = 9  // 9 = canal de batería MIDI
)

data class ParsedMidi(
    val hits: List<DrumHit>,
    val durationMicros: Long,
    val originalBpm: Double
)

private sealed class RawType {
    object NoteOn : RawType()
    object NoteOff : RawType()
    data class Tempo(val microsPerQuarter: Long) : RawType()
}

private data class RawEvent(val tick: Long, val type: RawType, val a: Int, val b: Int, val channel: Int = 0)

object MidiParser {

    private const val DEFAULT_TEMPO_MICROS_PER_QUARTER = 500_000L

    fun parse(input: InputStream): ParsedMidi {
        val data = input.readBytes()
        val reader = ByteReader(data)

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
                                val channel = statusByte and 0x0F
                                if (velocity == 0) {
                                    allTrackEvents.add(RawEvent(tick, RawType.NoteOff, note, 0))
                                } else {
                                    allTrackEvents.add(RawEvent(tick, RawType.NoteOn, note, velocity, channel))
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
                    hits.add(DrumHit(accumulatedMicros, event.a, event.b, event.channel))
                }
                RawType.NoteOff -> { }
            }
        }

        val totalDeltaTicks = maxTick - lastTick
        val totalDeltaMicros = (totalDeltaTicks * currentTempo) / ticksPerQuarter
        val durationMicros = accumulatedMicros + totalDeltaMicros

        hits.sortBy { it.timeMicros }

        val originalBpm = 60_000_000.0 / firstTempoMicros.toDouble()

        return ParsedMidi(hits, durationMicros.coerceAtLeast(1L), originalBpm)
    }
}

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

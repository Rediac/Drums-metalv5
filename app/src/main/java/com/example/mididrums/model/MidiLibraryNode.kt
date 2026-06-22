package com.example.mididrums.model

sealed class MidiLibraryNode {
    data class File(
        val name: String,
        val path: String
    ) : MidiLibraryNode()

    data class Folder(
        val name: String,
        val children: List<MidiLibraryNode>
    ) : MidiLibraryNode()
}

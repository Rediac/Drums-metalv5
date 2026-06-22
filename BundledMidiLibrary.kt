package com.example.mididrums

import android.content.Context

/**
 * Representa un nodo dentro de la librería de MIDIs empaquetados en
 * assets/midi/: puede ser una carpeta (con hijos) o un archivo .mid hoja.
 *
 * path es la ruta completa dentro de assets, por ejemplo
 * "midi/rock/groove1.mid", necesaria para abrirlo luego con
 * context.assets.open(path).
 */
sealed class MidiLibraryNode {
    abstract val name: String

    data class Folder(
        override val name: String,
        val path: String,
        val children: List<MidiLibraryNode>
    ) : MidiLibraryNode()

    data class File(
        override val name: String,
        val path: String
    ) : MidiLibraryNode()
}

/**
 * Explora recursivamente assets/midi/ y construye el árbol de carpetas y
 * archivos .mid disponibles. Si la carpeta no existe (el usuario todavía
 * no agregó ningún MIDI empaquetado), devuelve una lista vacía sin error.
 */
object BundledMidiLibrary {

    private const val ROOT = "midi"

    fun listRoot(context: Context): List<MidiLibraryNode> {
        return listFolder(context, ROOT)
    }

    private fun listFolder(context: Context, path: String): List<MidiLibraryNode> {
        val entries = try {
            context.assets.list(path)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val nodes = mutableListOf<MidiLibraryNode>()
        for (entry in entries.sorted()) {
            val entryPath = "$path/$entry"
            if (entry.endsWith(".mid", ignoreCase = true) || entry.endsWith(".midi", ignoreCase = true)) {
                nodes.add(MidiLibraryNode.File(name = entry, path = entryPath))
            } else {
                val children = listFolder(context, entryPath)
                if (children.isNotEmpty()) {
                    nodes.add(MidiLibraryNode.Folder(name = entry, path = entryPath, children = children))
                }
            }
        }
        return nodes
    }
}

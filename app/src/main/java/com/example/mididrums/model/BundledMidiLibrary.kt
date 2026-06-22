package com.example.mididrums.model

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

object BundledMidiLibrary {

    private var cachedEntries: List<ZipEntryInfo>? = null

    private data class ZipEntryInfo(
        val name: String,
        val path: String,
        val isDirectory: Boolean
    )

    fun listRoot(context: Context): List<MidiLibraryNode> {
        val entries = getEntries(context)
        return buildTree(entries)
    }

    /**
     * Construye el árbol completo de carpetas y archivos respetando
     * toda la jerarquía del ZIP (Black Metal / STRAIGHT_4#4 / 100-VERSE / archivo.mid).
     */
    private fun buildTree(entries: List<ZipEntryInfo>): List<MidiLibraryNode> {
        val midiFiles = entries
            .filter { !it.isDirectory }
            .filter { it.name.endsWith(".mid", ignoreCase = true) ||
                      it.name.endsWith(".midi", ignoreCase = true) }

        // Agrupar por el primer nivel de carpeta
        val rootMap = mutableMapOf<String, MutableList<ZipEntryInfo>>()

        for (file in midiFiles) {
            val parts = file.path.split("/")
            if (parts.size == 1) {
                // Archivo suelto en la raíz
                rootMap.getOrPut("__ROOT__") { mutableListOf() }.add(file)
            } else {
                // Pertenece a una carpeta
                val rootFolder = parts.first()
                rootMap.getOrPut(rootFolder) { mutableListOf() }.add(file)
            }
        }

        val rootNodes = mutableListOf<MidiLibraryNode>()

        // Procesar cada carpeta raíz
        for ((folderName, files) in rootMap) {
            if (folderName == "__ROOT__") {
                // Archivos sueltos
                for (file in files) {
                    rootNodes.add(MidiLibraryNode.File(name = file.name, path = file.path))
                }
            } else {
                // Construir subcarpetas recursivamente
                val children = buildSubTree(files, folderName)
                rootNodes.add(MidiLibraryNode.Folder(name = folderName, children = children))
            }
        }

        return rootNodes
    }

    /**
     * Dado un grupo de archivos que comparten el mismo prefijo de carpeta,
     * construye el subárbol de subcarpetas y archivos.
     */
    private fun buildSubTree(files: List<ZipEntryInfo>, prefix: String): List<MidiLibraryNode> {
        val nodes = mutableListOf<MidiLibraryNode>()

        // Archivos que están DIRECTAMENTE en esta carpeta (sin más "/")
        val directFiles = files.filter { file ->
            val relative = file.path.removePrefix("$prefix/")
            !relative.contains("/")
        }

        for (file in directFiles) {
            nodes.add(MidiLibraryNode.File(name = file.name, path = file.path))
        }

        // Subcarpetas: agrupar por el siguiente nivel
        val subFolderFiles = files.filter { file ->
            val relative = file.path.removePrefix("$prefix/")
            relative.contains("/")
        }

        val subFolderMap = mutableMapOf<String, MutableList<ZipEntryInfo>>()
        for (file in subFolderFiles) {
            val relative = file.path.removePrefix("$prefix/")
            val subFolderName = relative.split("/").first()
            subFolderMap.getOrPut(subFolderName) { mutableListOf() }.add(file)
        }

        for ((subFolderName, subFiles) in subFolderMap) {
            val newPrefix = "$prefix/$subFolderName"
            val children = buildSubTree(subFiles, newPrefix)
            nodes.add(MidiLibraryNode.Folder(name = subFolderName, children = children))
        }

        return nodes
    }

    fun openMidiStream(context: Context, path: String): ByteArrayInputStream? {
        return try {
            val zipBytes = context.assets.open("midi/EZDrummer1.zip").use { it.readBytes() }
            ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == path) {
                        val data = zip.readBytes()
                        return ByteArrayInputStream(data)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            null
        } catch (e: IOException) {
            null
        }
    }

    private fun getEntries(context: Context): List<ZipEntryInfo> {
        if (cachedEntries != null) return cachedEntries!!

        val entries = mutableListOf<ZipEntryInfo>()
        try {
            context.assets.open("midi/EZDrummer1.zip").use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entries.add(
                            ZipEntryInfo(
                                name = entry.name.substringAfterLast("/"),
                                path = entry.name,
                                isDirectory = entry.isDirectory
                            )
                        )
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (_: IOException) { }

        cachedEntries = entries
        return entries
    }
}

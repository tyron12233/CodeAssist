// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.platform

/**
 * The browser has no disk, so the portable file contract (`readFile`, `listDirectory`, ...) is answered from
 * memory. The Playground mounts the project's sources, and the classpath jars it fetched, here under ordinary
 * absolute paths (`/project/Main.kt`, `/classpath/kotlin-stdlib.jar`); everything above the platform layer
 * then walks them the way it walks a real tree.
 *
 * Paths are normalised (`//` and a trailing `/` collapse, `.` and `..` resolve) so two spellings of one path
 * name one entry. Single-threaded by construction: the wasm target has one thread.
 */
object MemoryFileSystem {
    private class Node(var bytes: ByteArray?, var modified: Long) // bytes == null -> directory

    private val nodes = HashMap<String, Node>().apply { put("/", Node(null, 0)) }

    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    private fun parentOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i <= 0) "/" else path.substring(0, i)
    }

    fun info(path: String): FileInfo? {
        val p = normalize(path)
        val n = nodes[p] ?: return null
        return FileInfo(p, n.bytes == null, n.bytes?.size?.toLong() ?: 0L, n.modified)
    }

    fun list(path: String): List<String> {
        val p = normalize(path)
        if (nodes[p]?.bytes != null || nodes[p] == null) return emptyList()
        val prefix = if (p == "/") "/" else "$p/"
        return nodes.keys.filter { it != p && it.startsWith(prefix) && '/' !in it.substring(prefix.length) }.sorted()
    }

    fun read(path: String): ByteArray? = nodes[normalize(path)]?.bytes

    fun mkdirs(path: String): Boolean {
        var p = normalize(path)
        val toCreate = ArrayList<String>()
        while (true) {
            val existing = nodes[p]
            if (existing != null) {
                if (existing.bytes != null) return false // a file is in the way
                break
            }
            toCreate.add(p)
            p = parentOf(p)
        }
        val now = epochMillis()
        for (d in toCreate.asReversed()) nodes[d] = Node(null, now)
        return true
    }

    /** Create or replace [path], making missing parent directories. False when a file blocks a parent. */
    fun write(path: String, bytes: ByteArray): Boolean {
        val p = normalize(path)
        if (nodes[p]?.bytes == null && nodes[p] != null) return false // it is a directory
        if (!mkdirs(parentOf(p))) return false
        nodes[p] = Node(bytes, epochMillis())
        return true
    }

    fun delete(path: String): Boolean {
        val p = normalize(path)
        if (p == "/") return false
        val n = nodes[p] ?: return false
        if (n.bytes == null && list(p).isNotEmpty()) return false // like Files.deleteIfExists: not a non-empty dir
        nodes.remove(p)
        return true
    }

    fun move(from: String, to: String): Boolean {
        val f = normalize(from)
        val n = nodes[f] ?: return false
        if (n.bytes == null) return false
        if (!write(to, n.bytes!!)) return false
        if (normalize(to) != f) nodes.remove(f)
        return true
    }
}

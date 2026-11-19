package dev.ide.ui.backend.remote

import dev.ide.ui.backend.GitStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode

/**
 * The project tree of a backend that lives on the other side of a byte channel, as bytes.
 *
 * The iOS app shows the tree its own code can make, which is the folder as it is on disk. The tree the other
 * hosts show (modules, source roots by role, packages folded into one row) is made by the engine from the
 * project model, and where the app embeds a JVM that engine is running in it. This is the one call that
 * fetches its tree: both ends use this object, so they cannot disagree about the bytes.
 */
object RemoteFileTree {
    /** First int of a request, so a channel shared with other protocols can tell this one apart. */
    private const val MAGIC = 0x46545245 // "FTRE"

    fun request(mode: TreeViewMode): ByteArray = Out().apply { int(MAGIC); int(mode.ordinal) }.done()

    /** The mode [request] asks a tree for, or null when it is not a request of this protocol. */
    fun modeOf(request: ByteArray): TreeViewMode? {
        if (request.size != 8) return null
        val input = In(request)
        if (input.int() != MAGIC) return null
        return TreeViewMode.entries.getOrNull(input.int())
    }

    fun answer(tree: TreeNode): ByteArray = Out().apply { int(1); node(tree) }.done()

    fun failure(message: String): ByteArray = Out().apply { int(0); string(message) }.done()

    /** The tree in [reply]; throws with the far side's message when it answered with a failure. */
    fun read(reply: ByteArray): TreeNode {
        val input = In(reply)
        if (input.int() != 1) error(input.string() ?: "The backend did not answer.")
        return input.node()
    }

    private fun Out.node(n: TreeNode) {
        string(n.id); string(n.name); int(n.kind.ordinal); string(n.filePath); string(n.iconId)
        int(n.gitStatus?.ordinal ?: -1); string(n.sourceRootPath)
        int(n.packageSegments.size)
        for (s in n.packageSegments) { string(s.packageName); string(s.dirPath) }
        string(n.resDirPath); string(n.aidlRootPath); string(n.moduleConfigName); string(n.dirPath); string(n.styleHint)
        int(n.children.size)
        for (c in n.children) node(c)
    }

    private fun In.node(): TreeNode {
        val id = string()!!
        val name = string()!!
        val kind = NodeKind.entries[int()]
        val filePath = string()
        val iconId = string()!!
        val git = int().let { if (it < 0) null else GitStatus.entries[it] }
        val sourceRoot = string()
        val segments = List(int()) { PackageSegment(string()!!, string()!!) }
        val resDir = string()
        val aidlRoot = string()
        val moduleConfig = string()
        val dirPath = string()
        val style = string()
        val children = List(int()) { node() }
        return TreeNode(
            id = id, name = name, kind = kind, filePath = filePath, iconId = iconId, children = children, gitStatus = git,
            sourceRootPath = sourceRoot, packageSegments = segments, resDirPath = resDir, aidlRootPath = aidlRoot,
            moduleConfigName = moduleConfig, dirPath = dirPath, styleHint = style,
        )
    }

    /** Ints big-endian, strings as UTF-8 behind their byte length (-1 for null). */
    private class Out {
        private var bytes = ByteArray(1024)
        private var size = 0
        private fun room(extra: Int) {
            if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
        }
        fun int(v: Int) {
            room(4)
            bytes[size++] = (v ushr 24).toByte(); bytes[size++] = (v ushr 16).toByte()
            bytes[size++] = (v ushr 8).toByte(); bytes[size++] = v.toByte()
        }
        fun string(v: String?) {
            if (v == null) { int(-1); return }
            val utf8 = v.encodeToByteArray()
            int(utf8.size); room(utf8.size)
            utf8.copyInto(bytes, size); size += utf8.size
        }
        fun done(): ByteArray = bytes.copyOf(size)
    }

    private class In(private val bytes: ByteArray) {
        private var at = 0
        fun int(): Int {
            val v = (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
                (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)
            at += 4
            return v
        }
        fun string(): String? {
            val n = int()
            if (n < 0) return null
            return bytes.decodeToString(at, at + n).also { at += n }
        }
    }
}

package dev.ide.core.backend

import java.io.File

/**
 * The device's own record of what it has installed from the store.
 *
 * It exists for one reason: the recommendation model is anonymous, so the server holds install ids and
 * has no way to know which of them is this phone. The seed for "Because you installed X" can therefore
 * only come from the device, and nothing was keeping it — `recordInstall` told the server and forgot,
 * which is why the personalized shelf could never appear in the shipping app however well the server
 * computed it.
 *
 * Most-recent first, deduplicated and capped. Nothing here throws: an install must not fail because a
 * recommendation seed could not be written.
 */
internal class StoreInstallHistory(private val file: () -> File?) {

    /** The most recent install, or null on a device that has installed nothing from the store. */
    fun mostRecent(): String? = read().firstOrNull()

    /** Every remembered install, newest first. */
    fun read(): List<String> =
        file()?.takeIf { it.isFile }
            ?.let { runCatching { it.readLines() }.getOrNull() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /** Push an id onto the front. An id already present MOVES to the front rather than being duplicated. */
    fun remember(id: String) {
        if (id.isBlank()) return
        val target = file() ?: return
        val next = (listOf(id) + read()).distinct().take(LIMIT)
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(next.joinToString("\n"))
        }
    }

    private companion object {
        /**
         * How many installs to keep.
         *
         * Only the head is read today. The rest are kept because a seed the server no longer publishes
         * yields an empty shelf, and the obvious next step is to fall back to the one before it.
         */
        const val LIMIT = 10
    }
}

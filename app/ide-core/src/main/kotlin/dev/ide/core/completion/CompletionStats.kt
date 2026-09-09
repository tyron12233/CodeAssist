package dev.ide.core.completion

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Per-project completion-acceptance counters — the store behind the `platform.stats` weigher (IntelliJ's
 * `stats`): items the user actually accepts float up on later completions. Keyed by the item label's
 * leading identifier ONLY (not its kind): the accept notification crosses the UI boundary as a
 * `UiCompletionItem` whose kind enum is a lossy mapping of the engine's, so a kind-qualified key would
 * count under one name and look up under another.
 *
 * Persisted as a small properties file under `.platform/` (NOT `caches/` — those are wiped by
 * "Clear caches", and learned ranking should survive that). Loaded lazily once; every accept rewrites the
 * file (accepts are human-paced). Counts are capped so decades of accepts can't overflow or pin the list.
 */
class CompletionStats(private val file: Path) {

    private val lock = Any()
    @Volatile private var counts: MutableMap<String, Int>? = null

    private fun loaded(): MutableMap<String, Int> {
        counts?.let { return it }
        synchronized(lock) {
            counts?.let { return it }
            val map = HashMap<String, Int>()
            if (Files.isRegularFile(file)) {
                runCatching {
                    val props = Properties()
                    Files.newInputStream(file).use { props.load(it) }
                    for ((k, v) in props) map[k.toString()] = v.toString().toIntOrNull() ?: 0
                }
            }
            counts = map
            return map
        }
    }

    /** How often the user accepted an item keyed [key]; 0 when never. */
    fun countFor(key: String): Int = if (key.isEmpty()) 0 else loaded()[key] ?: 0

    /** Record one acceptance of [key] and persist. */
    fun noteAccepted(key: String) {
        if (key.isEmpty()) return
        synchronized(lock) {
            val map = loaded()
            map[key] = ((map[key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
            runCatching {
                file.parent?.let(Files::createDirectories)
                val props = Properties()
                for ((k, v) in map) props.setProperty(k, v.toString())
                Files.newOutputStream(file).use { props.store(it, "CodeAssist completion acceptance stats") }
            }
        }
    }

    companion object {
        private const val MAX_COUNT = 1000

        /** The stable stats key for an item label: its leading identifier (`Text(text: String)` → `Text`). */
        fun keyOf(label: String): String =
            label.takeWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
    }
}

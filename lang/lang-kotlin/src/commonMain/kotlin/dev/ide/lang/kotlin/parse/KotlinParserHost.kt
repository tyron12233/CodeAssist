package dev.ide.lang.kotlin.parse

import dev.ide.kotlin.syntax.KotlinSyntax
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.LazyBodyParser
import dev.ide.platform.DeviceMemory
import dev.ide.platform.Lock
import org.jetbrains.kotlin.kmp.tree.LightSyntaxTree

/**
 * The resolution-free Kotlin parse host: `text -> KtFile`.
 *
 * The grammar is the Kotlin compiler's own, vendored into `:kotlin-syntax` from
 * `compiler/multiplatform-parsing` and built for the JVM and both Apple targets. The tree it produces is
 * behind the `Kt*` facade in `dev.ide.kotlin.syntax.psi`, which carries the compiler's accessor names, so
 * everything above this line reads the same as it did over `org.jetbrains.kotlin.psi`.
 *
 * It never builds a `BindingContext` or runs the analyzer/FIR: all semantic work (symbols, resolution,
 * inference, completion) is done on top of this tree. The parser is error-tolerant: broken regions become
 * error elements and it recovers, so a [KtFile] always covers the whole file, satisfying the DOM's
 * error-tolerance contract, which is essential because completion fires mid-edit.
 *
 * **Threading: there is nothing to serialize.** The old host parsed into IntelliJ PSI, whose tree is built
 * lazily and mutated as it is walked, and so needed a coarse parse lock plus a `forceFullParse` pass while
 * holding it, without which a `buildTree` could run during a concurrent traversal and take the process down
 * with a native SIGSEGV on ART. A `LightSyntaxTree` is built in full by [KotlinSyntax.parse] and never
 * mutated afterwards, so any number of threads may read one. The facade's per-index wrapper cache is the
 * only mutable state, and it is per [dev.ide.kotlin.syntax.psi.KtTreeSession], i.e. per parsed file.
 */
object KotlinParserHost {

    /**
     * Parse [text] into a [KtFile] named [name]. Never throws on syntactically invalid input — broken regions
     * become error elements in the returned tree. A `.kts` [name] is parsed as a script; anything else is
     * parsed as a source file, and the name need not correspond to a real one.
     */
    fun parse(name: String, text: CharSequence): KtFile {
        val fileName = if (name.endsWith(".kt") || name.endsWith(".kts")) name else "$name.kt"
        val isScript = fileName.endsWith(".kts")
        return if (lazyBodies) KotlinSyntax.parseFileLazily(text, BodyCache, isScript = isScript, name = fileName)
        else KotlinSyntax.parseFile(text, isScript = isScript, name = fileName)
    }

    /** Parse lazily with cached bodies (the default), or every file in full; the switch exists for A/B tests. */
    var lazyBodies: Boolean = true

    /**
     * The parses of function bodies and lambdas, kept by their exact text.
     *
     * Every parse is LAZY: the file is parsed with its bodies collapsed, and a body is parsed on its own the
     * first time something looks inside it (the tree reads exactly like a full parse, see
     * `LazyBlockParityTest`). A keystroke changes one body, so the next parse of the file finds every other
     * body here and parses only the one that changed; a consumer that never enters a body (a file's
     * structure, its declarations) never parses one at all. Bounded by the text it holds; least recently used
     * first out. A body tree is immutable, so one entry serves every file and thread that has the same body.
     */
    private object BodyCache : LazyBodyParser {
        private val lock = Lock()
        // Insertion order is the recency order, maintained by removing and re-putting on a hit.
        private val entries = LinkedHashMap<String, LightSyntaxTree>()
        private var chars = 0L
        private val maxChars = DeviceMemory.pick(normal = 4_000_000L, low = 1_000_000L)

        override fun parse(text: String, kind: LazyBodyParser.Kind): LightSyntaxTree {
            val key = if (kind == LazyBodyParser.Kind.BLOCK) text else "\u0000$text"
            val hit = lock.withLock { entries.remove(key)?.also { entries[key] = it } }
            if (hit != null) return hit
            val tree = LazyBodyParser.UNCACHED.parse(text, kind)
            lock.withLock {
                if (entries.put(key, tree) == null) chars += key.length
                while (chars > maxChars && entries.isNotEmpty()) {
                    val eldest = entries.keys.first()
                    entries.remove(eldest)
                    chars -= eldest.length
                }
            }
            return tree
        }

        fun clear() = lock.withLock {
            entries.clear()
            chars = 0
        }
    }

    /** Drop every cached body parse (memory pressure); the next parses rebuild what they touch. */
    fun releaseMemory() = BodyCache.clear()

    /**
     * Kept so the startup path and its tests still have something to call, and deliberately a no-op.
     *
     * The PSI host had a genuinely expensive first parse: it stood up a `KotlinCoreEnvironment`, an
     * application environment and a project, and hiding that behind a background warm-up at startup was
     * worth doing. The vendored parser has no environment: the first parse costs a lexer table and nothing
     * else. Removing the call sites instead would be churn for no gain, and would take the option away if
     * something here ever becomes expensive again.
     */
    fun warmUp() = Unit
}

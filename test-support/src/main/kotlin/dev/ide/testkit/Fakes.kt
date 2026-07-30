package dev.ide.testkit

import dev.ide.index.ClassNameValue
import dev.ide.index.Hit
import dev.ide.index.IndexId
import dev.ide.index.IndexOrigin
import dev.ide.index.IndexScope
import dev.ide.index.IndexService
import dev.ide.index.IndexStatus
import dev.ide.platform.Disposable
import java.nio.file.Path

/**
 * A deterministic, data-driven fake [IndexService] — the symbol/package store that completion queries for
 * unimported-type (auto-import), type-position, and package completion. It mirrors the contract the real
 * `index-impl` engine satisfies (`java.classNames` fuzzy, `java.packages` prefix, `java.packageTypes` exact)
 * but is fixed and side-effect-free, so completion behaviour over it is reproducible run to run. Previously
 * copied privately into several completion tests + lang-jdt's RegressionFixtures.
 */
fun fakeIndex(
    classNames: List<ClassNameValue> = defaultClassNames(),
    packages: List<String> = defaultPackages(),
): IndexService = FakeIndexService(classNames, packages)

/** Common JDK collection/util types (SDK) plus synthetic source types for fuzzy/prefix volume. */
fun defaultClassNames(): List<ClassNameValue> = buildList {
    val javaUtil = listOf(
        "ArrayList", "LinkedList", "ArrayDeque", "HashMap", "LinkedHashMap", "TreeMap", "Hashtable",
        "HashSet", "LinkedHashSet", "TreeSet", "List", "Map", "Set", "Collection", "Collections",
        "Arrays", "Optional", "Objects", "Comparator", "Iterator", "Queue", "Deque", "Vector", "Stack",
        "PriorityQueue", "Random", "Scanner", "StringJoiner", "WeakHashMap", "IdentityHashMap",
    )
    for (n in javaUtil) add(ClassNameValue("java.util.$n", IndexOrigin.SDK, "class"))
    add(ClassNameValue("java.io.BufferedReader", IndexOrigin.SDK, "class"))
    add(ClassNameValue("java.io.File", IndexOrigin.SDK, "class"))
    // pad with synthetic source types so the prefix/fuzzy filter has realistic volume to chew through
    for (i in 0 until 200) add(ClassNameValue("app.gen.Type$i", IndexOrigin.SOURCE, "class"))
}

fun defaultPackages(): List<String> = listOf(
    "java", "java.util", "java.util.concurrent", "java.util.function", "java.util.stream",
    "java.io", "java.nio", "java.lang", "java.lang.reflect",
)

/** The fake [IndexService] behind [fakeIndex]; use directly when a named type is convenient. */
class FakeIndexService(
    private val classNames: List<ClassNameValue> = defaultClassNames(),
    private val packages: List<String> = defaultPackages(),
) : IndexService {

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> exact(id: IndexId, key: String): Sequence<V> =
        if (id.value == "java.packageTypes")
            classNames.asSequence().filter { it.fqn.substringBeforeLast('.') == key }.map { it as V }
        else emptySequence()

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> prefix(id: IndexId, prefix: String, limit: Int): Sequence<Hit<V>> =
        if (id.value == "java.packages")
            packages.asSequence().filter { it.startsWith(prefix) }.take(limit).map { Hit(it, it as V, 700) }
        else emptySequence()

    @Suppress("UNCHECKED_CAST")
    override fun <V : Any> fuzzy(id: IndexId, pattern: String, limit: Int): Sequence<Hit<V>> =
        if (id.value == "java.classNames")
            classNames.asSequence()
                .filter { it.fqn.substringAfterLast('.').startsWith(pattern, ignoreCase = true) }
                .take(limit)
                .map { Hit(it.fqn.substringAfterLast('.'), it as V, 800) }
        else emptySequence()

    override suspend fun ensureUpToDate(scope: IndexScope) {}
    override suspend fun reindexSource(path: Path, text: String) {}
    override val status: IndexStatus = IndexStatus()
    override fun observeStatus(listener: (IndexStatus) -> Unit): Disposable = Disposable {}
}
